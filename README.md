## Version 1.2.0 — conversation organization and usability

- Fixed launcher-icon switching by enabling the selected activity alias before disabling the previous alias.
- Set the transparent-background Minimal vector icon as the installed app/APK default; each launcher alias now points to its own icon asset.
- Added user-selected pinned conversations, sorted above regular chats.
- Added custom conversation pages (for example Family, Friends, Work) and moving chats into a page.
- Added pinned home-screen shortcuts for individual conversations on Android 8+.
- Tapping anywhere on a message bubble opens its copy/forward/edit actions.
- Added multi-selection and bulk removal for deleted and blocked archive entries.
- Added an independent temporary enable/disable switch for each configured SMS-forwarding destination.
- The new-message recipient field accepts multiple phone numbers separated by commas or semicolons; a contact picker selection can also be accumulated, and the SMS is sent separately to each selected recipient.
- Expanded the About page with the new features.

**Important group-SMS limitation:** Android's `SmsManager` sends a standard SMS to one destination at a time. This revision sends the same text separately to each selected number. It does not create a shared group-MMS conversation/thread; true group messaging requires MMS/group-thread support, which this project did not previously implement.

## Build and verification
The source archive is prepared for the existing GitHub Actions workflow. This environment does not have Gradle/Android SDK configured, so a full Android APK build and device test could not be run here. Push the project to GitHub and run **Actions → Android APK**. Test icon changes, pinned shortcuts, SMS sending, and forwarding on a physical device.

## Version 1.1.2 (مدیریت پیامک v27)

- Main inbox now queries Android's SMS conversation summary view rather than taking a capped window of individual SMS rows.
- Thread history is loaded on demand for the selected phone number.
- SMS/MMS/thread ContentObservers trigger a debounced refresh; a refresh arriving during an active refresh is queued.
- Phonebook names are indexed in one query instead of running multiple Contacts queries per conversation.
- Added URL links in message bodies, persistent per-conversation drafts, and an explicit back button beside the composer.

Build/test note: this source archive has not been compiled on this host because Gradle/Gradle wrapper is unavailable here.

# مدیریت پیامک (SMS Manager)

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
- Keeps exact-number, keyword, and sender-prefix filtering rules stored locally. For automatic filtering of incoming SMS, select مدیریت پیامک as the system default SMS app and grant the requested permissions.

## Important testing note
This source archive was updated, but an Android build could not be run in this environment because Gradle is not installed. Use the included GitHub Actions workflow to compile it and download the APK artifact. Confirm permissions and SMS behavior on a physical device with an active SIM; emulator/build success alone cannot verify carrier delivery.


## First launch and SMS access (important)

On Android 10 and newer, the app requests the **default SMS app role first**. Accept the Android system dialog and select **مدیریت پیامک** as the default SMS app. Only after that will the app request unrelated permissions such as Contacts and Phone state. This order is required because SMS permissions are restricted to the default SMS handler on modern Android. If the system dialog is declined, open **Settings → Apps → Default apps → SMS app** and select مدیریت پیامک, then reopen the app.

If Android still reports that SMS access was denied after مدیریت پیامک is selected as the default SMS app, remove the older installation, install the newly built APK, and accept the system default-SMS prompt. SMS delivery and sending depend on Android granting the SMS role and on an active SIM/mobile service; they cannot be bypassed by requesting `SEND_SMS` alone.


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


## Myket metadata alignment (v1.1.5)
- Unified the installed launcher/application label to «مدیریت پیامک».
- Replaced the launcher icon with one classic SMS chat-and-envelope icon (no shield).
- The launcher aliases now all reference the same icon and app label so changing a saved icon selection cannot cause a Myket listing/install icon mismatch.
- Suggested short English name: SMS Manager.


## SMSGuardian 1.2.1

- Fixed pinned conversation refresh: the list reloads the pinned state immediately and scrolls the pinned conversation to the top without restarting the app.
- Home-screen conversation shortcuts now use a generated contact-initials or phone-digit avatar so shortcuts are visually distinguishable.
- Custom conversation pages can be renamed or deleted from the page strip. Deleting a page returns its conversations to the All view; messages are not deleted.
- Multi-recipient compose now creates a persistent internal group conversation. Follow-up messages from that group are sent to all current members; group name and members can be edited by tapping the group header.

### SMS group behavior

This is an in-app SMS group, not carrier Group MMS. The app sends a separate SMS to each member. Incoming SMS from group members after the group was created are shown in the group timeline and labeled by sender. Since ordinary SMS has no group-thread identifier, the app cannot prove that every such incoming message was a reply to that group; unrelated messages from those members may also appear. Group metadata and locally grouped outgoing messages are stored in app preferences.

## Version 1.2.3 – forwarding reliability and interface updates

- Automatic forwarding now waits for Android's per-part SMS send callbacks before counting a destination as successful or inserting the forwarded copy into Sent history. Failed modem/network submissions are recorded as failures rather than appearing as sent messages.
- The forwarding path explicitly resolves the system's preferred SMS subscription and logs the selected subscription and callback result codes.
- The default-SMS button now checks/requests the required permissions when the app already holds the default SMS role.
- Renamed the visible app title to «مدیریت پیامک», removed the App Icon section from Settings, refreshed the navigation drawer and About page, and added two-finger pinch zoom for message text.

### Redmi Note 15 forwarding verification

1. Set «مدیریت پیامک» as the default SMS app and grant SMS permissions.
2. In Android/HyperOS SIM settings, confirm the preferred SIM for SMS is selected.
3. First test forwarding to one destination, then test all configured destinations and a multipart (long) message.
4. Check the forwarding history after Android returns the send callbacks. A destination is counted as successful only when all parts receive `RESULT_OK`; this confirms submission to the mobile network, not final handset delivery.
5. For diagnostics, capture `adb logcat -s SMSForwarding` and share the output if a failure remains.
