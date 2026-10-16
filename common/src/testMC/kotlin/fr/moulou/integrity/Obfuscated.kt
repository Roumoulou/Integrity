package fr.moulou.integrity

import fr.moulou.integrity.config.ServerConfig
import fr.moulou.integrity.model.Category
import fr.moulou.integrity.model.ControlChallenge
import fr.moulou.integrity.model.InventoryReport
import fr.moulou.integrity.model.PackIdentity
import fr.moulou.integrity.model.UploadChunk
import fr.moulou.integrity.model.ValidationResult
import net.minecraft.network.FriendlyByteBuf
import net.minecraft.network.codec.StreamCodec
import java.lang.reflect.Method
import java.nio.file.Files
import java.nio.file.Path
import java.util.UUID

/**
 * Le fichier de correspondances ProGuard d'un JAR distribué : pour chaque classe, son nom renommé et ceux de ses membres.
 * Une méthode se retrouve par son nom d'origine et ses types de paramètres d'origine, parce que ProGuard donne volontiers le même nom court
 * à deux méthodes d'une classe qui ne diffèrent que par leurs types.
 */
class ProguardMapping private constructor(private val classes: Map<String, String>, private val members: Map<String, List<Member>>) {
    companion object {
        private val lineNumbers = Regex("^(\\d+:)+")

        fun load(file: Path): ProguardMapping {
            val classes = HashMap<String, String>()
            val members = HashMap<String, MutableList<Member>>()
            var current = ""
            Files.readAllLines(file).forEach { line ->
                if (line.isBlank() || line.startsWith("#")) return@forEach
                if (!line.startsWith(" ")) {
                    val (original, renamed) = line.removeSuffix(":").split(" -> ")
                    classes[original] = renamed
                    current = original
                } else {
                    val (signature, renamed) = line.trim().replace(lineNumbers, "").split(" -> ")
                    val declaration = signature.substringAfter(' ')
                    val name = declaration.substringBefore('(')
                    val parameters = if ('(' in declaration) declaration.substringAfter('(').removeSuffix(")").split(',').filter { it.isNotEmpty() } else null
                    members.getOrPut(current) { ArrayList() } += Member(name, parameters, renamed)
                }
            }
            return ProguardMapping(classes, members)
        }
    }

    /** Le nom renommé d'une classe d'origine ; une classe absente du mapping est une erreur. */
    fun className(original: String): String = classes[original] ?: error("Class $original is not in the mapping")

    /** Le nom d'un type tel que la JVM le donne à l'exécution : renommé s'il est dans le mapping, inchangé sinon (bibliothèques, primitifs, tableaux). */
    fun typeName(original: String): String = classes[original] ?: original

    fun fieldName(owner: String, original: String): String = single(owner, original) { it.parameters == null }

    fun methodName(owner: String, original: String, parameters: List<String>): String = single(owner, original) { it.parameters == parameters }

    private fun single(owner: String, original: String, matches: (Member) -> Boolean): String {
        val candidates = members[owner].orEmpty().filter { it.name == original && matches(it) }
        require(candidates.size == 1) { "Member $owner.$original matches ${candidates.size} mapping entries" }
        return candidates.single().renamed
    }

    private data class Member(val name: String, val parameters: List<String>?, val renamed: String)
}

/**
 * Le JAR distribué atteint par ses noms d'origine, à travers le fichier de correspondances que `integrity.distributionMapping` désigne : les tests
 * nomment la façade, le protocole et les codecs comme dans les sources, et prouvent au passage que ce fichier, joint à chaque release, retrouve le
 * code renommé. Les classes de données gardent leur nom et se prennent directement.
 */
object Obfuscated {
    private const val SERVICE = "fr.moulou.integrity.api.IntegrityService"
    private const val EXCHANGE = "fr.moulou.integrity.protocol.SecureExchange"
    private const val SERVER_SIDE = "fr.moulou.integrity.protocol.SecureExchange\$ServerSide"
    private const val EXPECTATIONS = "fr.moulou.integrity.rules.ServerExpectations"
    private const val CHALLENGE_PAYLOAD = "fr.moulou.integrity.network.ChallengePayload"
    private const val CHUNK_PAYLOAD = "fr.moulou.integrity.network.ChunkPayload"
    private const val COMPANION = "\$Companion"
    private const val INVENTORY_REPORT = "fr.moulou.integrity.model.InventoryReport"
    private const val CONTROL_CHALLENGE = "fr.moulou.integrity.model.ControlChallenge"

    val mapping: ProguardMapping by lazy { ProguardMapping.load(Path.of(System.getProperty("integrity.distributionMapping"))) }

    /** La classe renommée d'un nom d'origine, chargée depuis le JAR distribué. */
    fun type(original: String): Class<*> = Class.forName(mapping.className(original))

