import fr.moulou.integrity.buildlogic.CommonCompatibilityCheck
import fr.moulou.integrity.buildlogic.JdkLibraryJar
import fr.moulou.integrity.buildlogic.PublicationSettings
import me.modmuss50.mpp.PublishModTask
import proguard.gradle.ProGuardTask
import java.util.jar.Manifest
import java.util.zip.ZipFile

plugins {
    id("integrity.loom-module")
    id("me.modmuss50.mod-publish-plugin")
}

val libs = the<VersionCatalogsExtension>().named("libs")
val mc = the<VersionCatalogsExtension>().named("mc")
val baseVersion = version.toString()
val modId = providers.gradleProperty("mod_id").get()
val releaseLine = name.removePrefix("mc-")
require(name.startsWith("mc-") && Regex("\\d+\\.\\d+").matches(releaseLine)) { "Version module must be named mc-<release line>" }
// Le catalogue de la lignée, mc263 pour mc-26.3 : il porte aussi le SHA-1 du client.jar que le serveur attend d'un client.
val lineCatalog = the<VersionCatalogsExtension>().named("mc" + releaseLine.replace(".", ""))
val minecraftVersion = objects.property<String>()

configurations.named("minecraft") {
    dependencies.configureEach {
        val declared = version.orEmpty()
        require(declared == releaseLine || declared.startsWith("$releaseLine.")) { "Minecraft $declared does not belong to $releaseLine" }
        minecraftVersion.set(declared)
        project.version = "$baseVersion+$declared"
    }
}

val commonCode = configurations.dependencyScope("commonCode")
listOf("compileClasspath", "runtimeClasspath", "clientCompileClasspath", "clientRuntimeClasspath").forEach { classpath ->
    configurations.named(classpath) { extendsFrom(commonCode.get()) }
}
dependencies {
    "commonCode"(project(path = ":common", configuration = "commonClasses"))
    implementation(mc.findLibrary("fabric-loader").get())
    implementation(mc.findLibrary("fabric-language-kotlin").get())
    implementation(libs.findLibrary("kotlinx-serialization-json").get())
    implementation(libs.findLibrary("storify").get())
    "include"(libs.findLibrary("storify").get())
    "include"(libs.findLibrary("tomlkt").get())
    "include"(libs.findLibrary("json5").get())
}
loom {
    mods {
        register(modId) {
            sourceSet("main"); sourceSet("client")
            sourceSet("main", ":common"); sourceSet("client", ":common")
        }
    }
    // Un essai sans clic : -Pintegrity.dev.quickPlay=127.0.0.1:25563 fait rejoindre ce serveur au client dès son lancement.
    // Deux essais de refus sans toucher au code : -Pintegrity.dev.addMods=<jar> fait charger un mod hors de mods par Loader, et
    // -Pintegrity.dev.declaredVersion=<version> fait déclarer cette version d'Integrity au client de développement.
    val quickPlay = providers.gradleProperty("integrity.dev.quickPlay")
    val addMods = providers.gradleProperty("integrity.dev.addMods")
    val declaredVersion = providers.gradleProperty("integrity.dev.declaredVersion")
    runs.configureEach {
        generateRunConfig = true; appendProjectPathToDisplayName = true
        if (name.startsWith("client")) {
            if (quickPlay.isPresent) programArguments.addAll("--quickPlayMultiplayer", quickPlay.get())
            if (addMods.isPresent) vmArg("-Dfabric.addMods=${addMods.get()}")
            if (declaredVersion.isPresent) vmArg("-Dintegrity.dev.declaredVersion=${declaredVersion.get()}")
        }
    }
}

