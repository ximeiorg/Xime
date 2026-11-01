<p align="center">
  <img src="docs/logo.jpg" alt="Xime Logo" width="600">
</p>

<h1 align="center">Xime（曦碼） - 五筆/拼音輸入法</h1>

<p align="center">
  <a href="README.md">English</a> · <a href="README.zh-CN.md">简体中文</a>
</p>

<p align="center">
  <a href="https://gitcode.com/ximeiorg/Xime/releases"><img alt="國內下載" src="https://img.shields.io/badge/%E5%9C%8B%E5%85%A7%E4%B8%8B%E8%BC%89-GitCode%20%E9%8F%A1%E5%83%8F-FC5531?logo=git&logoColor=white"></a>
  <a href="https://github.com/ximeiorg/Xime/releases"><img alt="Release" src="https://img.shields.io/github/v/release/ximeiorg/Xime?label=release&color=2ea44f"></a>
  <a href="https://github.com/ximeiorg/Xime/actions/workflows/build.yml"><img alt="Build" src="https://img.shields.io/github/actions/workflow/status/ximeiorg/Xime/build.yml?label=build"></a>
  <img alt="Android 9.0+" src="https://img.shields.io/badge/Android-9.0%2B%20%7C%20API%2028-3DDC84?logo=android&logoColor=white">
  <a href="LICENSE"><img alt="License" src="https://img.shields.io/badge/license-GPL--3.0-blue"></a>
  <a href="https://rime.im/"><img alt="Rime" src="https://img.shields.io/badge/powered%20by-Rime-00A0E9"></a>
</p>

<p align="center"><b>給國內使用者</b>：GitHub 下載慢？直接到 <a href="https://gitcode.com/ximeiorg/Xime/releases"><b>GitCode 鏡像下載 APK</b></a>，程式碼與 Release 會自動同步。</p>

