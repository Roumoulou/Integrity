pluginManagement {
    repositories {
        mavenCentral()
        maven("https://maven.fabricmc.net/") { name = "Fabric" }
        gradlePluginPortal()
        maven("https://repo.repsy.io/roumoulou/maven") { name = "Repsy" }
    }
    includeBuild("build-logic")
    val source = providers.gradleProperty("outfitter_source").getOrElse("repsy")
    require(source in listOf("repsy", "composite")) { "outfitter_source must be repsy or composite" }
    if (source == "composite") includeBuild("../../../Outfitter/main-project/Outfitter")
}

dependencyResolutionManagement {
    versionCatalogs {
        create("mc") { from(files("gradle/minecraft.versions.toml")) }
        create("mc261") { from(files("gradle/mc-26.1.versions.toml")) }
        create("mc262") { from(files("gradle/mc-26.2.versions.toml")) }
        create("mc263") { from(files("gradle/mc-26.3.versions.toml")) }
    }
}

rootProject.name = "Integrity"
include("common", "mc-26.1", "mc-26.2", "mc-26.3")
