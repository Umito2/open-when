import { randomInt } from "node:crypto";
import { initializeApp } from "firebase-admin/app";
import { getFirestore } from "firebase-admin/firestore";
import { getStorage } from "firebase-admin/storage";
import { HttpsError, onCall, CallableRequest } from "firebase-functions/v2/https";
import { warn } from "firebase-functions/logger";
import { canPair, openingDecision, validReaction, validSong } from "./policy";
import type { EnvelopePolicy } from "./policy";
import { ensureDownloadToken } from "./downloadToken";

initializeApp();
const db = getFirestore();
const options = { region: "us-central1", enforceAppCheck: process.env.FUNCTIONS_EMULATOR !== "true", maxInstances: 20 };
type Input = Record<string, unknown>;

function identity(request: CallableRequest<Input>): string {
  if (!request.auth) throw new HttpsError("unauthenticated", "Sign in to your little corner of the world first.");
  return request.auth.uid;
}
function text(input: unknown, max: number, label: string): string {
  if (typeof input !== "string" || !input.trim() || input.length > max) throw new HttpsError("invalid-argument", `${label} is required (up to ${max} characters).`);
  return input.trim();
}
function id(input: unknown): string {
  const value = text(input, 200, "Identifier");
  if (!/^[A-Za-z0-9_-]+$/.test(value)) throw new HttpsError("invalid-argument", "Invalid identifier.");
  return value;
}
async function userSpace(uid: string): Promise<{ spaceId: string; partnerId: string }> {
  const user = (await db.doc(`users/${uid}`).get()).data();
  if (!user?.coupleSpaceId || !user.partnerId) throw new HttpsError("failed-precondition", "Connect with your partner first.");
  return { spaceId: user.coupleSpaceId, partnerId: user.partnerId };
}

export const createSpace = onCall(options, async (request: CallableRequest<Input>) => {
  const uid = identity(request);
  const anniversary = request.data.anniversaryDate;
  if (typeof anniversary !== "number" || !Number.isSafeInteger(anniversary) || anniversary < 0 || anniversary > Date.now()) {
    throw new HttpsError("invalid-argument", "Choose an anniversary date on or before today.");
  }
  for (let attempt = 0; attempt < 10; attempt++) {
    const code = randomInt(100000, 1000000).toString();
    const ref = db.collection("couple_spaces").doc();
    const result = await db.runTransaction(async (tx) => {
      const userRef = db.doc(`users/${uid}`);
      const inviteRef = db.doc(`_invites/${code}`);
      const [user, invite] = await Promise.all([tx.get(userRef), tx.get(inviteRef)]);
      if (!user.exists) throw new HttpsError("failed-precondition", "Finish your profile first.");
      if (user.data()?.coupleSpaceId) {
        const existing = await tx.get(db.doc(`couple_spaces/${user.data()!.coupleSpaceId}`));
        return existing.data();
      }
      if (invite.exists) return null;
      const now = Date.now();
      const space = { spaceId: ref.id, inviteCode: code, user1Id: uid, user2Id: null, anniversaryDate: anniversary, createdAt: now };
      tx.create(ref, space);
      tx.create(inviteRef, { spaceId: ref.id, ownerUid: uid, createdAt: now });
      tx.update(userRef, { coupleSpaceId: ref.id });
      return space;
    });
    if (result) return result;
  }
  throw new HttpsError("resource-exhausted", "We could not make an invitation just now. Please try again.");
});

