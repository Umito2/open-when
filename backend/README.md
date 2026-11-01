# Firebase backends

**`spark` uses Authentication and Firestore directly on the free Spark plan. `live` uses the TypeScript/Cloud Functions backend in this directory and requires Blaze.** The `demo` flavor stays entirely local.

The dedicated Spark project is **`open-when-umito2`** (project number `828591288686`). Billing has not been enabled. Wepick is a different app and must not receive this repository's rules or functions.

## Free Spark edition

Spark supports Anonymous/Email sign-in, six-digit pairing, text letters, HTTPS song links, date locks, reactions and foreground activity. Google sign-in is hidden until configured. This edition does not use Storage uploads, photo/voice attachments, Functions or `ONE_TIME_ONLY` envelopes.

Register the Android application ID matching the build: `com.openwhen.app` for release or `com.openwhen.app.debug` for debug. Save its Firebase configuration to `app/src/spark/google-services.json`. Enable the corresponding Authentication providers and create the Firestore `(default)` database.

Use **[firebase.spark.json](../firebase.spark.json)** and **[firestore.spark.rules](../firestore.spark.rules)** for Spark. From the repository root on Windows:

```powershell
$env:DEBUG = ''
npm --prefix backend ci
.\backend\node_modules\.bin\firebase.cmd login
.\backend\node_modules\.bin\firebase.cmd deploy --config firebase.spark.json --project open-when-umito2 --only firestore:rules,firestore:indexes
```

On macOS/Linux use `./backend/node_modules/.bin/firebase` instead of the `.cmd` executable. This deployment selects only Firestore rules and indexes; it does not deploy Functions or enable billing. Keep explicit `--config` and `--project` arguments rather than relying on a previously selected project.

### Spark authorization

Profiles/shared spaces use `users/{uid}` and `couple_spaces/{spaceId}`. Envelope metadata and receipts are separate from `envelopes/{id}/content/letter`. Rules restrict access to the couple and enforce accepted pairing, envelope and receipt transitions. The sender can read their letter; recipients can read only when the date condition permits, checked against server `request.time`.

The invitation index permits an authenticated exact-code document lookup and forbids collection listing. **Spark has no server-side invitation attempt limiter.** Six digits are not a strong secret against determined guessing. Share the invitation privately and pair promptly. This differs from the callable backend's rate-limited flow.

Receipts/reactions come from the authenticated recipient's app under rules. A receipt records an allowed account update, not proof of physical viewing or completion of the wax-seal gesture. Spark does not offer one-time opening because direct client reads cannot provide the callable backend's single-opening gate.

App Check is **not enforced** in the initial Spark setup. Firebase Authentication and Firestore rules remain mandatory. The full-backend emulator suite below does not validate Spark rules or the deployed Spark project; validate this edition separately before release.

Run `npm --prefix backend run test:spark` for the 16 Spark rules tests. These cover client transactions, membership isolation, expired invitations, concurrent pairing/sending/opening, date locks and immutable content. They use the isolated `demo-open-when-spark` emulator project.

For an explicitly authorized live check, `node backend/test/spark-cloud-smoke.cjs --project open-when-umito2` reads the ignored Android configuration and creates disposable accounts/data. It deletes its Auth accounts and writes the exact Firestore cleanup targets under `.tools/spark-cloud-smoke`. An administrator must then remove only those recorded documents, checking invitation ownership before deleting an invitation candidate. This opt-in script is never run by CI. The deployed project's smoke test passed on 1 November 2026, and its temporary accounts/documents were removed.

## Full multimedia edition on Blaze

Use a separately selected Blaze project for `live`. Register the matching Android IDs and save configuration at `app/src/live/google-services.json`. Enable Anonymous, Email/Password and Google authentication. Register signing SHA-1/SHA-256 fingerprints; Google Credential Manager needs the generated Web OAuth client ID, `default_web_client_id`.

Provision Firestore `(default)`, a default Storage bucket and Functions. Backend and Android Functions instances use **us-central1**. Register Play Integrity App Check for the release certificate and a debug token for each local test installation. Callables enforce App Check outside the emulator. For GitHub/outside-Play APKs, follow [distribution-specific App Check settings](https://firebase.google.com/docs/app-check/android/play-integrity-provider); default Play recognition requirements are unsuitable for an app never published on Play.

Install Node 22. From the repository root on Windows, replace the placeholder with the intended Blaze project ID:

```powershell
$env:DEBUG = ''
$BlazeProjectId = 'YOUR_DEDICATED_BLAZE_PROJECT_ID'
npm --prefix backend ci
npm --prefix backend test
npm --prefix backend run build
.\backend\node_modules\.bin\firebase.cmd login
.\backend\node_modules\.bin\firebase.cmd deploy --config firebase.json --project $BlazeProjectId --only functions,firestore:rules,firestore:indexes,storage
```

Do not use `open-when-umito2` for that command while it is the free Spark deployment. Both config files define complete rulesets, not additive patches. Replacing Spark rules with full rules prevents Spark clients from pairing/writing; replacing full rules with Spark rules changes the security model.

Run the full backend's tests with `npm --prefix backend test`, `npm --prefix backend run test:rules` and `npm --prefix backend run test:integration`. The rule/integration scripts use the isolated `demo-open-when` emulator project and require a JDK. For manual emulator use, build Functions and start Auth/Functions/Firestore/Storage with `firebase.json`; point Android SDK instances to `10.0.2.2` and its configured ports before creating repositories. A normal installed app points to its real project. App Check bypass is restricted to the Functions emulator runtime.

### Full-backend data and authorization

`users` and `couple_spaces` follow the requested schema. Callable transactions alone create and join spaces, make receipts and write reactions. The six-digit invitation is stored in a server-only index, cannot be enumerated by clients, is deleted after pairing and has a per-user attempt limit. Transactions prevent two people consuming one invitation.

Firestore cannot hide individual fields inside a readable document. Envelope documents therefore contain the requested public envelope fields and receipt state, while `letterText`, `photoUrl`, `photoCaption`, `voiceNoteUrl` and `songUrl` live in `envelopes/{id}/content/letter`. The metadata listener never downloads a date-locked letter. Only the sender can read that content document directly; recipients receive content through the `openEnvelope` callable after server-time checks. One-time envelopes can be reacted to after opening but cannot be opened again. A two-minute retry window for the same opening request ID prevents lost network responses from consuming a second receipt. The Android repository retains request IDs across retry attempts in the active process.

Media is uploaded under its owner's private `couple_media/{spaceId}/{uid}` prefix. Recipients cannot list or directly read the sender's uploads. The callable releases Firebase download URLs together with opened content. Such URLs are bearer links once disclosed; recipients can keep copies of letters, photos and audio they have already received. One-time envelopes control app access, not copying or screenshots.

The full backend updates receipts inside the authorized opening callable rather than accepting direct receipt writes. The initial metadata stream waits for a server snapshot to avoid treating stale cached receipts as new events.

## Retention and operations

Both editions show Firestore-driven activity while the app is running. **Neither sends background FCM push messages**; `fcmToken` is modeled only. Letters are not end-to-end encrypted, and the free service is subject to Spark quotas.

For full `live`, unattached uploads can remain if a user abandons the studio. Configure cleanup of old unreferenced objects before operating at scale; immutable Storage rules avoid users deleting attachments from sent letters. Complete account deletion, export, unpairing and retention flows before a public store launch. Signing out intentionally does not delete a couple's shared letters.
