# 简单记账 SimpleBookkeeper

> 一款轻量、简洁、注重隐私的 Android 个人记账应用，支持收入/支出管理、储蓄追踪、WebDAV 云端同步

**版本**：v 0.2.14　｜　**最低系统**：Android 12（API 31）

---

## 主要功能

### 📒 账本
- 按自然月记录每笔收入 / 支出，支持月份前后切换
- 每笔记录包含：金额、分类、付款方式、日期、备注
- 支持付款方式：微信 / 支付宝 / 现金 / 银行卡等
- 点击记录进入编辑页，数据自动回填，**右上角可删除记录**

### 📊 统计
- 按月展示收支汇总与分类占比图表
- 按年展示年收入、年支出、年结余、**年储蓄**
- 年储蓄 = 储蓄总额 - 支取总额（追踪银行账户实际存款）
- 支持点击图表条目查看对应明细记录

### 🔍 搜索
- 四维度模糊搜索：时间范围 + 金额范围 + 分类 + 备注关键词
- 结果实时更新，支持点击跳转编辑

### ⚙️ 设置
| 功能 | 说明 |
|------|------|
| 密码保护 | 设置 4 位以上数字/字母密码，下次启动需验证 |
| 生物识别 | 支持指纹 / 面容解锁（硬件不支持时自动隐藏） |
| WebDAV 云同步 | 配置私有云服务器，数据库自动备份到云端 |
| 数据导出/导入 | `.db` 文件格式，支持完整数据迁移 |
| 导出日志 | 导出运行日志文本，便于排查同步问题 |
| 分类管理 | 查看 / 删除自定义分类 |

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

---

## 隐私与安全

- 所有数据**仅存储在本地**，不依赖任何第三方云服务
- 云同步完全由用户自主配置，默认关闭
- 密码使用哈希存储（DataStore），不明文保存
- 可选生物识别二次验证

---

## 技术栈

| 层级 | 技术 |
|------|------|
| 语言 | Kotlin 2.0 |
| UI | Jetpack Compose + Material3 |
| 数据库 | Room（WAL 模式） |
| 配置存储 | DataStore Preferences |
| 网络 | OkHttp 4（WebDAV） |
| 后台任务 | WorkManager |
| 安全 | Biometric API + Security Crypto |
| 架构 | MVVM（ViewModel + Flow + Repository） |
| 适配 | 手机（底部导航栏）+ 平板（侧边 NavigationRail） |

---

## 构建方式

**环境要求：**
- Android Studio Hedgehog 或更高版本
- JDK 17+
- Android SDK API 36

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
| v 0.2.14 | 修复记账页分类添加后不显示、修复云端同步覆盖本地数据问题、修复本地导入数据不生效 |
| v 0.2.13 | 修复编辑记录删除功能 |
| v 0.2.12 | 统计页新增年储蓄统计（储蓄总额-支取总额） |
| v 0.2.11 | 修复同步冲突选择界面、同步改用文件哈希检测 |
| v 0.2.10 | 新增日志系统、数据导入导出、关于弹窗 |
| v 0.1.0 | 初始版本，核心记账、搜索、统计功能 |

---

## 目录结构

```
app/src/main/java/com/simplebookkeeper/
├── BookkeeperApp.kt          # Application 入口
├── data/
│   ├── AppDatabase.kt        # Room 数据库 & 默认分类
│   ├── dao/                  # TransactionDao, CategoryDao
│   ├── model/                # Transaction, Category, Converters
│   └── repository/           # TransactionRepository, SettingsRepository
├── security/
│   ├── PasswordManager.kt     # 密码管理
│   └── BiometricAuth.kt      # 生物识别
├── sync/
│   ├── WebDavManager.kt      # WebDAV 同步核心
│   └── SyncWorker.kt         # 后台同步任务
├── ui/
│   ├── MainActivity.kt       # Activity 入口
│   ├── AppNavigation.kt      # 导航图
│   ├── screens/             # 页面组件
│   ├── components/          # 公共 UI 组件
│   └── theme/               # Material3 主题
├── util/
│   └── AppLogger.kt         # 文件日志
└── viewmodel/
    └── MainViewModel.kt     # 主 ViewModel
```

---

*本项目为个人使用工具，不收集任何用户数据。*
