# U message for Android

U message is a fast, secure and free messenger built on the [Telegram API](https://core.telegram.org/api).
This repository contains the source code of the U message Android app.

> U message is an **unofficial** Telegram client created and maintained by [@Javokhiir](https://github.com/Javokhiir). It is not affiliated with or endorsed by Telegram.

## ✨ Features

Everything you love in Telegram, plus features built for safety, privacy and everyday life.

### 🛡️ Built with your safety in mind

U message is designed to protect its users — especially in Uzbekistan — from harmful content, scams and legal risks.

- **Banned sources warning** — U message recognizes channels and chats that are banned in Uzbekistan by the Supreme Court and **warns you before you open them**. You can enter only after confirming that you understand the risk, so nobody ends up in a prohibited source by accident.
- **Inappropriate content filter** — NSFW photos and videos are detected and blurred right on the device, nothing is uploaded anywhere.
- **Parental control** — a safe mode for children's accounts.
- **Malicious APK protection** — warns about dangerous apps and files before you install them.
- **Masked link warnings** — shows where a link really leads when its text pretends to be something else, protecting against phishing.
- **Stranger protection** — messages from unknown people are filtered, so scammers can't reach you directly.
- **Group spam guard** — automatically stops spam and scam bots in groups.

### 🔐 Privacy
- **Privacy Guard** — hides your chats when someone else looks at your screen (on-device face recognition)
- **Hidden chats** behind a password
- **PIN-protected account switching**

### 🌐 Works everywhere
- **Built-in proxy** — connects automatically where Telegram is blocked or slow: works in **China, Russia and the UAE (Dubai)**
- **Up to 20 accounts** in one app

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
