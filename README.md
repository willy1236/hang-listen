# 掛聽（HangListen）

Android App：同時掛多個 Twitch 台，只播聲音，關螢幕、切到別的 App 也會繼續播。

## 建置

1. 用 Android Studio 開啟這個資料夾，等 Gradle Sync 完成
2. 接上手機，按 Run；或執行 `./gradlew assembleDebug`，APK 會在 `app/build/outputs/apk/debug/`

需要 JDK 17 以上（可用 Android Studio 內附的 `jbr`）。依賴版本：AGP 8.7.3、Kotlin 2.1.0、Media3 1.5.1、compileSdk 35、minSdk 26。
已在 Android 11、14 模擬器上測試過。

## 架構

| 檔案 | 作用 |
|---|---|
| `TwitchResolver.kt` | 頻道 → GQL 取 token → usher 主清單 → 挑出 `audio_only` 播放清單網址 |
| `PlaybackService.kt` | 前景服務（`mediaPlayback`），裡面每台一個 ExoPlayer；統一管理音訊焦點、通知、斷線重試，並記下正在播的台 |
| `ChannelHistory.kt` | 加過的頻道紀錄（最近加的在前，最多 30 個） |
| `MainActivity.kt` | Compose UI：加台、最近加過的台（點一下加回）、各台音量、全部暫停／停止、電池最佳化提醒 |

重點設計：

- **多台不互搶**：每個 ExoPlayer 都關掉自己的音訊焦點處理（`handleAudioFocus = false`），改由服務統一向系統要一次焦點。來電時全部暫停、結束後自動恢復；其他 App 開始播音樂時全部暫停。
- **只抓音訊**：直接播 Twitch 的 `audio_only` 媒體清單。若把主清單丟給 ExoPlayer，它會在有影像變體時把純音訊變體濾掉，所以由 `TwitchResolver` 自己挑。插播廣告時 `audio_only` 清單裡也會夾帶影像片段，因此每個 player 都關掉影像軌，避免多台同時開硬體影像解碼器而卡住。
- **自動重連**：token 過期、斷線、下播都會重新解析；錯誤時退避 5 秒到最多 2 分鐘，未開台則每分鐘檢查一次。
- **背景存活**：前景服務加 `WAKE_MODE_NETWORK`（持有 CPU 與 Wi-Fi wake lock）；滑掉 App 不會停。
- **接著播**：正在播的台與音量會存起來，App 被滑掉、被系統砍掉或重開機後再打開，會自動接著播。按「全部停止」或移除到一台不剩則不會。

## 已知限制

- 使用 Twitch 網頁播放器的非官方介面，Twitch 改版可能失效。
- 不登入帳號，所以**不會累積台點或 Drops**，只是在聽。
- 會聽到 Twitch 插入的廣告。
- 小米、三星等廠牌除了電池最佳化還有各自的背景限制（自啟動、鎖定最近任務），需要手動放行。
- 目前只支援 Twitch。要加 YouTube 的話，在 `PlaybackService.load()` 依輸入改用另一個 resolver 即可。
