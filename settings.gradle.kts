pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositories {
        google()
        mavenCentral()
    }
}

rootProject.name = "ReadersRecorder"
include(":app")

// Speech on the phone (whisper.cpp, llama.cpp, the models), shared with the sibling app as a git submodule.
include(":speech")
