plugins {
    id("dev.kikugie.stonecutter")
    // Load the loader plugins ONCE on the root buildscript classloader (apply false)
    // so every Stonecutter version node shares a single Loom classloader. Applying
    // Loom per-node instead triggers a LoomGradleExtension cross-classloader
    // ClassCastException. Mirrors the classic Architectury `apply false` at root.
    // Loom 1.17 / architectury-plugin 3.5 are required for MC 26.x support.
    id("dev.architectury.loom") version "1.17-SNAPSHOT" apply false
    // Deobfuscated MC 26.x uses the no-remap Loom variant (same jar, no mappings step).
    // Each node applies whichever matches its version — see the loader/common builds.
    id("dev.architectury.loom-no-remap") version "1.17-SNAPSHOT" apply false
    id("architectury-plugin") version "3.5-SNAPSHOT" apply false
    id("com.gradleup.shadow") version "8.3.5" apply false
}

// The version checked out in the working tree. `chiseledBuild` builds every node
// regardless; this is just the one active for plain `./gradlew build`, dev clients,
// and IDE runs. Stonecutter rewrites this line on `./gradlew "Set active <ver>"`.
stonecutter active "1.21.1" /* [SC] DO NOT EDIT */

// Stonecutter 0.9 materialises every (loader, version) as a real subproject
// (e.g. :fabric:1.21.1) with its own preprocessed source + `buildAndCollect` task,
// so the chiseled aggregate tasks are plain dependsOn fan-outs over those nodes.

// Builds + collects every loader/version jar into build/libs/{mod.version}/{loader}.
tasks.register("chiseledBuild") {
    group = "project"
    description = "Build + collect every loader/version production jar."
    for (node in stonecutter.tree.nodes) {
        if (node.branch.id.isEmpty()) continue // skip the common (root) nodes
        dependsOn("${node.project.path}:buildAndCollect")
    }
}

// Per-loader aggregate: chiseledBuildFabric / chiseledBuildForge / chiseledBuildNeoforge.
for (branch in stonecutter.tree.branches) {
    if (branch.id.isEmpty()) continue
    val loader = branch.id.upperCaseFirst()
    tasks.register("chiseledBuild$loader") {
        group = "project"
        description = "Build + collect every $loader version production jar."
        for (node in stonecutter.tree.nodes) {
            if (node.branch.id != branch.id) continue
            dependsOn("${node.project.path}:buildAndCollect")
        }
    }
}

// runActiveClientFabric / runActiveClientNeoForge / … — dev client/server for the
// active version of each loader.
for (node in stonecutter.tree.nodes) {
    if (node.metadata != stonecutter.current || node.branch.id.isEmpty()) continue
    val loader = node.branch.id.upperCaseFirst()
    for (type in listOf("Client", "Server")) {
        node.project.tasks.register("runActive$type$loader") {
            group = "project"
            dependsOn("run$type")
        }
    }
}