[<img src="https://f-droid.org/badge/get-it-on.png"
    alt="Get it on F-Droid"
    height="80">](https://f-droid.org/packages/com.kingzcheung.xime)


[Xime 輸入法 (Windows 版)](https://github.com/ximeiorg/XimeYao) | [Xime 輸入法 (Linux 版)](https://github.com/ximeiorg/XimeChe) | [聯想詞預測模型](https://github.com/ximeiorg/predictive-text) | [手寫輸入法模型](https://github.com/ximeiorg/ochwpro)


一款基於 <a href="https://rime.im/">Rime</a> 引擎構建的 Android 五筆/拼音輸入法，專注於簡潔高效的中文輸入體驗。

如果你覺得 UI 或者功能不符合你的要求，你可以直接 fork 一份自行修改。

---

> 本輸入法支援五筆/拼音輸入，只是本人以五筆為主，拼音為輔，因此資源會傾向於五筆為主。

<table align="center">
  <tr>
    <td><img src="docs/Screenshot/full_keyboard_light.jpg" width="180"><br><p align="center">全鍵盤（亮色）</p></td>
    <td><img src="docs/Screenshot/full_keyboard_dark.jpg" width="180"><br><p align="center">全鍵盤（暗色）</p></td>
    <td><img src="docs/Screenshot/全键盘_下滑_light.jpg" width="180"><br><p align="center">字根下滑</p></td>
    <td><img src="docs/Screenshot/shotcut_light.jpg" width="180"><br><p align="center">快捷操作</p></td>
  </tr>
  <tr>
    <td><img src="docs/Screenshot/floating.jpg" width="180"><br><p align="center">懸浮鍵盤</p></td>
    <td><img src="docs/Screenshot/t9_pinyin.jpg" width="180"><br><p align="center">T9 九宮格拼音</p></td>
    <td><img src="docs/Screenshot/number.jpg" width="180"><br><p align="center">數字鍵盤</p></td>
    <td><img src="docs/Screenshot/symbol.jpg" width="180"><br><p align="center">符號鍵盤</p></td>
  </tr>
  <tr>
    <td><img src="docs/Screenshot/hw.png" width="180"><br><p align="center">手寫輸入</p></td>
    <td><img src="docs/Screenshot/hw2.png" width="180"><br><p align="center">手寫找字（候選）</p></td>
    <td><img src="docs/Screenshot/voice.jpg" width="180"><br><p align="center">語音輸入</p></td>
    <td><img src="docs/Screenshot/emoji.jpg" width="180"><br><p align="center">Emoji 鍵盤</p></td>
  </tr>
  <tr>
    <td><img src="docs/Screenshot/theme_light.jpg" width="180"><br><p align="center">主題設定（亮色）</p></td>
    <td><img src="docs/Screenshot/theme_dark.jpg" width="180"><br><p align="center">主題設定（暗色）</p></td>
    <td><img src="docs/Screenshot/plugin_light.jpg" width="180"><br><p align="center">外掛管理</p></td>
    <td><img src="docs/Screenshot/扩展商店.png" width="180"><br><p align="center">擴充商店</p></td>
  </tr>
</table>

## 功能特點

- **多種輸入方案** - 內建五筆86/98、拼音、混輸方案，支援自訂（雙拼、筆畫等），可透過方案市場下載或無線匯入
- **Rime 引擎** - 使用成熟穩定的 Rime 輸入法引擎，精準可靠的中文輸入體驗
- **豐富鍵盤佈局** - QWERTY 全鍵盤、T9 九宮格拼音、九宮格筆畫、手寫、數字（含計算機）
- **懸浮鍵盤** - 懸浮卡片樣式，支援拖拽移動、半透明圓角設計
- **語音轉文字** - 本地離線語音辨識（內建串流 zipformer2 引擎），也支援線上 ASR 外掛（FunAsr、Volc 等）
- **AI 智能增強** - 基於 Transformer 的聯想詞預測，輸入更高效
- **簡潔介面** - Material Design 3 風格，支援淺色/深色主題及多種配色方案
- **鍵盤調節** - 支援鍵盤高度調整和位置移動
- **工具列定製** - 可自訂工具列按鈕佈局和功能
- **按鍵反饋** - 可調節音效和振動強度
- **滑動手勢** - 游標移動、刪除、符號輸入等滑動手勢操作
- **剪貼簿管理** - 剪貼簿歷史記錄，支援快捷傳送和置頂
- **剪貼簿同步** - 透過外掛與遠端裝置雙向同步剪貼簿（WebDAV、ximed 等）
- **候選詞編碼提示** - 候選詞顯示五筆編碼，輔助學習
- **字根顯示** - 下滑按鈕顯示五筆字根，方便健忘用戶
- **實體鍵盤支援** - 連接實體/藍牙鍵盤時顯示浮動候選欄
- **WebDAV 同步** - 透過 WebDAV 備份和還原方案與設定
- **外掛市場** - 透過內建擴充商店安裝可擴充 Lua 外掛（表情、剪貼簿同步、線上 ASR 等）

## 系統需求

- Android 9.0 (API 28) 及以上

## 安裝

### 主程式下載

選擇對應架構的 APK：
- **arm64-v8a**: 適用於大多數現代手機（**絕大部分人的手機都是這個**）
- **armeabi-v7a**: 適用於舊款32位元手機
- **x86_64**: 適用於模擬器
- **universal**: 包含所有架構，體積較大

### 外掛下載（選用）

外掛為 Lua 指令碼外掛（.xipk 格式），可在主應用程式「設定 > 擴充商店」中安裝和啟用：
- **kaomoji**: 顏文字外掛（提供精選顏文字）
- **meme-bunny**: 惡搞兔表情包外掛（提供8個表情）
- **xime-fluent-emoji**: Fluent UI 3D 風格表情外掛（222 個精選 3D 表情，9 大分類）
- **funasr-asr**: 阿里百煉 FunAsr 線上語音辨識
- **volc-asr**: 火山引擎線上語音辨識
- **webdav-clipboard-sync**: 基於 WebDAV 的剪貼簿同步
- **ximed-clipboard-sync**: 基於 ximed 服務的剪貼簿同步

更多外掛請查看 [外掛中心列表](https://ime.ximei.me/plugin-list.html)，或直接到手機應用程式「設定 > 擴充商店」中瀏覽安裝。

### 從 Release 下載

1. 在 [Releases](https://github.com/ximeiorg/Xime/releases) 頁面下載最新版本的 APK
2. 安裝應用程式
3. 在系統設定中啟用 Xime 輸入法
4. 將 Xime 設為目前輸入法

### 國內下載

GitHub Release 在國內偶爾存取不穩定，推薦使用 GitCode 鏡像倉庫：

- 鏡像倉庫：[https://gitcode.com/ximeiorg/Xime](https://gitcode.com/ximeiorg/Xime)
- 鏡像 Release：[https://gitcode.com/ximeiorg/Xime/releases](https://gitcode.com/ximeiorg/Xime/releases)

鏡像的程式碼、tag 與 Release 附件（APK）都會自動同步，國內存取速度更快。

如果鏡像也不可用，可以自行構建安裝，或透過 [https://github.akams.cn](https://github.akams.cn) 加速下載 GitHub Release。

### 手動構建安裝

1. 克隆專案並構建 APK
2. 安裝應用程式
3. 在系統設定中啟用 Xime 輸入法
4. 將 Xime 設為目前輸入法

## 使用文件

詳細使用說明請檢視 [使用文件](https://ime.ximei.me)。

- [常見問題 FAQ](https://ime.ximei.me/faq.html)
- [Rime 方案列表](https://ime.ximei.me/rime-list.html)
- [外掛列表](https://ime.ximei.me/plugin-list.html)
- [AI 模型列表](https://ime.ximei.me/model-list.html)

## 構建

```bash
# 克隆專案（包含子模組）
git clone --recursive https://github.com/ximeiorg/Xime.git

# 國內網路可改用 GitCode 鏡像（子模組仍指向 GitHub，必要時自行替換 .gitmodules 中的位址）
git clone https://gitcode.com/ximeiorg/Xime.git

# 或者在已克隆的專案中初始化子模組
git submodule update --init --recursive

# 構建 Release APK
./gradlew assembleRelease
```

## 技術棧

- Kotlin
- Jetpack Compose
- Material Design 3
- Rime (librime)
- JNI (Native C++)

## 貢獻

歡迎貢獻！在提交 PR 之前，請先閱讀 [CONTRIBUTING.md](CONTRIBUTING.md) 瞭解貢獻流程。

核心規則：
- **先提 Issue** — 所有改動必須先建立 Issue 討論
- **最小修改** — PR 只包含所需的最小改動
- **GPG 簽章** — 所有 commit 必須 GPG 簽章

## 致謝

- [Rime](https://rime.im/) - 中州韻輸入法引擎
- [Trime](https://github.com/osfans/trime) - 同文輸入法，設定參考
- [fcitx5-android](https://github.com/fcitx5-android/fcitx5-android) - 鍵盤佈局參考
- [onnxruntime](https://github.com/microsoft/onnxruntime) - 聯想詞預測與語音辨識的 ONNX 推論引擎

## 授權條款

GPLv3 License

Copyright © 2026 Kingz Cheung

"Xime" 名稱、Logo 及其他品牌資產**不屬於** GPLv3 開源授權範圍，詳見 [TRADEMARKS.md](TRADEMARKS.md)。