    /** `IntegrityService.scan` avec ses valeurs par défaut. */
    fun scan(directory: Path, identity: PackIdentity): InventoryReport =
        method(SERVICE, "scan", "java.nio.file.Path", "fr.moulou.integrity.model.PackIdentity", "fr.moulou.integrity.config.ServerConfig", "java.util.Set", "fr.moulou.integrity.inventory.FileHashCache", "kotlin.jvm.functions.Function2")
            .invoke(instance(SERVICE), directory, identity, ServerConfig(), Category.entries.toSet(), null, null) as InventoryReport

    /** `IntegrityService.compare` avec les attentes que le build a posées dans le JAR. */
    fun compare(reference: InventoryReport, client: InventoryReport, config: ServerConfig, player: UUID): ValidationResult =
        method(SERVICE, "compare", INVENTORY_REPORT, INVENTORY_REPORT, "fr.moulou.integrity.config.ServerConfig", "java.util.UUID", EXPECTATIONS, "byte[]")
            .invoke(instance(SERVICE), reference, client, config, player, expectations(), null) as ValidationResult

    /** `ServerExpectations.current`, lue dans la ressource du JAR, comme le serveur la lit. */
    fun expectations(): Any = method(EXPECTATIONS + COMPANION, "getCurrent").invoke(companion(EXPECTATIONS))

    /** Une attente par le nom de sa propriété : `minecraftVersion`, `clientSha1` ou `integrityVersion`. */
    fun expectation(property: String): String? = method(EXPECTATIONS, "get" + property.replaceFirstChar(Char::uppercase)).invoke(expectations()) as String?

    fun server(): ServerSide = ServerSide(method(EXCHANGE, "server", "int", "long", "java.util.Set").invoke(instance(EXCHANGE), 10_000, 68_719_476_736L, Category.entries.toSet()))

    fun seal(challenge: ControlChallenge, report: InventoryReport): ByteArray = method(EXCHANGE, "seal", CONTROL_CHALLENGE, INVENTORY_REPORT).invoke(instance(EXCHANGE), challenge, report) as ByteArray

    @Suppress("UNCHECKED_CAST")
    fun challengeCodec(): StreamCodec<FriendlyByteBuf, Any> = method(CHALLENGE_PAYLOAD + COMPANION, "getCODEC").invoke(companion(CHALLENGE_PAYLOAD)) as StreamCodec<FriendlyByteBuf, Any>

    fun challengePayload(challenge: ControlChallenge): Any = type(CHALLENGE_PAYLOAD).getDeclaredConstructor(ControlChallenge::class.java).newInstance(challenge)

    fun challengeOf(payload: Any): ControlChallenge = method(CHALLENGE_PAYLOAD, "getChallenge").invoke(payload) as ControlChallenge

    @Suppress("UNCHECKED_CAST")
    fun chunkCodec(): StreamCodec<FriendlyByteBuf, Any> = method(CHUNK_PAYLOAD + COMPANION, "getCODEC").invoke(companion(CHUNK_PAYLOAD)) as StreamCodec<FriendlyByteBuf, Any>

    fun chunkPayload(chunk: UploadChunk): Any = type(CHUNK_PAYLOAD).getDeclaredConstructor(UploadChunk::class.java).newInstance(chunk)

    fun chunkOf(payload: Any): UploadChunk = method(CHUNK_PAYLOAD, "getChunk").invoke(payload) as UploadChunk

    /** La méthode renommée d'une classe d'origine, par son nom et ses types de paramètres d'origine, eux-mêmes traduits par le mapping. */
    private fun method(owner: String, original: String, vararg parameters: String): Method {
        val renamed = mapping.methodName(owner, original, parameters.toList())
        val expected = parameters.map(mapping::typeName)
        return type(owner).declaredMethods.single { it.name == renamed && it.parameterTypes.map { type -> type.typeName } == expected }.also { it.isAccessible = true }
    }

    private fun instance(owner: String): Any = staticField(owner, "INSTANCE")

    private fun companion(owner: String): Any = staticField(owner, "Companion")

    private fun staticField(owner: String, original: String): Any = type(owner).getDeclaredField(mapping.fieldName(owner, original)).also { it.isAccessible = true }.get(null)

    /** Un contrôle côté serveur : son challenge, et l'ouverture d'une réponse chiffrée. */
    class ServerSide(private val handle: Any) {
        val challenge: ControlChallenge get() = method(SERVER_SIDE, "getChallenge").invoke(handle) as ControlChallenge

        fun open(encrypted: ByteArray): InventoryReport = method(SERVER_SIDE, "open", "byte[]").invoke(handle, encrypted) as InventoryReport
    }
}
