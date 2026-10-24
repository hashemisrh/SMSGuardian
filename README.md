## Version 1.1.2 (SMS Guardian v27)

- Main inbox now queries Android's SMS conversation summary view rather than taking a capped window of individual SMS rows.
- Thread history is loaded on demand for the selected phone number.
- SMS/MMS/thread ContentObservers trigger a debounced refresh; a refresh arriving during an active refresh is queued.
- Phonebook names are indexed in one query instead of running multiple Contacts queries per conversation.
- Added URL links in message bodies, persistent per-conversation drafts, and an explicit back button beside the composer.

Build/test note: this source archive has not been compiled on this host because Gradle/Gradle wrapper is unavailable here.

# SMS Guardian

Android SMS app project built with Kotlin and Jetpack Compose.

## Build with GitHub Actions
1. Upload the **contents** of this `SMSGuardian` directory to the repository root, or keep this directory as `SMSGuardian/`.
2. Commit and push to `main` or `master`.
3. Open **Actions → Android APK**. Download `SMS-Guardian-release-apk` for Myket/production (signed with the configured release keystore), or `SMS-Guardian-debug-apk` for testing.
4. The release build requires repository secrets `ANDROID_KEYSTORE_BASE64`, `ANDROID_KEYSTORE_PASSWORD`, `ANDROID_KEY_ALIAS`, and `ANDROID_KEY_PASSWORD`. Keep the original release keystore; future updates must use the same signing key.

## Important Android requirements
- The app must be granted the Android **default SMS app** role to receive `SMS_DELIVER` broadcasts and manage the SMS provider.
- Grant SMS and phone-state permissions when prompted. On some Samsung devices, check **Settings → Security and privacy → Auto Blocker** if sideloaded app permissions are being blocked, then check **Settings → Apps → Choose default apps → SMS app**.
- Sending SMS requires an active SIM, mobile network service, and `SEND_SMS` permission. Multi-part SMS is supported.
- Filtering and message storage run on-device. Blocked messages are deliberately not inserted into the SMS provider.
- The app reads all SMS rows exposed by Android's SMS provider (no hard-coded 100-message limit). Very large inboxes may take longer to load.
- The conversation view supports replying in the selected thread and opening the dialer for that sender.

MMS parsing/receiving is not implemented in this version. SMS role availability and permission behavior can vary by Android version and device manufacturer.

## Changes in this revision (v27)
- Open the requested conversation from SMS notification taps and clear the tapped notification.
- Accept recipients launched from external SMS intents (contacts/call history).
- Select and delete multiple conversations at once.
- Add a daily time window to automatic SMS forwarding, including overnight ranges.
- Add separate chat-list and SMS-message font-size controls to the hamburger menu.
- Preserve the conversation list scroll position when returning from a chat.
- Increase Android versionCode to 12 and versionName to 1.1.2.

## Changes in earlier revisions
- Checks `SEND_SMS` at send time and requests it again if missing; the previous behavior could surface Android's `uid ... does not have ... SEND_SMS` exception after the permission had been denied.
- Adds an actionable permission error message and logs the underlying exception for debugging.
- Adds a phone-book picker next to the recipient number field.
- Adds a hamburger navigation menu and an About screen with developer details.
- Keeps exact-number, keyword, and sender-prefix filtering rules stored locally. For automatic filtering of incoming SMS, select SMS Guardian as the system default SMS app and grant the requested permissions.

## Important testing note
This source archive was updated, but an Android build could not be run in this environment because Gradle is not installed. Use the included GitHub Actions workflow to compile it and download the APK artifact. Confirm permissions and SMS behavior on a physical device with an active SIM; emulator/build success alone cannot verify carrier delivery.


## First launch and SMS access (important)

On Android 10 and newer, the app requests the **default SMS app role first**. Accept the Android system dialog and select **SMS Guardian** as the default SMS app. Only after that will the app request unrelated permissions such as Contacts and Phone state. This order is required because SMS permissions are restricted to the default SMS handler on modern Android. If the system dialog is declined, open **Settings → Apps → Default apps → SMS app** and select SMS Guardian, then reopen the app.

If Android still reports that SMS access was denied after SMS Guardian is selected as the default SMS app, remove the older installation, install the newly built APK, and accept the system default-SMS prompt. SMS delivery and sending depend on Android granting the SMS role and on an active SIM/mobile service; they cannot be bypassed by requesting `SEND_SMS` alone.


Build fix v1.0.2: removed invalid references to `Manifest.permission.WRITE_SMS`. Android SDK does not expose this as a runtime permission constant; the previous references caused Kotlin compilation to fail. The app no longer requests/checks WRITE_SMS as a runtime permission.


## Revision 8.2 - SMS send gate fix
- Sending is no longer blocked only because `Telephony.Sms.getDefaultSmsPackage()` disagrees with the system Settings UI.
- The app checks `SEND_SMS` permission, attempts the actual send when granted, and reports the actual Android send result or the specific exception.
- This source revision must be built in GitHub Actions and tested on the target phone; it is not represented as device-tested.


## Version 1.1.1 (incremental update from v25 / 1.1.0)

- Preserves the v25 conversation-provider loading/refresh algorithm.
- Adds Android Back handling and a visible back-to-chats item in the hamburger menu.
- Persists launcher icon choice without resetting a saved selection to Minimal.
- Adds Midnight Black, Pink, Cherry, Deep Ocean, Amber, and Jade themes.
- Expands About feature list and documents long-SMS support (carrier limits apply).
- Adds Donate / Support card-number dialog.
- Updates the chat header tagline to “Messenger; But Lovely”.
- Adds in-conversation text search; selecting a result scrolls to and highlights that message.
