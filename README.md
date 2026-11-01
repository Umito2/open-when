# Open When…

A native Android home for the things you want your person to hear, exactly when they need them. Kotlin, Jetpack Compose Material 3, Hilt, StateFlow, Firebase and private media storage.

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
3. [Repository contract](app/src/main/java/com/openwhen/app/domain/repository/EnvelopeRepository.kt), [Firebase implementation](app/src/main/java/com/openwhen/app/data/FirebaseEnvelopeRepository.kt), [offline demo](app/src/main/java/com/openwhen/app/data/DemoEnvelopeRepository.kt), [AAC recorder](app/src/main/java/com/openwhen/app/media/AudioRecorderHelper.kt), [voice player](app/src/main/java/com/openwhen/app/media/VoiceNotePlayer.kt).
4. [EnvelopeViewModel](app/src/main/java/com/openwhen/app/presentation/EnvelopeViewModel.kt), [UI states](app/src/main/java/com/openwhen/app/presentation/state/EnvelopeUiStates.kt), [receipt/reaction change tracker](app/src/main/java/com/openwhen/app/presentation/state/PartnerActivityTracker.kt).
5. [Pairing](app/src/main/java/com/openwhen/app/presentation/screens/PairingScreen.kt), [dashboard](app/src/main/java/com/openwhen/app/presentation/screens/EnvelopeDashboardScreen.kt), [wax-seal envelope](app/src/main/java/com/openwhen/app/presentation/components/WaxSealEnvelopeCard.kt), [letter reveal](app/src/main/java/com/openwhen/app/presentation/screens/LetterRevealScreen.kt), [creation studio](app/src/main/java/com/openwhen/app/presentation/screens/CreateEnvelopeScreen.kt), [warm/cozy theme](app/src/main/java/com/openwhen/app/presentation/theme/Theme.kt).

## Connect two devices

The `live` flavor uses your own Firebase project. No Firebase credentials or project identifiers are committed.

1. Register Android apps for `com.openwhen.app` (release) and `com.openwhen.app.debug` (debug). Put a configuration containing the appropriate client entries at **app/src/live/google-services.json**.
2. Enable Anonymous, Email/Password and Google authentication. Add debug/release signing SHA certificates and download the updated configuration. Google Credential Manager reads the generated **Web OAuth client ID**.
3. Enable Firestore, Cloud Storage and callable Cloud Functions in **us-central1**. Register Play Integrity App Check for release and register the debug App Check token for local debug builds.
4. Install Node 22, then follow [backend setup](backend/README.md) to install dependencies, test and deploy the supplied functions and rules. Cloud resources and deployment remain under your Firebase account.
5. Select **liveDebug**. On the first device, sign in and create a space with your anniversary date. Share its six-digit invitation with the second device, whose user signs in and joins that space.

```powershell
.\gradlew.bat :app:assembleLiveDebug
```

Demo builds continue to work when a live configuration is present. Live builds fail with a specific setup message when the configuration is missing. Release signing belongs in your local Android Studio/CI signing configuration; release builds do not use a committed signing key.

## How the app behaves

- **Dashboard:** days together, partner mood, most recent opening, inbox/sent tabs, live sender activity banners and a shared invitation view.
- **Opening:** a 1.5-second continuous seal hold, progress, haptic feedback and an animated flap. Releasing early or backgrounding cancels the gesture. The hold duration is enforced even when system animation scale is zero. Screen readers receive an accessible opening action.
- **Reveal:** selectable letter text on warm paper, tilted Polaroid, optional song link, asynchronous voice playback with audio focus and progress, and recipient reactions. Backgrounding pauses audio. Sender previews leave receipt state unchanged.
- **Studio:** preset/custom prompts, envelope colors, five seal styles, Android photo picker, microphone permission, hold-to-record AAC `.m4a`, tap-to-preview, photo caption, HTTPS song link, date/time locks and one-time opening. Voice notes stop at five minutes.
- **Reliability:** listeners are removed when scopes change; failures retry with backoff. Receipt banners ignore initial history. Sending uses a stable request ID and reuses successful uploads after uncertain responses. Changing accounts clears private UI state before the new profile loads.
- **Languages:** the interface follows the device's English/Arabic locale, supports RTL, uses start/end positioning and mirrored navigation, and contains both English and Arabic demo letters. Pastel light and cozy dark palettes follow the system theme.

