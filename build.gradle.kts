import org.gradle.api.GradleException
import org.gradle.api.artifacts.ProjectDependency

// Top-level build file where you can add configuration options common to all sub-projects/modules.
plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.android.library) apply false
    alias(libs.plugins.kotlin.jvm) apply false
    alias(libs.plugins.kotlin.compose) apply false
    alias(libs.plugins.chaquopy) apply false
}

val allowedDeviceModuleDependencies = mapOf(
    "device-core" to emptySet(),
    "device-profiles" to setOf(":device-core"),
    "device-ui" to setOf(":device-core"),
)
val firstPartyPackagePrefix = "com.prfd.tinytuya."
val devicePackagePrefix = "com.prfd.tinytuya.device."

val verifyDeviceModuleBoundaries by tasks.registering {
    group = "verification"
    description = "Verifies device modules cannot depend on or import app-owned code."

    allowedDeviceModuleDependencies.keys.forEach { moduleName ->
        inputs.file("$moduleName/build.gradle.kts")
        inputs.files(fileTree("$moduleName/src") { include("**/*.kt") })
    }

    doLast {
        allowedDeviceModuleDependencies.forEach { (moduleName, allowedDependencies) ->
            val module = project(":$moduleName")
            val projectDependencies = module.configurations
                .flatMap { configuration ->
                    configuration.dependencies
                        .withType(ProjectDependency::class.java)
                        .map { dependency: ProjectDependency -> ":${dependency.name}" }
                }
                .toSet()
            // Android test configurations contain a synthetic self dependency on the tested module.
            val forbiddenDependencies = projectDependencies - allowedDependencies - module.path
            if (forbiddenDependencies.isNotEmpty()) {
                throw GradleException(
                    ":$moduleName has forbidden project dependencies: " +
                        forbiddenDependencies.sorted().joinToString(),
                )
            }

            val forbiddenImports = fileTree("$moduleName/src") { include("**/*.kt") }
                .files
                .sortedBy { source -> source.path }
                .flatMap { source ->
                    source.readLines().mapIndexedNotNull { index, line ->
                        val importedName = line.trim()
                            .takeIf { candidate -> candidate.startsWith("import ") }
                            ?.removePrefix("import ")
                            ?.substringBefore(" as ")
                            ?: return@mapIndexedNotNull null
                        if (
                            importedName.startsWith(firstPartyPackagePrefix) &&
                            !importedName.startsWith(devicePackagePrefix)
                        ) {
                            "${source.relativeTo(rootDir).invariantSeparatorsPath}:${index + 1} " +
                                importedName
                        } else {
                            null
                        }
                    }
                }
            if (forbiddenImports.isNotEmpty()) {
                throw GradleException(
                    ":$moduleName imports app-owned code:\n" + forbiddenImports.joinToString("\n"),
                )
            }
        }
    }
}

subprojects {
    if (name in allowedDeviceModuleDependencies) {
        tasks.matching { task ->
            task.name == "check" ||
                (task.name.startsWith("compile") && task.name.endsWith("Kotlin"))
        }.configureEach {
            dependsOn(rootProject.tasks.named("verifyDeviceModuleBoundaries"))
        }
    }
}

tasks.register("ktfmtFormatAll") {
    dependsOn(
        subprojects.mapNotNull { project ->
            project.tasks.findByName("ktfmtFormat")
        }
    )
}
