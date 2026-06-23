@file:Suppress("UnstableApiUsage")

plugins {
    id("dev.architectury.loom")
    id("architectury-plugin")
    id("com.gradleup.shadow")
}

val loader = prop("loom.platform")!!
val mc = stonecutter.current.version
val common: Project = requireNotNull(stonecutter.node.sibling("")?.project) {
    "No common project for $project"
}

// Per-MC dependency coordinates.
val fabricApiVersion = when (mc) {
    "1.20.1" -> "0.92.9+1.20.1"
    else     -> "0.103.0+1.21.1"
}
// Mod Menu — Fabric-only, optional. Provides the "Config" button in the Mods list.
val modMenuVersion = when (mc) {
    "1.20.1" -> "7.2.2"
    else     -> "11.0.3"
}
val minecraftRange = when (mc) {
    "1.20.1" -> "~1.20.1"
    else     -> "~1.21.1"
}
val javaMin = if (stonecutter.eval(mc, ">=1.20.5")) "21" else "17"

version = "${common.mod.version}+$mc"
base {
    archivesName.set("${common.mod.id}-$loader")
}
architectury {
    platformSetupLoomIde()
    fabric()
}

// Accept the Mojang mappings license before officialMojangMappings() finalizes it.
loom {
    silentMojangMappingsLicense()
}

repositories {
    maven("https://maven.terraformersmc.com/") { name = "Terraformers" }
}

val commonBundle: Configuration by configurations.creating {
    isCanBeConsumed = false
    isCanBeResolved = true
}
val shadowBundle: Configuration by configurations.creating {
    isCanBeConsumed = false
    isCanBeResolved = true
}

configurations {
    compileClasspath.get().extendsFrom(commonBundle)
    runtimeClasspath.get().extendsFrom(commonBundle)
    get("developmentFabric").extendsFrom(commonBundle)
}

dependencies {
    minecraft("com.mojang:minecraft:$mc")
    mappings(loom.officialMojangMappings())
    modImplementation("net.fabricmc:fabric-loader:${prop("fabric_loader_version")}")
    modImplementation("net.fabricmc.fabric-api:fabric-api:$fabricApiVersion")

    // Mod Menu — compile-only + dev-runtime. Adds the "Config" button to the Mods list
    // in dev so we can verify the integration. NOT shaded into the release jar; players
    // without Mod Menu see no button (existing behaviour).
    modCompileOnly("com.terraformersmc:modmenu:$modMenuVersion")
    modLocalRuntime("com.terraformersmc:modmenu:$modMenuVersion")

    commonBundle(project(common.path, "namedElements")) { isTransitive = false }
    shadowBundle(project(common.path, "transformProductionFabric")) { isTransitive = false }
}

loom {
    runConfigs.all {
        isIdeConfigGenerated = true
        runDir = "../../../run/$loader"
    }
}

java {
    withSourcesJar()
    val javaVersion = if (stonecutter.eval(mc, ">=1.20.5")) JavaVersion.VERSION_21 else JavaVersion.VERSION_17
    sourceCompatibility = javaVersion
    targetCompatibility = javaVersion
    toolchain {
        languageVersion = JavaLanguageVersion.of(21)
    }
}

tasks.shadowJar {
    exclude("architectury.common.json")
    configurations = listOf(shadowBundle)
    archiveClassifier = "dev-shadow"
}

tasks.remapJar {
    injectAccessWidener = true
    input = tasks.shadowJar.get().archiveFile
    archiveClassifier = null
    dependsOn(tasks.shadowJar)
}

tasks.jar {
    archiveClassifier = "dev"
}

tasks.processResources {
    expandProps(listOf("fabric.mod.json"),
        "version" to common.mod.version,
        "fabric_loader_version" to prop("fabric_loader_version")!!,
        "minecraft_range" to minecraftRange,
        "java_min" to javaMin,
        "mod_name" to common.mod.name,
        "mod_description" to common.mod.description,
        "mod_authors" to common.mod.authors,
        "mod_license" to common.mod.license,
    )
}

tasks.build {
    group = "versioned"
    description = "Must run through 'chiseledBuild'"
}

tasks.register<Copy>("buildAndCollect") {
    group = "versioned"
    description = "Must run through 'chiseledBuild'"
    from(tasks.remapJar.get().archiveFile, tasks.remapSourcesJar.get().archiveFile)
    into(rootProject.layout.buildDirectory.dir("libs/${common.mod.version}/$loader"))
    dependsOn("build")
}
