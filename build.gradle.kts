plugins {
    id("dev.architectury.loom")
    id("architectury-plugin")
    id("maven-publish")
}

val mc = stonecutter.current.version
val java17 = stonecutter.eval(mc, "<1.20.5") // 1.20.1 → Java 17, 1.21.1 → Java 21

// pack.mcmeta format numbers are MC-version-specific. The shipped themed packs
// (resourcepacks/*/) load as *world* data packs, so a mismatched pack_format makes
// Minecraft flag them incompatible and silently drop them — the "your own datapack
// doesn't load on 1.20.1" regression. Data: 1.20.1 → 15, 1.21.1 → 48. Resource (mod
// root, which is primarily the assets pack): 1.20.1 → 15, 1.21.1 → 34.
val dataPackFormat = if (java17) 15 else 48
val resourcePackFormat = if (java17) 15 else 34

version = "${mod.version}+$mc"
group = mod.group
base {
    archivesName.set("${mod.id}-common")
}

// Architectury 'common' module — bytecode is transformed per loader at build time.
architectury.common("fabric", "forge", "neoforge")

loom {
    silentMojangMappingsLicense()
}

repositories {
    mavenCentral()
    maven("https://maven.neoforged.net/releases/")
}

dependencies {
    minecraft("com.mojang:minecraft:$mc")
    mappings(loom.officialMojangMappings())

    // Fabric loader provides the mixin/annotation deps + @Environment annotations used
    // by common code. We do NOT use other Fabric loader classes here — those would be
    // remapped incorrectly on Forge/NeoForge.
    modImplementation("net.fabricmc:fabric-loader:${prop("fabric_loader_version")}")

    testImplementation(platform("org.junit:junit-bom:5.10.2"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

java {
    withSourcesJar()
    val javaVersion = if (java17) JavaVersion.VERSION_17 else JavaVersion.VERSION_21
    sourceCompatibility = javaVersion
    targetCompatibility = javaVersion
    toolchain {
        languageVersion = JavaLanguageVersion.of(21)
    }
}

tasks.withType<JavaCompile>().configureEach {
    options.encoding = "UTF-8"
    options.release = if (java17) 17 else 21
}

tasks.test {
    useJUnitPlatform()
}

// The mixin compatibility level must match the per-version Java level — 1.20.1's
// bundled Mixin (Java 17) rejects a JAVA_21 compatibilityLevel at load time. The
// mixin config lives in `common` so the single expanded file ships in all jars.
tasks.processResources {
    expandProps(listOf("adventureitemnames.mixins.json"),
        "mixin_compat" to if (java17) "JAVA_17" else "JAVA_21",
    )
    // Version-correct pack_format for the shipped themed data packs so they load as
    // world data packs on both 1.20.1 and 1.21.1 (see dataPackFormat above).
    expandProps(listOf("resourcepacks/*/pack.mcmeta"),
        "data_pack_format" to dataPackFormat,
    )
    // Mod's own combined pack — force-loaded, but kept version-correct (resource
    // format) and resolves the ${mod_name} placeholder it already carries.
    expandProps(listOf("pack.mcmeta"),
        "resource_pack_format" to resourcePackFormat,
        "mod_name" to mod.name,
    )
    // 1.20.1 renamed the item-tag registry directory: 1.21 reads data/<ns>/tags/item/
    // (singular), 1.20.1 reads tags/items/ (plural). The repo stores tags under the
    // 1.21 singular path, so mirror them into the plural path on the 1.20.1 build only.
    // Both dirs then ship in the 1.20.1 jar; each MC version reads the one it knows.
    if (java17) {
        // Read from the source set's resource dirs (Stonecutter feeds processResources
        // from a generated dir, not the raw src/main/resources tree).
        from(sourceSets["main"].resources.srcDirs) {
            include("data/*/tags/item/**")
            eachFile { path = path.replaceFirst("/tags/item/", "/tags/items/") }
            includeEmptyDirs = false
        }
    }
}

tasks.build {
    group = "versioned"
    description = "Must run through 'chiseledBuild'"
}

publishing {
    publications {
        create<MavenPublication>("mavenCommon") {
            artifactId = "${mod.id}-common"
            from(components["java"])
        }
    }
    repositories {
        maven { url = uri("file://${rootProject.projectDir}/repo") }
    }
}
