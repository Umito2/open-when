# Firebase service

The Android `demo` flavor works independently of Firebase. The `live` flavor uses Firebase Authentication, callable Functions, Firestore metadata streams and Cloud Storage.

1. Create a Firebase project and an Android app matching the live application ID from `app/build.gradle.kts`; put its real `google-services.json` in `app/`.
2. Enable Anonymous, Email/Password and Google in Authentication. Register the debug and release SHA-256 signing certificates. Google Credential Manager uses the Web OAuth client ID (`default_web_client_id` supplied by the Google Services plugin), not the Android client ID.
3. Enable Firestore, Storage and Functions on the Blaze plan. Register Play Integrity in Firebase App Check. Register a debug token for local debug builds. Callable functions enforce App Check outside the emulator.
4. From `backend`, run `npm ci`, `npm test`, then `npx firebase login`. From the repository root run `npx --prefix backend firebase use --add` to select the real project. Build functions with `npm --prefix backend run build`; deploy with `backend/node_modules/.bin/firebase deploy --only functions,firestore:rules,firestore:indexes,storage` (or the equivalent `.cmd` executable on Windows).
5. Run rule tests with `npm run test:rules` from `backend`. A JDK is needed for the Firebase emulators. Start all local services with `npm run serve`, then point Android Firebase SDK instances to Android emulator host `10.0.2.2` and ports from `firebase.json` before creating repositories. App Check is disabled only by the Functions emulator runtime.

## Data and authorization

`users` and `couple_spaces` follow the requested schema. Callable transactions alone create and join spaces, make receipts and write reactions. The six-digit invitation is stored in a server-only index, cannot be enumerated by clients, is deleted after pairing and has a per-user attempt limit. Transactions prevent two people consuming one invitation.

Firestore cannot hide individual fields inside a readable document. Envelope documents therefore contain the requested public envelope fields and receipt state, while `letterText`, `photoUrl`, `photoCaption`, `voiceNoteUrl` and `songUrl` live in `envelopes/{id}/content/letter`. The metadata listener never downloads a date-locked letter. Only the sender can read that content document directly; recipients receive content through the `openEnvelope` callable after server-time checks. One-time envelopes can be reacted to after opening but cannot be opened again. A two-minute retry window for the same opening request ID prevents lost network responses from consuming a second receipt. The Android repository retains request IDs across retry attempts in the active process.

Media is uploaded under its owner's private `couple_media/{spaceId}/{uid}` prefix. Recipients cannot list or directly read the sender's uploads. The callable releases Firebase download URLs together with opened content. Such URLs are bearer links once disclosed; recipients can keep copies of letters, photos and audio they have already received. One-time envelopes control app access, not copying or screenshots.

The in-app sender banner comes from Firestore changes while the app is running. This project does not send background FCM push messages; `fcmToken` is modeled for a future push channel. Client writes cannot forge sender receipt alerts. The initial metadata stream waits for a server snapshot to avoid treating stale cached receipts as new events.

## Retention and operations

Unattached uploads can remain if a user abandons the studio. Configure a scheduled cleanup for old unreferenced objects before operating at scale; immutable storage rules avoid users deleting attachments from already-sent letters. Decide the product's account deletion, export, breakup/unpairing and retention policy before launching to a public audience. This implementation intentionally does not silently delete a couple's shared letters when one partner signs out.
