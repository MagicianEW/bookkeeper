# 简单记账 SimpleBookkeeper v0.4.6 — 产品功能 × 代码实现审查报告

- 审查对象：`https://github.com/MagicianEW/bookkeeper` @ `2d632fc`（v0.4.6）
- 技术栈：Kotlin + Jetpack Compose + Room 6 + SQLCipher + DataStore + OkHttp/WebDAV + WorkManager
- 规模：44 个 Kotlin 文件 / 7,538 行
- 方法：以 README 承诺的产品功能为纲，逐条核对实现路径；关键推断已实际运行验证

---

## 一、结论摘要

代码整体结构清晰（单一数据库架构、Repository 分层、Room Flow 驱动 UI、导入用 `withTransaction` 保护），但存在**三个会导致用户数据不可用或泄露的严重缺陷**，以及多个"README 承诺了但代码里根本没有"的功能缺口。

| 级别 | 数量 | 说明 |
|------|------|------|
| P0 严重 | 3 | 永久锁死、数据损坏、同步凭据可被劫持 |
| P1 高 | 5 | 核心功能不可用 / 金额算错 / 宣传功能缺失 |
| P2 中 | 10 | 数据一致性、国际化、搜索、加密一致性 |
| P3 低 | 12 | 死代码、资源泄漏、体验与文案 |

**最高优先级三件事**

1. `PasswordManager.hexToBytes` 用 `BigInteger` 解析盐的 hex，前导零字节被吞掉 → 约 1/256 用户设置密码后**永久无法解锁**，全部账目只能清数据找回。
2. 数据导入 / 云端恢复后会 `close()` 正在使用的 Room 单例，且 UI 只提示"导入成功" → 应用进入损坏状态，**记不上账直到强杀重启**。
3. WebDAV 关闭了全部 TLS 校验并允许明文流量 → 私有云账号密码与账目 ZIP 可被同网段中间人直接获取，与"注重隐私 / AES-256 全库加密"的定位冲突。

---

## 二、P0 严重缺陷

### P0-1【密码保护】盐 hex 编解码丢前导零 → 约 0.39% 用户永久锁死

**位置**：`security/PasswordManager.kt:245-254`（`hexToBytes`）、`:96-107`（`setPassword`）、`:110-140`（`verifyPassword`）

`setPassword` 用 `SecureRandom` 生成 16 字节盐并以 hex 存盘；`verifyPassword` 再用 `BigInteger(hex, 16).toByteArray()` 还原。`BigInteger` 会丢弃全部前导零字节，而现有的补零补偿条件 `bytes.size == hex.length/2 + 1 && bytes[0] == 0` 只覆盖"最高位为 1 补 0x00"这一种情况。

实测复现（用该算法原样运行）：

```
in  (16 bytes) 1f3a9c22b7e5d0148ac6b2f9e7d31075
out (16 bytes) 1f3a9c22b7e5d0148ac6b2f9e7d31075   ✓

in  (16 bytes) 0041c9a2b7e5d0148ac6b2f9e7d3107a   ← 随机盐首字节恰为 0x00
out (15 bytes) 041c9a2b7e5d0148ac6b2f9e7d3107a   ✗ 少了 1 字节
```

**影响**：还原出的盐与原始盐不同 → PBKDF2 派生密钥不同 → 哈希不匹配。随机盐首字节为 `0x00` 的概率 = 1/256 ≈ 0.39%。这类用户输入任何正确密码都验证失败，且没有任何找回入口（`disablePassword` 需先验证密码），唯一出路是清除应用数据或卸载 —— **全部账目丢失**。

**修复**：用 `MessageDigest`/`hexToBytes` 的逐字节实现替代 `BigInteger`：

```kotlin
private fun hexToBytes(hex: String): ByteArray {
    val out = ByteArray(hex.length / 2)
    for (i in out.indices) {
        out[i] = ((hex[i * 2].digitToInt(16) shl 4) or hex[i * 2 + 1].digitToInt(16)).toByte()
    }
    return out
}
```

**顺带**：`verifyPassword:122` 用 `String.==` 比较哈希，非恒定时间；建议改 `MessageDigest.isEqual`。

---

### P0-2【数据导入 / 云端恢复】导入后关闭数据库单例且无重启提示 → 应用损坏

