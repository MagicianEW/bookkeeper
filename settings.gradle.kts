// 仓库源策略
//
// 背景：阿里云镜像的境外 CDN 节点上有部分构件没同步，对这些路径会返回
// 502 Bad Gateway。而 Gradle 遇到 5xx 会直接中止整条解析链、不会退到下一个
// 仓库 —— 于是表现为「本地能构建、CI 必红」，且失败点会随构件不同而漂移
// （先后踩过 KSP 插件、io.netty、com.google.j2objc）。
//
// 策略：
// - CI（GitHub Actions runner 在境外）：只用官方源，不碰阿里云，彻底避开 502。
// - 本地（国内）：仍优先走阿里云镜像保证速度，官方源兜底。
//
// 遇到本地镜像抽风时，可用 -PofficialRepos=true 强制切到官方源。
//
// 注意：pluginManagement {} 会被 Gradle 单独编译，看不到脚本顶层的声明，
// 所以这里的判断条件在两个块里各写一份。

pluginManagement {
    repositories {
        val officialOnly = System.getenv("CI") == "true" ||
            providers.gradleProperty("officialRepos").orNull == "true"
        if (!officialOnly) {
            maven { url = uri("https://maven.aliyun.com/repository/google") }
            maven { url = uri("https://maven.aliyun.com/repository/central") }
            maven { url = uri("https://maven.aliyun.com/repository/gradle-plugin") }
        }
        // 官方源
        gradlePluginPortal()
        mavenCentral()
        google()
    }
}
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        val officialOnly = System.getenv("CI") == "true" ||
            providers.gradleProperty("officialRepos").orNull == "true"
        if (!officialOnly) {
            maven { url = uri("https://maven.aliyun.com/repository/google") }
            maven { url = uri("https://maven.aliyun.com/repository/central") }
        }
        // 官方源
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

rootProject.name = "SimpleBookkeeper"
include(":app")
