# 簡單記帳 SimpleBookkeeper

> 一款輕量、簡潔、注重隱私的 Android 個人記帳應用，支援收入/支出管理、儲蓄追蹤、WebDAV 雲端同步

**版本**：v0.4.7　｜　**最低系統**：Android 12（API 31）

[简体中文](README.md) ｜ [English](README.en.md)

---

## 主要功能

### 📒 帳本
- 按自然月記錄每筆收入 / 支出，支援月份前後切換
- 每筆記錄包含：金額、分類、付款方式、日期、備註
- 支援付款方式：微信 / 支付寶 / 現金 / 銀行卡等
- 點擊記錄進入編輯頁，資料自動回填，右上角可刪除記錄

### 🏦 儲蓄
- 獨立儲蓄管理模組，追蹤銀行帳戶實際存款
- 支援儲蓄存入與支取，自動計算餘額
- 取出金額不可超過目前餘額
- _規劃中：年儲蓄趨勢曲線_

### 📊 統計
- 按年展示年收入、年支出、年結餘
- 年份切換自動收斂到有資料的年份範圍
- 展示該年全部明細記錄，點擊可跳轉編輯
- _規劃中：月度收支彙總、分類佔比圖表、年儲蓄、點擊圖表條目聯動_

### 🔍 搜尋
- 四維度模糊搜尋：時間範圍 + 金額範圍 + 分類 + 備註關鍵詞
- 分類篩選橫向滾動，涵蓋全部自訂分類
- 結果即時更新，支援點擊跳轉編輯

### 🌐 多語言
- 支援简体中文、繁體中文、English 三種語言
- 跟隨系統 / 手動切換
- 介面文字均已國際化（含帳目列內的付款方式）
- _切換語言後需重新啟動應用程式_

### 🎨 外觀
- 支援淺色 / 深色 / 跟隨系統三種主題模式
- 適配手機（底部導航欄）與平板（側邊 NavigationRail）

### ⚙️ 設定
| 功能 | 說明 |
|------|------|
| 密碼保護 | 設定 4 位以上數字/字母密碼，下次啟動需驗證 |
| 生物辨識 | 支援指紋 / 面容解鎖（硬體不支援時自動隱藏），關閉時需密碼驗證 |
| 資料庫加密 | SQLCipher AES-256 全庫加密，金鑰由 Android Keystore 託管 |
| WebDAV 雲端同步 | 設定私有雲伺服器，資料自動備份到雲端（支援 HTTP / HTTPS） |
| WebDAV 安全 | 伺服器憑證預設嚴格驗證；自簽憑證需在設定中明確開啟信任。明文 HTTP 可使用但憑據會以明文傳輸，介面會給出提示 |
| 資料匯出/匯入 | 加密 ZIP 格式，支援完整資料遷移，密碼與應用鎖統一 |
| 匯出日誌 | 匯出執行日誌文字，便於排查同步問題 |
| 分類管理 | 查看 / 刪除自訂分類 |
| 語言切換 | 简体中文 / 繁體中文 / English / 跟隨系統 |
| 主題模式 | 淺色 / 深色 / 跟隨系統 |

---

## 預設分類

| 類型 | 分類 |
|------|------|
| 支出 | 餐飲、交通、購物、住房、娛樂、醫療、教育、通訊、其他支出 |
| 收入 | 工資、理財、兼職、禮金、其他收入 |

> 可在記帳頁或設定頁自由新增自訂分類。

---

## 雲端同步

採用 **WebDAV 協議**，相容主流私有雲（Nextcloud、堅果雲、群暉等）。

**同步機制：**
1. 每次帳目變更後觸發一次上傳（連續儲存會合併為一次）
2. 上傳前將目前帳目匯出為 ZIP，並將雲端舊檔重新命名為帶日期的歷史版本
3. 雲端最多保留 4 個歷史版本 + 1 個目前版本
4. 支援 WorkManager 後台定時自動同步（每15分鐘）
5. 支援 HTTP 與 HTTPS；使用 HTTPS 時伺服器憑證預設嚴格驗證，自簽憑證需在設定中明確開啟信任

> ⚠️ 目前為**單向備份**：上傳會直接覆寫雲端檔案，不做差異比較。多裝置同時使用時，後台同步可能覆寫另一台裝置剛上傳的資料。
> _規劃中：內容雜湊差異檢測、衝突提示與手動選擇保留哪一端_