**位置**：`data/DataExporter.kt:212` → `data/DatabaseManager.kt:268-272` → `data/AppDatabase.kt:134-137`；提示文案在 `ui/screens/SettingsScreen.kt:169-171`

调用链：

```
importFromZip()  → 写入数据完成
                 → dbManager.resetForReEncryption()
                 → AppDatabase.clearInstance()   // INSTANCE?.close(); INSTANCE = null
```

而 `DatabaseManager._db` 是 `by lazy` 的 **val**（`DatabaseManager.kt:52-55`），早已持有那个被 `close()` 的对象引用；所有 Repository/DAO 都从它取。`clearInstance()` 只把**静态** `INSTANCE` 置空，**不会**让已持有的 `_db` 重新创建。

**影响**：导入成功后用户看到"导入成功"，但接下来任何一次记账/查询都会打在已关闭的 RoomDatabase 上（`IllegalStateException`）——表现为保存无响应、`onDone()` 不回调、列表不再刷新，或直接崩溃。README 宣传的"数据导出/导入""完整数据迁移"以及首次启动的云端恢复（`FirstLaunchSyncDialog`）都会命中。

**修复**（二选一）：
- 导入成功后显式提示并引导重启：`AppUtil.restartApp(context)`，同时在 `DatabaseManager` 暴露 `reopen()`；
- 或让 `_db` 不缓存已关闭实例：`resetForReEncryption()` 之后所有 DAO 走 `AppDatabase.getInstance(...)` 重新获取。

> 附带：`resetForReEncryption()` 想触发的"重新加密"链路其实不存在 —— `DatabaseEncryption.isMigrationDone()` / `markMigrationDone()` 全项目无调用方，该函数目前只是"关库 + 翻一个没用的标志位"。

---

### P0-3【WebDAV 同步】TLS 校验全关 + 允许明文 → 凭据与账目可被中间人获取

**位置**：`sync/WebDavManager.kt:40-41`、`:335-347`；`AndroidManifest.xml` 的 `android:usesCleartextTraffic="true"`

```kotlin
.hostnameVerifier { _, _ -> true }                                  // 任意主机名都"有效"
.sslSocketFactory(createUnsafeSslSocketFactory(), createUnsafeTrustManager())

override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) {}  // 空实现
```

所有 WebDAV 请求都用 HTTP Basic 携带 `Credentials.basic(username, password)`（`:128, :171, :200, :236, :252, :270, :292, :311`），叠加 `usesCleartextTraffic="true"` 后**明文 HTTP 也被允许**。`testConnection` 的错误提示甚至主动引导用户"请尝试使用HTTP"（`:149`）。

**影响**：同一网络（公共 Wi-Fi、恶意热点、被劫持的路由器）上的攻击者可伪装成用户的 Nextcloud/坚果云，拿到私有云账号密码，并读取/替换上传的账目 ZIP。这个缺陷直接抵消了 SQLCipher AES-256 + Android Keystore 的全部防护意义。

**修复**：
- 删除 `hostnameVerifier { true }` 与空 `checkServerManager`；
- 自签名需求改为显式开关（"允许自签名证书"）+ 用户导入自签 CA 证书，或使用证书固定；
- `usesCleartextTraffic` 设为 `false`，明文 HTTP 在设置页给出明确警告而非默认推荐。

---

## 三、P1 高优先级

### P1-1【账本】保存按钮可能永久失效（v0.4.5 修复引入的回归）

**位置**：`ui/screens/AddEditTransactionScreen.kt:372-382`

```kotlin
if (isSaving) { return@Button }
isSaving = true                                  // ← 先置位
val amountYuan = amountText.toDoubleOrNull() ?: return@Button      // ← 早退不复位
if (amountYuan <= 0 || selectedCategoryId == 0L) { return@Button } // ← 早退不复位
```

全文件对 `isSaving` 只有两处赋值：`:68` 初始化 `false`、`:376` 置 `true`，**没有任何复位点**。因此只要用户在金额为空/为 0 或未选分类时点一次"保存"，按钮在本页面生命周期内就彻底失效 —— 即使随后补全金额也再无响应，只能返回重进。这是 v0.4.5 "fix duplicate save bug - add isSaving guard" 引入的回归。

