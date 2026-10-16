package fr.moulou.integrity.buildlogic

import org.gradle.api.Project
import org.gradle.api.file.Directory
import org.gradle.api.tasks.testing.Test
import org.gradle.kotlin.dsl.named
import org.gradle.kotlin.dsl.register

/** Prépare deux couples client/serveur Loom ; les déploiements de JAR restent des tâches distinctes. */
fun Project.configureDevTesting(minecraftVersion: String, publication: Directory) {
    val line = minecraftVersion.substringBeforeLast('.').takeIf { minecraftVersion.count { it == '.' } > 1 } ?: minecraftVersion
    val modpackVersion = providers.gradleProperty("mdtk_version").get()
    val preserved = providers.gradleProperty("integrity.dev.preserveClientChanges").map { it.toBooleanStrict() }.orElse(false)
    tasks.matching { it.name.startsWith("syncClientModded") }.configureEach {
        onlyIf("la synchronisation du client est suspendue pendant un essai de refus") { !preserved.get() }
    }
    listOf("Client", "Server").forEach { side ->
        val source = if (side == "Client") publication.dir("MDTK-CORE-CLIENT_$modpackVersion+$minecraftVersion/minecraft")
            else publication.dir("MDTK-CORE-SERVER_$modpackVersion+$minecraftVersion/server")
        val content = tasks.register<PrepareMdtkDevContent>("prepare${side}MdtkDevContent") {
            group = "outfitter"
            description = "Pose dans le run $side moddé les packs et réglages de la publication MDTK, sans lancer le jeu"
            dependsOn("sync${side}ModdedProfile", "sync${side}ModdedWorlds", "sync${side}ModdedMods")
            this.publication.set(source)
            directory.set(layout.projectDirectory.dir("run/${side.lowercase()}-modded"))
            if (side == "Client") onlyIf("le client conserve ses écarts volontaires") { !preserved.get() }
        }
        // Les tâches de contenu Outfitter constatent les marqueurs de la publication après sa copie, sans appeler PackTool.
        tasks.matching { it.name in listOf("sync${side}ModdedPacks", "sync${side}ModdedDatapacks", "sync${side}ModdedSettings") }.configureEach { dependsOn(content) }
        tasks.named("prepare${side}Modded") { dependsOn(content) }
    }
    listOf(false, true).forEach { modded ->
        val suffix = if (modded) "-modded" else ""
        val taskSuffix = if (modded) "Modded" else ""
        tasks.register<PrepareDevReference>(if (modded) "prepareMdtkDevTests" else "prepareDevTests") {
            group = "outfitter"
            description = "Prépare le couple Loom ${if (modded) "MDTK" else "vanilla"} et sa première copie client approuvée"
            dependsOn("prepareClient$taskSuffix", "prepareServer$taskSuffix")
            client.set(layout.projectDirectory.dir("run/client$suffix"))
            server.set(layout.projectDirectory.dir("run/server$suffix"))
            reference.set(layout.projectDirectory.dir("run/server$suffix/integrity/reference-client/${if (modded) "MDTK-DEV" else "Integrity-DEV"}-$minecraftVersion/.minecraft"))
            this.minecraftVersion.set(minecraftVersion)
            modpackId.set(if (modded) "mdtk" else "integrity-dev")
            this.modpackVersion.set(if (modded) modpackVersion else providers.gradleProperty("mod_version").get())
            port.set((if (modded) 25620 else 25560) + line.substringAfter('.').toInt())
            onlyIf("la copie approuvée ne se prépare pas depuis un client volontairement modifié") { !preserved.get() }
        }
    }
    if (minecraftVersion in listOf("26.2", "26.3")) {
        val distribution = tasks.named<Test>("testDistribution")
        val releaseJar = layout.buildDirectory.file("libs/integrity-${providers.gradleProperty("mod_version").get()}+$minecraftVersion.jar")
        tasks.register<Test>("testMdtkDev") {
            group = "verification"
            description = "Vérifie les inventaires MDTK du couple Loom et les refus sur des copies temporaires, sans client graphique"
            dependsOn("prepareMdtkDevTests", distribution, ":downloadMdtkFixtures")
            testClassesDirs = distribution.get().testClassesDirs
            classpath = distribution.get().classpath
            useJUnitPlatform()
            filter.includeTestsMatching("fr.moulou.integrity.MdtkDistributionTest")
            outputs.upToDateWhen { false }
            outputs.doNotCacheIf("Loom development instances contain live deliberate changes") { true }
            systemProperty("integrity.expectedMinecraft", minecraftVersion)
            systemProperty("integrity.distributionJar", releaseJar.get().asFile.absolutePath)
            systemProperty("integrity.distributionMapping", layout.buildDirectory.file("proguard/mapping.txt").get().asFile.absolutePath)
            systemProperty("integrity.mdtk.development", "true")
            systemProperty("integrity.mdtk.client", layout.projectDirectory.dir("run/client-modded").asFile.absolutePath)
            systemProperty("integrity.mdtk.reference", layout.projectDirectory.dir("run/server-modded/integrity/reference-client/MDTK-DEV-$minecraftVersion/.minecraft").asFile.absolutePath)
            systemProperty("integrity.mdtk.fixtures", layout.settingsDirectory.dir("../../02-data/00-test/$minecraftVersion").asFile.absolutePath)
            workingDir = layout.buildDirectory.dir("run/testMdtkDev").get().asFile
            doFirst { workingDir.mkdirs() }
            testLogging { events("passed", "failed"); showStandardStreams = true }
        }
    }
}
