const test = require('node:test');
const assert = require('node:assert/strict');
const { randomUUID } = require('node:crypto');
const { initializeApp, deleteApp } = require('firebase/app');
const { getAuth, connectAuthEmulator, signInAnonymously } = require('firebase/auth');
const { getFirestore, connectFirestoreEmulator, doc, setDoc, getDoc } = require('firebase/firestore');
const { getFunctions, connectFunctionsEmulator, httpsCallable } = require('firebase/functions');
const { getStorage, connectStorageEmulator, ref, uploadBytes } = require('firebase/storage');
const apps = [];

async function person(name) {
  const app = initializeApp({ projectId:'demo-open-when', apiKey:'demo-api-key', appId:`demo:${randomUUID()}`, storageBucket:'demo-open-when.appspot.com' }, randomUUID());
  apps.push(app);
  const auth = getAuth(app);
  connectAuthEmulator(auth, 'http://127.0.0.1:9099', { disableWarnings:true });
  const db = getFirestore(app);
  connectFirestoreEmulator(db, '127.0.0.1', 8080);
  const functions = getFunctions(app);
  connectFunctionsEmulator(functions, '127.0.0.1', 5001);
  const storage = getStorage(app);
  connectStorageEmulator(storage, '127.0.0.1', 9199);
  const uid = (await signInAnonymously(auth)).user.uid;
  await setDoc(doc(db, `users/${uid}`), { uid, name, partnerId:null, coupleSpaceId:null, fcmToken:null, currentMood:'' });
  return { uid, db, storage, call: async (method, data) => (await httpsCallable(functions, method)(data)).data };
}

test.after(async () => { await Promise.all(apps.map(deleteApp)); });
test('real callable transactions enforce pairing, idempotent sends, date locks and concurrent one-time opens', async () => {
  const [sender, receiver, outsider] = await Promise.all([person('Adam'), person('Noor'), person('Stranger')]);
  const space = await sender.call('createSpace', { anniversaryDate:Date.now() - 86400000 });
  assert.match(space.inviteCode, /^\d{6}$/);
  await assert.rejects(sender.call('joinSpace', { inviteCode:space.inviteCode }));
  const attempts = await Promise.allSettled([
    receiver.call('joinSpace', { inviteCode:space.inviteCode }),
    outsider.call('joinSpace', { inviteCode:space.inviteCode }),
  ]);
  assert.equal(attempts.filter(result => result.status === 'fulfilled').length, 1);
  const recipient = attempts[0].status === 'fulfilled' ? receiver : outsider;
  const stranger = recipient === receiver ? outsider : receiver;
  const senderProfile = (await getDoc(doc(sender.db, `users/${sender.uid}`))).data();
  assert.equal(senderProfile.partnerId, recipient.uid);
  const draft = { titlePrompt:'Open when you need courage', letterText:'I am here with you.', envelopeColorHex:'#F0C8C3', sealIcon:'Heart',
    photoUrl:null, photoCaption:null, voiceNoteUrl:null, voiceDurationSec:0, songUrl:null,
    unlockRule:'ONE_TIME_ONLY', unlockTimestamp:null, requestId:randomUUID() };
  const first = await sender.call('sendEnvelope', draft);
  const retry = await sender.call('sendEnvelope', draft);
  assert.equal(first.envelopeId, retry.envelopeId);
  const preview = await sender.call('openEnvelope', { envelopeId:first.envelopeId, requestId:randomUUID() });
  assert.equal(preview.letterText, draft.letterText);
  assert.equal(preview.openCount, 0);
  await assert.rejects(stranger.call('openEnvelope', { envelopeId:first.envelopeId, requestId:randomUUID() }));
  const openingIds = [randomUUID(), randomUUID()];
  const openings = await Promise.allSettled(openingIds.map(requestId => recipient.call('openEnvelope', { envelopeId:first.envelopeId, requestId })));
  assert.equal(openings.filter(result => result.status === 'fulfilled').length, 1);
  const winnerIndex = openings.findIndex(result => result.status === 'fulfilled');
  const repeated = await recipient.call('openEnvelope', { envelopeId:first.envelopeId, requestId:openingIds[winnerIndex] });
  assert.equal(repeated.openCount, 1);
  const metadata = (await getDoc(doc(sender.db, `couple_spaces/${space.spaceId}/envelopes/${first.envelopeId}`))).data();
  assert.equal(metadata.openCount, 1);
  assert.equal(metadata.letterText, undefined);
  await assert.rejects(sender.call('reactToEnvelope', { envelopeId:first.envelopeId, reaction:'❤️' }));
  await recipient.call('reactToEnvelope', { envelopeId:first.envelopeId, reaction:'Sending a hug 🫂' });
  const dated = await sender.call('sendEnvelope', { ...draft, requestId:randomUUID(), unlockRule:'DATE_LOCKED', unlockTimestamp:Date.now() + 3600000 });
  await assert.rejects(recipient.call('openEnvelope', { envelopeId:dated.envelopeId, requestId:randomUUID() }));
  const photo = ref(sender.storage, `couple_media/${space.spaceId}/${sender.uid}/${randomUUID()}`);
  await uploadBytes(photo, new Uint8Array([255,216,255,217]), { contentType:'image/jpeg' });
  const withPhoto = await sender.call('sendEnvelope', { ...draft, requestId:randomUUID(), unlockRule:'ANYTIME', photoUrl:photo.toString() });
  const photoLetter = await recipient.call('openEnvelope', { envelopeId:withPhoto.envelopeId, requestId:randomUUID() });
  assert.equal(typeof photoLetter.photoUrl, 'string');
  assert.equal((await fetch(photoLetter.photoUrl)).status, 200);
  const missingPhoto = await sender.call('sendEnvelope', { ...draft, requestId:randomUUID(), unlockRule:'ONE_TIME_ONLY',
    photoUrl:`gs://demo-open-when.appspot.com/couple_media/${space.spaceId}/${sender.uid}/missing` });
  const missingPhotoLetter = await recipient.call('openEnvelope', { envelopeId:missingPhoto.envelopeId, requestId:randomUUID() });
  assert.equal(missingPhotoLetter.letterText, draft.letterText);
  assert.equal(missingPhotoLetter.photoUrl, null);
  assert.equal(missingPhotoLetter.openCount, 1);
});
