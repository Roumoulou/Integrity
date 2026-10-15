package fr.moulou.integrity.model

import fr.moulou.integrity.config.DeviationKind

data class Violation(val category: Category, val path: String, val kind: DeviationKind)

data class ValidationResult(val violations: List<Violation> = emptyList(), val problems: List<String> = emptyList()) {
    val accepted: Boolean get() = violations.isEmpty() && problems.isEmpty()

    fun playerMessage(): String {
        val details = problems.take(3) + violations.take(5).map {
            val reason = when (it.kind) {
                DeviationKind.ADDED -> "ajout non autorisé"
                DeviationKind.MISSING -> "élément manquant"
                DeviationKind.MODIFIED -> "contenu modifié"
            }
            "${it.path} : $reason"
        }
        return "Integrity : installation non conforme.\n" + details.joinToString("\n") + if (problems.size + violations.size > details.size) "\nConsulte l'administrateur pour le détail." else ""
    }
}
