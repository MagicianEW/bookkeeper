# 简单记账 SimpleBookkeeper

> 一款轻量、简洁、注重隐私的 Android 个人记账应用，支持收入/支出管理、储蓄追踪、WebDAV 云端同步

**版本**：v0.4.3　｜　**最低系统**：Android 12（API 31）

[English](README.en.md) ｜ [繁體中文](README.zh-TW.md)

---

## 主要功能

### 📒 账本
- 按自然月记录每笔收入 / 支出，支持月份前后切换
- 每笔记录包含：金额、分类、付款方式、日期、备注
- 支持付款方式：微信 / 支付宝 / 现金 / 银行卡等
- 点击记录进入编辑页，数据自动回填，右上角可删除记录

### 🏦 储蓄
- 独立储蓄管理模块，追踪银行账户实际存款
- 支持储蓄存入与支取，自动计算总额与年储蓄趋势
- 年储蓄 = 储蓄总额 - 支取总额

### 📊 统计
- 按月展示收支汇总与分类占比图表
- 按年展示年收入、年支出、年结余、年储蓄
- 支持点击图表条目查看对应明细记录

### 🔍 搜索
- 四维度模糊搜索：时间范围 + 金额范围 + 分类 + 备注关键词
- 结果实时更新，支持点击跳转编辑

### 🌐 多语言
- 支持简体中文、繁體中文、English 三种语言
- 跟随系统 / 手动切换，即时生效
- 界面所有文字均已国际化

### 🎨 外观
- 支持浅色 / 深色 / 跟随系统三种主题模式
- Material You 动态配色
- 适配手机（底部导航栏）与平板（侧边 NavigationRail）

### ⚙️ 设置
| 功能 | 说明 |
|------|------|
| 密码保护 | 设置 4 位以上数字/字母密码，下次启动需验证 |
| 生物识别 | 支持指纹 / 面容解锁（硬件不支持时自动隐藏），关闭时需密码验证 |
| 数据库加密 | SQLCipher AES-256 全库加密，密钥由 Android Keystore 托管 |
| WebDAV 云同步 | 配置私有云服务器，数据库自动备份到云端 |
| 数据导出/导入 | 加密 ZIP 格式，支持完整数据迁移，密码与应用锁统一 |
| 导出日志 | 导出运行日志文本，便于排查同步问题 |
| 分类管理 | 查看 / 删除自定义分类 |
| 语言切换 | 简体中文 / 繁體中文 / English / 跟随系统 |
| 主题模式 | 浅色 / 深色 / 跟随系统 |

---

## 默认分类

| 类型 | 分类 |
|------|------|
| 支出 | 餐饮、交通、购物、住房、娱乐、医疗、教育、通讯、其他支出 |
| 收入 | 工资、理财、兼职、礼金、其他收入 |

> 可在记账页或设置页自由添加自定义分类。

---

## 云端同步

采用 **WebDAV 协议**，兼容主流私有云（Nextcloud、坚果云、群晖等）。

**同步机制：**
1. 上传前自动执行 `PRAGMA wal_checkpoint(TRUNCATE)`，确保数据完整
2. 创建临时副本再上传，避免上传过程中数据库被修改
3. **比较文件内容 MD5 哈希值**，精准检测数据差异
4. 冲突时弹出提示，由用户选择以哪端数据为准
5. 支持 WorkManager 后台定时自动同步（每15分钟）

**配置路径：** 设置 → WebDAV 同步 → 配置服务器地址、用户名、密码

**数据导出格式：**
- ZIP 压缩包内含数据库文件与 meta.json
- 可选 AES-256 加密，加密密码与应用锁密码统一
- 支持导入恢复，兼容跨设备数据迁移

---

## 隐私与安全

- 所有数据**仅存储在本地**，不依赖任何第三方云服务
- 云同步完全由用户自主配置，默认关闭
- 密码使用哈希存储（DataStore），不明文保存
- 可选生物识别二次验证
- **数据库加密**：SQLCipher AES-256 加密，密钥由 Android Keystore 管理，存储于 EncryptedSharedPreferences
- 关闭生物识别需要密码二次验证

---

## 技术栈

| 层级 | 技术 |
|------|------|
| 语言 | Kotlin 2.0 |
| UI | Jetpack Compose + Material3 |
| 数据库 | Room（WAL 模式）+ SQLCipher 加密 |
| 配置存储 | DataStore Preferences |
| 网络 | OkHttp 4（WebDAV） |
| 后台任务 | WorkManager |
| 安全 | Biometric API + Security Crypto + SQLCipher |
| 架构 | MVVM（ViewModel + Flow + Repository） |
| 国际化 | 3 套 strings.xml（简中/繁中/英文）+ 运行时切换 |
| 适配 | 手机（底部导航栏）+ 平板（侧边 NavigationRail） |

