package fr.moulou.integrity.rules

import fr.moulou.integrity.config.ComplianceRule
import fr.moulou.integrity.config.ConfigValidation
import fr.moulou.integrity.config.DeviationKind
import fr.moulou.integrity.config.RuleAction
import fr.moulou.integrity.config.ServerConfig
import fr.moulou.integrity.model.InventoryEntry
import fr.moulou.integrity.model.InventoryReport
import fr.moulou.integrity.model.ValidationResult
import fr.moulou.integrity.model.Violation
import java.util.UUID

/** Compare un inventaire reçu à la référence selon la configuration, et le relevé d'exécution du client à ce que le serveur attend (`expectations`). */
class RulesEngine(private val config: ServerConfig, private val expectations: ServerExpectations = ServerExpectations.current) {
    private val selectors = config.rules.map { rule -> Selector(rule, rule.path?.let(::glob)) }

    /** Le verdict ; `nonce` est celui du contrôle en cours, qui lie l'authentification du JAR client à ce contrôle, ou `null` hors d'un contrôle. */
    fun compare(reference: InventoryReport, client: InventoryReport, player: UUID, nonce: ByteArray? = null): ValidationResult {
        val problems = ConfigValidation.problems(config).map { "Configuration invalide : ${it.first}." } + ReportValidation.problems(reference, config.maxElements) + ReportValidation.problems(client, config.maxElements)
        if (problems.isNotEmpty()) return ValidationResult(problems = problems.distinct())
        val versionProblems = buildList {
            if (config.requireSameMinecraft && reference.pack.minecraftVersion != client.pack.minecraftVersion) add("Minecraft ${reference.pack.minecraftVersion} est requis ; le client déclare ${client.pack.minecraftVersion}.")
            if (config.requireSameModpack && (reference.pack.id != client.pack.id || reference.pack.version != client.pack.version)) add("Le modpack ${reference.pack.id} ${reference.pack.version} est requis.")
        } + RuntimeRules.problems(reference, client, config, expectations, nonce)
        val expected = reference.entries.toMutableList()
        val unmatched = client.entries.toMutableList()
        val violations = ArrayList<Violation>()
        val ruleProblems = ArrayList<String>()

        // Un contenu identique s'apparie avant les noms, pour accepter les renommages sans confondre les doublons.
        val remaining = unmatched.iterator()
        while (remaining.hasNext()) {
            val actual = remaining.next()
            val index = expected.indexOfFirst { sameContent(it, actual) }
            if (index >= 0) {
                val target = expected.removeAt(index)
                if (target.path != actual.path) {
                    listOf(target to DeviationKind.MISSING, actual to DeviationKind.ADDED).forEach { (element, deviation) ->
                        val explicit = selectors.filter { it.rule.path != null && it.matches(element, deviation, player) }
                        if (explicit.isNotEmpty() && !allowed(element, deviation, player, ruleProblems, explicit)) violations += Violation(element.category, element.path, deviation)
                    }
                }
                remaining.remove()
            }
        }
        unmatched.forEach { actual ->
            val index = expected.indexOfFirst { it.category == actual.category && ((actual.modId != null && actual.modId == it.modId) || actual.path == it.path) }
            val deviation = if (index >= 0) { expected.removeAt(index); DeviationKind.MODIFIED } else DeviationKind.ADDED
            if (!allowed(actual, deviation, player, ruleProblems)) violations += Violation(actual.category, actual.path, deviation)
        }
        expected.forEach { target -> if (!allowed(target, DeviationKind.MISSING, player, ruleProblems)) violations += Violation(target.category, target.path, DeviationKind.MISSING) }
        return ValidationResult(violations, versionProblems + ruleProblems.distinct())
    }

    private fun sameContent(expected: InventoryEntry, actual: InventoryEntry): Boolean = expected.category == actual.category && expected.kind == actual.kind &&
        expected.sha256 == actual.sha256 && expected.size == actual.size && expected.modId == actual.modId && expected.modVersion == actual.modVersion

    private fun allowed(element: InventoryEntry, deviation: DeviationKind, player: UUID, problems: MutableList<String>, candidates: List<Selector> = selectors): Boolean {
        val matching = candidates.filter { it.matches(element, deviation, player) }
        if (matching.isEmpty()) return config.policies.getValue(element.category).allows(deviation)
        val priority = matching.maxOf { it.priority() }
        val verdicts = matching.filter { it.priority() == priority }.map { it.rule.action != RuleAction.DENY }.toSet()
        if (verdicts.size != 1) {
            problems += "Règles contradictoires pour ${element.path}."
            return false
        }
        return verdicts.single()
    }

    private fun glob(pattern: String): Regex {
        val result = StringBuilder("^")
        var index = 0
        while (index < pattern.length) {
            when {
                pattern.startsWith("**/", index) -> { result.append("(?:.*/)?"); index += 3 }
                pattern.startsWith("**", index) -> { result.append(".*"); index += 2 }
                pattern[index] == '*' -> { result.append("[^/]*"); index++ }
                else -> { result.append(Regex.escape(pattern[index].toString())); index++ }
            }
        }
        return Regex(result.append('$').toString())
    }

    private class Selector(val rule: ComplianceRule, val regex: Regex?) {
        fun matches(element: InventoryEntry, deviation: DeviationKind, player: UUID): Boolean = rule.category == element.category && deviation in rule.deviations &&
            (rule.playerUuid == null || rule.playerUuid.equals(player.toString(), ignoreCase = true)) && (regex == null || regex.matches(element.path)) && (rule.modId == null || rule.modId == element.modId)

        fun priority(): Int {
            val specificity = when {
                rule.modId != null || (rule.path != null && '*' !in rule.path) -> 10_000
                rule.path != null -> 100 + rule.path.count { it != '*' }
                else -> 0
            }
            return specificity * 2 + if (rule.playerUuid != null) 1 else 0
        }
    }
}
