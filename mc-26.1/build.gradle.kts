import fr.moulou.outfitter.tasks.DeployTask
import fr.moulou.outfitter.tasks.SyncModsTask
import fr.moulou.integrity.buildlogic.configureDevTesting
import fr.moulou.integrity.buildlogic.mdtkPublication

plugins {
    id("integrity.game-version")
    id("fr.moulou.outfitter")
}

dependencies {
    minecraft(mc261.minecraft)
    implementation(mc261.fabric.api)
}

val mdtkPublication = mdtkPublication(mc261.versions.minecraft.get())

outfitter {
    minecraftVersion = mc261.versions.minecraft
    environments {
        register("client") { client(); profile = "vanilla" }
        register("server") { server(); profile = "dev" }
        register("clientModded") { client(); profile = "vanilla"; modded = true }
        register("serverModded") { server(); profile = "dev"; modded = true }
    }
    deployTargets {
        register("serverPur") { directory = layout.settingsDirectory.dir("../../05-instances/server-pur-26.1/server"); profile = "dev" }
        register("prism") { directory = referenceInstance; expectedMods.set(listOf("fabric-api", "fabric-language-kotlin")) }
    }
    gameTests { register("gameTest") { directory = layout.buildDirectory.dir("run/gameTest"); filter = "integrity-gametest:*" } }
}

dependencies {
    "serverPurBundle"(mc261.fabric.api)
    "serverPurBundle"(mc.fabric.language.kotlin)
}

// Un déploiement utilise le même JAR obfusqué que la distribution.
tasks.withType<DeployTask>().configureEach {
    dependsOn("obfuscateJar")
    jar.set(layout.buildDirectory.file("libs/integrity-${providers.gradleProperty("mod_version").get()}+${mc261.versions.minecraft.get()}.jar"))
}

configureDevTesting(mc261.versions.minecraft.get(), mdtkPublication)

afterEvaluate {
    tasks.named<SyncModsTask>("syncClientModdedMods") { instanceDirectory.set(mdtkPublication.dir("MDTK-CORE-CLIENT_${providers.gradleProperty("mdtk_version").get()}+${mc261.versions.minecraft.get()}/minecraft")) }
    tasks.named<SyncModsTask>("syncServerModdedMods") { instanceDirectory.set(mdtkPublication.dir("MDTK-CORE-SERVER_${providers.gradleProperty("mdtk_version").get()}+${mc261.versions.minecraft.get()}/server")) }
}
