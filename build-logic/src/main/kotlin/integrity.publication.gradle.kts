import fr.moulou.integrity.buildlogic.PublicationSettings
import me.modmuss50.mpp.PublishModTask

// La racine crée la release GitHub de la version ; chaque module de version y joint son JAR et envoie le sien sur Modrinth et CurseForge.
// `gradlew publishMods` fait donc tout : une release GitHub, trois versions Modrinth, trois fichiers CurseForge ; à blanc sans -Ppublish_live=true.
plugins {
    id("me.modmuss50.mod-publish-plugin")
}

val publication = PublicationSettings(providers, layout)

// La release vise le tag annoté v<version>, posé sur le commit publié et poussé avant l'envoi ; sans lui, GitHub créerait un tag léger sur
// la tête de master, qui peut avoir bougé. checkPublication le vérifie. Elle naît vide ici : les modules y joignent leurs JAR.
publishMods {
    dryRun = !publication.live
    version = publication.modVersion
    displayName = "Integrity ${publication.modVersion}"
    changelog = providers.fileContents(publication.changelog).asText.orElse("")
    type = publication.type

    github {
        accessToken = providers.environmentVariable("GITHUB_TOKEN")
        repository = providers.gradleProperty("github_repository")
        commitish = "master"
        tagName = publication.tag
        allowEmptyFiles = true
    }
}

/* La sortie d'une commande git lancée à la racine du dépôt, vide quand elle échoue ; lue à la demande, jamais à la configuration. */
fun git(vararg arguments: String): Provider<String> = providers.exec {
    commandLine("git", *arguments)
    workingDir = layout.settingsDirectory.asFile
    isIgnoreExitValue = true
}.standardOutput.asText.map(String::trim)

// Le garde-fou de tout envoi : le changelog, les identifiants des projets, les trois jetons (leur présence seule) et l'état Git. À blanc, ce qui
// manque se dit ; en envoi réel, ce qui manque refuse tout, avant le premier envoi.
val checkPublication = tasks.register("checkPublication") {
    group = "publishing"
    description = "Vérifie avant tout envoi le changelog, les identifiants des projets, les jetons et le tag ; en envoi réel, ce qui manque refuse tout"
    val live = publication.live
    val tag = publication.tag
    val missing = publication.missingForLive
    val tokens = PublicationSettings.TOKEN_VARIABLES.joinToString(", ")
    val head = git("rev-parse", "HEAD")
    val taggedCommit = git("rev-parse", "--verify", "--quiet", "$tag^{commit}")
    // Avec un seul motif, ls-remote ne rend que l'objet du tag annoté ; le motif pelé `^{}` donne le commit visé.
    val tagOnOrigin = git("ls-remote", "--tags", "origin", "refs/tags/$tag", "refs/tags/$tag^{}")
    val workingTree = git("status", "--porcelain")
    doLast {
        val problems = buildList {
            addAll(missing)
            if (workingTree.get().isNotEmpty()) add("working tree (not clean)")
            when {
                taggedCommit.get().isEmpty() -> add("tag $tag (missing)")
                taggedCommit.get() != head.get() -> add("tag $tag (not on HEAD)")
            }
            // Un tag annoté s'y lit sur deux lignes, le commit visé sur la seconde ; un tag léger sur une seule, qui est le commit.
            if (!tagOnOrigin.get().contains(head.get())) add("tag $tag (not on origin at HEAD)")
        }
        when {
            problems.isEmpty() -> logger.lifecycle("[publication] ${if (live) "envoi réel" else "à blanc"} : rien ne manque")
            live -> throw GradleException("Publication refused before any upload, missing: ${problems.joinToString(", ")}. The tokens come from dev-secrets.ps1 -Apply $tokens, in this terminal.")
            else -> logger.lifecycle("[publication] à blanc ; un envoi réel refuserait, il manque : ${problems.joinToString(", ")}")
        }
    }
}

tasks.withType<PublishModTask>().configureEach { dependsOn(checkPublication) }
