pluginManagement {
    repositories {
        maven("https://maven.fabricmc.net/")
        maven("https://maven.architectury.dev/")
        maven("https://maven.minecraftforge.net/")
        maven("https://maven.neoforged.net/releases/")
        maven("https://maven.kikugie.dev/releases")
        maven("https://maven.kikugie.dev/snapshots")
        gradlePluginPortal()
    }
}

plugins {
    id("dev.kikugie.stonecutter") version "0.9.6"
    // Auto-provisions JDKs for the per-version toolchains (17/21/25 — MC 26.x needs Java 25).
    id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
}

stonecutter {
    centralScript = "build.gradle.kts"
    kotlinController = true
    create(rootProject) {
        // Root `src/` is the Architectury 'common' module. Each loader is a branch.
        versions("1.20.1", "1.21.1", "26.2")
        vcsVersion = "1.21.1"
        branch("fabric")  // all three (inherits the root version list)
        // Forge dropped off the 26.x line → Forge targets 1.20.1 + 1.21.1 only.
        branch("forge") { versions("1.20.1", "1.21.1") }
        // NeoForge only published a standalone `net.neoforged:neoforge` artifact from
        // 1.20.2 onward — for 1.20.1 it was a Forge fork (binary-compatible with Forge
        // 1.20.1), so the Forge 1.20.1 jar covers that. NeoForge targets 1.21.1 + 26.2.
        branch("neoforge") { versions("1.21.1", "26.2") }
    }
}

rootProject.name = "adventureitemnames-mc"