**設定路徑：** 設定 → WebDAV 同步 → 設定伺服器位址、使用者名稱、密碼

**資料匯出格式：**
- ZIP 壓縮包內含各資料表的 CSV 與 meta.json
- 可選 AES-256 加密，加密密碼與應用鎖密碼統一
- 支援匯入恢復，相容跨裝置資料遷移

---

## 隱私與安全

- 所有資料**僅儲存在本機**，不依賴任何第三方雲端服務
- 雲端同步完全由使用者自主設定，預設關閉
- 密碼使用雜湊儲存（DataStore），不明文儲存
- 可選生物辨識二次驗證
- **資料庫加密**：SQLCipher AES-256 加密，金鑰由 Android Keystore 管理，儲存於 EncryptedSharedPreferences
- 關閉生物辨識需要密碼二次驗證

---

## 技術棧

| 層級 | 技術 |
|------|------|
| 語言 | Kotlin 2.0 |
| UI | Jetpack Compose + Material3 |
| 資料庫 | Room（WAL 模式）+ SQLCipher 加密 |
| 設定儲存 | DataStore Preferences |
| 網路 | OkHttp 4（WebDAV） |
| 後台任務 | WorkManager |
| 安全 | Biometric API + Security Crypto + SQLCipher |
| 架構 | MVVM（ViewModel + Flow + Repository） |
| 國際化 | 3 套 strings.xml（简中/繁中/英文）+ 執行時切換 |
| 適配 | 手機（底部導航欄）+ 平板（側邊 NavigationRail） |

---

## 下載

