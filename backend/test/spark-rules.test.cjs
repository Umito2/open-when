const { readFileSync } = require('node:fs');
const { resolve } = require('node:path');
const { randomUUID } = require('node:crypto');
const assert = require('node:assert/strict');
const test = require('node:test');
const { initializeTestEnvironment, assertFails, assertSucceeds } = require('@firebase/rules-unit-testing');
const {
  doc, getDoc, setDoc, updateDoc, deleteDoc, collection, getDocs, writeBatch,
  runTransaction, serverTimestamp, Timestamp, increment, Bytes,
} = require('firebase/firestore');
let env;
const clientUids = new WeakMap();
const user = uid => ({ uid, name: uid, partnerId: null, coupleSpaceId: null, fcmToken: null, currentMood: '' });
const dbFor = uid => {
  const db = env.authenticatedContext(uid).firestore();
  clientUids.set(db, uid);
  return db;
};
const envelopePath = (id = 'letter') => `couple_spaces/ours/envelopes/${id}`;
// A disposable 2 x 2 JPEG; no user photo or metadata is included in test fixtures.
const jpegBase64 = '/9j/4AAQSkZJRgABAQEAYABgAAD/2wBDAAMCAgMCAgMDAwMEAwMEBQgFBQQEBQoHBwYIDAoMDAsKCwsNDhIQDQ4RDgsLEBYQERMUFRUVDA8XGBYUGBIUFRT/2wBDAQMEBAUEBQkFBQkUDQsNFBQUFBQUFBQUFBQUFBQUFBQUFBQUFBQUFBQUFBQUFBQUFBQUFBQUFBQUFBQUFBQUFBT/wAARCAACAAIDASIAAhEBAxEB/8QAHwAAAQUBAQEBAQEAAAAAAAAAAAECAwQFBgcICQoL/8QAtRAAAgEDAwIEAwUFBAQAAAF9AQIDAAQRBRIhMUEGE1FhByJxFDKBkaEII0KxwRVS0fAkM2JyggkKFhcYGRolJicoKSo0NTY3ODk6Q0RFRkdISUpTVFVWV1hZWmNkZWZnaGlqc3R1dnd4eXqDhIWGh4iJipKTlJWWl5iZmqKjpKWmp6ipqrKztLW2t7i5usLDxMXGx8jJytLT1NXW19jZ2uHi4+Tl5ufo6erx8vP09fb3+Pn6/8QAHwEAAwEBAQEBAQEBAQAAAAAAAAECAwQFBgcICQoL/8QAtREAAgECBAQDBAcFBAQAAQJ3AAECAxEEBSExBhJBUQdhcRMiMoEIFEKRobHBCSMzUvAVYnLRChYkNOEl8RcYGRomJygpKjU2Nzg5OkNERUZHSElKU1RVVldYWVpjZGVmZ2hpanN0dXZ3eHl6goOEhYaHiImKkpOUlZaXmJmaoqOkpaanqKmqsrO0tba3uLm6wsPExcbHyMnK0tPU1dbX2Nna4uPk5ebn6Onq8vP09fb3+Pn6/9oADAMBAAIRAxEAPwD751bVr611W9ggvbiGGOZ0SOOVlVVDEAAA8ADtRRRWB9Sf/9k=';
const photoContent = (changes = {}) => ({
  letterText: 'A little memory for you.', songUrl: null,
  photoBytes: Bytes.fromBase64String(jpegBase64), photoMimeType: 'image/jpeg', photoCaption: 'Our little memory',
  ...changes,
});

