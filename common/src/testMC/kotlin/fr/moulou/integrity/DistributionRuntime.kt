package fr.moulou.integrity

import fr.moulou.integrity.model.Category
import fr.moulou.integrity.model.GameJar
import fr.moulou.integrity.model.InventoryReport
import fr.moulou.integrity.model.LoadedMod
import fr.moulou.integrity.model.OriginKind
import fr.moulou.integrity.model.PreLaunchSnapshot
import fr.moulou.integrity.model.RuntimeReport
import fr.moulou.integrity.model.SelectedPack
import fr.moulou.integrity.protocol.ControlWire

/** Le relevé d'exécution d'un client de production conforme, bâti sur les attentes que le build a posées dans le JAR distribué. */
object DistributionRuntime {
    /** Une attente du JAR en cours ; absente, le test échoue ici plutôt que par un refus obscur. */
    private fun expectation(property: String): String = requireNotNull(Obfuscated.expectation(property)) { "integrity-game.properties lacks $property" }

    fun runtime(report: InventoryReport): RuntimeReport {
        val mods = listOf(LoadedMod("minecraft", report.pack.minecraftVersion, OriginKind.PATH, listOf("versions/${report.pack.minecraftVersion}.jar")), LoadedMod("java", "25", OriginKind.UNKNOWN)) +
            report.entries.filter { it.category == Category.MODS && it.modId != null }.map { LoadedMod(it.modId!!, it.modVersion!!, OriginKind.PATH, listOf(it.path)) }
        return RuntimeReport(
            integrityVersion = expectation("integrityVersion"),
            protocolVersion = ControlWire.VERSION,
            loaderVersion = "0.19.5",
            minecraftVersion = report.pack.minecraftVersion,
            developmentEnvironment = false,
            gameJars = listOf(GameJar("versions/${report.pack.minecraftVersion}.jar", expectation("clientSha1"), 1)),
            mods = mods,
            selectedPacks = listOf(SelectedPack("vanilla", "BUILT_IN")),
            resourcePackDirectory = "resourcepacks",
            preLaunch = PreLaunchSnapshot(mods),
        )
    }
}

/** Le même inventaire, avec le relevé d'exécution d'un client de production conforme. */
fun InventoryReport.withRuntime(): InventoryReport = copy(runtime = DistributionRuntime.runtime(this))
