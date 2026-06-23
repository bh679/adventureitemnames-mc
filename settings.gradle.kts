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
}

stonecutter {
    centralScript = "build.gradle.kts"
    kotlinController = true
    create(rootProject) {
        // Root `src/` is the Architectury 'common' module. Each loader is a branch.
        versions("1.20.1", "1.21.1")
        vcsVersion = "1.21.1"
        branch("fabric")  // both versions
        branch("forge")   // both versions (47.x / 52.x)
        // NeoForge only published a standalone `net.neoforged:neoforge` artifact from
        // 1.20.2 onward — for 1.20.1 it was a Forge fork (`net.neoforged:forge`,
        // binary-compatible with Forge 1.20.1). So NeoForge targets 1.21.1 only; the
        // Forge 1.20.1 jar covers the (rare) NeoForge-1.20.1 user.
        branch("neoforge") { versions("1.21.1") }
    }
}

rootProject.name = "adventureitemnames-mc"
