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
import fr.moulou.integrity.rules.ServerExpectations

/** Un relevé d'exécution cohérent avec un inventaire, tel qu'un client de production conforme le déclarerait, pour les tests du cœur. */
object TestRuntime {
    const val CLIENT_SHA1 = "e877b6a07acd633fb3bb475002175cec036e7b87"
    const val INTEGRITY_VERSION = "1.1.0-TEST+26.3"
    val expectations = ServerExpectations("26.3", CLIENT_SHA1, INTEGRITY_VERSION)

    /** Les mods internes de Loader, puis un mod chargé par JAR de `mods` inventorié. */
    fun mods(report: InventoryReport): List<LoadedMod> = listOf(
        LoadedMod("minecraft", report.pack.minecraftVersion, OriginKind.PATH, listOf("versions/${report.pack.minecraftVersion}.jar")),
        LoadedMod("fabricloader", "0.19.5", OriginKind.PATH, listOf("C:/launcher/libraries/fabric-loader-0.19.5.jar")),
        LoadedMod("java", "25", OriginKind.UNKNOWN),
    ) + report.entries.filter { it.category == Category.MODS && it.modId != null }.map { LoadedMod(it.modId!!, it.modVersion!!, OriginKind.PATH, listOf(it.path)) }

    fun runtime(report: InventoryReport, mods: List<LoadedMod> = mods(report)): RuntimeReport = RuntimeReport(
        integrityVersion = INTEGRITY_VERSION,
        protocolVersion = ControlWire.VERSION,
        loaderVersion = "0.19.5",
        minecraftVersion = report.pack.minecraftVersion,
        developmentEnvironment = false,
        jvmArguments = listOf("-Xmx4G", "-XX:+UseG1GC"),
        gameJars = listOf(GameJar("versions/${report.pack.minecraftVersion}.jar", CLIENT_SHA1, 41_483_720)),
        mods = mods,
        selectedPacks = listOf(SelectedPack("vanilla", "BUILT_IN")) + report.entries.filter { it.category == Category.RESOURCEPACKS }.map { SelectedPack("file/${it.path.removePrefix("resourcepacks/")}", "DEFAULT") },
        resourcePackDirectory = "resourcepacks",
        preLaunch = PreLaunchSnapshot(mods),
    )
}

/** Le même inventaire, avec le relevé d'exécution d'un client de production conforme. */
fun InventoryReport.withRuntime(): InventoryReport = copy(runtime = TestRuntime.runtime(this))