async function register(uid) {
  const db = dbFor(uid);
  await setDoc(doc(db, `users/${uid}`), user(uid));
  return db;
}
async function createSpace(db, uid = 'adam', spaceId = 'ours', code = '123456') {
  await runTransaction(db, async tx => {
    const profile = doc(db, `users/${uid}`);
    const invite = doc(db, `_invites/${code}`);
    await tx.get(profile);
    assert.equal((await tx.get(invite)).exists(), false);
    tx.set(doc(db, `couple_spaces/${spaceId}`), {
      spaceId, inviteCode: code, user1Id: uid, user2Id: null,
      anniversaryDate: 1700000000000, createdAt: serverTimestamp(),
    });
    tx.update(profile, { coupleSpaceId: spaceId });
    tx.set(invite, {
      spaceId, ownerUid: uid, createdAt: serverTimestamp(),
      expiresAt: Timestamp.fromMillis(Date.now() + 24 * 60 * 60 * 1000),
    });
  });
}
async function join(db, uid = 'noor', code = '123456', change = {}, selfChange = {}) {
  await runTransaction(db, async tx => {
    const self = doc(db, `users/${uid}`);
    const inviteRef = doc(db, `_invites/${code}`);
    await tx.get(self);
    const invite = (await tx.get(inviteRef)).data();
    if (!invite) throw new Error('The invitation is no longer available.');
    tx.update(doc(db, `couple_spaces/${invite.spaceId}`), { user2Id: uid });
    tx.update(doc(db, `users/${invite.ownerUid}`), { partnerId: uid, ...change });
    tx.update(self, { partnerId: invite.ownerUid, coupleSpaceId: invite.spaceId, ...selfChange });
    tx.delete(inviteRef);
  });
}
async function couple() {
  const adam = await register('adam');
  const noor = await register('noor');
  await createSpace(adam);
  await join(noor);
  return { adam, noor };
}
function metadata(id = 'letter', options = {}) {
  return {
    envelopeId: id, senderId: 'adam', receiverId: 'noor', titlePrompt: 'Open when you miss me',
    envelopeColorHex: '#F2C6C2', sealIcon: 'Heart', unlockRule: 'ANYTIME', unlockTimestamp: null,
    isOpened: false, lastOpenedAt: null, openCount: 0, partnerReaction: null,
    createdAt: serverTimestamp(), lastOpenRequestId: null, ...options,
  };
}
async function send(db, id = 'letter', options = {}, content = { letterText: 'You are my safe place. أنتِ بيتي.', songUrl: null }) {
  return runTransaction(db, async tx => {
    await tx.get(doc(db, `users/${clientUids.get(db)}`));
    const ref = doc(db, envelopePath(id));
    if ((await tx.get(ref)).exists()) {
      const existing = (await tx.get(doc(db, `${envelopePath(id)}/content/letter`))).data();
      assert.equal(existing.letterText, content.letterText, 'A retry must preserve the sealed words.');
      assert.equal(existing.songUrl ?? null, content.songUrl ?? null, 'A retry must preserve the sealed song.');
      assert.equal(existing.photoBytes?.toBase64() ?? null, content.photoBytes?.toBase64() ?? null, 'A retry must preserve the sealed photo.');
      assert.equal(existing.photoMimeType ?? null, content.photoMimeType ?? null, 'A retry must preserve the photo type.');
      assert.equal(existing.photoCaption ?? null, content.photoCaption ?? null, 'A retry must preserve the photo caption.');
      return id;
    }
    tx.set(ref, metadata(id, options));
    tx.set(doc(db, `${envelopePath(id)}/content/letter`), content);
    return id;
  });
}
async function open(db, id = 'letter', requestId = randomUUID()) {
  return runTransaction(db, async tx => {
    await tx.get(doc(db, `users/${clientUids.get(db)}`));
    const ref = doc(db, envelopePath(id));
    const previous = (await tx.get(ref)).data();
    const content = (await tx.get(doc(db, `${envelopePath(id)}/content/letter`))).data();
    if (previous.lastOpenRequestId !== requestId) {
      tx.update(ref, {
        isOpened: true, openCount: increment(1),
        lastOpenedAt: serverTimestamp(), lastOpenRequestId: requestId,
      });
    }
    return content;
  });
}

test.before(async () => {
  env = await initializeTestEnvironment({
    projectId: 'demo-open-when-spark',
    firestore: { rules: readFileSync(resolve(__dirname, '../../firestore.spark.rules'), 'utf8') },
  });
});
test.beforeEach(async () => env.clearFirestore());
test.after(async () => { if (env) await env.cleanup(); });

test('real client create/join transactions link both users and consume the code', async () => {
  const { adam, noor } = await couple();
  assert.equal((await getDoc(doc(adam, 'users/adam'))).data().partnerId, 'noor');
  assert.equal((await getDoc(doc(noor, 'users/noor'))).data().coupleSpaceId, 'ours');
  assert.equal((await getDoc(doc(noor, 'users/adam'))).data().partnerId, 'noor');
  assert.equal((await getDoc(doc(noor, 'couple_spaces/ours'))).data().user2Id, 'noor');
  assert.equal((await getDoc(doc(noor, '_invites/123456'))).exists(), false);
});