tasks.processResources {
    val replacements: Map<String, Any> = mapOf(
        "version" to minecraftVersion.map { "$baseVersion+$it" },
        "minecraft_version" to minecraftVersion,
        "minecraft_client_sha1" to lineCatalog.findVersion("client-jar-sha1").get().requiredVersion,
        "java_version" to libs.findVersion("java").get().requiredVersion,
        "fabric_loader_version" to mc.findVersion("fabric-loader").get().requiredVersion,
        "fabric_language_kotlin_version" to mc.findVersion("fabric-language-kotlin").get().requiredVersion,
    )
    from(layout.settingsDirectory.dir("common/src/main/resources"))
    filteringCharset = "UTF-8"
    inputs.properties(replacements)
    filesMatching(listOf("fabric.mod.json", "defaults/modpack.json", "integrity-game.properties")) { expand(replacements.mapValues { (_, value) -> if (value is Provider<*>) value.get() else value }) }
}
val commonClasses = configurations.runtimeClasspath.map { classpath ->
    classpath.incoming.artifactView { componentFilter { it is ProjectComponentIdentifier && it.projectPath == ":common" } }.files
}
tasks.jar { archiveClassifier = "dev"; from(commonClasses); from(layout.settingsDirectory.file("LICENSE")); from(layout.settingsDirectory.file("LICENSE.GPL")) }
tasks.named<Jar>("sourcesJar") {
    listOf("main/kotlin", "main/java", "main/resources", "client/kotlin").forEach { from(layout.settingsDirectory.dir("common/src/$it")) }
}

val releaseJar = layout.buildDirectory.file(minecraftVersion.map { "libs/integrity-$baseVersion+$it.jar" })
// Dans son propre dossier : une copie dans build\libs ferait de tout ce dossier une sortie de la tâche, aux yeux de Gradle.
val releaseMappingFile = layout.buildDirectory.file(minecraftVersion.map { "mappings/integrity-$baseVersion+$it-mapping.txt" })
val obfuscatedJar = layout.buildDirectory.file(minecraftVersion.map { "proguard/integrity-$baseVersion+$it-obfuscated.jar" })
val jdkLibraryJar = rootProject.tasks.findByName("jdkLibraryJar")?.let { rootProject.tasks.named<JdkLibraryJar>(it.name) }
    ?: rootProject.tasks.register<JdkLibraryJar>("jdkLibraryJar") {
        val launcher = javaToolchains.launcherFor { languageVersion = JavaLanguageVersion.of(libs.findVersion("java").get().requiredVersion.toInt()) }
        runtimeImage = launcher.map { it.metadata.installationPath.file("lib/modules") }
        archive = rootProject.layout.buildDirectory.file("proguard/jdk-library.jar")
    }