**修复**：把 `isSaving = true` 移到全部校验通过之后，或在每个 `return@Button` 前复位；更稳妥的做法是用局部 `val amountFen = parseAmount(amountText) ?: return@Button` 后再置位。

---

### P1-2【账本】金额元→分用浮点截断，必然少记 1 分

**位置**：`AddEditTransactionScreen.kt:386`、`viewmodel/SavingsViewModel.kt:32`、`AddSavingDialog.kt:128`、`SearchScreen.kt:198-199`

`(amountYuan * 100).toLong()` 作用在 `Double` 上。输入框正则 `^\d{0,10}(\.\d{0,2})?$`（`AddEditTransactionScreen.kt:211`）允许两位小数，因此必然命中：

```
19.99 -> (long)(19.99*100)  = 1998   应为 1999
1.15  -> (long)(1.15*100)   = 114    应为 115
0.29  -> (long)(0.29*100)   = 28     应为 29
2.675 -> 267（且输入框不允许 3 位，暂不触发）
```

**影响**：记账类应用的核心准确性缺陷，且**静默发生**、用户无感知，日/月/年统计全部随之偏差。

**修复**：全程用整数分存储，输入侧用 `BigDecimal(amountText).movePointRight(2).setScale(0, RoundingMode.HALF_UP).toLong()`，或解析字符串（去掉小数点后取前两位）。

---

### P1-3【统计】README 宣传的图表与"年储蓄"完全不存在

**证据**：

```
$ grep -rn "Canvas|DonutChart|PieChart|drawArc" app/src/main --include=*.kt   → 无结果
$ grep -rn "yearly_saving|年储蓄" app/src/main                               → 无结果
```

`ui/screens/StatisticsScreen.kt` 实际只有：年切换 + "年收入/年支出/年结余"三项 + 全年明细列表。**没有**月度收支汇总、**没有**分类占比图表、**没有**图表点击查看明细。储蓄页（`SavingsScreen.kt`）同样没有 README 宣称的"年储蓄趋势"，只有余额卡 + 按日分组的流水。

另外 `StatisticsScreen.kt:37` 收集了 `availableYears` 却**完全未使用**（死代码），年份切换依赖硬编码边界 `2000 ~ 今年+1`（`:75, :87`），且这两处无障碍文案是硬编码中文"上一年/下一年"。

**建议**：要么补齐图表（Compose `Canvas` + 年度储蓄聚合查询），要么先修正 README，避免用户按文档找功能。

---

### P1-4【WebDAV 同步】MD5 差异比较与冲突提示完全未实现

**证据**：`grep -rn "md5|MD5" app/src/main --include=*.kt` → 无结果。

README 承诺"**比较文件内容 MD5 哈希值**，精准检测数据差异 / 冲突时弹出提示，由用户选择以哪端数据为准"。实际实现：

- `SyncWorker.doWork()`（`:28-50`）只做**单向覆盖上传**，从不下载、从不比较；
- `SyncViewModel.downloadFromCloud()` 无任何 UI 调用方（`MainViewModel:81` 是唯一转发点，界面未接入），是死代码；
- 唯一能"从云端恢复"的入口是首次启动的 `FirstLaunchSyncDialog`。

**影响**：README 描述的是"双向同步 + 冲突解决"，实际是"每 15 分钟静默覆盖云端"。双设备场景下 A 设备的后台同步会直接抹掉 B 设备刚恢复/上传的数据，且用户看不到任何提示。

---

### P1-5【账本】付款方式"只写不显"——v0.4.6 主打功能在界面上不可见

**证据**：`grep -rn "customPaymentMethod"` 命中仅限 `AppDatabase`、`DataExporter`（CSV）、`SettingsScreen`（管理列表）、`AddEditTransactionScreen`（写入）。**没有任何 UI 组件读取并展示它**。

`ui/components/CommonComponents.kt:28-105` 的 `TransactionItem`（账本、搜索、统计三处复用）只渲染分类名、备注、金额、日期。README 写明"每笔记录包含：金额、**付款方式**、日期、备注"，v0.4.6 的提交信息是"自定义收/付款方式功能"，但用户记完之后**无法看到任何一笔记录用了哪种付款方式** —— 该字段对用户而言是只写数据。

