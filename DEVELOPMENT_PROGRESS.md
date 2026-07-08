# 开发进度报告

## 最近操作 (2026-07-04)

### 版本升级 v0.4.2 → v0.4.3

**修改文件：**
- `app/build.gradle.kts`: versionCode 402→403, versionName "0.4.2"→"0.4.3"
- `README.md` / `README.en.md` / `README.zh-TW.md`:
  - 顶部版本号更新
  - 版本历史添加 v0.4.3 条目

**版本说明 (v0.4.3)：**
- 密码哈希升级为 PBKDF2
- WebDAV 凭据迁移至 EncryptedSharedPreferences
- 重构 MainViewModel 为 TransactionViewModel、SyncViewModel、SavingsViewModel
- 移除 runBlocking 调用
- 缓存可用年份避免重复数据库查询
- WebDAV SSL 支援改进
- 新增 PasswordManager 单元测试

### 发布

**GitHub Release:** https://github.com/MagicianEW/bookkeeper/releases/tag/v0.4.3

**包含文件：**
- `app-debug.apk` (70MB) - 调试版，已签名
- `app-release-unsigned.apk` (10MB) - 发布版，未签名

**提交记录：**
- `b337052` fix: update version to v0.4.3 in README header
- `9609672` v0.4.3: bump version for release

---

## 项目当前状态

### 分支状态
- `main` 分支领先 `origin/main` 0 个 commits（已同步）

### 本地修改（已推送）
无未提交的修改

### 本地 APKs（未纳入版本控制）
- `app/build/outputs/apk/debug/app-debug.apk`
- `app/build/outputs/apk/release/app-release-unsigned.apk`

---

## 待办/后续工作

### 代码质量
- [ ] 废弃警告修复：
  - `Icons.Filled.ArrowBack` → `Icons.AutoMirrored.Filled.ArrowBack`
  - `Icons.Filled.KeyboardArrowLeft/Right` → `Icons.AutoMirrored.Filled.KeyboardArrowLeft/Right`
  - `@Deprecated fun fallbackToDestructiveMigration()` 参数问题
  - `@ExperimentalCoroutinesApi` opt-in 标注

### 功能建议
- [ ] 考虑添加自动备份功能
- [ ] 导出文件命名规范化（如 `bookkeeper_20260704.zip`）

---

## 本次会话记录

本次会话主要完成：
1. 版本号更新到 v0.4.3
2. 更新三国语言 README 版本信息和升级日志
3. 构建并发布 Debug + Unsigned Release APK
4. 创建 GitHub Release v0.4.3

---

## 开发环境

- **Java:** Android Studio bundled JBR
- **Gradle:** 8.11.1
- **Kotlin:** 2.0.21
- **Compose BOM:** 2024.06.00
- **Min SDK:** 31 (Android 12)
- **Target SDK:** 35
