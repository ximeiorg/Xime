<p align="center">
  <img src="docs/logo.jpg" alt="Xime Logo" width="600">
</p>

<h1 align="center">Xime - Wubi / Pinyin Input Method for Android</h1>

<p align="center">
  <a href="README.zh-CN.md">简体中文</a> · <a href="README.zh-TW.md">繁體中文</a>
</p>

<p align="center">
  <a href="https://gitcode.com/ximeiorg/Xime/releases"><img alt="China mirror" src="https://img.shields.io/badge/China%20mirror-GitCode-FC5531?logo=git&logoColor=white"></a>
  <a href="https://github.com/ximeiorg/Xime/releases"><img alt="Release" src="https://img.shields.io/github/v/release/ximeiorg/Xime?label=release&color=2ea44f"></a>
  <a href="https://github.com/ximeiorg/Xime/actions/workflows/build.yml"><img alt="Build" src="https://img.shields.io/github/actions/workflow/status/ximeiorg/Xime/build.yml?label=build"></a>
  <img alt="Android 9.0+" src="https://img.shields.io/badge/Android-9.0%2B%20%7C%20API%2028-3DDC84?logo=android&logoColor=white">
  <a href="LICENSE"><img alt="License" src="https://img.shields.io/badge/license-GPL--3.0-blue"></a>
  <a href="https://rime.im/"><img alt="Rime" src="https://img.shields.io/badge/powered%20by-Rime-00A0E9"></a>
</p>

<p align="center"><b>Users in China</b>: if GitHub is slow, grab the APK from the <a href="https://gitcode.com/ximeiorg/Xime/releases"><b>GitCode mirror</b></a> — code and releases are synced automatically.</p>

