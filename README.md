# 語音工作室 Voice Studio

一套**可以重複使用的語音製作模組**：電腦上產生「語音專案檔」，手機 App 離線產生全部語音，再匯入遊戲或教材。

```
電腦                                 Android 手機（App「語音工作室」）               遊戲／教材
程式或「語音專案編輯器」              匯入專案 → 下載模型（只要一次）                voice/all.txt
產生 xxx.voiceproj.json  ──傳到手機──▶ 離線產生語音、試聽、選版本、兒童音色後製 ──▶ voice/map.txt
                                     匯出 all.txt + map.txt
```

- 語音模型：**Qwen3-TTS 1.7B CustomVoice**（阿里通義千問，Apache 2.0，可商用），9 種內建音色，用中文指令控制情緒。
- 完全離線：模型第一次開啟 App 時下載（約 2.4 GB），之後不需要網路、沒有額度限制。
- 不需要任何人錄參考聲音。小孩角色用年輕音色＋手機上的「兒童音色後製」（音高＋共振峰）。

## 檔案

| 路徑 | 內容 |
|---|---|
| `docs/語音專案規格書.md` | **給以後寫程式用的規格書**：專案檔格式、輸出格式、播放範例、驗證清單 |
| `docs/voiceproj.schema.json` | 專案檔的 JSON Schema |
| `examples/校園安全時光機.voiceproj.json` | 完整範例（83 句、9 個角色） |
| `pc/語音專案編輯器.html` | 電腦上用瀏覽器開啟：建立、編輯、檢查專案檔；可匯入舊版 Gemini 語音工具的 script.json／voices.json |
| `android/` | App 原始碼（Java＋WebView 介面＋C++ 推論引擎） |
| `.github/workflows/build-apk.yml` | GitHub 自動編譯 APK |

## 安裝 App（vivo X100 Pro 等 Android 10 以上、64 位元、建議 12 GB 以上記憶體）

1. 在手機瀏覽器打開本儲存庫的 **Releases → latest**，下載 `VoiceStudio-1.0.xx.apk`（儲存庫是私人的話要先登入 GitHub）。
2. 點下載好的 APK 安裝。第一次會問要不要允許「安裝未知應用程式」，選允許。
3. 打開「語音工作室」→「模型」分頁 → 下載「標準品質」（約 2.4 GB，請用 Wi-Fi，可以中斷後繼續）。
4. 「專案」分頁 → 匯入專案檔 → 「錄音」分頁 → 「產生未完成的句子」。
5. 錄好後「匯出到下載資料夾」，檔案在 `Download/語音工作室/<專案id>/`。

更新 App：下載新版 APK 直接安裝即可（已錄好的語音和模型都會保留）。

## 在電腦上準備專案檔

- **用程式產生**：照 `docs/語音專案規格書.md`。給 AI 的指令範例：
  > 請依照 RFPSCode/voice-studio-app 的 docs/語音專案規格書.md，為這個遊戲產生語音專案檔 `<id>.voiceproj.json`，並讓遊戲用 game-voice-v1 格式（voice/all.txt + voice/map.txt）播放語音、提供「匯入語音」按鈕。
- **手動編輯**：用瀏覽器打開 `pc/語音專案編輯器.html`。
- 傳到手機：LINE、Google 雲端硬碟、USB、Email 都可以；或把檔案放在 GitHub，App 用「從網址匯入」貼上連結。

## 手機上的操作

| 分頁 | 功能 |
|---|---|
| 專案 | 匯入專案檔（檔案或網址）、切換專案 |
| 錄音 | 產生未完成的句子、每句再產生幾個版本挑最好的、篩選「可能有問題」的句子（長度不正常）、匯出、分享 |
| 角色 | 兒童音色後製：預設（8／11 歲男孩女孩、小天使）、音高、共振峰、語速滑桿，試聽後套用；不用重錄 |
| 模型 | 下載／刪除模型、切換「標準」或「快速」模型、鏡像網站、從手機檔案匯入模型 |

- 產生時請讓 App 留在前景（螢幕會保持開啟）。速度依手機而定，畫面會顯示「每秒語音約幾秒」。
- 同一個專案再匯入新版時，只有內容改過的句子會標成「已修改」，按「產生未完成的句子」就只重錄那些。

## 編譯（開發者）

GitHub Actions 會在 `android/` 有變更時自動編譯，並把 APK 放到 Releases 的 `latest`。本機編譯：

```bash
bash scripts/fetch_deps.sh          # 取得 qwentts.cpp 與 shine（固定版本）
cd android && gradle assembleRelease  # 需要 JDK 17、Android SDK 35、NDK 27.2、CMake 3.22.1
```

## 授權

- 本專案程式碼：MIT
- [Qwen3-TTS](https://github.com/QwenLM/Qwen3-TTS)：Apache 2.0（模型不包含在 App 內，使用時下載）
- [qwentts.cpp](https://github.com/ServeurpersoCom/qwentts.cpp)：MIT
- [shine](https://github.com/toots/shine) MP3 編碼器：LGPL 2.0
- App 簽章金鑰 `android/app/voicestudio.keystore` 是公開的（方便自動編譯與覆蓋安裝），只適合個人／教學使用。
