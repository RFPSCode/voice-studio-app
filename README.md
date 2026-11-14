# 語音工作室 Voice Studio

一套**可以重複使用的語音製作模組**：電腦上產生「語音專案檔」，手機 App 離線產生全部語音，再匯入遊戲或教材。

```
電腦                                 Android 手機（App「語音工作室」）               遊戲／教材
程式或「語音專案編輯器」              匯入專案 → 下載模型（只要一次）                voice/all.txt
產生 xxx.voiceproj.json  ──傳到手機──▶ 離線產生語音、試聽、選版本、兒童音色後製 ──▶ voice/map.txt
                                     匯出 all.txt + map.txt
```

- 語音模型：**Qwen3-TTS 1.7B CustomVoice**（阿里通義千問，Apache 2.0，可商用），9 種內建音色，用中文指令控制情緒。
- 運算速度：自動選用手機 CPU 最快的指令集（i8mm、SVE2…），可以選核心數，也可以試用 GPU（Vulkan，實驗功能）；「模型」分頁有速度測試。
- 台灣讀音：自動轉簡體再送進模型、常用詞改成台灣讀音（星期、垃圾、學姊…），唸錯的字標注音就能修正（例如「縫 ㄈㄥˊ」）。
- 乾淨語音：自動去除喘氣、雜音和過長的停頓，長度不對自動重錄，語音長度和遊戲字幕同步。
- 完全離線：模型第一次開啟 App 時下載（約 2.4 GB），之後不需要網路、沒有額度限制。
- 不需要任何人錄參考聲音。每個角色固定一個音色，角色感用手機上的「後製」做：男童、女童、可愛女聲、渾厚男聲、一般女聲、一般男聲、惡魔、天使，或自訂（音高、音高統一、共振峰、語速、疊音、沙啞、低音高音、回聲、殘響）。

## 檔案

| 路徑 | 內容 |
|---|---|
| `docs/語音專案規格書.md` | **給以後寫程式用的規格書**：專案檔格式、輸出格式、播放範例、驗證清單 |
| `docs/voiceproj.schema.json` | 專案檔的 JSON Schema |
| `examples/校園安全時光機.voiceproj.json` | 完整範例（83 句、9 個角色） |
| `pc/pron-data.js` | 讀音資料（編輯器用，和 App 的 `assets/pron.json` 相同；由 `scripts/build_pron_data.sh` 產生） |
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
| 錄音 | **⚡ 一鍵完成**（錄好所有未錄／已修改的句子＋後製＋匯出）、產生未完成的句子、全部重新產生、每句再產生幾個版本挑最好的、篩選「可能有問題」的句子（長度不正常）、匯出、分享 |
| 角色 | 替每個角色選 Qwen 內建音色並**試聽**（音色＋後製一起聽）；後製設定組（男童、女童、可愛女聲、渾厚男聲、一般女聲、一般男聲、惡魔、天使、自訂）與 13 個細項滑桿，試聽後套用；不用重錄 |
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
- GPU：[Vulkan-Headers](https://github.com/KhronosGroup/Vulkan-Headers)、[SPIRV-Headers](https://github.com/KhronosGroup/SPIRV-Headers)（Apache 2.0 / MIT）
- 讀音資料：[OpenCC](https://github.com/BYVoid/OpenCC)（Apache 2.0）繁簡轉換；台灣讀音取自 [libchewing-data](https://github.com/chewing/libchewing-data)（LGPL 2.1），並以教育部《重編國語辭典修訂本》（[萌典資料](https://github.com/g0v/moedict-data)，CC BY-ND 3.0 TW）核對；大陸讀音 [pypinyin](https://github.com/mozillazg/python-pinyin)（MIT）
- App 簽章金鑰 `android/app/voicestudio.keystore` 是公開的（方便自動編譯與覆蓋安裝），只適合個人／教學使用。
