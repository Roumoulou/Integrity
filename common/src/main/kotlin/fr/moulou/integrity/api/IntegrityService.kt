package fr.moulou.integrity.api

import fr.moulou.integrity.config.ServerConfig
import fr.moulou.integrity.inventory.FileHashCache
import fr.moulou.integrity.inventory.InventoryScanner
import fr.moulou.integrity.model.Category
import fr.moulou.integrity.model.InventoryReport
import fr.moulou.integrity.model.PackIdentity
import fr.moulou.integrity.model.ValidationResult
import fr.moulou.integrity.reference.ReferenceDiff
import fr.moulou.integrity.rules.RulesEngine
import fr.moulou.integrity.rules.ServerExpectations
import java.nio.file.Path
import java.util.UUID

/** Les opérations d'Integrity indépendantes du jeu, utilisables pour préparer une référence et vérifier un inventaire. */
object IntegrityService {
    /** L'inventaire d'un dossier de jeu ; `cache` évite de relire les fichiers inchangés et `progress` suit l'avancement depuis le fil du scan. */
    fun scan(
        gameDirectory: Path, identity: PackIdentity, config: ServerConfig = ServerConfig(), categories: Set<Category> = Category.entries.toSet(),
        cache: FileHashCache? = null, progress: ((done: Int, total: Int) -> Unit)? = null,
    ): InventoryReport = InventoryScanner(config.maxElements, config.maxScanBytes, cache).scan(gameDirectory, identity, categories, progress)

    /**
     * Le verdict du serveur pour un joueur : versions, relevé d'exécution, règles et écarts entre la référence et l'inventaire reçu.
     * `expectations` dit ce que le serveur attend du jeu, de la version et du JAR du client, par défaut ce que le build a posé dans le JAR en
     * cours ; `nonce` est celui du contrôle, qui lie l'authentification du JAR client à ce contrôle, `null` hors d'un contrôle.
     */
    fun compare(
        reference: InventoryReport, client: InventoryReport, config: ServerConfig, player: UUID, expectations: ServerExpectations = ServerExpectations.current, nonce: ByteArray? = null,
    ): ValidationResult = RulesEngine(config, expectations).compare(reference, client, player, nonce)

    /** Ce qui change entre deux inventaires, sans règle ni joueur : pour comparer deux références. */
    fun diff(before: InventoryReport, after: InventoryReport): ReferenceDiff = ReferenceDiff.between(before, after)
}