---

## 构建方式

**环境要求：**
- Android Studio Hedgehog 或更高版本
- JDK 17+
- Android SDK API 35

**步骤：**

```bash
# 克隆项目
git clone https://github.com/MagicianEW/bookkeeper.git
cd bookkeeper

# 生成 local.properties（指向 SDK 路径）
echo "sdk.dir=/path/to/Android/sdk" > local.properties

# 编译 Debug APK
./gradlew assembleDebug

# APK 输出路径
app/build/outputs/apk/debug/app-debug.apk
```

---

## 版本历史

| 版本 | 说明 |
|------|------|
| v0.4.3 | **安全性与稳定性增强**：密码哈希升级为 PBKDF2；WebDAV 凭据迁移至 EncryptedSharedPreferences；重构 MainViewModel 为 TransactionViewModel、SyncViewModel、SavingsViewModel；移除 runBlocking 调用；缓存可用年份避免重复数据库查询；WebDAV SSL 支持改进；新增 PasswordManager 单元测试 |
| v0.4.2 | **重构同步/导出为 CSV+ZIP 方案**：使用 Zip4j AES-256 加密；优化数据同步和导出流程 |
| v0.4.1 | 开屏页应用名国际化 + 英文应用名修正 |
| v0.4.0 | **架构重构 + 国际化**：回归单库架构（bookkeeper.db 含 Transaction + Category + Saving 三表）；新增储蓄管理模块；新增 i18n 多语言支持（简中/繁中/英文）；新增主题模式切换（浅色/深色/跟随系统）；数据导出改为加密 ZIP 格式；修复生物识别指纹弹窗不弹出（BiometricScheduler 队列堆积 + Activity onPause 自杀问题）；修复语言切换无限循环；版本号统一管理 |
| v0.3.9 | 修复分类丢失问题 - MetaDatabase onCreate 中使用同步 SQL 插入默认分类 |
| v0.3.8 | 修复应用重启后分类丢失问题；新增应用开屏页面；新增刷新按钮；修复搜索状态异常 |
| v0.3.7 | 修复 SyncWorker 单例冲突导致的数据库连接泄漏；修复跨年搜索失效；修复密码哈希安全性（PBKDF2+盐值） |
| v0.3.6 | SQLCipher AES-256 数据库加密；修复 WebDAV 多项问题；关闭生物识别增加密码二次验证 |
| v0.3.5 | 修复 WebDAV 同步空数据库问题；优化同步冲突处理逻辑 |
| v0.3.0 | 数据库按年分库 + 元数据库架构；WebDAV 多文件同步+MD5校验 |
| v0.2.x | 基础功能迭代：记账、搜索、统计、同步、日志、导入导出 |
| v0.1.0 | 初始版本，核心记账、搜索、统计功能 |

---

## 目录结构

```
app/src/main/java/com/simplebookkeeper/
├── BookkeeperApp.kt           # Application 入口
├── crypto/
│   └── DatabaseEncryption.kt   # SQLCipher 密钥管理（Android Keystore）
├── data/
│   ├── AppDatabase.kt         # Room 数据库（Transaction + Category + Saving）
│   ├── DatabaseManager.kt     # 数据库管理器
│   ├── DataExporter.kt        # 加密 ZIP 导入/导出
│   ├── dao/                   # TransactionDao, CategoryDao, SavingDao
│   ├── model/                 # Transaction, Category, Saving
│   └── repository/            # TransactionRepository, SavingRepository, SettingsRepository
├── security/
│   ├── PasswordManager.kt     # 密码管理
│   └── BiometricAuth.kt      # 生物识别（生命周期安全处理）
├── sync/
│   ├── WebDavManager.kt       # WebDAV 同步核心
│   └── SyncWorker.kt          # 后台同步任务
├── ui/
│   ├── MainActivity.kt        # Activity 入口
│   ├── AppNavigation.kt       # 导航图
│   ├── screens/               # 页面组件（账本/搜索/统计/储蓄/设置/锁屏/开屏）
│   ├── components/            # 公共 UI 组件
│   └── theme/                 # Material3 主题 + ThemeMode + LanguageMode
├── util/
│   ├── AppLogger.kt           # 文件日志
│   └── LocaleHelper.kt        # 运行时语言切换
└── viewmodel/
    └── MainViewModel.kt       # 主 ViewModel
```

---

*本项目为个人使用工具，不收集任何用户数据。*
