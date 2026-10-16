package fr.moulou.integrity

import fr.moulou.integrity.inventory.RuntimeInspector
import fr.moulou.integrity.model.PreLaunchSnapshot
import net.fabricmc.loader.api.entrypoint.PreLaunchEntrypoint

/**
 * Prend l'instantané des mods chargés avant les entrypoints `main` et `client` des autres mods, dont les `preLaunch` passent dans un ordre qui
 * n'est pas le nôtre. Un échec laisse l'instantané à `null`, que le serveur refuse, et ne fait jamais échouer le lancement.
 */
class IntegrityPreLaunch : PreLaunchEntrypoint {
    companion object {
        /** L'instantané du lancement, ou `null` quand l'entrypoint n'a pas pu le prendre. */
        @Volatile
        var snapshot: PreLaunchSnapshot? = null
            private set
    }

    override fun onPreLaunch() {
        snapshot = try {
            RuntimeInspector.snapshot().also { IntegrityMod.LOGGER.info("Pre-launch snapshot: {} mods", it.mods.size) }
        } catch (error: Throwable) {
            IntegrityMod.LOGGER.warn("Cannot take the pre-launch snapshot; the server will refuse this client", error)
            null
        }
    }
}
