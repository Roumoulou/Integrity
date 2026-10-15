package fr.moulou.integrity.config

import fr.moulou.integrity.model.Category
import kotlinx.serialization.Serializable

@Serializable
enum class DeviationKind { ADDED, MISSING, MODIFIED }

@Serializable
enum class RuleAction { ALLOW, DENY, IGNORE }

@Serializable
data class CategoryPolicy(val ignore: Boolean = false, val allowAdded: Boolean = false, val allowMissing: Boolean = false, val allowModified: Boolean = false) {
    fun allows(kind: DeviationKind): Boolean = ignore || when (kind) {
        DeviationKind.ADDED -> allowAdded
        DeviationKind.MISSING -> allowMissing
        DeviationKind.MODIFIED -> allowModified
    }
}

@Serializable
data class ComplianceRule(
    val category: Category,
    val path: String? = null,
    val modId: String? = null,
    val playerUuid: String? = null,
    val action: RuleAction = RuleAction.ALLOW,
    val deviations: Set<DeviationKind> = DeviationKind.entries.toSet(),
)

@Serializable
data class ServerConfig(
    val referenceInstance: String = "integrity/reference-client/modpack",
    var activeReference: String = "",
    val requireSameMinecraft: Boolean = true,
    val requireSameModpack: Boolean = true,
    val enforceSingleplayer: Boolean = false,
    val timeoutSeconds: Int = 120,
    val recheckIntervalSeconds: Int = 300,
    val maxElements: Int = 10_000,
    val maxScanBytes: Long = 68_719_476_736,
    val policies: Map<Category, CategoryPolicy> = Category.entries.associateWith { CategoryPolicy() },
    val rules: List<ComplianceRule> = emptyList(),
)
