// Top-level build file where you can add configuration options common to all sub-projects/modules.
plugins {
    alias(libs.plugins.android.application) apply false
}

// AGP 9.1.0 bundles Kotlin 2.2.x as its built-in toolchain, but
// litertlm-android 0.18.0 (the memory bank's on-device embedder) is compiled
// against Kotlin 2.4 metadata, which the 2.2 compiler cannot read. Pinning
// kotlin-gradle-plugin 2.4.0 here puts it on the buildscript classpath with an
// explicit version, so :app can apply org.jetbrains.kotlin.android without a
// version and Gradle's newest-wins resolution overrides AGP's transitive
// 2.2.10. See gradle.properties (android.builtInKotlin=false).
buildscript {
    dependencies {
        classpath("org.jetbrains.kotlin:kotlin-gradle-plugin:2.4.0")
    }
}