前往 [Releases 頁面](https://github.com/MagicianEW/bookkeeper/releases) 下載最新版本。

| 產物 | 說明 |
|------|------|
| `app-debug.apk` | 偵錯版，已用偵錯憑證簽章，可直接安裝體驗全部功能 |
| `app-release-unsigned.apk` | 發布版（已做 R8 壓縮與資源裁剪，體積約 10M），**未簽章**，需自行簽章後才能安裝 |

> 發布版未附帶簽章憑證，因此無法直接安裝。若要自行簽章，可使用 Android Studio 的
> **Build → Generate Signed Bundle / APK**，或用 `apksigner` 工具簽章。

---

## 建置方式

**環境需求：**
- Android Studio Hedgehog 或更高版本
- JDK 17+
- Android SDK API 35

**步驟：**

```bash
# 複製專案
git clone https://github.com/MagicianEW/bookkeeper.git
cd bookkeeper

# 產生 local.properties（指向 SDK 路徑）
echo "sdk.dir=/path/to/Android/sdk" > local.properties

# 編譯 Debug APK
./gradlew assembleDebug

# APK 輸出路徑
app/build/outputs/apk/debug/app-debug.apk
```

---

## 版本歷史

| 版本 | 說明 |
|------|------|
| v0.4.7 | **代碼審查修復（資料安全與準確性）**：修復密碼鹽 hex 解碼吞掉前導零，導致約 1/256 使用者設定密碼後永久無法解鎖（附 5000 次隨機鹽往返測試）；修復金額用 `Double` 換算靜默少記 1 分（如 19.99 被記成 19.98）；修復儲存按鈕一次誤點後永久失效；修復資料匯入／雲端恢復後應用程式崩潰退出（根因為 SQLCipher `SupportFactory` 首次開啟後會清除記憶體中的金鑰，不可重複使用）；修復匯入匯出時備註含換行導致記錄被拆行、舊格式記錄被靜默丟棄；WebDAV 恢復 TLS 憑證驗證（自簽憑證改為需明確開啟信任並二次確認）；帳目列新增付款方式顯示（v0.4.6 引入但一直未顯示）；修復英文介面預設收付款方式重複出現；統一三處日期選擇器時區；修復搜尋收集器洩漏與年份清單不重新整理；移除無用儲存權限並關閉資料備份；新增 GitHub Actions CI 與 Room schema 匯出 |
| v0.4.6 | **代碼審查修復 + 自訂收/付款方式**：修復搜索結果不更新問題（Flow改為collect）；數據導入添加事務保護；同步時密碼存儲損壞時主動報錯；EncryptedSharedPreferences實例緩存優化；getAllYears改為suspend；SimpleDateFormat提取為頂層常數；年份選擇增加邊界保護；密碼最小長度從4位改為6位；hexToBytes有符號位元組修復；WorkManager同步策略改為UPDATE；AppLogger流式讀寫；MainViewModel lazy初始化優化；**新增自訂收/付款方式功能**，支援添加、刪除、導入導出備份 |
| v0.4.4 | **UI 優化與鍵盤適配**：主題模式和語言選項改為下拉選單；修復虛擬鍵盤遮擋輸入框問題；修復 HomeScreen 日視圖卡片間距 |
| v0.4.3 | **安全性與穩定性增強**：密碼雜湊升級為 PBKDF2；WebDAV 憑據遷移至 EncryptedSharedPreferences；重構 MainViewModel 為 TransactionViewModel、SyncViewModel、SavingsViewModel；移除 runBlocking 呼叫；快取可用年份避免重複資料庫查詢；WebDAV SSL 支援改進；新增 PasswordManager 單元測試 |
| v0.4.2 | **重構同步/匯出為 CSV+ZIP 方案**：使用 Zip4j AES-256 加密；最佳化資料同步和匯出流程 |
| v0.4.1 | 開啟頁面應用名國際化 + 英文應用名修正 |
| v0.4.0 | **架構重構 + 國際化**：回歸單庫架構（bookkeeper.db 含 Transaction + Category + Saving 三表）；新增儲蓄管理模組；新增 i18n 多語言支援（简中/繁中/英文）；新增主題模式切換（淺色/深色/跟隨系統）；資料匯出改為加密 ZIP 格式；修復生物辨識指紋彈窗不彈出（BiometricScheduler 佇列堆積 + Activity onPause 自殺問題）；修復語言切換無限迴圈；版本號統一管理 |
| v0.3.9 | 修復分類遺失問題 - MetaDatabase onCreate 中使用同步 SQL 插入預設分類 |
| v0.3.8 | 修復應用重啟後分類遺失問題；新增應用開屏頁面；新增重新整理按鈕；修復搜尋狀態異常 |
| v0.3.7 | 修復 SyncWorker 單例衝突導致的資料庫連線洩漏；修復跨年搜尋失效；修復密碼雜湊安全性（PBKDF2+鹽值） |
| v0.3.6 | SQLCipher AES-256 資料庫加密；修復 WebDAV 多項問題；關閉生物辨識增加密碼二次驗證 |
| v0.3.5 | 修復 WebDAV 同步空資料庫問題；最佳化同步衝突處理邏輯 |
| v0.3.0 | 資料庫按年分庫 + 元資料庫架構；WebDAV 多檔案同步+MD5校驗 |
| v0.2.x | 基礎功能迭代：記帳、搜尋、統計、同步、日誌、匯入匯出 |
| v0.1.0 | 初始版本，核心記帳、搜尋、統計功能 |

---

## 目錄結構

```
app/src/main/java/com/simplebookkeeper/
├── BookkeeperApp.kt           # Application 入口
├── crypto/
│   └── DatabaseEncryption.kt   # SQLCipher 金鑰管理（Android Keystore）
├── data/
│   ├── AppDatabase.kt         # Room 資料庫（Transaction + Category + Saving）
│   ├── DatabaseManager.kt     # 資料庫管理器
│   ├── DataExporter.kt        # 加密 ZIP 匯入/匯出
│   ├── dao/                   # TransactionDao, CategoryDao, SavingDao
│   ├── model/                 # Transaction, Category, Saving
│   └── repository/            # TransactionRepository, SavingRepository, SettingsRepository
├── security/
│   ├── PasswordManager.kt     # 密碼管理
│   └── BiometricAuth.kt      # 生物辨識（生命週期安全處理）
├── sync/
│   ├── WebDavManager.kt       # WebDAV 同步核心
│   └── SyncWorker.kt          # 後台同步任務
├── ui/
│   ├── MainActivity.kt        # Activity 入口
│   ├── AppNavigation.kt       # 導航圖
│   ├── screens/               # 頁面元件（帳本/搜尋/統計/儲蓄/設定/鎖屏/開屏）
│   ├── components/            # 公共 UI 元件
│   └── theme/                 # Material3 主題 + ThemeMode + LanguageMode
├── util/
│   ├── AppLogger.kt           # 檔案日誌
│   └── LocaleHelper.kt        # 執行時語言切換
└── viewmodel/
    └── MainViewModel.kt       # 主 ViewModel
```

---

*本專案為個人使用工具，不收集任何使用者資料。*
