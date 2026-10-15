package fr.moulou.integrity.buildlogic

import org.gradle.api.Project
import org.gradle.api.file.Directory
import org.gradle.api.tasks.testing.Test
import org.gradle.kotlin.dsl.named
import org.gradle.kotlin.dsl.register
import java.io.StringReader
import java.util.Properties

/** Dossier d'une publication MDTK, depuis la racine locale déclarée ; aucune lecture du modpack n'est nécessaire au build ordinaire. */
fun Project.mdtkPublication(minecraftVersion: String): Directory {
    val local = Properties().apply { load(StringReader(providers.fileContents(layout.settingsDirectory.file("machine.properties")).asText.orElse("").get())) }
    val root = local.getProperty("integrity.mdtk.releases_dir")?.takeIf { it.isNotBlank() }
        ?: providers.gradleProperty("integrity.mdtk.releases_dir").getOrElse("build/mdtk-not-configured")
    val modpackVersion = providers.gradleProperty("mdtk_version").get()
    return layout.projectDirectory.dir("$root/$minecraftVersion/MDTK-$modpackVersion+$minecraftVersion")
}

/** Branche la préparation des copies, le déploiement Outfitter du vrai JAR et une validation sans client graphique sur le modpack publié. */
fun Project.configureMdtkTesting(minecraftVersion: String, publication: Directory, instances: Directory) {
    val modpackVersion = providers.gradleProperty("mdtk_version")
    val clientPublication = publication.dir("MDTK-CORE-CLIENT_${modpackVersion.get()}+$minecraftVersion")
    val client = instances.dir("client")
    val server = instances.dir("server")
    val approved = server.dir("server/integrity/reference-client/MDTK-CORE-CLIENT_${modpackVersion.get()}+$minecraftVersion/.minecraft")
    val releaseJar = layout.buildDirectory.file("libs/integrity-${providers.gradleProperty("mod_version").get()}+$minecraftVersion.jar")
    val copyClient = tasks.register<InstallMdtkClient>("copyMdtkClient") {
        group = "outfitter"
        description = "Copie MDTK dans un client de test neuf, sans remettre un client présent en conformité"
        this.publication.set(clientPublication)
        target.set(client)
        this.minecraftVersion.set(minecraftVersion)
        this.modpackVersion.set(modpackVersion)
    }
    tasks.named("installClientMdtk") { dependsOn(copyClient) }
    val prepareReference = tasks.register<PrepareMdtkReference>("prepareMdtkReference") {
        group = "outfitter"
        description = "Prépare la copie client approuvée et la configuration initiale du serveur MDTK local"
        dependsOn("installServerMdtk", "obfuscateJar")
        this.publication.set(clientPublication)
        this.server.set(server)
        reference.set(approved)
        jar.set(releaseJar)
        this.minecraftVersion.set(minecraftVersion)
        this.modpackVersion.set(modpackVersion)
        port.set(when (minecraftVersion) { "26.2" -> 25622; "26.3" -> 25623; else -> error("No MDTK test port for $minecraftVersion") })
    }
    val prepare = tasks.register("prepareMdtkTests") {
        group = "outfitter"
        description = "Installe les copies MDTK, déploie le JAR distribué et prépare la référence à générer en console"
        dependsOn("deployToClientMdtk", "deployToServerMdtk", prepareReference)
    }
    val distribution = tasks.named<Test>("testDistribution")
    tasks.register<Test>("testMdtk") {
        group = "verification"
        description = "Vérifie le JAR distribué sur les copies MDTK publiées, sans lancer de client graphique"
        dependsOn(prepare, distribution, ":downloadMdtkFixtures")
        testClassesDirs = distribution.get().testClassesDirs
        classpath = distribution.get().classpath
        useJUnitPlatform()
        filter.includeTestsMatching("fr.moulou.integrity.MdtkDistributionTest")
        // Les copies restent modifiables à la main : une ancienne réussite ne vaut pas pour leur état présent.
        outputs.upToDateWhen { false }
        outputs.doNotCacheIf("MDTK instances contain live deliberate changes") { true }
        systemProperty("integrity.expectedMinecraft", minecraftVersion)
        systemProperty("integrity.distributionJar", releaseJar.get().asFile.absolutePath)
        systemProperty("integrity.mdtk.client", client.dir("minecraft").asFile.absolutePath)
        systemProperty("integrity.mdtk.reference", approved.asFile.absolutePath)
        systemProperty("integrity.mdtk.fixtures", layout.settingsDirectory.dir("../../02-data/00-test/$minecraftVersion").asFile.absolutePath)
        workingDir = layout.buildDirectory.dir("run/testMdtk").get().asFile
        doFirst { workingDir.mkdirs() }
        testLogging { events("passed", "failed"); showStandardStreams = true }
    }
}
