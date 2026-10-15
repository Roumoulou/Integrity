plugins { id("integrity.loom-module") }

dependencies {
    minecraft(mc263.minecraft)
    implementation(mc.fabric.loader)
    implementation(mc263.fabric.api)
    implementation(mc.fabric.language.kotlin)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.storify)
}

tasks.processResources { enabled = false }
tasks.jar { enabled = false }
tasks.named("sourcesJar") { enabled = false }

val testMC = sourceSets.create("testMC")
val gametest = sourceSets.create("gametest")
configurations {
    named("testImplementation") { setExtendsFrom(emptyList()) }
    named("testRuntimeOnly") { setExtendsFrom(emptyList()) }
}
dependencies {
    testImplementation(libs.junit.jupiter)
    testImplementation(libs.kotlinx.serialization.json)
    testImplementation(libs.storify)
    testRuntimeOnly(libs.junit.platform.launcher)
    "testMCImplementation"(libs.junit.jupiter)
    "gametestCompileOnly"(mc263.fabric.gametest.api)
}

sourceSets.named("test") {
    val isMinecraft: (File) -> Boolean = { it.name.startsWith("minecraft-") }
    compileClasspath = configurations["testCompileClasspath"].filter { !isMinecraft(it) } + sourceSets["main"].output
    runtimeClasspath = output + sourceSets["main"].output + configurations["testRuntimeClasspath"].filter { !isMinecraft(it) }
}
testMC.compileClasspath += sourceSets["main"].compileClasspath + sourceSets["client"].compileClasspath + sourceSets["main"].output + sourceSets["client"].output
gametest.compileClasspath += sourceSets["main"].compileClasspath + sourceSets["main"].output

tasks.test {
    useJUnitPlatform()
    workingDir = layout.buildDirectory.dir("run/test").get().asFile
    doFirst { workingDir.mkdirs() }
    testLogging { events("failed"); exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL }
}

fun ConfigurationPublications.classesOf(vararg sets: SourceSet) {
    sets.forEach { set -> set.output.classesDirs.files.forEach { directory -> artifact(directory) { builtBy(set.output) } } }
}
configurations.consumable("commonClasses") { outgoing.classesOf(sourceSets["main"], sourceSets["client"]) }
configurations.consumable("testMCElements") { outgoing.classesOf(testMC) }
val gametestJar = tasks.register<Jar>("gametestJar") { archiveClassifier = "gametest"; from(gametest.output) }
configurations.consumable("gametestElements") { outgoing.artifact(gametestJar) }