test('only one of two concurrent partners can claim an invitation', async () => {
  const adam = await register('adam');
  const noor = await register('noor');
  const other = await register('other');
  await createSpace(adam);
  const result = await Promise.allSettled([join(noor), join(other, 'other')]);
  assert.equal(result.filter(item => item.status === 'fulfilled').length, 1);
  const space = (await getDoc(doc(adam, 'couple_spaces/ours'))).data();
  assert.ok(['noor', 'other'].includes(space.user2Id));
  assert.equal((await getDoc(doc(adam, 'users/adam'))).data().partnerId, space.user2Id);
});

test('invites do not grant pre-join access to a space or owner profile and cannot be listed', async () => {
  const adam = await register('adam');
  const other = await register('other');
  await createSpace(adam);
  await assertSucceeds(getDoc(doc(other, '_invites/123456')));
  await assertFails(getDoc(doc(other, 'users/adam')));
  await assertFails(getDoc(doc(other, 'couple_spaces/ours')));
  await assertFails(getDocs(collection(other, '_invites')));
  await assertFails(getDocs(collection(other, 'users')));
  await assertFails(getDocs(collection(other, 'couple_spaces')));
  await assertFails(getDoc(doc(env.unauthenticatedContext().firestore(), '_invites/123456')));
});

test('an expired invitation cannot pair; its owner can renew it without changing identity', async () => {
  const adam = await register('adam');
  const noor = await register('noor');
  await createSpace(adam);
  await env.withSecurityRulesDisabled(async context => {
    await updateDoc(doc(context.firestore(), '_invites/123456'), { expiresAt: Timestamp.fromMillis(Date.now() - 1000) });
  });
  await assertFails(join(noor));
  await assertFails(updateDoc(doc(noor, '_invites/123456'), { expiresAt: Timestamp.fromMillis(Date.now() + 100000) }));
  await assertSucceeds(updateDoc(doc(adam, '_invites/123456'), { expiresAt: Timestamp.fromMillis(Date.now() + 86400000) }));
  await assertSucceeds(join(noor));
});

test('membership cannot be claimed without the complete join transaction or altered afterward', async () => {
  const adam = await register('adam');
  const noor = await register('noor');
  await createSpace(adam);
  await assertFails(updateDoc(doc(noor, 'users/noor'), { partnerId: 'adam', coupleSpaceId: 'ours' }));
  await assertFails(updateDoc(doc(noor, 'couple_spaces/ours'), { user2Id: 'noor' }));
  await assertFails(join(noor, 'noor', '123456', { name: 'Impersonation' }));
  await assertFails(join(noor, 'noor', '123456', {}, { name: 'Combined profile mutation' }));
  await join(noor);
  await assertFails(updateDoc(doc(noor, 'users/noor'), { partnerId: 'other' }));
  await assertFails(updateDoc(doc(adam, 'couple_spaces/ours'), { user2Id: 'other' }));
  await assertFails(deleteDoc(doc(noor, 'users/noor')));
  await assertSucceeds(updateDoc(doc(noor, 'users/noor'), { currentMood: 'Missing you' }));
});

test('profile creation and space creation cannot bypass initial empty membership', async () => {
  const attacker = dbFor('attacker');
  await assertFails(setDoc(doc(attacker, 'users/attacker'), { ...user('attacker'), partnerId: 'adam', coupleSpaceId: 'ours' }));
  await register('attacker');
  await assertFails(setDoc(doc(attacker, 'couple_spaces/forged'), {
    spaceId: 'forged', inviteCode: '123456', user1Id: 'attacker', user2Id: null,
    anniversaryDate: 0, createdAt: serverTimestamp(),
  }));
  await createSpace(attacker, 'attacker', 'first', '654321');
  await assertFails(createSpace(attacker, 'attacker', 'second', '987654'));
});

test('letters and song links are sent atomically; deterministic retry does not overwrite them', async () => {
  const { adam, noor } = await couple();
  assert.equal(await send(adam), 'letter');
  assert.equal(await send(adam), 'letter');
  await assert.rejects(send(adam, 'letter', {}, { letterText: 'replacement', songUrl: null }), /preserve the sealed words/);
  const body = (await getDoc(doc(adam, `${envelopePath()}/content/letter`))).data();
  assert.equal(body.letterText, 'You are my safe place. أنتِ بيتي.');
  await assertSucceeds(getDocs(collection(noor, 'couple_spaces/ours/envelopes')));
  await assertFails(setDoc(doc(adam, envelopePath('orphan')), metadata('orphan')));
  await assertFails(setDoc(doc(adam, `${envelopePath('orphan')}/content/letter`), { letterText: 'orphan', songUrl: null }));
  await assertFails(updateDoc(doc(adam, `${envelopePath()}/content/letter`), { letterText: 'changed after sending' }));
  await assertFails(deleteDoc(doc(adam, envelopePath())));
});

