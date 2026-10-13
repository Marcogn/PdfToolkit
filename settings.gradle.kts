pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
        // Clipper2-java (polygon clipping for the partial eraser, plan T-a, ADR 0004) is published only on
        // JitPack; the filter keeps every other dependency off it.
        exclusiveContent {
            forRepository { maven("https://jitpack.io") }
            filter { includeGroup("com.github.micycle1") }
        }
    }
}

rootProject.name = "PdfToolkit"
include(":app")