**建议**：在 `TransactionItem` 增加付款方式副标题（`customPaymentMethod ?: 标准方式本地化名`），并在统计页提供按付款方式的筛选/占比。

---

## 四、P2 中优先级

| # | 功能 | 问题 | 位置 |
|---|------|------|------|
| P2-1 | 多语言 | 默认分类名（餐饮/交通…）与预置收付款方式名以中文硬编码入库，无本地化映射；英文用户看到中文分类 | `AppDatabase.kt:140-171` |
| P2-2 | 多语言 | 英文界面下预置支付方式**重复出现两套**：`getByType()` 不过滤 `isDefault=0`，而隐藏逻辑是拿本地化字符串去比对 DB 里的中文名（英文下比对失败） | `AddEditTransactionScreen.kt:326-332`、`PaymentMethodDao.kt:23-24` |
| P2-3 | 多语言 | 语言切换不"即时生效"：用已废弃的 `resources.updateConfiguration`，无 `AppCompatDelegate.setApplicationLocales`、manifest 无 `localeConfig`；`LocaleHelper.applyLanguage` 是死代码 | `MainActivity.kt:161-167`、`LocaleHelper.kt:10-17` |
| P2-4 | 外观 | README 称"Material You 动态配色"，实际 `Theme.kt` 全是静态 `lightColorScheme/darkColorScheme`，无 `dynamicColorScheme` 调用 | `ui/theme/Theme.kt` |
| P2-5 | 账本 | 日期选择器时区错位：Material3 DatePicker 的 millis 是 UTC 语义，三处混用本地时间。UTC+8 下搜索起始日 00:00–08:00 的记录被排除；UTC-5 下结束日整体前移一天 | `AddEditTransactionScreen.kt:419-427`、`AddSavingDialog.kt:146-159`、`SearchScreen.kt:252-270` |
| P2-6 | 搜索 | `viewModelScope` 内 `collect` 常驻 Room Flow 且永不取消；`MainViewModel` 手工 `new` 三个子 ViewModel，其 `viewModelScope` 永不 `clear()`。每点一次搜索就多一个 DB 观察者，多个收集者竞争写 `_searchState` | `TransactionViewModel.kt:131-147`、`MainViewModel.kt:20-22` |
| P2-7 | 搜索 | 分类筛选 `take(5)`，分类多于 5 个时其余无法按分类检索，与"四维度模糊搜索"不符 | `SearchScreen.kt:172` |
| P2-8 | 数据管理 | CSV 往返：备注含换行时 `escapeCsv` 生成跨物理行的引号字段，而 `parseCsvLine` 按行解析 → 错行损坏；`parseTransactionsCsv:334` 对恰好 9 列的旧格式记录 `else continue` **整条静默丢弃** | `DataExporter.kt:268, 317-340, 390-440` |
| P2-9 | 安全 | 日志把财务信息写入**未加密**文件（DB 是 SQLCipher，日志不是），且"导出日志"可一键分享；`AppLogger.timeFormat` 还是跨线程共享的 `SimpleDateFormat`（非线程安全） | `AppLogger.kt:28-29, 123-133`、`TransactionViewModel.kt:89, 393` |
| P2-10 | 安全 | `android:allowBackup="true"`（明文日志会被备份）；同时声明 `READ_MEDIA_IMAGES`/`READ_EXTERNAL_STORAGE`/`WRITE_EXTERNAL_STORAGE`，而实际用 SAF 完全不需要 | `AndroidManifest.xml` |

---

## 五、P3 低优先级