## Sealed content and the schema

The domain `Envelope` contains all requested fields, plus `songUrl` and `createdAt`. `users/{uid}` and `couple_spaces/{spaceId}` follow the requested schema.

Firestore cannot restrict individual fields within a readable document. The Firestore envelope document therefore contains dashboard metadata and receipt state. Its **content/letter** subdocument holds `letterText`, `photoUrl`, `photoCaption`, `voiceNoteUrl` and `songUrl`. Recipients receive those fields only through an authorized callable after server-side unlock checks. This prevents a modified client from downloading a future-locked letter through the inbox listener.

Only callable transactions pair users, create envelopes, increment receipts or save reactions. Invitations use a private index and transactional consumption. Upload rules restrict objects to their owner's shared space and enforce type/size limits. See [Firestore rules](firestore.rules), [Storage rules](storage.rules) and [server implementation](backend/src/index.ts).

An opening updates `isOpened`, `lastOpenedAt` and `openCount` in one server transaction. The sender's Firestore listener shows the banner while their app is running. **Background FCM push delivery is not implemented**; the requested `fcmToken` field is present in the user model. Download URLs become bearer links after an authorized opening, and one-time access cannot prevent a recipient from keeping received content.

## Validation

```powershell
.\gradlew.bat :app:testDemoDebugUnitTest :app:lintDemoDebug :app:assembleDemoDebug
.\gradlew.bat :app:connectedDemoDebugAndroidTest
npm --prefix backend test
npm --prefix backend run test:rules
npm --prefix backend run test:integration
```

The instrumentation command requires an Android emulator or connected device. Tests cover canceled/full seal holds, accessible opening, receipt history suppression, immutable send retries, account isolation, server unlock policy, invitation races and Firestore/Storage authorization. GitHub Actions runs the Android build, JVM tests and lint, plus the Firebase emulator suites. Device gesture tests run separately with the connected-device command above.

Verified locally on **1 November 2026**:

- Android demo APK assembly and APK signature verification passed; minimum API 26, target API 36.
- All **7 Android JVM tests** passed: three ViewModel tests and four receipt/reaction tracker tests.
- Android lint passed with no errors. Remaining warnings include optional dependency updates and Kotlin convenience suggestions.
- The instrumentation test APK compiled successfully. Its three gesture tests have **not been executed on a device**.
- Firebase TypeScript compilation and all **17 backend tests** passed: nine unit tests, seven Firestore/Storage rules tests and one callable integration suite with transaction, private-media and retry assertions.

Local build outputs: `app/build/outputs/apk/demo/debug/app-demo-debug.apk`, `app/build/reports/tests/testDemoDebugUnitTest/index.html` and `app/build/reports/lint-results-demoDebug.html`. These generated files are excluded from Git. Successful GitHub Actions builds also provide the downloadable `open-when-demo` APK artifact.

## Operational boundaries

Composer drafts and uncertain-send request IDs survive configuration changes through the ViewModel, but do not survive OS process death. After restart, the sent list recovers committed envelopes from Firestore; unfinished local drafts are lost. Open-request retries use a two-minute server recovery window. Offline demo storage is private application storage; cloud writes require connectivity. Personal letters are not end-to-end encrypted.

Application data is excluded from Android cloud backups and device transfers. Demo media and the dynamically looked-up Google OAuth client resource have explicit resource-shrinker retention rules.

Before a public store launch, configure your real Firebase service, release signing, privacy policy and your product's account deletion/data retention flows. The included backend notes describe abandoned-upload cleanup and shared-space retention. No deployment, billing change or external publishing was performed.

Dependency choices use the [AGP 8.10 compatibility requirements](https://developer.android.com/build/releases/agp-8-10-0-release-notes), [Kotlin Compose compiler plugin](https://developer.android.com/jetpack/androidx/releases/compose-kotlin), Firebase's [main-module Kotlin APIs](https://firebase.google.com/docs/android/kotlin-migration) and [Credential Manager Google authentication](https://firebase.google.com/docs/auth/android/google-signin). API 36 meets the [current Google Play target requirement](https://support.google.com/googleplay/android-developer/answer/11926878).