[<img src="https://f-droid.org/badge/get-it-on.png"
    alt="Get it on F-Droid"
    height="80">](https://f-droid.org/packages/com.kingzcheung.xime)


[Windows Version](https://github.com/ximeiorg/XimeYao) | [Linux Version](https://github.com/ximeiorg/XimeChe) | [Predictive Text Model](https://github.com/ximeiorg/predictive-text) | [Handwriting Model](https://github.com/ximeiorg/ochwpro)

An Android input method built on the [Rime](https://rime.im/) engine, designed for efficient Chinese text input with Wubi (五笔) and Pinyin support.

---

> This input method supports both Wubi (五笔) and Pinyin input. The author primarily uses Wubi with Pinyin as a fallback, so resources lean toward Wubi.

<table align="center">
  <tr>
    <td><img src="docs/Screenshot/full_keyboard_light.jpg" width="180"><br><p align="center">Full Keyboard (Light)</p></td>
    <td><img src="docs/Screenshot/full_keyboard_dark.jpg" width="180"><br><p align="center">Full Keyboard (Dark)</p></td>
    <td><img src="docs/Screenshot/全键盘_下滑_light.jpg" width="180"><br><p align="center">Radical Swipe</p></td>
    <td><img src="docs/Screenshot/shotcut_light.jpg" width="180"><br><p align="center">Quick Actions</p></td>
  </tr>
  <tr>
    <td><img src="docs/Screenshot/floating.jpg" width="180"><br><p align="center">Floating Keyboard</p></td>
    <td><img src="docs/Screenshot/t9_pinyin.jpg" width="180"><br><p align="center">T9 Pinyin</p></td>
    <td><img src="docs/Screenshot/number.jpg" width="180"><br><p align="center">Numpad</p></td>
    <td><img src="docs/Screenshot/symbol.jpg" width="180"><br><p align="center">Symbols</p></td>
  </tr>
  <tr>
    <td><img src="docs/Screenshot/hw.png" width="180"><br><p align="center">Handwriting</p></td>
    <td><img src="docs/Screenshot/hw2.png" width="180"><br><p align="center">Handwriting (Candidates)</p></td>
    <td><img src="docs/Screenshot/voice.jpg" width="180"><br><p align="center">Voice Input</p></td>
    <td><img src="docs/Screenshot/emoji.jpg" width="180"><br><p align="center">Emoji Keyboard</p></td>
  </tr>
  <tr>
    <td><img src="docs/Screenshot/theme_light.jpg" width="180"><br><p align="center">Theme Settings (Light)</p></td>
    <td><img src="docs/Screenshot/theme_dark.jpg" width="180"><br><p align="center">Theme Settings (Dark)</p></td>
    <td><img src="docs/Screenshot/plugin_light.jpg" width="180"><br><p align="center">Plugin Manager</p></td>
    <td><img src="docs/Screenshot/扩展商店.png" width="180"><br><p align="center">Extension Store</p></td>
  </tr>
</table>

## Features

- **Multiple Input Schemas** - Built-in Wubi 86/98, Pinyin, and mixed schemas; supports custom schemas (Shuangpin, Stroke, etc.) via the schema marketplace or wireless import
- **Rime Engine** - Powered by the mature and reliable Rime input method engine for accurate Chinese input
- **Rich Keyboard Layouts** - QWERTY full keyboard, T9 Pinyin, Stroke 9-key, Handwriting, Numpad (with calculator)
- **Floating Keyboard** - Floating card style with drag support, semi-transparent rounded design
- **Voice-to-Text** - Local offline ASR (built-in streaming zipformer2 engine) plus online ASR plugins (FunAsr, Volc, etc.)
- **AI Enhancement** - Transformer-based predictive text for faster input
- **Clean UI** - Material Design 3, light/dark themes with multiple color schemes
- **Keyboard Adjustment** - Adjustable keyboard height and position
- **Toolbar Customization** - Customizable toolbar button layout and functions
- **Haptic Feedback** - Adjustable sound and vibration intensity
- **Swipe Gestures** - Cursor movement, deletion, symbol input via swipe gestures
- **Clipboard Manager** - Clipboard history with quick send and pinning
- **Clipboard Sync** - Bidirectional clipboard sync with remote devices via plugins (WebDAV, ximed, etc.)
- **Candidate Coding Hints** - Display Wubi codes for candidates to aid learning
- **Radical Display** - Swipe down on keys to show Wubi radicals for memory aid
- **Physical Keyboard Support** - Floating candidate bar when using hardware/bluetooth keyboards
- **WebDAV Sync** - Backup and restore schemas and settings via WebDAV
- **Plugin Marketplace** - Extensible Lua plugins (emoji, clipboard sync, online ASR, etc.) via the built-in marketplace

## Requirements

- Android 9.0 (API 28) or later

## Installation

### Download

Choose the APK matching your device architecture:
- **arm64-v8a**: Modern phones (recommended for most users)
- **armeabi-v7a**: Older 32-bit phones
- **x86_64**: Emulators
- **universal**: All architectures (larger file size)

### From Releases

1. Download the latest APK from [Releases](https://github.com/ximeiorg/Xime/releases)
2. Install the application
3. Enable Xime in system input method settings
4. Set Xime as the current input method

> **China mirror**: if GitHub is slow or blocked, use the GitCode mirror — [repository](https://gitcode.com/ximeiorg/Xime) · [releases](https://gitcode.com/ximeiorg/Xime/releases). Code, tags and release assets are synced automatically.

### Plugins (Optional)

Plugins are Lua-script plugins (`.xipk` format), installable and enabled from the app's Settings > Extension Store:
- **kaomoji**: Kaomoji text emoticons
- **meme-bunny**: Funny bunny sticker pack (8 stickers)
- **xime-fluent-emoji**: Fluent UI 3D-style emoji plugin (222 curated 3D emojis, 9 categories)
- **funasr-asr**: Alibaba Bailian FunAsr online speech recognition
- **volc-asr**: Volcano Engine online speech recognition
- **webdav-clipboard-sync**: WebDAV-based clipboard sync
- **ximed-clipboard-sync**: ximed-service-based clipboard sync

For the full plugin list, see the [Plugin Center](https://ime.ximei.me/plugin-list.html), or browse and install directly from the app's Settings > Extension Store.

### Build from Source

1. Clone the project and build the APK
2. Install the application
3. Enable Xime in system input method settings
4. Set Xime as the current input method

## Documentation

For detailed documentation, visit [https://ime.ximei.me](https://ime.ximei.me).

- [FAQ](https://ime.ximei.me/faq.html)
- [Rime Schemas List](https://ime.ximei.me/rime-list.html)
- [Plugin List](https://ime.ximei.me/plugin-list.html)
- [AI Models List](https://ime.ximei.me/model-list.html)

## Building

```bash
# Clone with submodules
git clone --recursive https://github.com/ximeiorg/Xime.git

# China mirror (submodules still point at GitHub — rewrite .gitmodules if needed)
git clone https://gitcode.com/ximeiorg/Xime.git

# Or initialize submodules in an existing clone
git submodule update --init --recursive

# Build Release APK
./gradlew assembleRelease
```

## Tech Stack

- Kotlin
- Jetpack Compose
- Material Design 3
- Rime (librime)
- JNI (Native C++)

## Contributing

Contributions welcome! Please read [CONTRIBUTING.md](CONTRIBUTING.md) before submitting a PR.

Core rules:
- **File an Issue first** — All changes require a prior discussion via an Issue
- **Minimal changes** — PRs must contain only the minimum changes needed
- **GPG signing** — All commits must be GPG-signed

## Acknowledgments

- [Rime](https://rime.im/) - Input method engine
- [Trime](https://github.com/osfans/trime) - Configuration reference
- [fcitx5-android](https://github.com/fcitx5-android/fcitx5-android) - Keyboard layout reference
- [onnxruntime](https://github.com/microsoft/onnxruntime) - ONNX inference runtime for predictive text and speech recognition models

## License

GPLv3 License

Copyright © 2026 Kingz Cheung

The Xime name, logo and other brand assets are **not** covered by the GPLv3 license.
See [TRADEMARKS.md](TRADEMARKS.md) for details.
