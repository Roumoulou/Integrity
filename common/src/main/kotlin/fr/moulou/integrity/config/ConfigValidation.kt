package fr.moulou.integrity.config

import fr.moulou.integrity.model.Category
import fr.moulou.integrity.model.PackIdentity
import fr.moulou.storify.validation.ValidationContext
import fr.moulou.storify.validation.Validator
import java.util.UUID

object ConfigValidation : Validator<ServerConfig> {
    private val referenceId = Regex("[a-zA-Z0-9][a-zA-Z0-9._-]{0,63}")

    override fun validate(data: ServerConfig, ctx: ValidationContext) {
        problems(data).forEach { (path, message) -> ctx.check(false, path, message) }
    }

    fun problems(data: ServerConfig): List<Pair<String, String>> = buildList {
        if (data.referenceInstance.isBlank()) add("referenceInstance" to "must not be blank")
        if (data.activeReference.isNotEmpty() && !referenceId.matches(data.activeReference)) add("activeReference" to "invalid reference identifier")
        if (data.timeoutSeconds !in 5..600) add("timeoutSeconds" to "must be between 5 and 600")
        if (data.recheckIntervalSeconds !in 0..86_400 || data.recheckIntervalSeconds in 1..29) add("recheckIntervalSeconds" to "must be zero or between 30 and 86400")
        if (data.maxElements !in 1..10_000) add("maxElements" to "must be between 1 and 10000")
        if (data.maxScanBytes !in 1..1_099_511_627_776) add("maxScanBytes" to "must be between 1 byte and 1 TiB")
        if (data.policies.keys != Category.entries.toSet()) add("policies" to "all three categories are required")
        if (data.rules.size > 1000) add("rules" to "at most 1000 rules are supported")
        data.rules.forEachIndexed { index, rule ->
            val prefix = "rules[$index]"
            if (rule.path != null && rule.modId != null) add(prefix to "choose path or modId, not both")
            if (rule.path != null && !safePattern(rule.path)) add("$prefix.path" to "use a relative path with forward slashes and * or ** wildcards")
            if (rule.modId != null && (rule.category != Category.MODS || !Regex("[a-z][a-z0-9_-]{1,63}").matches(rule.modId))) add("$prefix.modId" to "requires a valid Fabric mod id in mods")
            if (rule.playerUuid != null && runCatching { UUID.fromString(rule.playerUuid).toString() == rule.playerUuid.lowercase() }.getOrDefault(false).not()) add("$prefix.playerUuid" to "invalid UUID")
            if (rule.deviations.isEmpty()) add("$prefix.deviations" to "must not be empty")
            data.rules.take(index).filter { it.category == rule.category && it.path == rule.path && it.modId == rule.modId && it.playerUuid == rule.playerUuid }.forEach { previous ->
                if ((previous.action == RuleAction.DENY) != (rule.action == RuleAction.DENY) && previous.deviations.intersect(rule.deviations).isNotEmpty()) add(prefix to "conflicting rules for the same selector")
            }
        }
    }

    fun validReferenceId(id: String): Boolean = referenceId.matches(id)

    private fun safePattern(path: String): Boolean = path.isNotBlank() && path.length <= 512 && !path.startsWith('/') && '\\' !in path && ':' !in path &&
        path.split('/').none { it == ".." || it == "." || it.isEmpty() } && path.none { it.isISOControl() || it in "[]{}?" }
}

object PackIdentityValidator : Validator<PackIdentity> {
    override fun validate(data: PackIdentity, ctx: ValidationContext) {
        listOf("id" to data.id, "version" to data.version, "minecraftVersion" to data.minecraftVersion).forEach { (name, value) ->
            ctx.check(value.isNotBlank() && value.length <= 128 && value.none(Char::isISOControl), name, "must be a nonblank identifier of at most 128 characters", value)
        }
    }
}
