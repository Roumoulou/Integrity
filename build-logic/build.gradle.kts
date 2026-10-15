plugins { `kotlin-dsl` }

dependencies {
    implementation(pluginArtifact(mc.plugins.fabric.loom))
    implementation(pluginArtifact(libs.plugins.kotlin.jvm))
    implementation(pluginArtifact(libs.plugins.kotlin.serialization))
    implementation(libs.proguard)
    implementation(pluginArtifact(libs.plugins.mod.publish))
}

fun pluginArtifact(plugin: Provider<PluginDependency>): Provider<String> =
    plugin.map { "${it.pluginId}:${it.pluginId}.gradle.plugin:${it.version}" }
