plugins {
    id("dev.architectury.loom")
    id("architectury-plugin")
    id("maven-publish")
}

val mc = stonecutter.current.version
val java17 = stonecutter.eval(mc, "<1.20.5") // 1.20.1 → Java 17, 1.21.1 → Java 21

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
