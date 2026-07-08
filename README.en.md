# SimpleBookkeeper

> A lightweight, clean, privacy-focused Android personal expense tracker with income/expense management, savings tracking, and WebDAV cloud sync

**Version**: v0.4.4 ｜ **Minimum OS**: Android 12 (API 31)

[简体中文](README.md) ｜ [繁體中文](README.zh-TW.md)

---

## Features

### 📒 Ledger
- Record income and expenses by month, swipe to switch months
- Each entry includes: amount, category, payment method, date, note
- Payment methods: WeChat / Alipay / Cash / Bank Card, etc.
- Tap an entry to edit; data auto-fills; delete via top-right button

### 🏦 Savings
- Dedicated savings module to track actual bank deposits
- Support deposits and withdrawals; auto-calculate totals and yearly savings trends
- Annual savings = Total deposits - Total withdrawals

### 📊 Statistics
- Monthly income/expense summary with category breakdown charts
- Annual view: income, expenses, balance, and savings
- Tap chart items to view detailed records

### 🔍 Search
- Four-dimensional fuzzy search: date range + amount range + category + note keywords
- Real-time results; tap to jump to edit

### 🌐 Multi-Language
- Supports Simplified Chinese, Traditional Chinese, and English
- Follow system / manual switch, takes effect immediately
- All UI text fully internationalized

### 🎨 Appearance
- Light / Dark / Follow System theme modes
- Material You dynamic color
- Adaptive layout: phone (bottom navigation) + tablet (side NavigationRail)

### ⚙️ Settings
| Feature | Description |
|---------|-------------|
| Password Protection | Set a 4+ digit/letter password; required on next launch |
| Biometric Auth | Fingerprint / face unlock (auto-hidden if hardware unsupported); password required to disable |
| Database Encryption | SQLCipher AES-256 full-database encryption; keys managed by Android Keystore |
| WebDAV Cloud Sync | Configure your private cloud server for automatic database backup |
| Data Export/Import | Encrypted ZIP format; supports full data migration; password unified with app lock |
| Export Logs | Export runtime logs for troubleshooting sync issues |
| Category Management | View / delete custom categories |
| Language Switch | Simplified Chinese / Traditional Chinese / English / Follow System |
| Theme Mode | Light / Dark / Follow System |

---

## Default Categories

| Type | Categories |
|------|-----------|
| Expense | Dining, Transport, Shopping, Housing, Entertainment, Medical, Education, Telecom, Other Expense |
| Income | Salary, Investment, Part-time, Gift Money, Other Income |

> Custom categories can be added from the ledger or settings page.

---

## Cloud Sync

Uses the **WebDAV protocol**, compatible with major private cloud services (Nextcloud, Nutstore, Synology, etc.).

**Sync mechanism:**
1. Automatically runs `PRAGMA wal_checkpoint(TRUNCATE)` before upload to ensure data integrity
2. Creates a temporary copy before uploading to prevent mid-upload modifications
3. **Compares file content MD5 hashes** for precise change detection
4. Prompts on conflict — user chooses which version to keep
5. Supports WorkManager scheduled background sync (every 15 minutes)

**Configuration path:** Settings → WebDAV Sync → Configure server address, username, password

**Data export format:**
- ZIP archive containing database file and meta.json
- Optional AES-256 encryption; encryption password unified with app lock
- Supports import/restore; compatible with cross-device data migration

---

## Privacy & Security

- All data is **stored locally only**; no reliance on any third-party cloud services
- Cloud sync is entirely user-configured; disabled by default
- Passwords stored as hashes (DataStore); never in plaintext
- Optional biometric secondary verification
- **Database encryption**: SQLCipher AES-256; keys managed by Android Keystore, stored in EncryptedSharedPreferences
- Disabling biometric auth requires password verification

---

## Tech Stack

| Layer | Technology |
|-------|-----------|
| Language | Kotlin 2.0 |
| UI | Jetpack Compose + Material3 |
| Database | Room (WAL mode) + SQLCipher encryption |
| Preferences | DataStore Preferences |
| Network | OkHttp 4 (WebDAV) |
| Background Tasks | WorkManager |
| Security | Biometric API + Security Crypto + SQLCipher |
| Architecture | MVVM (ViewModel + Flow + Repository) |
| i18n | 3 strings.xml sets (zh-CN/zh-TW/en) + runtime switching |
| Layout | Phone (bottom navigation) + Tablet (side NavigationRail) |

