# Changelog

## 3.1

- Notifications: only one notification at a time. When the reply is ready, the "generation in progress" notification is cancelled and replaced by the "reply ready" notification.
- Notifications: the session (chat) name is now shown in both the "generation in progress" and "reply ready" notifications.
- Notifications: when a generation ends in error and Studio retries, the notification says so: "generation failed, retry from the app" while waiting for the retry, "reply ready (after an error, generation retried)" when the retried generation finishes, "generation ended in error" if no retry happens.
- Keyboard: the soft keyboard is now always closed when re-entering the chat (app resume, "Back to chat" button, notification tap).
- "Back to chat" button: fixed — it now opens the last chat directly instead of walking the WebView history, which often landed on a page that created a new chat.
- Version: 3.1 / versionCode 16 everywhere (manifest, APP_VERSION, Credits).

## 3.0.1

- Update banner "Generating response…": raised z-index (999999) so it always stays on top of the Studio page content.
- Credits page: contact link now visible in the dark theme (white on dark, green on light).
- Credits page: new "Changelog" button (green pill, same style as the other buttons) opening this file on GitHub.
- First launch of a new version: in-app changelog screen with the current version's notes, shown once per version.
- Changelog screen and Close button translated into Italian (Close / Chiudi).
- No IP and no DDNS set: warning toast asking to enter one in Options (English and Italian).

## 3.0

- Theme: new "Theme" row in the Options panel with a Light / Dark / System spinner. Applies to the native app and to the Studio page (localStorage.theme), so the WebView page follows the same theme.
- Palette: new "Palette" row in the Options panel with the 24 Studio palettes (standard is the default). Applies to the Studio page accent color (localStorage.palette); works live on an already-open page, no reload needed.
- Crash guard: a repeating inexact broadcast alarm scheduled in onResume lets a receiver post a full-screen-intent notification that restarts the app after a crash (Android 14 BAL-safe).
- Credits page: version now 3.0 under the logo; screenshot updated.
- Version: 3.0 / versionCode 14 everywhere (manifest, APP_VERSION, Credits).
- Existing installs: no migration needed.
