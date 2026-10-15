import fr.moulou.outfitter.tasks.DeployTask
import fr.moulou.outfitter.tasks.SyncModsTask
import fr.moulou.integrity.buildlogic.configureDevTesting
import fr.moulou.integrity.buildlogic.configureMdtkTesting
import fr.moulou.integrity.buildlogic.mdtkPublication

plugins {
    id("integrity.game-version")
    id("fr.moulou.outfitter")
}

dependencies {
    minecraft(mc263.minecraft)
    implementation(mc263.fabric.api)
}

val mdtkPublication = mdtkPublication(mc263.versions.minecraft.get())
val mdtkInstances = layout.settingsDirectory.dir("../../05-instances/26.3/mdtk")

outfitter {
    minecraftVersion = mc263.versions.minecraft
    environments {
        register("client") { client(); profile = "vanilla" }
        register("server") { server(); profile = "dev" }
        register("clientModded") { client(); profile = "vanilla"; modded = true }
        register("serverModded") { server(); profile = "dev"; modded = true }
    }
    deployTargets {
        register("serverPur") { directory = layout.settingsDirectory.dir("../../05-instances/server-pur-26.3/server"); profile = "dev" }
        register("prism") { directory = referenceInstance; expectedMods.set(listOf("fabric-api", "fabric-language-kotlin")) }
        register("clientMdtk") { directory = mdtkInstances.dir("client/minecraft"); expectedMods.set(listOf("fabric-api", "fabric-language-kotlin")) }
        register("serverMdtk") {
            directory = mdtkInstances.dir("server")
            source = mdtkPublication.dir("MDTK-CORE-SERVER_${providers.gradleProperty("mdtk_version").get()}+${mc263.versions.minecraft.get()}")
            expectedMods.set(listOf("fabric-api", "fabric-language-kotlin"))
        }
    }
    gameTests { register("gameTest") { directory = layout.buildDirectory.dir("run/gameTest"); filter = "integrity-gametest:*" } }
}

dependencies {
    "serverPurBundle"(mc263.fabric.api)
    "serverPurBundle"(mc.fabric.language.kotlin)
}

// Un déploiement utilise le même JAR obfusqué que la distribution.
tasks.withType<DeployTask>().configureEach {
    dependsOn("obfuscateJar")
    jar.set(layout.buildDirectory.file("libs/integrity-${providers.gradleProperty("mod_version").get()}+${mc263.versions.minecraft.get()}.jar"))
}

configureMdtkTesting(mc263.versions.minecraft.get(), mdtkPublication, mdtkInstances)
configureDevTesting(mc263.versions.minecraft.get(), mdtkPublication)

// Les publications client et serveur sont distinctes ; un mod serveur déclaré '*' ne doit pas devenir un mod du client.
afterEvaluate {
    tasks.named<SyncModsTask>("syncClientModdedMods") { instanceDirectory.set(mdtkPublication.dir("MDTK-CORE-CLIENT_${providers.gradleProperty("mdtk_version").get()}+${mc263.versions.minecraft.get()}/minecraft")) }
    tasks.named<SyncModsTask>("syncServerModdedMods") { instanceDirectory.set(mdtkPublication.dir("MDTK-CORE-SERVER_${providers.gradleProperty("mdtk_version").get()}+${mc263.versions.minecraft.get()}/server")) }
}