val obfuscateJar = tasks.register<ProGuardTask>("obfuscateJar") {
    group = "build"
    description = "Obfusque le JAR distribué, avec les métadonnées Kotlin et les points d'entrée préservés"
    dependsOn(tasks.jar, jdkLibraryJar)
    injars(tasks.jar.flatMap { it.archiveFile })
    outjars(obfuscatedJar)
    configuration(layout.settingsDirectory.file("gradle/proguard.pro").asFile)
    libraryjars((sourceSets["main"].runtimeClasspath + sourceSets["client"].runtimeClasspath).filter { it.isFile })
    libraryjars(jdkLibraryJar.flatMap { it.archive })
    printmapping(layout.buildDirectory.file("proguard/mapping.txt").get().asFile)
    // ProGuard travaille dans le JVM Gradle ; les autres contrôles restent parallèles.
    when (releaseLine) {
        "26.2" -> mustRunAfter(":mc-26.1:obfuscateJar")
        "26.3" -> mustRunAfter(":mc-26.1:obfuscateJar", ":mc-26.2:obfuscateJar")
    }
}
// Loom liste les entrées réservées au client dans l'ordre du système de fichiers, qui change d'un système à l'autre : le manifeste du JAR
// distribué est réécrit avec cette liste triée, par l'écriture canonique de java.util.jar.Manifest.
val normalizedManifest = layout.buildDirectory.file(minecraftVersion.map { "proguard/integrity-$baseVersion+$it-MANIFEST.MF" })
val normalizeManifest = tasks.register("normalizeManifest") {
    description = "Réécrit le manifeste du JAR obfusqué avec ses listes triées, les mêmes sur tout système"
    dependsOn(obfuscateJar)
    inputs.file(obfuscatedJar)
    outputs.file(normalizedManifest)
    doLast {
        val manifest = ZipFile(obfuscatedJar.get().asFile).use { zip ->
            zip.getInputStream(zip.getEntry("META-INF/MANIFEST.MF")).use { Manifest(it) }
        }
        val attributes = manifest.mainAttributes
        attributes.getValue("Fabric-Loom-Client-Only-Entries")?.let { entries ->
            attributes.putValue("Fabric-Loom-Client-Only-Entries", entries.split(';').sorted().joinToString(";"))
        }
        normalizedManifest.get().asFile.outputStream().use { manifest.write(it) }
    }
}
// ProGuard date chaque entrée à l'heure du build : le JAR distribué est réempaqueté à dates fixes et en ordre trié, pour qu'un build du même
// tag donne le même fichier, octet pour octet, ce qu'un relecteur peut vérifier. Les correspondances des noms l'accompagnent dans la release.
val packReleaseJar = tasks.register<Jar>("packReleaseJar") {
    group = "build"
    description = "Réempaquète le JAR obfusqué à dates fixes et en ordre trié : le même tag donne le même fichier"
    dependsOn(obfuscateJar, normalizeManifest)
    // Le manifeste normalisé est écrit en tête de l'archive par la tâche Jar ; les autres entrées suivent, triées.
    manifest { from(normalizedManifest) }
    from(zipTree(obfuscatedJar)) { exclude("META-INF/MANIFEST.MF") }
    destinationDirectory = layout.buildDirectory.dir("libs")
    archiveFileName = minecraftVersion.map { "integrity-$baseVersion+$it.jar" }
    isPreserveFileTimestamps = false
    isReproducibleFileOrder = true
}
val releaseMapping = tasks.register("releaseMapping") {
    group = "build"
    description = "Copie les correspondances ProGuard sous le nom du JAR distribué, en LF, pour la release"
    dependsOn(obfuscateJar)
    val source = layout.buildDirectory.file("proguard/mapping.txt")
    inputs.file(source)
    outputs.file(releaseMappingFile)
    doLast {
        // ProGuard écrit les fins de ligne de la plateforme : la release les porte en LF, les mêmes sur tout système.
        releaseMappingFile.get().asFile.writeText(source.get().asFile.readText().replace("\r\n", "\n"))
    }
}
tasks.assemble { dependsOn(packReleaseJar, releaseMapping) }

val testMC = sourceSets.create("testMC")
dependencies {
    "testMCRuntimeOnly"(project(path = ":common", configuration = "testMCElements"))
    "testMCRuntimeOnly"(libs.findLibrary("junit-jupiter").get())
    "testMCRuntimeOnly"(libs.findLibrary("junit-platform-launcher").get())
    "testMCRuntimeOnly"(mc.findLibrary("fabric-loader-junit").get())
}
val testClasses = configurations.named(testMC.runtimeClasspathConfigurationName).map { classpath ->
    classpath.incoming.artifactView { componentFilter { it is ProjectComponentIdentifier && it.projectPath == ":common" } }.files
}
val testDistribution = tasks.register<Test>("testDistribution") {
    group = "verification"
    description = "Vérifie le JAR obfusqué sur la version de Minecraft du module"
    dependsOn(packReleaseJar)
    testClassesDirs = files(testClasses)
    classpath = files(testClasses, releaseJar, configurations.named(testMC.runtimeClasspathConfigurationName),
        sourceSets["main"].runtimeClasspath.filter { it.isFile }, sourceSets["client"].runtimeClasspath.filter { it.isFile })
    useJUnitPlatform()
    filter.excludeTestsMatching("fr.moulou.integrity.MdtkDistributionTest")
    mustRunAfter(":common:test")
    systemProperty("integrity.expectedMinecraft", minecraftVersion.get())
    systemProperty("integrity.distributionJar", releaseJar.get().asFile.absolutePath)
    // Les tests nomment le code renommé par ses noms d'origine, à travers les correspondances de ProGuard.
    systemProperty("integrity.distributionMapping", layout.buildDirectory.file("proguard/mapping.txt").get().asFile.absolutePath)
    workingDir = layout.buildDirectory.dir("run/testDistribution").get().asFile
    doFirst { workingDir.mkdirs() }
    testLogging { events("failed"); exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL }
}
val compatibility = tasks.register<CommonCompatibilityCheck>("checkCommonCompatibility") {
    group = "verification"
    commonCode.from(commonClasses)
    gameClasspath.from(sourceSets["main"].compileClasspath, sourceSets["client"].compileClasspath)
    gameVersion = minecraftVersion
    report = layout.buildDirectory.file("reports/common-compatibility.txt")
}
tasks.named("check") { dependsOn(compatibility, testDistribution) }

