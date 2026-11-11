# U message for Android

U message is a fast, secure and free messenger built on the [Telegram API](https://core.telegram.org/api).
This repository contains the source code of the U message Android app.

> U message is an **unofficial** Telegram client. It is not affiliated with or endorsed by Telegram.

## Features

- Cloud chats, channels, groups and bots
- End-to-end encrypted secret chats
- Voice and video calls
- Stories, friend map, document reader and more
- Up to 20 accounts
- Uzbek, Karakalpak, Russian and English interface

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
