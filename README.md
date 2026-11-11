# U message for Android

U message is a fast, secure and free messenger built on the [Telegram API](https://core.telegram.org/api).
This repository contains the source code of the U message Android app.

> U message is an **unofficial** Telegram client created and maintained by [@Javokhiir](https://github.com/Javokhiir). It is not affiliated with or endorsed by Telegram.

## ✨ Features

Everything you love in Telegram, plus features built for safety, privacy and everyday life.

### 🌐 Works everywhere
- **Built-in proxy** — connects automatically where Telegram is blocked or slow: works in **China, Russia and the UAE (Dubai)**
- **Up to 20 accounts** in one app, with PIN protection when switching

### 🛡️ Privacy & safety
- **Privacy Guard** — hides your chats when someone else looks at your screen (on-device face recognition)
- **Inappropriate content filter** — blurs and blocks NSFW photos and videos on the device
- **Parental control** for children's accounts
- **Hidden chats** behind a password
- **Stranger protection** — messages from unknown people are filtered
- **Malicious APK blocking** and **masked link warnings**
- **Group spam guard**
- **Banned sources warning** — alerts before opening sources banned by the Supreme Court of Uzbekistan

### 🗺️ Friends & places
- **Friend Map** — share your live location with friends on a 3D globe, request-and-accept only, encrypted
- **Nearby share** — send files to people around you

### 📸 Stories & camera
- **Pro story camera** — 60 fps video, TikTok-style tools, lenses and effects
- **Story privacy in one tap** for all your old stories
- **Round video effects** and front camera for video messages

### 💬 Chats & productivity
- **Document reader** — read PDFs right inside the app
- **Offline translation** and **Uzbek speech-to-text**
- **Message templates** and **auto-reply**
- **Chat trash** — restore chats you deleted by mistake
- **Focus mode**, folder icons, admin folders for your groups and channels
- **Send confirmations** for stickers, GIFs and voice messages
- **Chat export**, unread reminders, sleep timer for audio
- **Music recognition** and **QR login**
- **name@u usernames** and an official in-app support chat

### 🌍 Languages
Uzbek, Karakalpak, Russian and English interface.

## Building

1. Get your own `api_id` and `api_hash` at https://my.telegram.org and set them in
   `TMessagesProj/src/main/java/org/telegram/messenger/BuildVars.java`.
2. Replace `TMessagesProj/google-services.json` and `TMessagesProj_App/google-services.json`
   with your own Firebase config.
3. Copy `local.properties.example` to `local.properties` and fill in the optional keys.
4. Initialize submodules: `git submodule update --init --recursive`
5. Open the project in Android Studio and build `TMessagesProj_App`.

Please do not use the U message name and logo for your own builds.

## License

U message is based on [Telegram for Android](https://github.com/DrKLO/Telegram) and is licensed
under the GNU General Public License v2.0 or later. See [LICENSE](LICENSE).
