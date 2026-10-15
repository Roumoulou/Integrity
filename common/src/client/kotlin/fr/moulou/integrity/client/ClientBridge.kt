package fr.moulou.integrity.client

import net.minecraft.client.Minecraft
import net.minecraft.client.gui.components.toasts.ToastManager
import java.util.ServiceLoader

/**
 * Ce que le client demande au jeu et que les versions servies nomment différemment.
 * Chaque module de version fournit l'implémentation, déclarée dans `META-INF/services` et chargée par `ServiceLoader`.
 */
interface ClientBridge {
    companion object {
        /** L'implémentation du module de version chargé ; un JAR n'en porte qu'une. */
        val INSTANCE: ClientBridge by lazy { ServiceLoader.load(ClientBridge::class.java, ClientBridge::class.java.classLoader).single() }
    }

    /** Le gestionnaire des toasts : `Minecraft.getToastManager()` en 26.1, `Gui.toastManager()` en 26.2 et 26.3. */
    fun toastManager(client: Minecraft): ToastManager
}
