# SMS Guardian

A Kotlin + Jetpack Compose Android SMS app starter with a Persian/English UI, keyword filtering, exact-number rules, prefix rules (e.g. `3000*`), automatic sender blocking after keyword matches, dual-SIM selection for sending, and a GitHub Actions debug-APK workflow.

## Build on GitHub
1. Create a new repository on GitHub.
2. Extract this ZIP and upload the **contents** of `SMSGuardian` (including `.github`) to the repository root. Do not upload the ZIP itself as the only repository file.
3. Open **Actions** and enable workflows if GitHub asks.
4. Push a commit or run **Android APK → Run workflow**.
5. Open the successful workflow run, then download `SMS-Guardian-debug-apk` under **Artifacts**.

The workflow uses GitHub-hosted Ubuntu, JDK 17, Gradle 8.13, and versioned GitHub Actions. No local `gradlew` binary is required because the workflow installs Gradle directly.

## Install and setup
- Install the debug APK on a phone with SMS capability.
- Grant requested runtime permissions.
- In Settings, select **Set as default SMS app**. Android requires the default SMS role for reliable control of SMS storage and delivery.
- Add keywords, exact numbers, or prefixes in Filters. Prefix `3000*` matches senders beginning with `3000`.
- For dual SIM, choose the available subscription chip before sending.

## Important limitations
- No Android app can promise uninterrupted execution under every device-vendor battery policy. This app processes SMS through Android's system delivery broadcast while it is the default SMS handler; it does not run a permanent polling service.
- Keyword-matched messages and exact/prefix-blocked messages are not inserted into the SMS inbox by this app. Keyword matches also add the sender to the local blocked list.
- This is a starter project, not a certified production messaging client. MMS parsing/storage, delivery reports, multipart message composition, contact threading, backup/export, and device-specific SIM validation are not fully implemented. Verify behavior on each target Android version and dual-SIM handset before relying on it.
- Google Play SMS permissions are restricted by policy; distribution may require the app to qualify as the default SMS handler and meet Play's permitted-use requirements.
- A successful GitHub build is not a guarantee that every handset's OEM SMS provider behaves identically.

## Project structure
- `app/src/main/java/.../MainActivity.kt`: Compose UI, language preference, inbox, send SMS, SIM selector, default-role request.
- `RuleStore.kt`: local filter rules.
- `SmsReceivers.kt`: incoming SMS filtering and inbox storage.
- `RespondViaMessageService.kt`: Android respond-via-message integration.
- `.github/workflows/android.yml`: CI build and APK artifact upload.

## Technical baseline
- Android Gradle Plugin 8.13.2
- Kotlin 2.2.20 with Compose compiler plugin
- Gradle 8.13
- compileSdk 36 / targetSdk 35 / minSdk 23
- JDK 17