test('concurrent sends of the same deterministic envelope reconcile without duplicate letters', async context => {
  const { adam } = await couple();
  const results = await Promise.allSettled([send(adam), send(adam)]);
  context.diagnostic(`${results.filter(result => result.status === 'rejected').length} concurrent send needed a reconciliation retry.`);
  for (const result of results) {
    if (result.status === 'rejected') {
      assert.equal(result.reason.code, 'permission-denied');
      await send(adam);
    }
  }
  assert.equal((await getDocs(collection(adam, 'couple_spaces/ours/envelopes'))).size, 1);
  assert.equal((await getDoc(doc(adam, envelopePath()))).data().openCount, 0);
});

test('only the actual sender can create a valid envelope for their paired receiver', async () => {
  const { adam, noor } = await couple();
  await assertFails(send(noor, 'spoofed'));
  await assertFails(send(adam, 'outsider', { receiverId: 'stranger' }));
  await assertFails(send(adam, 'self', { receiverId: 'adam' }));
  await assertFails(send(adam, 'preforged', { openCount: 9, isOpened: true }));
  await assertFails(send(adam, 'one-time', { unlockRule: 'ONE_TIME_ONLY' }));
  await assertFails(send(adam, 'media', { photoUrl: 'https://example.test/private.jpg' }));
  await assertFails(send(adam, 'extra-body', {}, { letterText: 'Hi', songUrl: null, voiceNoteUrl: 'file:///private' }));
  await assertFails(send(adam, 'unsafe-song', {}, { letterText: 'Hi', songUrl: 'javascript:alert(1)' }));
});

test('date-locked content stays unreadable to the receiver until server time permits it', async () => {
  const { adam, noor } = await couple();
  await send(adam, 'future', { unlockRule: 'DATE_LOCKED', unlockTimestamp: Date.now() + 86400000 });
  await send(adam, 'past', { unlockRule: 'DATE_LOCKED', unlockTimestamp: Date.now() - 1000 });
  await assertSucceeds(getDoc(doc(noor, envelopePath('future'))));
  await assertFails(getDoc(doc(noor, `${envelopePath('future')}/content/letter`)));
  await assertSucceeds(getDoc(doc(adam, `${envelopePath('future')}/content/letter`)));
  await assertFails(open(noor, 'future'));
  await assertSucceeds(open(noor, 'past'));
  await assertFails(updateDoc(doc(noor, envelopePath('future')), { unlockTimestamp: 0 }));
});

test('receipt transactions increment once per retry ID and concurrent different openings are atomic', async () => {
  const { adam, noor } = await couple();
  await send(adam);
  const requestId = randomUUID();
  await assert.doesNotReject(open(noor, 'letter', requestId), 'first opening');
  await assert.doesNotReject(open(noor, 'letter', requestId), 'idempotent retry');
  assert.equal((await getDoc(doc(adam, envelopePath()))).data().openCount, 1);
  await assert.doesNotReject(Promise.all([open(noor), open(noor)]), 'concurrent openings');
  const opened = (await getDoc(doc(adam, envelopePath()))).data();
  assert.equal(opened.openCount, 3);
  assert.equal(opened.isOpened, true);
  assert.ok(opened.lastOpenedAt instanceof Timestamp);
});

test('concurrent retries with the same request ID reconcile to one receipt', async () => {
  const { adam, noor } = await couple();
  await send(adam);
  const requestId = randomUUID();
  async function retryOnce() {
    try { return await open(noor, 'letter', requestId); }
    catch (error) {
      if (error.code !== 'permission-denied') throw error;
      return open(noor, 'letter', requestId);
    }
  }
  await Promise.all([retryOnce(), retryOnce()]);
  assert.equal((await getDoc(doc(adam, envelopePath()))).data().openCount, 1);
});