export const joinSpace = onCall(options, async (request: CallableRequest<Input>) => {
  const uid = identity(request);
  const code = text(request.data.inviteCode, 6, "Invitation code");
  if (!/^\d{6}$/.test(code)) throw new HttpsError("invalid-argument", "Enter the six-digit invitation code.");
  const now = Date.now();
  // The rate-limit transaction commits even when a subsequent invitation lookup fails.
  await db.runTransaction(async (tx) => {
    const ref = db.doc(`_limits/${uid}`);
    const current = (await tx.get(ref)).data();
    const sameWindow = current && now - current.windowStart < 60_000;
    if (sameWindow && current.count >= 10) throw new HttpsError("resource-exhausted", "Please wait a minute before trying another code.");
    tx.set(ref, { windowStart: sameWindow ? current.windowStart : now, count: sameWindow ? current.count + 1 : 1 });
  });
  return db.runTransaction(async (tx) => {
    const userRef = db.doc(`users/${uid}`);
    const [user, invite] = await Promise.all([tx.get(userRef), tx.get(db.doc(`_invites/${code}`))]);
    if (!user.exists || !invite.exists) throw new HttpsError("not-found", "That invitation is unavailable. Check the code with your partner.");
    const inviteData = invite.data()!;
    const spaceRef = db.doc(`couple_spaces/${inviteData.spaceId}`);
    const ownerRef = db.doc(`users/${inviteData.ownerUid}`);
    const [space, owner] = await Promise.all([tx.get(spaceRef), tx.get(ownerRef)]);
    const current = space.data();
    if (!owner.exists || !current || !canPair(inviteData.ownerUid, uid, !!current.user2Id || !!owner.data()?.partnerId, !!user.data()?.coupleSpaceId)) {
      throw new HttpsError("failed-precondition", "This invitation cannot be used. Both partners must be unpaired.");
    }
    tx.update(spaceRef, { user2Id: uid });
    tx.update(userRef, { partnerId: inviteData.ownerUid, coupleSpaceId: spaceRef.id });
    tx.update(ownerRef, { partnerId: uid });
    tx.delete(invite.ref);
    return { spaceId: spaceRef.id };
  });
});

function attachment(input: unknown, spaceId: string, uid: string): string | null {
  if (input === null || input === undefined || input === "") return null;
  if (typeof input !== "string") throw new HttpsError("invalid-argument", "Invalid attachment.");
  const bucket = getStorage().bucket().name;
  if (!input.startsWith(`gs://${bucket}/couple_media/${spaceId}/${uid}/`) || input.includes("..")) {
    throw new HttpsError("invalid-argument", "Attachments must belong to your shared space.");
  }
  return input;
}

