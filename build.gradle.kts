// Top-level build file for Carlink Native Android App

plugins {
    id("com.android.application") version "9.4.1" apply false
    id("org.jetbrains.kotlin.plugin.compose") version "2.4.20" apply false
    id("org.jlleitschuh.gradle.ktlint") version "14.2.0" apply false
    id("io.gitlab.arturbosch.detekt") version "1.23.8" apply false
}

// Keep build artifacts OUT of ~/Documents. This tree is iCloud "Desktop & Documents"
// synced on this Mac; iCloud drops conflict copies (`classes 2.jar`, `Foo$Bar 2.dex`) into
// build trees, which the toolchain then eats as real inputs — nondeterministic
// duplicate-class / wrong-filename failures that a `clean` only fixes until sync catches up.
// Redirecting layout.buildDirectory out of the synced tree removes the input entirely.
// Same approach as ccpa_custom/host/CarlinkAndroid. Override with -Pcarlink.buildRoot=/path
// if ~/.cache is not writable.
val buildRoot =
    (findProperty("carlink.buildRoot") as String?)
        ?: "${System.getProperty("user.home")}/.cache/gradle-builds/carlink-native"

allprojects {
    layout.buildDirectory.set(
        file("$buildRoot/${project.path.replace(':', '_').trim('_').ifEmpty { "root" }}"),
    )
}
