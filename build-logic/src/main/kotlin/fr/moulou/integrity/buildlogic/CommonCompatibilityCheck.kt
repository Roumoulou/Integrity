// SPDX-FileCopyrightText: 2026 Roumoulou
// SPDX-License-Identifier: LGPL-3.0-only

package fr.moulou.integrity.buildlogic

import org.gradle.api.DefaultTask
import org.gradle.api.GradleException
import org.gradle.api.file.ConfigurableFileCollection
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.CacheableTask
import org.gradle.api.tasks.Classpath
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.OutputFile
import org.gradle.api.tasks.TaskAction
import java.io.File
import java.lang.classfile.ClassFile
import java.lang.classfile.ClassModel
import java.lang.classfile.constantpool.ClassEntry
import java.lang.classfile.constantpool.FieldRefEntry
import java.lang.classfile.constantpool.MemberRefEntry
import java.lang.classfile.constantpool.PoolEntry
import java.lang.reflect.AccessFlag
import java.util.zip.ZipFile

/**
 * Vérifie que le code compilé de common ne nomme que ce qui existe dans le jeu d'un module de version.
 *
 * common se compile contre la dernière release : le compilateur ne voit pas ce qu'il emploie et qu'une version plus ancienne n'a pas, ou
 * nomme autrement. Cette tâche lit son bytecode et cherche chaque classe, chaque méthode et chaque champ qu'il nomme dans le classpath du
 * module (le jeu, la Fabric API, les bibliothèques de cette version), en remontant les supertypes jusqu'au JDK. Ce qui manque fait échouer
 * la tâche, avec la liste des symboles et des classes qui les emploient, avant que le jeu ne le découvre en `NoSuchMethodError`.
 *
 * Ce qu'elle ne voit pas : les cibles des mixins, écrites en chaînes, les constantes que le compilateur a recopiées dans le bytecode, et ce
 * qui change de comportement sans changer de nom.
 */
@CacheableTask
abstract class CommonCompatibilityCheck : DefaultTask() {

    private companion object {
        /* Leurs méthodes invoke et compagnie acceptent toute signature : la JVM ne les résout pas par leur descripteur. */
        val SIGNATURE_POLYMORPHIC_OWNERS = setOf("java/lang/invoke/MethodHandle", "java/lang/invoke/VarHandle")

        const val MAX_LINES_IN_MESSAGE = 60
    }

    /** Le code compilé de common à vérifier : dossiers de classes et jars. */
    @get:Classpath
    abstract val commonCode: ConfigurableFileCollection

    /** Ce que ce code trouvera dans le module : le jeu, la Fabric API, les bibliothèques, et common lui-même. */
    @get:Classpath
    abstract val gameClasspath: ConfigurableFileCollection

    /** La version du jeu du module, celle que nomment le compte rendu et l'échec. */
    @get:Input
    abstract val gameVersion: Property<String>

    /** Le compte rendu de la dernière vérification : ce qui a été lu, et ce qui manque. */
    @get:OutputFile
    abstract val report: RegularFileProperty

    @TaskAction
    fun check() {
        val commonFiles = commonCode.files.filter(File::exists)
        val missing = sortedMapOf<String, MutableSet<String>>()
        val present = HashMap<String, Boolean>()
        var classCount = 0

        ClassIndex(commonFiles + gameClasspath.files.filter(File::exists)).use { index ->
            ClassIndex.classBytes(commonFiles).forEach { bytes ->
                val model = ClassFile.of().parse(bytes)
                classCount++
                val user = model.thisClass().asInternalName().substringBefore('$').replace('/', '.')
                for (entry in model.constantPool()) {
                    val symbol = symbolOf(entry) ?: continue
                    if (!present.getOrPut(symbol) { isPresent(index, entry) }) missing.getOrPut(symbol) { sortedSetOf() } += user
                }
            }
        }

        val version = gameVersion.get()
        val lines = buildList {
            add("Common code checked against Minecraft $version: $classCount classes, ${present.size} distinct references.")
            add("Missing: ${missing.size}")
            missing.forEach { (symbol, users) -> add("  $symbol"); add("      used by ${users.joinToString(", ")}") }
        }
        val reportFile = report.get().asFile
        reportFile.parentFile.mkdirs()
        reportFile.writeText(lines.joinToString("\n", postfix = "\n"))

        if (missing.isNotEmpty()) {
            throw GradleException(
                "common uses ${missing.size} symbol(s) that Minecraft $version does not have, see $reportFile:\n" +
                    lines.drop(2).take(MAX_LINES_IN_MESSAGE).joinToString("\n") +
                    "\nRoute them through the game version bridge (GameVersionBridge), implemented by each game version module."
            )
        }
        logger.info("common: {} references checked against Minecraft {}, none missing", present.size, version)
    }

    /** Le symbole qu'une entrée du constant pool nomme, ou `null` si elle n'en nomme pas un qui se cherche. */
    private fun symbolOf(entry: PoolEntry): String? = when (entry) {
        is MemberRefEntry -> {
            val owner = entry.owner().asInternalName()
            if (owner.startsWith("[")) null else "${if (entry is FieldRefEntry) "field" else "method"} $owner.${entry.name().stringValue()} ${entry.type().stringValue()}"
        }
        is ClassEntry -> elementClass(entry.asInternalName())?.let { "class $it" }
        else -> null
    }

