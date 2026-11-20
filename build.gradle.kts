plugins {
    id("com.android.application") version "9.5.1" apply false
    // AGP 9 compiles Kotlin itself; this plugin (and the newer Kotlin it brings) adds the Compose compiler.
    id("org.jetbrains.kotlin.plugin.compose") version "2.4.21" apply false
}
