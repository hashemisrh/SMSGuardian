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