test('sender cannot forge receipts and receiver cannot skip counts, edit content, or spoof dates', async () => {
  const { adam, noor } = await couple();
  await send(adam);
  const receipt = { isOpened: true, openCount: 1, lastOpenedAt: serverTimestamp(), lastOpenRequestId: randomUUID() };
  await assertFails(updateDoc(doc(adam, envelopePath()), receipt));
  await assertFails(updateDoc(doc(noor, envelopePath()), { ...receipt, openCount: 99 }));
  await assertFails(updateDoc(doc(noor, envelopePath()), { ...receipt, lastOpenedAt: Timestamp.fromMillis(0) }));
  await assertFails(updateDoc(doc(noor, envelopePath()), { ...receipt, titlePrompt: 'changed' }));
  await assertFails(updateDoc(doc(noor, `${envelopePath()}/content/letter`), { letterText: 'changed' }));
  await assertSucceeds(open(noor));
  const opened = (await getDoc(doc(noor, envelopePath()))).data();
  await assertFails(updateDoc(doc(noor, envelopePath()), { ...receipt, openCount: 2, lastOpenRequestId: opened.lastOpenRequestId }));
});

test('only receiver may react to an opened envelope, with a bounded text reaction', async () => {
  const { adam, noor } = await couple();
  await send(adam);
  await assertFails(updateDoc(doc(noor, envelopePath()), { partnerReaction: 'Too early' }));
  await open(noor);
  await assertSucceeds(updateDoc(doc(noor, envelopePath()), { partnerReaction: 'Sending a hug 🫂' }));
  assert.equal((await getDoc(doc(adam, envelopePath()))).data().partnerReaction, 'Sending a hug 🫂');
  await assertFails(updateDoc(doc(adam, envelopePath()), { partnerReaction: 'Forged' }));
  await assertFails(updateDoc(doc(noor, envelopePath()), { partnerReaction: 'x'.repeat(161) }));
});

test('outsiders and anonymous requests cannot read letters, write receipts, or enumerate contents', async () => {
  const { adam, noor } = await couple();
  await send(adam);
  for (const db of [dbFor('stranger'), env.unauthenticatedContext().firestore()]) {
    await assertFails(getDoc(doc(db, envelopePath())));
    await assertFails(getDoc(doc(db, `${envelopePath()}/content/letter`)));
    await assertFails(getDocs(collection(db, 'couple_spaces/ours/envelopes')));
    await assertFails(updateDoc(doc(db, envelopePath()), { partnerReaction: 'Intrusion' }));
  }
  await assertFails(getDocs(collection(noor, `${envelopePath()}/content`)));
});

test('partial join cannot leave an invitation reusable or change the owner profile', async () => {
  const adam = await register('adam');
  const noor = await register('noor');
  await createSpace(adam);
  const batch = writeBatch(noor);
  batch.update(doc(noor, 'couple_spaces/ours'), { user2Id: 'noor' });
  batch.update(doc(noor, 'users/adam'), { partnerId: 'noor' });
  batch.update(doc(noor, 'users/noor'), { partnerId: 'adam', coupleSpaceId: 'ours' });
  await assertFails(batch.commit());
  await join(noor);
  await assertFails(setDoc(doc(adam, '_invites/123456'), {
    spaceId: 'ours', ownerUid: 'adam', createdAt: serverTimestamp(), expiresAt: Timestamp.fromMillis(Date.now() + 86400000),
  }));
});

test('private photo letters are atomic and support captions without breaking old text clients', async () => {
  const { adam, noor } = await couple();
  await assertSucceeds(send(adam, 'with-photo', {}, photoContent()));
  const content = await open(noor, 'with-photo');
  assert.equal(content.photoBytes.toBase64(), jpegBase64);
  assert.equal(content.photoMimeType, 'image/jpeg');
  assert.equal(content.photoCaption, 'Our little memory');
  await assertSucceeds(send(adam, 'no-caption', {}, photoContent({ photoCaption: null })));
  await assertSucceeds(send(adam, 'old-text-client'));
  const boundary = new Uint8Array(245760);
  boundary.set(Buffer.from(jpegBase64, 'base64'));
  await assertSucceeds(send(adam, 'byte-boundary', {}, photoContent({ photoBytes: Bytes.fromUint8Array(boundary) })));
  await assertFails(setDoc(doc(adam, `${envelopePath('orphan-photo')}/content/letter`), photoContent()));
});