| # | 问题 | 位置 |
|---|------|------|
| P3-1 | 应用锁无失败次数限制与退避；密码下限 4 位。PBKDF2 10 万次迭代下单次校验约百毫秒量级，4 位数字 PIN 在无锁定策略下可被穷举 | `LockScreen.kt`、`SetPasswordDialog.kt:123` |
| P3-2 | 删除分类不清理交易：历史记录变悬空 `categoryId`，界面显示"未知分类" | `TransactionRepository.kt:140` |
| P3-3 | 储蓄删除不触发同步，新增触发 —— 同步行为不一致 | `SavingsViewModel.kt:41-51` |
| P3-4 | `availableYears` 缓存仅在 Repository `init` 刷新一次，写入后不更新；且收集后未使用 | `TransactionRepository.kt:28-37` |
| P3-5 | 每次增删改都 enqueue 一个 OneTimeWorkRequest，无 `enqueueUniqueWork` 去重 → 连续保存触发多次上传 | `SyncWorker.kt:87-97` |
| P3-6 | MOVE 备份缺 `Overwrite: T` 头，同一天第二次同步备份失败（代码吞掉错误继续），"保留 5 份"实际退化为每天 1 份；注释说 5 份、代码 `drop(4)` 是 4 份 | `WebDavManager.kt:194-230` |
| P3-7 | `REMOTE_NOT_FOUND` 魔法字符串无调用方处理，用户会看到字面量 | `SyncViewModel.kt:67` |
| P3-8 | 4 处 OkHttp `Response` 未 close → 连接泄漏 | `WebDavManager.kt:138, 207, 259, 301` |
| P3-9 | 5 处顶层共享 `SimpleDateFormat`（非线程安全，且语言切换后 Locale 陈旧） | `CommonComponents.kt:25`、`SavingsScreen.kt:25`、`AddSavingDialog.kt:22`、`SearchScreen.kt:25`、`AppLogger.kt:28-29` |
| P3-10 | 文案错配：同步成功复用 `export_success`，同步失败复用 `connection_failed_message`；`else ->` 分支不可达 | `SettingsScreen.kt:70-71, 430-433` |
| P3-11 | `exportSchema = false` + 全手写 Migration（3→4→5→6）→ 无 schema 校验与历史版本，后续 v7 迁移风险高 | `AppDatabase.kt:36, 79-124` |
| P3-12 | 死代码三处：`DatabaseEncryption.isMigrationDone/markMigrationDone` 无调用方（"导入后重新加密"链路不存在）、`LocaleHelper.applyLanguage` 未调用、`TransactionUiState.availableYears` 未使用；`BookkeeperApp.onCreate` 用无生命周期 `CoroutineScope`，DB 初始化与 UI 查询存在竞态 | `DatabaseEncryption.kt:51-55`、`BookkeeperApp.kt:54-64` |

另：编辑记录时构造全新 `Transaction` 对象导致 `createdAt` 被重置为当前时间，`@Update` 整行覆盖 → 原始创建时间丢失（`AddEditTransactionScreen.kt:383-392`）。

---

## 六、已核实无误的部分 ✓

- 平板适配：宽 ≥ Medium 时切换 `NavigationRail`（`AppNavigation.kt:42, 99`）
- 月份前后切换与月度收支汇总（`HomeScreen.kt:93-111` + `TransactionDao` 的 `localtime` 分组）
- 储蓄余额 `SUM(CASE WHEN type='DEPOSIT' THEN amount ELSE -amount END)` 逻辑正确（`SavingDao.kt:32`）
- 导入用 `withTransaction` 包裹 + 空集合不删除的守卫，避免部分写入（`DataExporter.kt:192-210`）
- 生物识别的 Activity 生命周期等待、重复请求取消（`BiometricAuth.kt:92-127`）实现完整
- WorkManager 自定义 `Configuration.Provider` 与 manifest 去除默认初始化器配套正确
- 三语字符串资源数量一致（216 / 216 / 216），翻译无缺失
- 旧分年数据库（MetaDatabase + YearDatabase）迁移带 `.bak` 备份与失败不置标志可重试（`DatabaseManager.kt:110-177`）

---

## 七、建议修复顺序

1. **立即**：`hexToBytes` 改为逐字节解析（P0-1，一次改动消除永久锁死）
2. **立即**：导入/恢复后重启应用或正确重建 DB 实例（P0-2）
3. **本迭代**：恢复 TLS 校验，去掉 `usesCleartextTraffic`（P0-3）
4. **本迭代**：金额改整数解析（P1-2）+ 保存按钮复位（P1-1）
5. **下迭代**：对齐 README（图表/年储蓄/MD5 冲突/动态配色/即时生效），或在 README 标注"规划中"
6. **下迭代**：`TransactionItem` 展示付款方式（P1-5）；修时区、搜索收集者泄漏、CSV 往返
7. **技术债**：`exportSchema = true` + 清理死代码与共享 `SimpleDateFormat`