---

## Building

**Prerequisites:**
- Android Studio Hedgehog or later
- JDK 17+
- Android SDK API 35

**Steps:**

```bash
# Clone the project
git clone https://github.com/MagicianEW/bookkeeper.git
cd bookkeeper

# Generate local.properties (point to SDK path)
echo "sdk.dir=/path/to/Android/sdk" > local.properties

# Build Debug APK
./gradlew assembleDebug

# APK output path
app/build/outputs/apk/debug/app-debug.apk
```

---

## Version History

| Version | Notes |
|---------|-------|
| v0.4.4 | **UI improvements & keyboard handling**: Theme mode and language switched to dropdown menus; fixed virtual keyboard covering input fields; fixed HomeScreen day card spacing |
| v0.4.3 | **Security & stability enhancements**: Upgraded password hashing to PBKDF2; migrated WebDAV credentials to EncryptedSharedPreferences; refactored MainViewModel into TransactionViewModel, SyncViewModel, SavingsViewModel; removed runBlocking calls; cached available years to avoid repeated DB queries; improved WebDAV SSL support; added PasswordManager unit tests |
| v0.4.2 | **Refactored sync/export to CSV+ZIP scheme**: Using Zip4j AES-256 encryption; optimized data sync and export flow |
| v0.4.1 | Splash screen app name i18n + English app name fix |
| v0.4.0 | **Architecture refactor + i18n**: Reverted to single-database architecture (bookkeeper.db with Transaction + Category + Saving tables); added savings management module; added i18n multi-language support (zh-CN/zh-TW/en); added theme mode switching (Light/Dark/System); data export now uses encrypted ZIP format; fixed biometric fingerprint dialog not appearing (BiometricScheduler queue buildup + Activity onPause self-kill issue); fixed language switching infinite loop; unified version number management |
| v0.3.9 | Fixed category loss — MetaDatabase onCreate uses synchronous SQL to insert default categories |
| v0.3.8 | Fixed category loss after app restart; added splash screen; added refresh button; fixed search state anomaly |
| v0.3.7 | Fixed SyncWorker singleton conflict causing database connection leak; fixed cross-year search; improved password hash security (PBKDF2+salt) |
| v0.3.6 | SQLCipher AES-256 database encryption; fixed multiple WebDAV issues; added password verification for disabling biometric auth |
| v0.3.5 | Fixed WebDAV syncing empty database; improved sync conflict handling |
| v0.3.0 | Per-year database sharding + metadata DB architecture; WebDAV multi-file sync with MD5 verification |
| v0.2.x | Core feature iterations: expense tracking, search, statistics, sync, logging, import/export |
| v0.1.0 | Initial release — core expense tracking, search, and statistics |

---

## Project Structure

```
app/src/main/java/com/simplebookkeeper/
├── BookkeeperApp.kt           # Application entry
├── crypto/
│   └── DatabaseEncryption.kt   # SQLCipher key management (Android Keystore)
├── data/
│   ├── AppDatabase.kt         # Room database (Transaction + Category + Saving)
│   ├── DatabaseManager.kt     # Database manager
│   ├── DataExporter.kt        # Encrypted ZIP import/export
│   ├── dao/                   # TransactionDao, CategoryDao, SavingDao
│   ├── model/                 # Transaction, Category, Saving
│   └── repository/            # TransactionRepository, SavingRepository, SettingsRepository
├── security/
│   ├── PasswordManager.kt     # Password management
│   └── BiometricAuth.kt      # Biometric auth (lifecycle-safe handling)
├── sync/
│   ├── WebDavManager.kt       # WebDAV sync core
│   └── SyncWorker.kt          # Background sync task
├── ui/
│   ├── MainActivity.kt        # Activity entry
│   ├── AppNavigation.kt       # Navigation graph
│   ├── screens/               # Screen composables (Ledger/Search/Statistics/Savings/Settings/Lock/Splash)
│   ├── components/            # Shared UI components
│   └── theme/                 # Material3 theme + ThemeMode + LanguageMode
├── util/
│   ├── AppLogger.kt           # File logging
│   └── LocaleHelper.kt        # Runtime language switching
└── viewmodel/
    └── MainViewModel.kt       # Main ViewModel
```

---

*This project is a personal tool. It does not collect any user data.*
