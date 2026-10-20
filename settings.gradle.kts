pluginManagement {
    repositories {
        // KSP 插件在阿里云的境外 CDN 节点上会返回 502 Bad Gateway（该构件没有同步到
        // 那个区域），而 Gradle 遇到 5xx 会直接中止整个解析、不会退到下一个仓库，
        // 于是排在后面的官方源根本没机会生效，CI 每次都红在插件解析这一步。
        // 用 exclusiveContent 把 com.google.devtools.ksp 这一组独占到官方插件门户，
        // 其他插件（AGP / Kotlin）仍走阿里云，国内本地开发速度不受影响。
        exclusiveContent {
            forRepository { gradlePluginPortal() }
            filter { includeGroup("com.google.devtools.ksp") }
        }
        maven { url = uri("https://maven.aliyun.com/repository/google") }
        maven { url = uri("https://maven.aliyun.com/repository/central") }
        maven { url = uri("https://maven.aliyun.com/repository/gradle-plugin") }
        // 官方源兜底
        gradlePluginPortal()
        mavenCentral()
        google()
    }
}
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        // 同理：KSP 的实际构件（symbol-processing-*）也从中央仓库取，绕开阿里云 502
        exclusiveContent {
            forRepository { mavenCentral() }
            filter { includeGroup("com.google.devtools.ksp") }
        }
        maven { url = uri("https://maven.aliyun.com/repository/google") }
        maven { url = uri("https://maven.aliyun.com/repository/central") }
        // 官方源兜底
        gradlePluginPortal()
        mavenCentral()
        google()
    }
}

rootProject.name = "SimpleBookkeeper"
include(":app")