    private fun isPresent(index: ClassIndex, entry: PoolEntry): Boolean = when (entry) {
        is FieldRefEntry -> index.hasMember(entry.owner().asInternalName(), "${entry.name().stringValue()}:${entry.type().stringValue()}", field = true)
        is MemberRefEntry -> {
            val owner = entry.owner().asInternalName()
            owner in SIGNATURE_POLYMORPHIC_OWNERS || index.hasMember(owner, entry.name().stringValue() + entry.type().stringValue(), field = false)
        }
        is ClassEntry -> elementClass(entry.asInternalName())?.let(index::hasClass) ?: true
        else -> true
    }

    /** La classe nommée par un nom interne, tableau compris (`[Lfoo/Bar;` donne `foo/Bar`), ou `null` pour un tableau de primitifs. */
    private fun elementClass(internalName: String): String? {
        val element = internalName.trimStart('[')
        return when {
            element == internalName -> internalName
            element.startsWith("L") && element.endsWith(";") -> element.substring(1, element.length - 1)
            else -> null
        }
    }

    /**
     * Les classes d'un classpath, lues à la demande : un jar n'est indexé que par ses noms d'entrées, une classe ne se décode qu'au moment
     * où un symbole la traverse. Ce que le classpath n'a pas se cherche dans le JDK, par le chargeur de plateforme seul : jamais dans les
     * classes de Gradle, qui donneraient raison à tort.
     */
    private class ClassIndex(classpath: Collection<File>) : AutoCloseable {

        companion object {
            /** Le bytecode de chaque classe des dossiers et des jars donnés. */
            fun classBytes(files: Collection<File>): Sequence<ByteArray> = sequence {
                for (file in files) {
                    if (file.isDirectory) {
                        file.walkTopDown().filter { it.isFile && it.name.endsWith(".class") }.forEach { yield(it.readBytes()) }
                    } else if (file.name.endsWith(".jar")) {
                        ZipFile(file).use { jar ->
                            for (entry in jar.entries()) {
                                if (isClassEntry(entry.name)) yield(jar.getInputStream(entry).use { it.readBytes() })
                            }
                        }
                    }
                }
            }

            private fun isClassEntry(name: String): Boolean = name.endsWith(".class") && !name.startsWith("META-INF/") && name != "module-info.class"
        }

        private val jars = mutableListOf<ZipFile>()
        private val readers = HashMap<String, () -> ByteArray>()
        private val infos = HashMap<String, ClassInfo?>()

        init {
            for (file in classpath) {
                if (file.isDirectory) {
                    file.walkTopDown().filter { it.isFile && it.name.endsWith(".class") }.forEach { classFile ->
                        readers.putIfAbsent(classFile.relativeTo(file).invariantSeparatorsPath.removeSuffix(".class")) { classFile.readBytes() }
                    }
                } else if (file.name.endsWith(".jar")) {
                    val jar = ZipFile(file)
                    jars += jar
                    for (entry in jar.entries()) {
                        if (isClassEntry(entry.name)) readers.putIfAbsent(entry.name.removeSuffix(".class")) { jar.getInputStream(entry).use { it.readBytes() } }
                    }
                }
            }
        }

        fun hasClass(name: String): Boolean = info(name) != null

        fun hasMember(owner: String, key: String, field: Boolean): Boolean = findMember(owner, key, field, HashSet())

        /* La résolution de la JVM : la classe, puis ses supertypes ; une interface hérite aussi des méthodes publiques d'Object. */
        private fun findMember(owner: String, key: String, field: Boolean, seen: MutableSet<String>): Boolean {
            if (!seen.add(owner)) return false
            val info = info(owner) ?: return false
            if (key in (if (field) info.fields else info.methods)) return true
            if ((listOfNotNull(info.superName) + info.interfaces).any { findMember(it, key, field, seen) }) return true
            return !field && info.isInterface && findMember("java/lang/Object", key, false, seen)
        }

        private fun info(name: String): ClassInfo? {
            if (name !in infos) infos[name] = read(name)?.let { ClassInfo.of(ClassFile.of().parse(it)) }
            return infos[name]
        }

        private fun read(name: String): ByteArray? =
            readers[name]?.invoke() ?: ClassLoader.getPlatformClassLoader().getResourceAsStream("$name.class")?.use { it.readBytes() }

        override fun close() {
            jars.forEach(ZipFile::close)
        }
    }

    private class ClassInfo(val superName: String?, val interfaces: List<String>, val methods: Set<String>, val fields: Set<String>, val isInterface: Boolean) {

        companion object {
            fun of(model: ClassModel): ClassInfo = ClassInfo(
                superName = model.superclass().map(ClassEntry::asInternalName).orElse(null),
                interfaces = model.interfaces().map(ClassEntry::asInternalName),
                methods = model.methods().mapTo(HashSet()) { it.methodName().stringValue() + it.methodType().stringValue() },
                fields = model.fields().mapTo(HashSet()) { "${it.fieldName().stringValue()}:${it.fieldType().stringValue()}" },
                isInterface = model.flags().has(AccessFlag.INTERFACE)
            )
        }
    }
}
