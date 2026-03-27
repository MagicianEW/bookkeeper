# 开发规范

## 版本号管理

修改版本号时，需要同步修改以下位置：

1. **`app/build.gradle.kts`**
   - `versionCode`: 数字递增（如 210 → 211）
   - `versionName`: 语义化版本（如 "0.2.10" → "0.2.11"）

2. **`app/src/main/java/com/simplebookkeeper/ui/screens/SettingsScreen.kt`**
   - About 对话框中的版本号显示（第 778 行左右）
   - 格式：`"v x.x.xx"`（如 `"v 0.2.11"`）

## 构建命令

```bash
# Debug 构建
./gradlew :app:assembleDebug

# Release 构建
./gradlew :app:assembleRelease
```

## 发布流程

1. 修改版本号（两处）
2. 构建 APK
3. 提交代码并推送
4. 创建/更新 GitHub Release
5. 上传 APK 文件
