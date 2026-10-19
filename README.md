# SMS Guardian

Android SMS app project built with Kotlin and Jetpack Compose.

## Build with GitHub Actions
1. Upload the **contents** of this `SMSGuardian` directory to the repository root, or keep this directory as `SMSGuardian/`.
2. Commit and push to `main` or `master`.
3. Open **Actions → Android APK** and download the `SMS-Guardian-debug-apk` artifact.

## Important Android requirements
- The app must be granted the Android **default SMS app** role to receive `SMS_DELIVER` broadcasts and manage the SMS provider.
- Grant SMS and phone-state permissions when prompted. On some Samsung devices, check **Settings → Security and privacy → Auto Blocker** if sideloaded app permissions are being blocked, then check **Settings → Apps → Choose default apps → SMS app**.
- Sending SMS requires an active SIM, mobile network service, and `SEND_SMS` permission. Multi-part SMS is supported.
- Filtering and message storage run on-device. Blocked messages are deliberately not inserted into the SMS provider.
- The app reads all SMS rows exposed by Android's SMS provider (no hard-coded 100-message limit). Very large inboxes may take longer to load.
- The conversation view supports replying in the selected thread and opening the dialer for that sender.

MMS parsing/receiving is not implemented in this version. SMS role availability and permission behavior can vary by Android version and device manufacturer.

## Changes in this revision
- Checks `SEND_SMS` at send time and requests it again if missing; the previous behavior could surface Android's `uid ... does not have ... SEND_SMS` exception after the permission had been denied.
- Adds an actionable permission error message and logs the underlying exception for debugging.
- Adds a phone-book picker next to the recipient number field.
- Adds a hamburger navigation menu and an About screen with developer details.
- Keeps exact-number, keyword, and sender-prefix filtering rules stored locally. For automatic filtering of incoming SMS, select SMS Guardian as the system default SMS app and grant the requested permissions.

## Important testing note
This source archive was updated, but an Android build could not be run in this environment because Gradle is not installed. Use the included GitHub Actions workflow to compile it and download the APK artifact. Confirm permissions and SMS behavior on a physical device with an active SIM; emulator/build success alone cannot verify carrier delivery.
