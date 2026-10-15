package fr.moulou.integrity.bridge

import fr.moulou.integrity.client.ClientBridge
import net.minecraft.client.Minecraft
import net.minecraft.client.gui.components.toasts.ToastManager

/** Le pont client de Minecraft 26.2. */
class GameClientBridge : ClientBridge {
    override fun toastManager(client: Minecraft): ToastManager = client.gui.toastManager()
}
