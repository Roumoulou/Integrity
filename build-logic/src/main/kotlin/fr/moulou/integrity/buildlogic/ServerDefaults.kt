package fr.moulou.integrity.buildlogic

/** Les valeurs par défaut du serveur que les tâches de préparation recopient dans une configuration complète ; les mêmes que `ServerConfig`. */
object ServerDefaults {
    val REJECTED_LAUNCH_OPTIONS: List<String> = listOf("-javaagent:", "-agentlib:", "-agentpath:", "-Xbootclasspath", "-Xrunjdwp", "-Xdebug")
}
