// An app on Kotlin 2.0, the oldest compiler the library supports, that depends on
// the library published to Maven Local (CI only):
//
//     ./gradlew publishToMavenLocal && ./gradlew -p compat-test run

pluginManagement {
    repositories {
        gradlePluginPortal()
        mavenCentral()
    }
}

dependencyResolutionManagement {
    repositories {
        mavenLocal()
        mavenCentral()
    }
}

rootProject.name = "compat-test"
