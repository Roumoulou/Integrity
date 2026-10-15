package fr.moulou.integrity.buildlogic

import org.gradle.api.DefaultTask
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.tasks.*
import java.net.URI
import java.nio.file.FileSystems
import java.nio.file.Files
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/** Fournit à ProGuard les classes du JDK de compilation, même si sa distribution omet les jmods. */
@CacheableTask
abstract class JdkLibraryJar : DefaultTask() {
    @get:InputFile
    @get:PathSensitive(PathSensitivity.NONE)
    abstract val runtimeImage: RegularFileProperty

    @get:OutputFile
    abstract val archive: RegularFileProperty

    @TaskAction
    fun extractClasses() {
        val javaHome = runtimeImage.get().asFile.parentFile.parentFile
        val destination = archive.get().asFile.toPath()
        Files.createDirectories(destination.parent)
        FileSystems.newFileSystem(URI.create("jrt:/"), mapOf("java.home" to javaHome.absolutePath)).use { image ->
            val root = image.getPath("/modules")
            ZipOutputStream(Files.newOutputStream(destination)).use { output ->
                Files.walk(root).use { paths ->
                    paths.filter { Files.isRegularFile(it) && it.fileName.toString().endsWith(".class") && it.fileName.toString() != "module-info.class" }
                        .sorted().forEach { path ->
                            val relative = root.relativize(path)
                            val entry = ZipEntry(relative.subpath(1, relative.nameCount).toString().replace('\\', '/'))
                            entry.time = 0
                            output.putNextEntry(entry)
                            Files.copy(path, output)
                            output.closeEntry()
                        }
                }
            }
        }
    }
}
