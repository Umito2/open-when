// Opt-in production smoke test. Creates only uniquely named disposable test data.
// The manifest records exact cleanup targets; Firestore rules intentionally forbid client deletion.
const assert = require('node:assert/strict');
const { readFileSync, writeFileSync, mkdirSync } = require('node:fs');
const { resolve } = require('node:path');
const { randomUUID, randomInt } = require('node:crypto');
const { initializeApp, deleteApp } = require('firebase/app');
const { getAuth, signInAnonymously, deleteUser } = require('firebase/auth');
const {
  getFirestore, doc, setDoc, getDoc, updateDoc, runTransaction, serverTimestamp,
  Timestamp, increment, onSnapshot, terminate, setLogLevel,
} = require('firebase/firestore');

const projectIndex = process.argv.indexOf('--project');
if (projectIndex < 0 || !process.argv[projectIndex + 1]) {
  throw new Error('Explicit --project is required. This test writes disposable data to that Firebase project.');
}
const requestedProject = process.argv[projectIndex + 1];
const configIndex = process.argv.indexOf('--config');
const configPath = configIndex >= 0 ? resolve(process.argv[configIndex + 1])
  : resolve(__dirname, '../../app/src/spark/google-services.json');
const android = JSON.parse(readFileSync(configPath, 'utf8'));
assert.equal(android.project_info.project_id, requestedProject, 'The explicit project must match the local Firebase configuration.');
assert.ok(!process.env.FIRESTORE_EMULATOR_HOST && !process.env.FIREBASE_AUTH_EMULATOR_HOST, 'Cloud smoke must not silently use emulators.');
const client = android.client.find(item => item.client_info.android_client_info.package_name === 'com.openwhen.app') || android.client[0];
const config = {
  projectId: requestedProject, apiKey: client.api_key[0].current_key,
  appId: client.client_info.mobilesdk_app_id,
};
setLogLevel('silent');
const runId = `smoke-${randomUUID()}`;
const spaceId = runId;
const outputDirectory = resolve(__dirname, '../../.tools/spark-cloud-smoke');
mkdirSync(outputDirectory, { recursive: true });
const manifestPath = resolve(outputDirectory, `${runId}.json`);
const manifest = {
  projectId: requestedProject, runId, createdAt: new Date().toISOString(),
  authUserIds: [], deletedAuthUserIds: [], documents: [], invitationCandidates: [], passed: false,
};
function saveManifest() { writeFileSync(manifestPath, JSON.stringify(manifest, null, 2)); }
function track(path) {
  if (!manifest.documents.includes(path)) manifest.documents.push(path);
  saveManifest();
  return path;
}
const clients = [];
const stops = new Set();
let phase = 'initialization';
saveManifest();

async function makeClient(label) {
  const app = initializeApp(config, `${runId}-${label}`);
  const auth = getAuth(app);
  const db = getFirestore(app);
  const result = { app, auth, db, uid: null };
  clients.push(result);
  const credential = await signInAnonymously(auth);
  result.uid = credential.user.uid;
  manifest.authUserIds.push(result.uid);
  saveManifest();
  await setDoc(doc(db, track(`users/${result.uid}`)), {
    uid: result.uid, name: `Open When smoke ${label}`, partnerId: null, coupleSpaceId: null,
    fcmToken: null, currentMood: '',
  });
  return result;
}
function awaitSnapshot(db, path, predicate) {
  let stop = () => {};
  let timer;
  const promise = new Promise((resolvePromise, reject) => {
    timer = setTimeout(() => { stop(); reject(new Error('Real-time update timed out.')); }, 30000);
    const unsubscribe = onSnapshot(doc(db, path), snapshot => {
      if (!snapshot.metadata.fromCache && snapshot.exists() && predicate(snapshot.data())) {
        stop(); resolvePromise(snapshot.data());
      }
    }, error => { stop(); reject(error); });
    stop = () => { clearTimeout(timer); unsubscribe(); stops.delete(stop); };
    stops.add(stop);
  });
  // A later failed write must not leave the pending listener rejection unhandled.
  promise.catch(() => {});
  return promise;
}
const denied = promise => assert.rejects(promise, error => error.code === 'permission-denied');

