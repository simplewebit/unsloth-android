# Unsloth Android app

Native Android app (Java + WebView) for your own [Unsloth Studio](https://github.com/unslothai) instance: chat with the model from your phone, download generated files, and get notified when a generation finishes.

Requires Android 7.0+ (minSdk 24). The app is not on the Play Store.

## What it does

- Connect to an Unsloth Studio instance by **IP or DDNS address** and port (saved on the device)
- Loads the Studio web app in a WebView, with a back-to-chat shortcut in the drawer
- Downloads files from the chat (HTTP and blob URLs) with progress and completion notifications
- "Reply ready" notification when a generation finishes; background keep-alive so long generations survive screen-off
- Debug log (`crash.log`) with an on/off switch in Options and a **share** button to attach the log to an email or a GitHub issue
- Interface in **English and Italian**, auto-selected from the system language by default (selector in Options)

## Install

1. Download the APK from the [latest release](https://github.com/simplewebit/unsloth-android/releases/latest).
2. Copy it to your phone and install it (Android will ask you to allow installs from unknown sources).
3. Open the app, enter the IP or DDNS address of your Unsloth Studio server (default port 8888) and connect.

Version: 1.0

## Credits

Developed by **Mattia Di Giuseppe** — [mattia.digiuseppe@simpleweb.it](mailto:mattia.digiuseppe@simpleweb.it) · [simpleweb.it](https://www.simpleweb.it)

This is not an official Unsloth app. It is based on Unsloth Studio.
