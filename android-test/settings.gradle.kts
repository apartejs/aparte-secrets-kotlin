// Runs the parity test on an Android device or emulator, against the library
// built from this repository (CI only: needs the Android SDK).
//
//     ./gradlew -p android-test connectedDebugAndroidTest

pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositories {
        google()
        mavenCentral()
    }
}

rootProject.name = "android-test"

// dev.apartejs:secrets resolves to the library in the parent directory
includeBuild("..")
