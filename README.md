# Open When…

A native Android home for the things you want your person to hear, exactly when they need them. Kotlin, Jetpack Compose Material 3, Hilt, StateFlow and Firebase, with English/Arabic support and cozy light/dark themes.

## Choose an edition

| Flavor | Backend | Features |
| --- | --- | --- |
| `spark` | Firebase Authentication + Firestore on the free Spark plan | Two-device pairing, letters with one compressed photo, song links, date locks, reactions, delayed phone alerts and in-app APK updates |
| `live` | Authentication + Firestore + Cloud Functions + Cloud Storage on Blaze | Full multimedia letters, server-managed receipts and one-time opening |
| `demo` | Private local storage | Six sample letters and local media features; no synchronization between devices |

The connected Spark edition targets the dedicated Firebase project **`open-when-umito2`**. Billing has not been enabled. It supports one resized photo per letter using private Firestore content, without Cloud Storage or Cloud Functions. Voice uploads and one-time envelopes remain unavailable. Its account options are Anonymous and Email/Password; Google sign-in stays hidden until configured.

Download **[OpenWhen-1.5.0.apk](https://github.com/Umito2/open-when/releases/download/v1.5.0/OpenWhen-1.5.0.apk)** from the **[v1.5.0 release](https://github.com/Umito2/open-when/releases/tag/v1.5.0)** for animated letter opening and photo letters on the free connected Spark edition. Version 1.3.0 or later users can choose **App updates** inside the app. Install over your existing connected app using the same application ID and signing key, preserving your account and shared letters. The earlier [v1.0.0-demo release](https://github.com/Umito2/open-when/releases/tag/v1.0.0-demo) is the offline demo and cannot connect two phones. Both editions can be installed side by side; local demo letters are not migrated to the cloud.

## Run the app

Open this directory in Android Studio. Install **JDK 17**, **Android SDK 36** and **Build Tools 35.0.0**, then select the **demoDebug** build variant. Android 8.0 / API 26 is the minimum supported version; the app targets Android 16 / API 36.

```powershell
.\gradlew.bat :app:assembleDemoDebug
.\gradlew.bat :app:testDemoDebugUnitTest :app:lintDemoDebug
```

On macOS/Linux, use `sh ./gradlew` in place of `.\gradlew.bat`.

This prepared workspace also contains a portable JDK and SDK in the ignored `.tools` directory. To reuse that toolchain without changing the system's Java installation:

```powershell
$env:JAVA_HOME = (Resolve-Path '.tools/jdk/jdk-17.0.17-lite').Path
$env:GRADLE_USER_HOME = (Resolve-Path '.tools/gradle-cache').Path
.\gradlew.bat :app:assembleDemoDebug
```

The demo starts in Noor and Adam's paired space with six complete English/Arabic letters, a bundled illustrated memory, a synthetic sample voice note and a future-locked capsule. Letter changes and created envelopes persist locally. The microphone and Android photo picker work without a Firebase project. Demo letters never sync to another device. Sign out to explore the account/pairing interface; the demo invite code is **143143**.

## Code deliverables

1. Build configuration: [project](build.gradle.kts), [app](app/build.gradle.kts), [settings](settings.gradle.kts), checked-in Gradle 8.11.1 wrapper.
2. [Domain models](app/src/main/java/com/openwhen/app/domain/model), [six demo letters](app/src/main/java/com/openwhen/app/data/DemoSeedDataSource.kt).
3. [Repository contract](app/src/main/java/com/openwhen/app/domain/repository/EnvelopeRepository.kt), [Spark implementation](app/src/main/java/com/openwhen/app/data/SparkEnvelopeRepository.kt), [full Firebase implementation](app/src/main/java/com/openwhen/app/data/FirebaseEnvelopeRepository.kt), [offline demo](app/src/main/java/com/openwhen/app/data/DemoEnvelopeRepository.kt), [AAC recorder](app/src/main/java/com/openwhen/app/media/AudioRecorderHelper.kt), [voice player](app/src/main/java/com/openwhen/app/media/VoiceNotePlayer.kt).
4. [EnvelopeViewModel](app/src/main/java/com/openwhen/app/presentation/EnvelopeViewModel.kt), [UI states](app/src/main/java/com/openwhen/app/presentation/state/EnvelopeUiStates.kt), [receipt/reaction change tracker](app/src/main/java/com/openwhen/app/presentation/state/PartnerActivityTracker.kt).
5. [Pairing](app/src/main/java/com/openwhen/app/presentation/screens/PairingScreen.kt), [dashboard](app/src/main/java/com/openwhen/app/presentation/screens/EnvelopeDashboardScreen.kt), [wax-seal envelope](app/src/main/java/com/openwhen/app/presentation/components/WaxSealEnvelopeCard.kt), [letter reveal](app/src/main/java/com/openwhen/app/presentation/screens/LetterRevealScreen.kt), [creation studio](app/src/main/java/com/openwhen/app/presentation/screens/CreateEnvelopeScreen.kt), [warm/cozy theme](app/src/main/java/com/openwhen/app/presentation/theme/Theme.kt).

## Connect two devices with Spark

1. Install the connected Spark edition on both phones and sign in with separate accounts.
2. On the first phone, create a shared space with your anniversary date. Share its six-digit invitation privately.
3. On the second phone, join using that code. You can now exchange text letters, song links and reactions while connected.

Anonymous accounts are tied to the installation. Use Email/Password to keep access after moving to another phone or reinstalling. Signing out does not delete the shared space.

### Enable phone notifications

On the receiving phone, turn on **Letter notifications** on the home screen and allow Android's notification permission. Setup needs an internet connection to mark the existing inbox as history. New, unopened letters received afterward can show a private **A little love is waiting** phone notification. Tapping it returns to **For me** without breaking any seals or opening locked content.

This free option uses Android WorkManager to check for new letters on a **15-minute interval**. Delivery can take longer because Android controls background scheduling, connectivity and battery limits; these are delayed checks, not instant push notifications. Force-stopping the app stops checks until it is opened again. Existing letters and already-opened letters do not trigger alerts. Preferences are per account and per phone, and signing out cancels that account's background checks and visible alerts. See [Android's periodic-work timing](https://developer.android.com/develop/background-work/background-tasks/persistent/getting-started/define-work#schedule_periodic_work).

To build Spark locally, register Android apps for `com.openwhen.app` (release) and `com.openwhen.app.debug` (debug), save the matching Firebase configuration to **app/src/spark/google-services.json**, and follow the separate Spark deployment instructions in [backend setup](backend/README.md).

```powershell
.\gradlew.bat :app:assembleSparkDebug
.\gradlew.bat :app:assembleSparkRelease
```

An installable release requires a configured private signing key. Gradle reads the ignored root `signing.properties` file with `storeFile`, `storePassword`, `keyAlias` and `keyPassword`. This workspace's persistent key is `.tools/signing/open-when-release.jks`; back up that file and `signing.properties` privately so subsequent APKs can update the installed app. Spark and full `live` use the same application ID; switching editions does not migrate cloud data automatically. Firebase configuration and signing material are excluded from Git.

### Send a photo memory

Update both phones to **v1.4.0 or later**. In the letter studio, write your letter, tap **Tuck in a photo memory**, choose a photo and optionally add a caption. You can remove or replace the attachment before sealing. The recipient sees the photo in a Polaroid frame when opening the letter. A date-locked photo follows the same unlock rule as its words.

Spark sends one JPEG photo per letter, resized to at most **1024 pixels** on its longest edge and compressed to at most **240 KiB**. Original image metadata, including location tags, is not copied into the shared JPEG. The photo stays in the private letter document; it has no public download URL and is not included in dashboard or notification queries. Inline photos are not placed in Coil's disk or shared memory caches. This small-photo option uses the existing free Firestore quota; full-resolution files and voice uploads require a different storage setup. Older app versions can still read a photo letter's words but need the update to display its picture.

## Update from inside the app

Install **v1.3.0 or later once from Releases** to add the updater; earlier installed versions cannot acquire this feature automatically. Keep the existing app installed when updating so its account and pairing remain available.

The signed Spark edition checks for a newer stable release when it opens, with a 15-minute interval between checks in the same process. When an update is available, choose **Download update**, wait for the progress bar, then choose **Install update**. Android may ask you to allow **Open When** to install apps from this source. Return to the app and confirm Android's installation prompt. You can decline or cancel, and retry through **App updates** in the home screen menu or the card below your letters. Checking failures do not interrupt your letters. No GitHub account or new cloud service is required.

The updater downloads into private app cache and verifies the release checksum, package ID, higher Android version code, supported Android version, and matching signing certificate before opening the installer. Android still performs installation and requires user confirmation. Only the signed Spark release has the install permission and private FileProvider; debug, demo and full `live` builds do not offer this updater. Canceled or interrupted downloads can be restarted; verified downloads can be reused while retained in cache.

For future releases, publish a stable tag **vX.Y.Z** with the fully uploaded asset **OpenWhen-X.Y.Z.apk**, mark it as latest, increment the Android `versionCode`, and keep the existing signing key. GitHub must provide the asset's SHA-256 `digest`. The app ignores prereleases, demo assets and older/equal versions. Release metadata comes from the public [GitHub Releases API](https://docs.github.com/en/rest/releases/releases); install permission follows [Android's APK distribution flow](https://developer.android.com/studio/publish).

## Full multimedia backend on Blaze

The `live` flavor retains the full multimedia backend and requires a separately selected Blaze project. The free Spark project is not configured for this edition.

1. Register Android apps for `com.openwhen.app` (release) and `com.openwhen.app.debug` (debug). Put a configuration containing the appropriate client entries at **app/src/live/google-services.json**.
2. Enable Anonymous, Email/Password and Google authentication. Add debug/release signing SHA certificates and download the updated configuration. Google Credential Manager reads the generated **Web OAuth client ID**.
3. Enable Firestore, Cloud Storage and callable Cloud Functions in **us-central1**. Register Play Integrity App Check for release and a debug App Check token for local test installations. For APK distribution outside Google Play, use the corresponding [App Check distribution settings](https://firebase.google.com/docs/app-check/android/play-integrity-provider).
4. Install Node 22, then follow [backend setup](backend/README.md) to install dependencies, test and deploy the supplied functions and rules. Cloud resources and deployment remain under your Firebase account.
5. Select **liveDebug**. On the first device, sign in and create a space with your anniversary date. Share its six-digit invitation with the second device, whose user signs in and joins that space.

```powershell
.\gradlew.bat :app:assembleLiveDebug
```

Demo builds continue to work when a cloud configuration is present. Use the correct config file and explicit Firebase project for each backend. Deploying one edition's rules over the other changes its access model and can break the installed app. Wepick is a separate app and is not a deployment target for this repository. Signing keys must never be committed.

## How the app behaves

- **Dashboard:** days together, partner mood, most recent opening, inbox/sent tabs, live sender activity banners and a shared invitation view.
- **Opening:** a 1.5-second continuous seal hold, progress and haptic feedback. The wax cracks, then a full-screen envelope unfolds, blank paper rises and small hearts drift away before the letter fades into view. Releasing early or backgrounding cancels an unconfirmed gesture. The hold duration remains deliberate when system animations are disabled; visual motion follows the system animation setting. Screen readers receive an accessible opening action. The decorative scene receives only envelope styling, and actual letter content is composed after both the animation and authorized fetch finish. Slow requests keep the unfolded envelope visible; failures show an error immediately. Rotation and reactions do not replay a completed opening or create another read receipt.
- **Reveal:** selectable letter text on warm paper, a tilted Polaroid photo, optional song links and recipient reactions. Demo and full `live` also support voice playback. Backgrounding pauses audio. Sender previews leave receipt state unchanged.
- **Studio:** preset/custom prompts, envelope colors, five seal styles, Android photo picker, photo captions, HTTPS song links and date/time locks. Demo and full `live` also provide microphone recording and one-time opening. Voice notes stop at five minutes.
- **Reliability:** listeners are removed when scopes change; failures retry with backoff. Receipt banners ignore initial history. Sending uses a stable request ID and reuses successful uploads after uncertain responses. Changing accounts clears private UI state before the new profile loads.
- **Languages:** the interface follows the device's English/Arabic locale, supports RTL, uses start/end positioning and mirrored navigation, and contains both English and Arabic demo letters. Pastel light and cozy dark palettes follow the system theme.

## Sealed content and the schema

The domain `Envelope` contains all requested fields, plus `songUrl` and `createdAt`. `users/{uid}` and `couple_spaces/{spaceId}` follow the requested schema.

Firestore cannot restrict individual fields within a readable document. The envelope document therefore contains dashboard metadata and receipt state. Its **content/letter** subdocument holds private letter, attachment and song fields.

**Spark:** authenticated clients write through [Spark Firestore rules](firestore.spark.rules). Rules constrain couple membership and accepted state transitions and compare locked content access with server `request.time`. Recipients read allowed content directly and submit receipts/reactions. A receipt is not proof that a person physically read the letter or completed the animation. One-time opening is unavailable. Six-digit invitations allow authenticated exact-code lookups and forbid listing, but have no server-side attempt limiter. App Check is not enforced in the initial Spark setup; Firebase Authentication and Firestore rules still govern access.

**Full `live`:** callable transactions pair users, create envelopes, increment receipts and save reactions. Recipients receive private content through an authorized callable after server unlock checks. Invitations use a private, rate-limited index. Upload rules restrict objects to their owner's shared space and enforce type/size limits. See [Firestore rules](firestore.rules), [Storage rules](storage.rules) and [server implementation](backend/src/index.ts).

The sender's Firestore listener shows activity while their app is running. Spark additionally supports opt-in delayed local notifications for newly received letters, using metadata queries and Android's scheduler. **FCM push delivery is not used**; no extra hosting account, Cloud Functions or billing is required for these delayed alerts. `fcmToken` remains unused. Received content can be copied. Full `live` download URLs become bearer links after opening, and one-time access does not prevent recipients keeping copies.

## Validation

```powershell
.\gradlew.bat :app:testDemoDebugUnitTest :app:lintDemoDebug :app:assembleDemoDebug
.\gradlew.bat :app:connectedDemoDebugAndroidTest
npm --prefix backend test
npm --prefix backend run test:rules
npm --prefix backend run test:integration
```

The instrumentation command requires an Android emulator or connected device. Tests cover canceled/full seal holds, accessible opening, receipt history suppression, immutable send retries, account isolation, server unlock policy, invitation races and Firestore/Storage authorization. GitHub Actions runs the Android build, JVM tests and lint, plus the Firebase emulator suites. Device gesture tests run separately with the connected-device command above.

The earlier demo/full-backend baseline was verified locally on **1 November 2026**. These results do not establish validation of the new Spark rules or deployed Spark service:

- Android demo APK assembly and APK signature verification passed; minimum API 26, target API 36.
- All **7 Android JVM tests** passed: three ViewModel tests and four receipt/reaction tracker tests.
- Android lint passed with no errors. Remaining warnings include optional dependency updates and Kotlin convenience suggestions.
- The instrumentation test APK compiled successfully. Its three gesture tests have **not been executed on a device**.
- Firebase TypeScript compilation and all **17 backend tests** passed: nine unit tests, seven Firestore/Storage rules tests and one callable integration suite with transaction, private-media and retry assertions.

Local build outputs: `app/build/outputs/apk/demo/debug/app-demo-debug.apk`, `app/build/reports/tests/testDemoDebugUnitTest/index.html` and `app/build/reports/lint-results-demoDebug.html`. These generated files are excluded from Git. Successful GitHub Actions builds also provide the downloadable `open-when-demo` APK artifact.

Spark's **21 security tests** pass, including photo field validation, privacy, date locks and immutable retries. A test against the actual `open-when-umito2` cloud service passed anonymous sign-in, two-account pairing, text/photo delivery, server date locks, outsider isolation, receipt retries and real-time receipt/reaction listeners. The photo run used three disposable Auth accounts and eighteen Firestore documents, all removed after testing. Billing remains disabled. A previous email registration/sign-in test restored the same account and also removed its temporary data.

Version 1.2.0 passed signed Spark release assembly, demo assembly, all **14 JVM tests in each variant** and lint with **zero errors**. Seven notification-policy tests cover historical baselines, empty inboxes, timestamp precision, cursor ties, duplicate pages, opened-letter filtering and account/permission checks. The live cloud smoke test also passed the deployed notification indexes and cursor queries; its disposable accounts/documents were removed. No physical Android device was connected for notification delivery, permission or tap testing.

Version 1.3.0 added **16 updater tests** for version ordering, release and URL validation, checksums, download bounds, quiet offline checks, dismissal, cancellation, explicit installation, failed verification, permission retry and saved-state restoration. Version 1.4.0 added eight photo-policy tests and an account-switch test during photo preparation. Version 1.5.0 adds two opening-state tests, bringing the JVM suite to **41 tests per variant**. Nine device tests cover seal gestures, duplicate accessibility activation, the private-content reveal gate, slow fetches, errors, disabled motion and saved-state restoration; GitHub compiles their test APK. They have not been executed in this workspace. APK installation, photo processing/rendering, animation appearance and TalkBack still require real-device validation; no physical phone was available.

GitHub Actions compiles Spark with an explicitly inert Firebase fixture and runs its JVM tests, lint and security suite. That CI configuration does not access the deployed service. Download the configured, signed Spark APK from Releases; the `open-when-demo` Actions artifact is the offline edition.

## Operational boundaries

Composer drafts and uncertain-send request IDs survive configuration changes through the ViewModel, but do not survive OS process death. After restart, the sent list recovers committed envelopes from Firestore; unfinished local drafts are lost. Full `live` open-request retries use a two-minute server recovery window. Offline demo storage is private application storage; cloud operations require connectivity. The free service is subject to Spark quotas. Personal letters are not end-to-end encrypted.

Application data is excluded from Android cloud backups and device transfers. Demo media and the dynamically looked-up Google OAuth client resource have explicit resource-shrinker retention rules.

Before a public store launch, complete privacy policy, account deletion, export, unpairing and data retention flows. The full backend also needs cleanup of abandoned media uploads. The included backend notes describe each edition's authorization and deployment boundaries.

Dependency choices use the [AGP 8.10 compatibility requirements](https://developer.android.com/build/releases/agp-8-10-0-release-notes), [Kotlin Compose compiler plugin](https://developer.android.com/jetpack/androidx/releases/compose-kotlin), Firebase's [main-module Kotlin APIs](https://firebase.google.com/docs/android/kotlin-migration) and [Credential Manager Google authentication](https://firebase.google.com/docs/auth/android/google-signin). API 36 meets the [current Google Play target requirement](https://support.google.com/googleplay/android-developer/answer/11926878).
