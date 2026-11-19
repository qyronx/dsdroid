pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
        // 위 저장소가 일시적으로 실패할 때를 대비한 Maven Central 미러(구글 호스팅)
        maven { url = uri("https://maven-central.storage-download.googleapis.com/maven2/") }
    }
}
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
        maven { url = uri("https://maven-central.storage-download.googleapis.com/maven2/") }
        maven { url = uri("https://jitpack.io") }
    }
}
rootProject.name = "DSDroid"
include(":app")
