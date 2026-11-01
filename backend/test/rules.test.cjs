const { readFileSync } = require('node:fs');
const { resolve } = require('node:path');
const test = require('node:test');
const { initializeTestEnvironment, assertFails, assertSucceeds } = require('@firebase/rules-unit-testing');
const { doc, getDoc, setDoc, updateDoc, collection, getDocs } = require('firebase/firestore');
const { ref, uploadBytes, getBytes, listAll } = require('firebase/storage');
let env;
test.before(async () => {
  env = await initializeTestEnvironment({
    projectId: 'demo-open-when',
    firestore: { rules: readFileSync(resolve(__dirname, '../../firestore.rules'), 'utf8') },
    storage: { rules: readFileSync(resolve(__dirname, '../../storage.rules'), 'utf8') },
  });
  await env.withSecurityRulesDisabled(async context => {
    const db = context.firestore();
    await setDoc(doc(db, 'users/adam'), { uid:'adam', name:'Adam', partnerId:'noor', coupleSpaceId:'ours', fcmToken:null, currentMood:'' });
    await setDoc(doc(db, 'users/noor'), { uid:'noor', name:'Noor', partnerId:'adam', coupleSpaceId:'ours', fcmToken:null, currentMood:'' });
    await setDoc(doc(db, 'couple_spaces/ours'), { user1Id:'adam', user2Id:'noor', inviteCode:'123456' });
    await setDoc(doc(db, 'couple_spaces/ours/envelopes/sealed'), { senderId:'adam', receiverId:'noor', isOpened:false, openCount:0 });
    await setDoc(doc(db, 'couple_spaces/ours/envelopes/sealed/content/letter'), { letterText:'A private letter.' });
    await setDoc(doc(db, '_invites/123456'), { ownerUid:'adam', spaceId:'ours' });
  });
});
test.after(async () => { if (env) await env.cleanup(); });
test('recipient reads metadata but cannot fetch sealed content directly', async () => {
  const db = env.authenticatedContext('noor').firestore();
  await assertSucceeds(getDoc(doc(db, 'couple_spaces/ours/envelopes/sealed')));
  await assertFails(getDoc(doc(db, 'couple_spaces/ours/envelopes/sealed/content/letter')));
});
test('sender can read their own letter', async () => {
  await assertSucceeds(getDoc(doc(env.authenticatedContext('adam').firestore(), 'couple_spaces/ours/envelopes/sealed/content/letter')));
});
test('outsider and unauthenticated clients cannot read couple metadata or invites', async () => {
  for (const context of [env.authenticatedContext('stranger'), env.unauthenticatedContext()]) {
    await assertFails(getDoc(doc(context.firestore(), 'couple_spaces/ours/envelopes/sealed')));
    await assertFails(getDoc(doc(context.firestore(), '_invites/123456')));
  }
});
test('client cannot forge receipts or change pairing', async () => {
  const db = env.authenticatedContext('noor').firestore();
  await assertFails(updateDoc(doc(db, 'couple_spaces/ours/envelopes/sealed'), { isOpened:true, openCount:100 }));
  await assertFails(updateDoc(doc(db, 'users/noor'), { partnerId:'stranger' }));
  await assertFails(updateDoc(doc(db, 'couple_spaces/ours'), { user2Id:'stranger' }));
  await assertSucceeds(updateDoc(doc(db, 'users/noor'), { currentMood:'Missing you' }));
});
test('a partner can read profile but cannot enumerate all users', async () => {
  const db = env.authenticatedContext('noor').firestore();
  await assertSucceeds(getDoc(doc(db, 'users/adam')));
  await assertFails(getDocs(collection(db, 'users')));
});
test('profile creation cannot claim a space or partner', async () => {
  const db = env.authenticatedContext('new-user').firestore();
  await assertFails(setDoc(doc(db, 'users/new-user'), { uid:'new-user', name:'Someone', partnerId:'adam', coupleSpaceId:'ours', fcmToken:null, currentMood:'' }));
  await assertSucceeds(setDoc(doc(db, 'users/new-user'), { uid:'new-user', name:'Someone', partnerId:null, coupleSpaceId:null, fcmToken:null, currentMood:'' }));
});
test('media uploads are immutable and private to their owner until callable disclosure', async () => {
  const path = 'couple_media/ours/adam/memory';
  const ownerStorage = env.authenticatedContext('adam').storage();
  await assertSucceeds(uploadBytes(ref(ownerStorage, path), new Uint8Array([1,2,3]), { contentType:'image/jpeg' }));
  await assertSucceeds(getBytes(ref(ownerStorage, path)));
  await assertFails(getBytes(ref(env.authenticatedContext('noor').storage(), path)));
  await assertFails(listAll(ref(ownerStorage, 'couple_media/ours/adam')));
  await assertFails(uploadBytes(ref(ownerStorage, path), new Uint8Array([4,5]), { contentType:'image/jpeg' }));
  await assertFails(uploadBytes(ref(ownerStorage, 'couple_media/ours/adam/executable'), new Uint8Array([1]), { contentType:'application/octet-stream' }));
  await assertFails(uploadBytes(ref(env.authenticatedContext('noor').storage(), 'couple_media/ours/adam/forged'), new Uint8Array([1]), { contentType:'image/jpeg' }));
});