async function main() {
  phase = 'anonymous authentication and profiles';
  const adam = await makeClient('sender');
  const noor = await makeClient('receiver');
  const stranger = await makeClient('outsider');
  phase = 'create shared space';
  track(`couple_spaces/${spaceId}`);
  let code;
  for (let attempt = 0; attempt < 10; attempt++) {
    const candidate = String(randomInt(100000, 1000000));
    manifest.invitationCandidates.push({ path: `_invites/${candidate}`, ownerUid: adam.uid });
    saveManifest();
    const created = await runTransaction(adam.db, async tx => {
      const own = doc(adam.db, `users/${adam.uid}`);
      const invite = doc(adam.db, `_invites/${candidate}`);
      await tx.get(own);
      if ((await tx.get(invite)).exists()) return false;
      tx.set(doc(adam.db, `couple_spaces/${spaceId}`), {
        spaceId, inviteCode: candidate, user1Id: adam.uid, user2Id: null,
        anniversaryDate: Date.now() - 86400000, createdAt: serverTimestamp(),
      });
      tx.set(invite, {
        spaceId, ownerUid: adam.uid, createdAt: serverTimestamp(),
        expiresAt: Timestamp.fromMillis(Date.now() + 86400000),
      });
      tx.update(own, { coupleSpaceId: spaceId, partnerId: null });
      return true;
    });
    if (created) { code = candidate; break; }
  }
  assert.ok(code, 'A fresh invitation must be available.');
  phase = 'pairing and membership protection';
  await denied(getDoc(doc(stranger.db, `couple_spaces/${spaceId}`)));
  await runTransaction(noor.db, async tx => {
    await tx.get(doc(noor.db, `users/${noor.uid}`));
    const inviteRef = doc(noor.db, `_invites/${code}`);
    const invite = (await tx.get(inviteRef)).data();
    assert.equal(invite.ownerUid, adam.uid);
    tx.update(doc(noor.db, `couple_spaces/${spaceId}`), { user2Id: noor.uid });
    tx.update(doc(noor.db, `users/${adam.uid}`), { partnerId: noor.uid });
    tx.update(doc(noor.db, `users/${noor.uid}`), { partnerId: adam.uid, coupleSpaceId: spaceId });
    tx.delete(inviteRef);
  });
  assert.equal((await getDoc(doc(adam.db, `users/${noor.uid}`))).data().partnerId, adam.uid);
  assert.equal((await getDoc(doc(adam.db, `_invites/${code}`))).exists(), false);

  const pathFor = id => `couple_spaces/${spaceId}/envelopes/${id}`;
  async function sendLetter(id, unlockTimestamp = null) {
    const path = track(pathFor(id));
    const bodyPath = track(`${path}/content/letter`);
    await runTransaction(adam.db, async tx => {
      await tx.get(doc(adam.db, `users/${adam.uid}`));
      const ref = doc(adam.db, path);
      if ((await tx.get(ref)).exists()) {
        assert.equal((await tx.get(doc(adam.db, bodyPath))).data().letterText, 'A disposable cloud test. رسالة اختبار.');
        return;
      }
      tx.set(ref, {
        envelopeId: id, senderId: adam.uid, receiverId: noor.uid,
        titlePrompt: 'Open when testing cloud sync', envelopeColorHex: '#F2C6C2', sealIcon: 'Heart',
        unlockRule: unlockTimestamp == null ? 'ANYTIME' : 'DATE_LOCKED', unlockTimestamp,
        isOpened: false, lastOpenedAt: null, openCount: 0, partnerReaction: null,
        createdAt: serverTimestamp(), lastOpenRequestId: null,
      });
      tx.set(doc(adam.db, bodyPath), { letterText: 'A disposable cloud test. رسالة اختبار.', songUrl: null });
    });
  }
  async function openLetter(id, requestId = randomUUID()) {
    const operation = () => runTransaction(noor.db, async tx => {
      await tx.get(doc(noor.db, `users/${noor.uid}`));
      const ref = doc(noor.db, pathFor(id));
      const current = (await tx.get(ref)).data();
      const content = (await tx.get(doc(noor.db, `${pathFor(id)}/content/letter`))).data();
      if (current.lastOpenRequestId !== requestId) {
        tx.update(ref, { isOpened: true, openCount: increment(1), lastOpenedAt: serverTimestamp(), lastOpenRequestId: requestId });
      }
      return content;
    });
    try { return await operation(); }
    catch (error) { if (error.code !== 'permission-denied') throw error; return operation(); }
  }
  phase = 'sending and idempotent retry';
  await sendLetter('anytime');
  await sendLetter('anytime');
  await sendLetter('future', Date.now() + 86400000);
  await sendLetter('past', Date.now() - 86400000);
  phase = 'date locks and outsider isolation';
  await denied(getDoc(doc(noor.db, `${pathFor('future')}/content/letter`)));
  await denied(getDoc(doc(stranger.db, `${pathFor('anytime')}/content/letter`)));
  await denied(updateDoc(doc(adam.db, pathFor('anytime')), {
    isOpened: true, openCount: increment(1), lastOpenedAt: serverTimestamp(), lastOpenRequestId: randomUUID(),
  }));
  phase = 'real-time receipt and opening retries';
  const receipt = awaitSnapshot(adam.db, pathFor('anytime'), data => data.openCount === 1);
  const requestId = randomUUID();
  await openLetter('anytime', requestId);
  await receipt;
  await openLetter('anytime', requestId);
  assert.equal((await getDoc(doc(adam.db, pathFor('anytime')))).data().openCount, 1);
  await openLetter('past');
  phase = 'real-time partner reaction';
  const reaction = awaitSnapshot(adam.db, pathFor('anytime'), data => data.partnerReaction === 'Sending a hug');
  await updateDoc(doc(noor.db, pathFor('anytime')), { partnerReaction: 'Sending a hug' });
  await reaction;
  manifest.passed = true;
  saveManifest();
  console.log('PASS: cloud anonymous sign-in, pairing, text sync, date locks, receipt retry, real-time alerts/reactions, outsider denial.');
}

(async () => {
  try { await main(); }
  catch (error) {
    console.error(`FAIL during ${phase}: ${error.code || error.name || 'UnknownError'}`);
    process.exitCode = 1;
  } finally {
    for (const stop of [...stops]) stop();
    for (const client of clients) {
      if (client.auth.currentUser) {
        try {
          await deleteUser(client.auth.currentUser);
          manifest.deletedAuthUserIds.push(client.uid);
          saveManifest();
        } catch (error) {
          console.error(`Disposable Auth cleanup still required: ${error.code || 'UnknownError'}`);
          process.exitCode = 1;
        }
      }
      await terminate(client.db).catch(() => {});
      await deleteApp(client.app).catch(() => {});
    }
    saveManifest();
    console.log(`Firestore cleanup manifest: ${manifestPath}`);
  }
})();
