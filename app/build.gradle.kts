plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
    id("com.google.devtools.ksp")
}

android {
    namespace = "com.simplebookkeeper"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.simplebookkeeper"
        minSdk = 31
        targetSdk = 35
        versionCode = 406
        versionName = "v0.4.6"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    signingConfigs {
        create("projectDebug") {
            storeFile = file("../debug.keystore")
            storePassword = "bookkeeper"
            keyAlias = "bookkeeper"
            keyPassword = "bookkeeper"
        }
    }

    buildTypes {
        debug {
            signingConfig = signingConfigs.getByName("projectDebug")
        }
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            signingConfig = null
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    lint {
        error += "RemoveWorkManagerInitializer"
    }

    // Room schema 导出目录：配合 @Database(exportSchema = true)
    ksp {
        arg("room.schemaLocation", "$projectDir/schemas")
    }

    testOptions {
        unitTests {
            // AppLogger 会调用 android.util.Log，纯 JVM 单元测试里没有实现。
            // 开启后未 mock 的 android 方法返回默认值而不是抛异常，
            // 这样 DataExporter 的跳过分支日志不会让测试假失败。
            isReturnDefaultValues = true
        }
    }

    packaging {
        jniLibs {
            useLegacyPackaging = true
        }
    }
}

dependencies {
    // Core
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.3")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.3")
    implementation("androidx.lifecycle:lifecycle-process:2.8.3")
    implementation("androidx.activity:activity-compose:1.9.1")

    // Compose BOM
    val composeBom = platform("androidx.compose:compose-bom:2024.06.00")
    implementation(composeBom)
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.compose.material3:material3-window-size-class")
    debugImplementation("androidx.compose.ui:ui-tooling")

    // Navigation
    implementation("androidx.navigation:navigation-compose:2.7.7")

    // Room
    implementation("androidx.room:room-runtime:2.7.0")
    implementation("androidx.room:room-ktx:2.7.0")
    ksp("androidx.room:room-compiler:2.7.0")

    // SQLCipher (database encryption)
    implementation("net.zetetic:android-database-sqlcipher:4.5.4")
    implementation("androidx.sqlite:sqlite-ktx:2.4.0")

    // Security
    implementation("androidx.security:security-crypto:1.1.0-alpha06")
    implementation("androidx.biometric:biometric:1.1.0")

    // WorkManager
    implementation("androidx.work:work-runtime-ktx:2.9.0")

    // DataStore
    implementation("androidx.datastore:datastore-preferences:1.1.1")

    // OkHttp (WebDAV)
    implementation("com.squareup.okhttp3:okhttp:4.12.0")

    // Coroutines
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")

    // Zip4j (encrypted ZIP with AES-256)
    implementation("net.lingala.zip4j:zip4j:2.11.5")

    // Testing
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.mockito:mockito-core:5.11.0")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.8.1")
    testImplementation("androidx.arch.core:core-testing:2.2.0")
    testImplementation("androidx.lifecycle:lifecycle-viewmodel-ktx:2.8.3")
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
    androidTestImplementation("androidx.test.espresso:espresso-core:3.6.1")
}

// 在构建时自动替换 README 中的 {{VERSION}} 占位符为实际版本号
// 只需在 build.gradle.kts 中修改 versionName 一处
val projectRoot = rootProject.projectDir.absolutePath
tasks.register("replaceReadmeVersion") {
    group = "version"
    description = "Replace {{VERSION}} placeholder in README files with the actual versionName from build.gradle.kts"
    doLast {
        val versionName = android.defaultConfig.versionName!!
        listOf(
            "$projectRoot/README.md",
            "$projectRoot/README.zh-TW.md",
            "$projectRoot/README.en.md"
        ).forEach { filePath ->
            val readmeFile = file(filePath)
            if (readmeFile.exists()) {
                val content = readmeFile.readText()
                if (content.contains("{{VERSION}}")) {
                    readmeFile.writeText(content.replace("{{VERSION}}", versionName))
                    println("[replaceReadmeVersion] Updated $filePath → $versionName")
                }
            }
        }
    }
}

// afterEvaluate 后再依赖，确保 Android 插件已完成 assembleDebug 任务注册
afterEvaluate {
    tasks.named("assembleDebug") {
        dependsOn("replaceReadmeVersion")
    }
    tasks.named("assembleRelease") {
        dependsOn("replaceReadmeVersion")
    }
}
