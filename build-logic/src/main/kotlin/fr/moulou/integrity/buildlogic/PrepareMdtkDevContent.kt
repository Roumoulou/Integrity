package fr.moulou.integrity.buildlogic

import org.gradle.api.DefaultTask
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.file.FileSystemOperations
import org.gradle.api.tasks.Internal
import org.gradle.api.tasks.TaskAction
import org.gradle.api.tasks.UntrackedTask
import javax.inject.Inject

/** Pose le contenu publié de MDTK dans un run Loom, une seule fois ; les mods sont copiés séparément par Outfitter. */
@UntrackedTask(because = "development environments retain deliberate changes between launches")
abstract class PrepareMdtkDevContent : DefaultTask() {
    @get:Internal abstract val publication: DirectoryProperty
    @get:Internal abstract val directory: DirectoryProperty
    @get:Inject abstract val fileSystemOperations: FileSystemOperations

    @TaskAction
    fun prepare() {
        val source = publication.get().asFile
        val target = directory.get().asFile
        require(source.resolve("mods").isDirectory) { "MDTK publication is missing: $source. Configure integrity.mdtk.releases_dir before launching a modded environment." }
        val markers = listOf(".packs-done", ".datapacks-done", ".settings-done")
        if (markers.all { target.resolve(it).isFile }) return
        fileSystemOperations.copy {
            from(source)
            into(target)
            include("config/**", "resourcepacks/**", "shaderpacks/**", "datapacks/**", "options.txt")
        }
        // Le contenu vient de la publication figée, déjà réglée par PackTool ; Outfitter n'a pas à le reconstruire depuis le projet courant.
        markers.forEach { target.resolve(it).writeText("MDTK publié, copié depuis $source\n", Charsets.UTF_8) }
        logger.lifecycle("[MDTK dev] contenu publié posé dans $target ; les changements suivants sont conservés")
    }
}
