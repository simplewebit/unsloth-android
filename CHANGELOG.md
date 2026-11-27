# Changelog

## 3.5

- Notification tap opens the right chat directly: the app no longer waits for the server thread list before navigating (cold start now navigates immediately on page load; warm start already did), instead of staying frozen on the home page until the check completes.
- In-app "reply ready" banner: tap on any notification (Android or in-app) closes the other channel - the heads-up notification is dismissed when the in-app banner shows, and when the app is opened from a notification the banner disappears.
- In-app "reply ready" banner: made lighter and smaller (padding 6dp, 16dp icon, 12sp bold title, 10sp sub, 32dp close, tight 8dp margin) - lighter and more immediate.
- In-app "reply ready" banner: restyled to the thin single-row flat variant (flat background, no radius/shadow/gradient, hairline accent in dark theme, inline title+sub).
- In-app "reply ready" banner: shown only while the app is in the foreground (the Android heads-up is the only signal in the background) - no duplicate signal in-app; thread-of-the-run race fix: the banner now gets the run's thread from the value captured before the run id is cleared.
- New option "Wake screen" (Settings): light the screen for ~2.5 s when a reply is ready (default on); no-op when the screen is already on.
- Heads-up channel gen_done_v2: heads-up banner + sound above other apps for the reply-ready notification (already shipped in v3.4, kept in this release).
- New "confirmation needed" signal for chat tool calls: when the chat shows a tool-call approval (Allow / Always allow / Deny / Full Access buttons), the app shows an Android notification in the background, an in-app banner in the foreground, and wakes the screen if the "Wake screen" option is on. Not shown when the app is already in the chat showing the pending confirmation (fully silenced).
- Notification channel `gen_approval_v1` (IMPORTANCE_HIGH), notification id 3 (no collision with the reply-ready id 2 and the ongoing id 1).
- Tap (banner or notification) opens the chat at the last message; dismiss button hides only the banner.
- No reload on chat re-entry from the "reply ready" notification tap: the app stays in the chat (no cache wipe / IndexedDB wipe / location.reload). The "confirmation needed" and other notifications keep their previous behavior (the tap re-enters the chat and reloads the page).
- Dedup per message id (`tid:messageId`, reset when the confirmation is no longer in the DOM).
- Near-real-time detection: dedicated ~700 ms approval-poll thread (JS check only) replaces the 3 s run-poll hook; the notification/banner fires immediately with default text, then the real chat title is fetched in the background and updates the banner/notification (same id 3).
- Wake screen also fires on the confirmation event (same option, same wakeScreen helper).
- Credits page: buttons moved closer together (Changelog + Check for updates), the update status message now appears below the update button, the Changelog button uses a lighter outlined style, the Unsloth logo and the "3.3.1" caption are removed, and the "Based on Unsloth Studio" sentence is gone from the "not official" line (italic, single sentence).
- Version: 3.5 / versionCode 20 everywhere (manifest, APP_VERSION, Credits page).

## 3.3.1

- Screen-on no longer reloads the chat page: re-entering a chat with the screen on only refreshes the access token, and the page stays where it was (no clearCache, no IndexedDB wipe, no reload).
- Anti-resync cooldown: a reload at most once every 30 seconds, so screen-on, generation-finished and re-entry cannot trigger back-to-back reloads.
- Version: 3.3.1 / versionCode 19 everywhere (manifest, APP_VERSION, Credits page).

## 3.3

- Network switch: the app re-resolves the host on every WiFi/mobile change and reloads the page keeping the current path/query (only when the host actually changes and the app is in the foreground; no reload if a generation is in progress).
- Selection rule: with mobile data active, the DDNS is tried first (if it doesn't answer, the IP is not tried); on WiFi/other networks, the IP is tried first with DDNS as fallback.
- Network state is read correctly: ACCESS_NETWORK_STATE declared in the manifest, and isMobile() logs the error and falls back to "not mobile" instead of failing silently.
- "Projects and sessions" button: outside a chat it opens a new chat and then the sidebar once the page is ready; inside a chat it opens the sidebar with retries (1 s delay + up to 5 attempts).
- Crash log download: saved directly to Downloads/ (no more share chooser, which produced an empty file) - MediaStore on API 29+, public Downloads on 24-28, logging suspended during the read, confirmation toast (English/Italian).
- Version: 3.3 / versionCode 18 everywhere (manifest, APP_VERSION, Credits page).

## 3.2

- Phone and other devices stay in sync: a run started on another device (e.g. the desktop app) no longer shows the error box on the phone — the phone now shows the banner instead, blocks sending and follows the run to its end.
- Fix: desktop-started runs (which never create chat-runs) are now detected via `GET /api/inference/active-generations`.
- Banner text: "Generation started by another device" with a small "Wait for it to finish" underneath (Italian: "Generazione avviata da altro dispositivo" / "Attendi la fine").
- Keyboard: soft keyboard stays closed on chat re-entry. If already on the same chat, a notification tap no longer reloads the page (no reload, keyboard stays closed); otherwise the reload happens but the keyboard is hidden afterwards (250 / 900 / 2000 ms, with a JS blur of the focused composer). A focusin listener + a 5 s lock blur any input that regains focus in the meantime; the lock is also armed on every in-page route change (pushState/replaceState/popstate) so the composer can't take focus after an in-page chat switch.
- Version: 3.2 / versionCode 17 everywhere (manifest, APP_VERSION, Credits).

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
