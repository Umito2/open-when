export type EnvelopePolicy = {
  senderId: string;
  receiverId: string;
  unlockRule: "ANYTIME" | "DATE_LOCKED" | "ONE_TIME_ONLY";
  unlockTimestamp: number | null;
  isOpened: boolean;
};

export function openingDecision(envelope: EnvelopePolicy, uid: string, now: number): "preview" | "open" {
  if (envelope.senderId === uid) return "preview";
  if (envelope.receiverId !== uid) throw new Error("Only the recipient can open this envelope.");
  if (envelope.unlockRule === "DATE_LOCKED" && (envelope.unlockTimestamp === null || now < envelope.unlockTimestamp)) {
    throw new Error("This envelope is waiting for its opening date.");
  }
  if (envelope.unlockRule === "ONE_TIME_ONLY" && envelope.isOpened) {
    throw new Error("This one-time envelope has already been opened.");
  }
  return "open";
}

export function validReaction(envelope: EnvelopePolicy, uid: string, reaction: unknown): reaction is string {
  return uid === envelope.receiverId && envelope.isOpened && typeof reaction === "string" && reaction.trim().length > 0 && reaction.length <= 160;
}

export function validSong(value: unknown): boolean {
  if (value === null || value === undefined || value === "") return true;
  if (typeof value !== "string" || value.length > 2048) return false;
  try { const url = new URL(value); return url.protocol === "https:" && url.hostname.length > 0; } catch { return false; }
}

export function canPair(ownerUid: string, joiningUid: string, ownerHasPartner: boolean, joinerHasSpace: boolean): boolean {
  return ownerUid !== joiningUid && !ownerHasPartner && !joinerHasSpace;
}
