dependencyResolutionManagement {
    repositories {
        mavenCentral()
        gradlePluginPortal()
        maven("https://maven.fabricmc.net/") { name = "Fabric" }
    }
    versionCatalogs {
        create("libs") { from(files("../gradle/libs.versions.toml")) }
        create("mc") { from(files("../gradle/minecraft.versions.toml")) }
    }
}
rootProject.name = "build-logic"
