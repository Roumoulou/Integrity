package fr.moulou.integrity.server

import net.minecraft.network.protocol.Packet
import net.minecraft.server.network.ConfigurationTask
import java.util.function.Consumer

class VerificationTask(private val send: (Consumer<Packet<*>>) -> Unit) : ConfigurationTask {
    companion object { val TYPE = ConfigurationTask.Type("integrity:verify") }
    override fun type(): ConfigurationTask.Type = TYPE
    override fun start(sender: Consumer<Packet<*>>) = send(sender)
}
