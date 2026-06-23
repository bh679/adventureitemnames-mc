@file:Suppress("UnstableApiUsage")

import net.fabricmc.loom.api.LoomGradleExtensionAPI
import net.fabricmc.loom.task.RemapJarTask
import net.fabricmc.loom.task.RemapSourcesJarTask

plugins {
    id("architectury-plugin")
    id("com.gradleup.shadow")
}

val loader = prop("loom.platform")!!
val mc = stonecutter.current.version
val obfuscated = stonecutter.eval(mc, "<26")
// Deobfuscated MC 26.x uses the no-remap Loom variant (same jar, no mappings step). The Loom
// plugin is applied via apply() because the plugins{} block can't branch on the version — so
// Loom-specific calls below use typed APIs (configure<LoomGradleExtensionAPI>/the<>/"minecraft"/
// "mappings"/named<RemapJarTask>) since apply() doesn't generate the Kotlin DSL accessors.
// architectury-plugin stays in plugins{} so its `architectury { }` + `tasks.shadowJar` accessors work.
apply(plugin = if (obfuscated) "dev.architectury.loom" else "dev.architectury.loom-no-remap")
// Mod-dep config name: obfuscated Loom creates modImplementation; the no-remap variant
// (deobfuscated 26.x) doesn't — mods need no remap there, so use plain implementation.
val modImpl = if (obfuscated) "modImplementation" else "implementation"
val common: Project = requireNotNull(stonecutter.node.sibling("")?.project) {
    "No common project for $project"
}

// Per-MC dependency coordinates.
val fabricApiVersion = when (mc) {
    "1.20.1" -> "0.92.9+1.20.1"
    "26.2"   -> "0.153.0+26.2"
    else     -> "0.103.0+1.21.1"
}
// Fabric loader: 26.2's Fabric API requires a newer loader than the 1.20.1/1.21.1 baseline.
val fabricLoaderVersion = when (mc) {
    "26.2" -> "0.19.3"
    else   -> prop("fabric_loader_version")!!
}
// Mod Menu (Fabric-only, optional dev "Config" button) — only the obfuscated 1.20.1/1.21.1
// nodes; no verified 26.2 coordinate, and it's compile-only dev convenience that's never
// shipped. The modmenu provider class is version-guarded out of the 26.2 build.
val modMenuVersion = when (mc) {
    "1.20.1" -> "7.2.2"
    else     -> "11.0.3"
}
val minecraftRange = when (mc) {
    "1.20.1" -> "~1.20.1"
    "26.2"   -> "~26.2"
    else     -> "~1.21.1"
}
val javaLevel = when {
    stonecutter.eval(mc, ">=26")     -> 25
    stonecutter.eval(mc, ">=1.20.5") -> 21
    else                             -> 17
}
val javaMin = javaLevel.toString()

version = "${common.mod.version}+$mc"
base {
    archivesName.set("${common.mod.id}-$loader")
}
architectury {
    platformSetupLoomIde()
    fabric()
}

// MC 26.x renamed several classes as pure renames — rewrite the tokens globally on the 26.x
// node (see the root build for rationale). Applies to this loader's own source (the entry class).
stonecutter.replacements {
    string(!obfuscated) {
        replace("ResourceLocation", "Identifier")
        replace("MobSpawnType", "EntitySpawnReason")
    }
}

// MC 26.x ships deobfuscated — no Mojang mappings/license there. Obfuscated 1.20.1/1.21.1 need them.
if (obfuscated) {
    configure<LoomGradleExtensionAPI> {
        silentMojangMappingsLicense()
    }
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
    "minecraft"("com.mojang:minecraft:$mc")
    if (obfuscated) {
        // project.the<>() — inside dependencies{} the implicit ExtensionAware receiver is the
        // DependencyHandler (whose only extension is ext), so qualify to the Project to reach Loom.
        "mappings"(project.the<LoomGradleExtensionAPI>().officialMojangMappings())
    }
    modImpl("net.fabricmc:fabric-loader:$fabricLoaderVersion")
    modImpl("net.fabricmc.fabric-api:fabric-api:$fabricApiVersion")

    // Mod Menu — compile-only + dev-runtime (obfuscated nodes only; see modMenuVersion note).
    // NOT shaded into the release jar; players without Mod Menu see no button.
    if (obfuscated) {
        "modCompileOnly"("com.terraformersmc:modmenu:$modMenuVersion")
        "modLocalRuntime"("com.terraformersmc:modmenu:$modMenuVersion")
    }

    // `namedElements` is Loom's remap-namespace classpath variant — it only exists in the
    // obfuscated build. Under no-remap (deobfuscated 26.x) there's no remap namespace, so we
    // compile against the common project's default variant instead. `transformProduction*`
    // (the architectury common→loader bytecode transform) exists in both modes.
    if (obfuscated) {
        commonBundle(project(common.path, "namedElements")) { isTransitive = false }
    } else {
        commonBundle(project(common.path)) { isTransitive = false }
    }
    shadowBundle(project(common.path, "transformProductionFabric")) { isTransitive = false }
}

configure<LoomGradleExtensionAPI> {
    runConfigs.all {
        isIdeConfigGenerated = true
        runDir = "../../../run/$loader"
    }
}

java {
    withSourcesJar()
    val compat = JavaVersion.toVersion(javaLevel)
    sourceCompatibility = compat
    targetCompatibility = compat
    toolchain {
        languageVersion = JavaLanguageVersion.of(if (javaLevel >= 25) 25 else 21)
    }
}

tasks.shadowJar {
    exclude("architectury.common.json")
    configurations = listOf(shadowBundle)
    archiveClassifier = "dev-shadow"
}

// Obfuscated Loom remaps the shadowJar into the production jar (no classifier). The no-remap
// variant (deobfuscated 26.x) registers no remapJar/remapSourcesJar — the shadowJar IS the
// production artifact there, so it takes the empty classifier directly.
if (obfuscated) {
    tasks.named<RemapJarTask>("remapJar") {
        injectAccessWidener.set(true)
        inputFile.set(tasks.shadowJar.flatMap { it.archiveFile })
        archiveClassifier.set(null as String?)
        dependsOn(tasks.shadowJar)
    }
    tasks.jar {
        archiveClassifier = "dev"
    }
} else {
    tasks.shadowJar {
        archiveClassifier = null as String?
    }
}

tasks.processResources {
    expandProps(listOf("fabric.mod.json"),
        "version" to common.mod.version,
        "fabric_loader_version" to fabricLoaderVersion,
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
    // Obfuscated: collect the remapped jar + remapped sources. No-remap (26.x): those tasks
    // don't exist — collect the shadowJar (production artifact) + the plain sources jar.
    if (obfuscated) {
        from(
            tasks.named<RemapJarTask>("remapJar").flatMap { it.archiveFile },
            tasks.named<RemapSourcesJarTask>("remapSourcesJar").flatMap { it.archiveFile },
        )
    } else {
        from(
            tasks.shadowJar.flatMap { it.archiveFile },
            tasks.named<Jar>("sourcesJar").flatMap { it.archiveFile },
        )
    }
    into(rootProject.layout.buildDirectory.dir("libs/${common.mod.version}/$loader"))
    dependsOn("build")
}
