pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
        maven("https://jitpack.io")
    }
}

dependencyResolutionManagement {
    repositories {
        google()
        mavenCentral()
        maven("https://jitpack.io")
    }
}

rootProject.name = "mihon-extensions"

include(":src:all:danbooru")
include(":src:all:gelbooru")
include(":src:all:konachan")
include(":src:all:rule34")
include(":src:all:yandere")