fabricApi {
    configureTests { createSourceSet = true; modId = "$modId-gametest"; enableGameTests = true; enableClientGameTests = false }
}
dependencies { "gametestRuntimeOnly"(project(path = ":common", configuration = "gametestElements")) }
tasks.named("runGameTest") { mustRunAfter(testDistribution, ":common:test") }

// La publication : le JAR obfusqué de ce module part sur Modrinth et CurseForge, une version par Minecraft, et rejoint la release GitHub que la
// racine crée (plugin integrity.publication). Sans -Ppublish_live=true, tout est à blanc : le plugin écrit sous build\publishMods ce qu'il
// aurait envoyé. Le JAR de sources ne part pas : le tag GitHub donne les sources, et le JAR distribué reste obfusqué.
val publication = PublicationSettings(providers, layout)
val releaseVersion: Provider<String> = minecraftVersion.map { "$baseVersion+$it" }
val githubRelease: TaskProvider<Task> = rootProject.tasks.named("publishGithub")
val checkPublication: TaskProvider<Task> = rootProject.tasks.named("checkPublication")

publishMods {
    dryRun = !publication.live
    file = packReleaseJar.flatMap { it.archiveFile }
    version = releaseVersion
    displayName = releaseVersion.map { "Integrity-$it" }
    changelog = providers.fileContents(publication.changelog).asText.orElse("")
    type = publication.type
    modLoaders.add("fabric")

    modrinth {
        accessToken = providers.environmentVariable("MODRINTH_TOKEN")
        // L'essai à blanc passe sans identifiant ; checkPublication refuse l'envoi réel sans lui.
        projectId = publication.modrinthProjectId.orElse("absent")
        minecraftVersions.add(minecraftVersion)
        environment = CLIENT_AND_SERVER
        requires("fabric-api", "fabric-language-kotlin")
    }

    curseforge {
        accessToken = providers.environmentVariable("CURSEFORGE_TOKEN")
        projectId = publication.curseforgeProjectId.orElse("absent")
        projectSlug = providers.gradleProperty("curseforge_project_slug").orElse(modId)
        minecraftVersions.add(minecraftVersion)
        client = true
        server = true
        requires("fabric-api", "fabric-language-kotlin")
    }

    github {
        accessToken = providers.environmentVariable("GITHUB_TOKEN")
        parent(githubRelease)
        // Les correspondances ProGuard rejoignent la release : un relecteur retrouve chaque classe obfusquée dans les sources.
        additionalFiles.from(releaseMapping.map { releaseMappingFile.get() })
    }
}

// La release naît sur la racine après le check de ce module, le JAR la rejoint ensuite, et tout envoi attend le garde-fou de la racine.
val checkTask = tasks.named("check")
githubRelease { dependsOn(checkTask) }
tasks.named("publishGithub") { dependsOn(githubRelease) }
tasks.withType<PublishModTask>().configureEach { dependsOn(checkPublication, checkTask, releaseMapping) }
checkTask { mustRunAfter(checkPublication) }