export const sendEnvelope = onCall(options, async (request: CallableRequest<Input>) => {
  const uid = identity(request);
  const { spaceId, partnerId } = await userSpace(uid);
  const d = request.data;
  const requestId = id(d.requestId);
  const ref = db.collection(`couple_spaces/${spaceId}/envelopes`).doc(`${uid}_${requestId}`);
  // Resolve a committed retry before time-sensitive validation; the lock date may have passed.
  const committed = await ref.get();
  if (committed.exists) return { envelopeId: ref.id };
  const titlePrompt = text(d.titlePrompt, 120, "Envelope title");
  const letterText = text(d.letterText, 20_000, "Letter");
  if (typeof d.envelopeColorHex !== "string" || !/^#[0-9A-Fa-f]{6}$/.test(d.envelopeColorHex)) throw new HttpsError("invalid-argument", "Choose a valid envelope color.");
  if (!["Heart", "Star", "Moon", "Hug", "Coffee"].includes(d.sealIcon as string)) throw new HttpsError("invalid-argument", "Choose a wax seal.");
  if (!["ANYTIME", "DATE_LOCKED", "ONE_TIME_ONLY"].includes(d.unlockRule as string)) throw new HttpsError("invalid-argument", "Choose an opening rule.");
  if (d.unlockRule === "DATE_LOCKED" && (typeof d.unlockTimestamp !== "number" || !Number.isSafeInteger(d.unlockTimestamp) || d.unlockTimestamp <= Date.now())) {
    throw new HttpsError("invalid-argument", "Choose a future opening date.");
  }
  if (typeof d.voiceDurationSec !== "number" || !Number.isInteger(d.voiceDurationSec) || d.voiceDurationSec < 0 || d.voiceDurationSec > 300) throw new HttpsError("invalid-argument", "Voice notes can be up to five minutes.");
  if (!validSong(d.songUrl)) throw new HttpsError("invalid-argument", "Song links must use HTTPS.");
  if (d.photoCaption != null && (typeof d.photoCaption !== "string" || d.photoCaption.length > 240)) throw new HttpsError("invalid-argument", "Photo captions can be up to 240 characters.");
  const content = { letterText, photoUrl: attachment(d.photoUrl, spaceId, uid), photoCaption: d.photoCaption ?? null,
    voiceNoteUrl: attachment(d.voiceNoteUrl, spaceId, uid), songUrl: d.songUrl || null };
  await db.runTransaction(async (tx) => {
    const existing = await tx.get(ref);
    if (existing.exists) return;
    tx.create(ref, { envelopeId: ref.id, senderId: uid, receiverId: partnerId, titlePrompt,
      envelopeColorHex: d.envelopeColorHex, sealIcon: d.sealIcon, voiceDurationSec: d.voiceDurationSec,
      unlockRule: d.unlockRule, unlockTimestamp: d.unlockRule === "DATE_LOCKED" ? d.unlockTimestamp : null,
      isOpened: false, lastOpenedAt: null, openCount: 0, partnerReaction: null, createdAt: Date.now() });
    tx.create(ref.collection("content").doc("letter"), content);
  });
  return { envelopeId: ref.id };
});

async function playableMedia(input: unknown): Promise<string | null> {
  if (typeof input !== "string") return null;
  const bucket = getStorage().bucket();
  const prefix = `gs://${bucket.name}/`;
  if (!input.startsWith(prefix)) return null;
  try {
    const file = bucket.file(input.slice(prefix.length));
    // Use the authorized GCS metadata API. The Firebase REST metadata endpoint
    // still applies private-read rules in the emulator even for an Admin SDK client.
    const token = await ensureDownloadToken(file);
    const endpoint = (process.env.STORAGE_EMULATOR_HOST || "https://firebasestorage.googleapis.com").replace(/\/$/, "");
    return `${endpoint}/v0/b/${encodeURIComponent(bucket.name)}/o/${encodeURIComponent(file.name)}?alt=media&token=${encodeURIComponent(token)}`;
  }
  catch (failure) {
    warn("An opened letter attachment is unavailable.", { storageCode: (failure as { code?: unknown }).code ?? "unknown" });
    return null; // A missing attachment must never consume access to the letter itself.
  }
}

export const openEnvelope = onCall(options, async (request: CallableRequest<Input>) => {
  const uid = identity(request);
  const { spaceId } = await userSpace(uid);
  const envelopeId = id(request.data.envelopeId);
  const requestId = id(request.data.requestId);
  const ref = db.doc(`couple_spaces/${spaceId}/envelopes/${envelopeId}`);
  const requestRef = ref.collection("open_requests").doc(`${uid}_${requestId}`);
  const result = await db.runTransaction(async (tx) => {
    const [document, content, previous] = await Promise.all([tx.get(ref), tx.get(ref.collection("content").doc("letter")), tx.get(requestRef)]);
    if (!document.exists || !content.exists) throw new HttpsError("not-found", "This envelope is unavailable.");
    const envelope = document.data()!;
    // A retry with the same request ID returns the committed opening without a second receipt.
    if (previous.exists && previous.data()?.uid === uid) {
      if (Date.now() - previous.data()!.createdAt < 120_000) return { ...envelope, ...content.data() };
      throw new HttpsError("failed-precondition", "That opening attempt expired. Please try opening the envelope again.");
    }
    let decision: "preview" | "open";
    try { decision = openingDecision(envelope as EnvelopePolicy, uid, Date.now()); }
    catch (failure) { throw new HttpsError("permission-denied", (failure as Error).message); }
    if (decision === "preview") return { ...envelope, ...content.data() };
    const update = { isOpened: true, lastOpenedAt: Date.now(), openCount: (envelope.openCount || 0) + 1 };
    tx.update(ref, update);
    tx.create(requestRef, { uid, createdAt: Date.now() });
    return { ...envelope, ...update, ...content.data() };
  });
  const resultMap = result as Record<string, unknown>;
  const [photoUrl, voiceNoteUrl] = await Promise.all([playableMedia(resultMap.photoUrl), playableMedia(resultMap.voiceNoteUrl)]);
  return { ...resultMap, photoUrl, voiceNoteUrl };
});

export const reactToEnvelope = onCall(options, async (request: CallableRequest<Input>) => {
  const uid = identity(request);
  const { spaceId } = await userSpace(uid);
  const ref = db.doc(`couple_spaces/${spaceId}/envelopes/${id(request.data.envelopeId)}`);
  await db.runTransaction(async (tx) => {
    const envelope = (await tx.get(ref)).data();
    if (!envelope || !validReaction(envelope as EnvelopePolicy, uid, request.data.reaction)) throw new HttpsError("permission-denied", "Only the recipient can react after opening a letter.");
    tx.update(ref, { partnerReaction: request.data.reaction.trim() });
  });
  return { success: true };
});
