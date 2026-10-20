pluginManagement {
    repositories {
        // 优先走国内镜像：本地开发在国内速度最快
        maven { url = uri("https://maven.aliyun.com/repository/google") }
        maven { url = uri("https://maven.aliyun.com/repository/central") }
        maven { url = uri("https://maven.aliyun.com/repository/gradle-plugin") }
        // 兜底：GitHub Actions runner 在境外，到国内 CDN 的链路不稳，
        // 只靠阿里云会导致 KSP 插件解析失败、CI 直接红。
        gradlePluginPortal()
        mavenCentral()
        google()
    }
}
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        maven { url = uri("https://maven.aliyun.com/repository/google") }
        maven { url = uri("https://maven.aliyun.com/repository/central") }
        // 同样加官方兜底，CI 与本地都不会被单一镜像卡住
        google()
        mavenCentral()
    }
}

rootProject.name = "SimpleBookkeeper"
include(":app")
