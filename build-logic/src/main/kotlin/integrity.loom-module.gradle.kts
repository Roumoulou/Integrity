import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.tasks.KotlinJvmCompile

plugins {
    id("net.fabricmc.fabric-loom")
    id("org.jetbrains.kotlin.jvm")
    id("org.jetbrains.kotlin.plugin.serialization")
}

val libs = the<VersionCatalogsExtension>().named("libs")
group = providers.gradleProperty("maven_group").get()
version = providers.gradleProperty("mod_version").get()
base { archivesName = providers.gradleProperty("archives_base_name") }

// Loom pose ses dépôts dans chaque module ; Repsy doit donc être déclaré ici aussi.
repositories {
    maven("https://repo.repsy.io/roumoulou/maven") {
        name = "Repsy"
        content { includeGroup("fr.moulou") }
    }
}

val javaVersion = libs.findVersion("java").get().requiredVersion.toInt()
java { toolchain.languageVersion = JavaLanguageVersion.of(javaVersion); withSourcesJar() }
loom { splitEnvironmentSourceSets() }
tasks.withType<JavaCompile>().configureEach { options.release = javaVersion; options.encoding = "UTF-8" }
tasks.withType<KotlinJvmCompile>().configureEach { compilerOptions.jvmTarget = JvmTarget.fromTarget(javaVersion.toString()) }

// Les classes communes et celles du module seront réunies dans le même JAR.
val modulePrefix = "integrity-$name"
kotlin {
    target.compilations.configureEach {
        val compilationName = if (name == "main") modulePrefix else "$modulePrefix-$name"
        compileTaskProvider.configure { (this as KotlinJvmCompile).compilerOptions.moduleName = compilationName }
    }
}
