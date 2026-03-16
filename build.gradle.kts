import com.android.build.api.dsl.ApplicationExtension
import com.android.build.api.dsl.LibraryExtension

plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.android.library) apply false
    alias(libs.plugins.kotlin.android) apply false
    alias(libs.plugins.kotlin.compose) apply false
    id("com.google.dagger.hilt.android") version "2.52" apply false
}

val lintFatalChecks = setOf("HardcodedText", "StopShip", "NewApi")
val duplicateMetaInfExcludes = setOf(
    "META-INF/DEPENDENCIES",
    "META-INF/LICENSE.md",
    "META-INF/LICENSE-notice.md",
    "META-INF/NOTICE.md",
    "META-INF/NOTICE-notice.md",
)
val writeLintBaseline = providers
    .gradleProperty("writeBaseline")
    .map { value -> value.equals("true", ignoreCase = true) }
    .orElse(false)
val requestedTasks = gradle.startParameter.taskNames

fun shouldRewriteBaselineFor(projectPath: String): Boolean {
    if (!writeLintBaseline.get()) return false
    if (requestedTasks.isEmpty()) return false
    return requestedTasks.any { task ->
        task == projectPath || task.startsWith("$projectPath:")
    }
}

subprojects {
    plugins.withId("com.android.application") {
        val baselineFile = layout.projectDirectory.file("lint-baseline.xml").asFile

        extensions.configure<ApplicationExtension>("android") {
            packaging {
                resources {
                    excludes += duplicateMetaInfExcludes
                }
            }
            lint {
                baseline = baselineFile
                fatal += lintFatalChecks
            }
        }

        // Rewrite only when lint task is explicitly scoped to this module path.
        if (shouldRewriteBaselineFor(path)) {
            baselineFile.delete()
        }
    }

    plugins.withId("com.android.library") {
        val baselineFile = layout.projectDirectory.file("lint-baseline.xml").asFile

        extensions.configure<LibraryExtension>("android") {
            packaging {
                resources {
                    excludes += duplicateMetaInfExcludes
                }
            }
            lint {
                baseline = baselineFile
                fatal += lintFatalChecks
            }
        }

        // Rewrite only when lint task is explicitly scoped to this module path.
        if (shouldRewriteBaselineFor(path)) {
            baselineFile.delete()
        }
    }
}
