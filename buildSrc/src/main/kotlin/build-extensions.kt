import org.gradle.api.Project
import org.gradle.kotlin.dsl.expand
import org.gradle.language.jvm.tasks.ProcessResources

/**
 * Tiny build-script helpers shared by the root (common) build and the three
 * loader builds after the Stonecutter migration.
 *
 * Adventure Item Names keeps its historical flat `gradle.properties` key names
 * (`mod_version`, `mod_id`, …) rather than the template's `mod.*` namespace —
 * the release-notes scripts and the project CLAUDE.md grep `^mod_version=`
 * straight out of `gradle.properties`, so those keys must not be renamed.
 */
fun Project.prop(key: String): String? = findProperty(key)?.toString()

fun String.upperCaseFirst() = replaceFirstChar { if (it.isLowerCase()) it.uppercaseChar() else it }

val Project.mod: ModData get() = ModData(this)

class ModData(private val project: Project) {
    val id: String get() = req("mod_id")
    val name: String get() = req("mod_name")
    val version: String get() = req("mod_version")
    val group: String get() = req("mod_group_id")
    val authors: String get() = req("mod_authors")
    val description: String get() = req("mod_description")
    val license: String get() = req("mod_license")

    private fun req(key: String): String =
        requireNotNull(project.prop(key)) { "Missing '$key' in gradle.properties" }
}

/** Expand `${...}` placeholders in the named resource files with [properties]. */
fun ProcessResources.expandProps(files: Iterable<String>, vararg properties: Pair<String, Any>) {
    for ((name, value) in properties) inputs.property(name, value)
    filesMatching(files) { expand(properties.toMap()) }
}
