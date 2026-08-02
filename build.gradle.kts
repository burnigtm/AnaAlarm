import org.gradle.api.artifacts.dsl.LockMode

plugins {
    id("com.android.application") version "8.9.1" apply false
    id("org.jetbrains.kotlin.android") version "2.1.0" apply false
    id("org.jetbrains.kotlin.plugin.compose") version "2.1.0" apply false
    id("org.jetbrains.kotlin.plugin.serialization") version "2.1.0" apply false
    id("com.google.devtools.ksp") version "2.1.0-1.0.29" apply false
}

allprojects {
    dependencyLocking {
        lockAllConfigurations()
        lockMode.set(LockMode.STRICT)
    }
}

/** Maintenance task used to create a complete lock baseline without running compilers. */
tasks.register("resolveAndLockAll") {
    notCompatibleWithConfigurationCache("Resolves every project configuration by design")
    doFirst {
        require(gradle.startParameter.isWriteDependencyLocks) {
            "Run resolveAndLockAll with --write-locks"
        }
    }
    doLast {
        allprojects.forEach { project ->
            project.configurations
                .filter { it.isCanBeResolved }
                // Resolve the dependency graph, not every Android artifact view. Some AGP
                // configurations intentionally require task-supplied artifact attributes.
                .forEach { it.incoming.resolutionResult.allComponents }
        }
    }
}