test('photo content rejects partial groups, wrong types, oversized bytes and invalid captions', async () => {
  const { adam } = await couple();
  const text = { letterText: 'A little memory.', songUrl: null };
  const invalid = [
    { ...text, photoBytes: Bytes.fromBase64String(jpegBase64) },
    { ...text, photoMimeType: 'image/jpeg' },
    { ...text, photoCaption: 'A caption without a photo' },
    { ...text, photoBytes: Bytes.fromBase64String(jpegBase64), photoMimeType: 'image/jpeg' },
    photoContent({ photoBytes: null }),
    photoContent({ photoBytes: jpegBase64 }),
    photoContent({ photoBytes: [255, 216, 255, 217] }),
    photoContent({ photoBytes: Bytes.fromUint8Array(new Uint8Array()) }),
    photoContent({ photoBytes: Bytes.fromUint8Array(new Uint8Array(245761)) }),
    photoContent({ photoMimeType: 'image/png' }),
    photoContent({ photoMimeType: 1 }),
    photoContent({ photoCaption: 'x'.repeat(241) }),
    photoContent({ photoCaption: 17 }),
    photoContent({ photoUrl: 'https://example.test/photo.jpg' }),
    photoContent({ letterText: '' }),
  ];
  for (const [index, content] of invalid.entries()) {
    await assertFails(send(adam, `invalid-photo-${index}`, {}, content));
    assert.equal((await getDoc(doc(adam, envelopePath(`invalid-photo-${index}`)))).exists(), false,
      'Rejected content must not leave envelope metadata behind.');
  }
});

test('photos stay out of metadata and cannot be read or listed by other accounts', async () => {
  const { adam, noor } = await couple();
  await send(adam, 'private-photo', {}, photoContent());
  const metadataOnly = (await getDoc(doc(noor, envelopePath('private-photo')))).data();
  for (const field of ['photoBytes', 'photoMimeType', 'photoCaption', 'letterText']) assert.equal(metadataOnly[field], undefined);
  const bodyPath = `${envelopePath('private-photo')}/content/letter`;
  await assertSucceeds(getDoc(doc(adam, bodyPath)));
  for (const client of [dbFor('outsider'), env.unauthenticatedContext().firestore()]) {
    await assertFails(getDoc(doc(client, bodyPath)));
  }
  await assertFails(getDocs(collection(noor, `${envelopePath('private-photo')}/content`)));
  await assertFails(send(adam, 'public-photo', { photoBytes: Bytes.fromBase64String(jpegBase64) }, photoContent()));
});

test('a photo follows its letter server date lock while its sender can preview it', async () => {
  const { adam, noor } = await couple();
  await send(adam, 'future-photo', { unlockRule: 'DATE_LOCKED', unlockTimestamp: Date.now() + 86400000 }, photoContent());
  await send(adam, 'past-photo', { unlockRule: 'DATE_LOCKED', unlockTimestamp: Date.now() - 1000 }, photoContent());
  await assertSucceeds(getDoc(doc(noor, envelopePath('future-photo'))));
  await assertSucceeds(getDoc(doc(adam, `${envelopePath('future-photo')}/content/letter`)));
  await assertFails(getDoc(doc(noor, `${envelopePath('future-photo')}/content/letter`)));
  await assertFails(open(noor, 'future-photo'));
  assert.equal((await open(noor, 'past-photo')).photoBytes.toBase64(), jpegBase64);
});

test('sealed photo bytes and captions are immutable and retries preserve the exact attachment', async () => {
  const { adam, noor } = await couple();
  const content = photoContent();
  await send(adam, 'same-photo', {}, content);
  await send(adam, 'same-photo', {}, content);
  await assert.rejects(send(adam, 'same-photo', {}, photoContent({ photoBytes: Bytes.fromUint8Array(new Uint8Array([255, 216, 255, 217])) })), /preserve the sealed photo/);
  await assert.rejects(send(adam, 'same-photo', {}, photoContent({ photoCaption: 'A changed caption' })), /preserve the photo caption/);
  await assert.rejects(send(adam, 'same-photo', {}, { letterText: content.letterText, songUrl: null }), /preserve the sealed photo/);
  assert.equal((await getDocs(collection(adam, 'couple_spaces/ours/envelopes'))).size, 1);
  const ref = doc(adam, `${envelopePath('same-photo')}/content/letter`);
  await assertFails(updateDoc(ref, { photoCaption: 'Edited after sealing' }));
  await assertFails(setDoc(ref, photoContent({ photoCaption: null })));
  await assertFails(deleteDoc(ref));
  await open(noor, 'same-photo');
  await assertFails(updateDoc(doc(noor, ref.path), { photoBytes: Bytes.fromBase64String(jpegBase64) }));
  assert.equal((await getDoc(ref)).data().photoCaption, content.photoCaption);
});
