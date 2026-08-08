pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
    plugins {
        id("com.android.application") version "8.2.0"
        id("com.android.library") version "8.2.0"
        id("org.jetbrains.kotlin.android") version "1.9.20"
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
        maven { url = uri("https://jitpack.io") }
    }
}

rootProject.name = "USBTCPBridge"

include(":USBTCPBridge")
include(":usbSerialForAndroid")

project(":usbSerialForAndroid").projectDir = file("usb-serial-for-android/usbSerialForAndroid")
