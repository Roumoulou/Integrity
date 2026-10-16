# Environnement de développement

> *Type : doc technique.*
> *Modèle : les règles générales de documents-markdown.md (The Human Readme).*

Vaut pour le travail dans la racine Gradle : les environnements Loom, les essais avec le modpack MDTK, la connexion directe d'un client de test et
l'ajout d'une version de Minecraft. Toutes les commandes partent de la racine Gradle, via PowerShell.

## 1. Les environnements Loom

Outfitter crée quatre environnements par module, chacun dans son dossier `run` ; le mod y est chargé depuis les classes compilées, et Fabric API, Fabric
Language Kotlin et Integrity viennent du classpath, sans JAR dans `mods`.

| Tâche, sous `:mc-26.3:` par exemple | Dossier | Contenu |
|---|---|---|
| `runClient` | `run\client` | client vanilla et Integrity |
| `runServer` | `run\server` | serveur vanilla et Integrity |
| `runClientModded` | `run\client-modded` | client MDTK et Integrity |
| `runServerModded` | `run\server-modded` | serveur MDTK et Integrity |

| Module | Minecraft | Serveur vanilla | Serveur MDTK |
|---|---|---|---|
| `mc-26.1` | `26.1.2` | `127.0.0.1:25561` | publication MDTK absente du kit |
| `mc-26.2` | `26.2` | `127.0.0.1:25562` | `127.0.0.1:25622` |
| `mc-26.3` | `26.3` | `127.0.0.1:25563` | `127.0.0.1:25623` |

Les serveurs de développement sont hors ligne et limités à `127.0.0.1`. Recharger le projet Gradle dans IntelliJ après un changement du build pour voir
les configurations Minecraft Client, Server, Client Modded et Server Modded.

Les chemins du poste vivent dans `machine.properties`, ignoré par Git, avec des `/` :

```properties
outfitter.profiles_dir=C:/MC-Kit/00-favorites
outfitter.maps_dir=C:/MC-Kit/01-maps
integrity.mdtk.releases_dir=C:/MC-Kit/08-modpacks
outfitter.username=Roumoulou
```

`mdtk_version`, dans `gradle.properties`, choisit la publication MDTK ; `outfitter.maps` nomme la map copiée dans les serveurs, une petite suffit.

## 2. Préparer les couples et leur référence

`prepareDevTests` prépare le couple vanilla d'un module et fige sa première copie client approuvée sous
`run\server\integrity\reference-client\Integrity-DEV-<minecraft>\.minecraft` ; `prepareMdtkDevTests` fait de même pour le couple MDTK, sous
`run\server-modded\integrity\reference-client\MDTK-DEV-<minecraft>\.minecraft`. Une copie approuvée présente n'est jamais remplacée. La configuration
stricte initiale et le port du serveur sont posés ; la génération et l'activation du manifeste restent des commandes de la console serveur. Cette
configuration porte `allowDevelopmentClients: true`, parce qu'un client Loom charge Integrity, Fabric API et FLK depuis le classpath et des jars du jeu
qui ne sont pas ceux de Mojang ; une configuration posée avant ce réglage reçoit la clé à la main, la préparation ne réécrit jamais un fichier présent.

```powershell
.\gradlew.bat :mc-26.3:prepareDevTests --console=plain
.\gradlew.bat :mc-26.3:prepareMdtkDevTests :mc-26.2:prepareMdtkDevTests --console=plain
```

Le contenu MDTK (mods, resourcepacks, shaderpacks, réglages) est copié depuis la publication figée du kit, en lecture seule, à la première pose ; les
publications client et serveur sont distinctes, et un mod serveur n'est jamais copié dans le client. La référence de développement contient les JAR
physiques du client Loom, donc pas les dépendances fournies au classpath : elle diffère normalement de la référence d'une installation distribuée.

## 3. Les essais sans client graphique

`testMdtkDev` charge le vrai JAR obfusqué et vérifie, sur les dossiers Loom, la conformité du client MDTK préparé puis les refus (mod ajouté, mod retiré,
resourcepack ajouté ou modifié, shaderpack ajouté, exception de catégorie) dans des copies temporaires. Il ne lance aucun client et n'exécute pas le
code des mods ajoutés. Comme les tests de distribution, il nomme le JAR obfusqué par ses noms d'origine, traduits par le fichier de correspondances de
ProGuard que le build lui donne (`integrity.distributionMapping`). Ses rapports sont sous `<module>\build\reports\tests\testMdtkDev` ; il n'est jamais considéré à jour.

```powershell
.\gradlew.bat :mc-26.3:testMdtkDev :mc-26.2:testMdtkDev --console=plain
```

Les fichiers d'essai (mods Xray Orevision et resourcepacks Faithful 32x) sont épinglés par le catalogue `..\..\02-data\00-test\mdtk-fixtures.json` du
classeur, hors dépôt, et téléchargés par `downloadMdtkFixtures` après vérification de leur taille et de leur SHA-512. Un fichier altéré est refusé, jamais
remplacé.

## 4. Un client de test sans clic

`-Pintegrity.dev.quickPlay=<adresse>` fait rejoindre ce serveur au client dès son lancement, par l'argument `--quickPlayMultiplayer` de Minecraft :

```powershell
.\gradlew.bat :mc-26.3:runClient '-Pintegrity.dev.quickPlay=127.0.0.1:25563' --console=plain
```

Le verdict se lit dans la console du serveur : `verified against reference <id>` puis `joined the game`, ou `rejected: ... violations=[...]` suivi de
`lost connection: Integrity : installation non conforme.` Le client, lui, reste au menu après un refus ; son processus se ferme à la main.

Deux propriétés jouent un refus du relevé d'exécution sans toucher au code ni aux dossiers : `-Pintegrity.dev.addMods=<jar>` fait charger ce mod par
Loader hors de `mods` (la propriété système `fabric.addMods`, que le serveur refuse toujours), et `-Pintegrity.dev.declaredVersion=<version>` fait
déclarer cette version d'Integrity au client, pour le refus d'un client trop ancien. La seconde n'a d'effet que dans un environnement de développement.

```powershell
.\gradlew.bat :mc-26.3:runClient '-Pintegrity.dev.quickPlay=127.0.0.1:25563' '-Pintegrity.dev.addMods=S:\16\_V\Integrity\02-data\00-test\26.3\mods\xray-orevision-3.0.1.jar' --console=plain
.\gradlew.bat :mc-26.3:runClient '-Pintegrity.dev.quickPlay=127.0.0.1:25563' '-Pintegrity.dev.declaredVersion=1.0.1+26.3' --console=plain
```

Pour piloter la console du serveur depuis un script, lancer `runServer` avec son entrée standard branchée sur un fichier suivi en continu, puis y ajouter
les commandes au fil de l'essai, `stop` compris. Depuis Git Bash :

```bash
: > server.cmd
tail -f server.cmd | ./gradlew :mc-26.3:runServer --console=plain > server.log 2>&1 &
echo "integrity status" >> server.cmd
```

Pour un client volontairement modifié dans le couple MDTK, ajouter `-Pintegrity.dev.preserveClientChanges=true` : les synchronisations du client moddé
sont suspendues et ses ajouts, retraits et fichiers d'identité survivent au lancement. Sans cette option, Outfitter retire un JAR ajouté et recopie un JAR
manquant. La copie approuvée et le manifeste actif ne bougent pas pendant ces essais ; les fichiers mis de côté se posent dans `<module>\run\holding`,
hors des catégories contrôlées.

## 5. Les cibles externes

`prepareMdtkTests` installe les copies MDTK sous `..\..\05-instances\<minecraft>\mdtk` du classeur et y déploie le JAR distribué ; `testMdtk` vérifie ces
copies comme `testMdtkDev`. Les cibles de déploiement `clientMdtk`, `serverMdtk`, `serverPur` et `prism` utilisent le JAR obfuscé. Un déploiement Prism
demande sa cible dans `outfitter.reference_instance_dir`. Ces copies servent à la phase avec le JAR distribué, après les essais dans Loom.

## 6. Ajouter une version de Minecraft

1. Un catalogue `gradle\mc-<lignée>.versions.toml` et son alias dans `settings.gradle.kts`.
2. Un module `mc-<lignée>` sur le modèle de `mc-26.3\build.gradle.kts`.
3. Le pont client du module, `src\client\kotlin\fr\moulou\integrity\bridge\GameClientBridge.kt`, qui implémente `fr.moulou.integrity.client.ClientBridge`
   pour ce que le jeu nomme autrement d'une version à l'autre, et sa déclaration `src\main\resources\META-INF\services\fr.moulou.integrity.client.ClientBridge`.
4. `checkCommonCompatibility` dit alors ce que le code partagé nomme et que ce jeu n'a pas ; ce qui manque passe par le pont.

`common` compile contre la dernière release servie. Loom, Fabric Loader et Fabric Language Kotlin sont dans `gradle\minecraft.versions.toml`, le socle
(Kotlin, Java, tests, Storify, Outfitter, ProGuard, mod-publish-plugin) dans `gradle\libs.versions.toml`.

## 7. Publier une version

mod-publish-plugin fait tout d'une commande : la racine (`integrity.publication`) crée la release GitHub de la version sur le tag `v<version>`, et chaque
module de version y joint son JAR obfusqué et ses correspondances ProGuard, puis envoie le JAR sur Modrinth et CurseForge, une version par Minecraft,
Fabric API et Fabric Language Kotlin déclarées requises. Le JAR de sources ne part pas : le tag donne les sources, et le JAR distribué reste obfusqué.

Le JAR distribué sort de `packReleaseJar`, qui réempaquète la sortie de ProGuard à dates fixes et en ordre trié : ProGuard date chaque entrée à l'heure du
build, et sans ce réempaquetage deux builds du même tag donnent deux fichiers différents. La reproductibilité est ce que Modrinth exige d'un JAR
obfusqué ; le workflow `Verify release` la contrôle à chaque release publiée, en reconstruisant le tag et en comparant avec les fichiers joints, puis
attache `SHA256SUMS`. Le workflow `Build` construit et teste chaque poussée sur `master`. Ni l'un ni l'autre ne publie : les jetons de publication restent
sur le poste.

| Clé de `gradle.properties` | Sens |
|---|---|
| `modrinth_project_id` | l'identifiant à huit caractères du projet Modrinth, pas son adresse |
| `curseforge_project_id` | l'identifiant numérique du projet CurseForge |
| `curseforge_project_slug` | le slug de l'adresse du projet CurseForge |
| `github_repository` | le dépôt de la release, `owner/repo` |

Les jetons `MODRINTH_TOKEN`, `CURSEFORGE_TOKEN` et `GITHUB_TOKEN` ne vivent jamais dans un fichier : `dev-secrets.ps1 -Apply` les pose dans le terminal qui
publie, et lui seul. Le changelog de la version est `..\..\04-releases\<version>\changelog - a publier.md` du classeur, le même texte pour les trois
plateformes.

Sans `-Ppublish_live=true`, `publishMods` est un essai à blanc : rien ne part, le plugin écrit sous `build\publishMods\` de la racine et de chaque module
ce qu'il aurait envoyé, et `checkPublication` dit ce qui manquerait à un envoi réel. En envoi réel, `checkPublication` refuse tout avant le premier envoi
s'il manque le changelog, un identifiant, un jeton, si l'arbre n'est pas propre ou si le tag `v<version>` n'est pas sur `HEAD` et sur `origin` : le tag
annoté se pose et se pousse avant, sinon GitHub créerait un tag léger sur une tête de `master` qui a pu bouger. Chaque envoi attend aussi `check` du
module.

```powershell
.\gradlew.bat publishMods --console=plain
dev-secrets.ps1 -Apply MODRINTH_TOKEN, CURSEFORGE_TOKEN, GITHUB_TOKEN
.\gradlew.bat publishMods -Ppublish_live=true --console=plain
```

`-Ppublish_type=beta` marque la version bêta sur les deux plateformes et la release GitHub pré-release. Si un envoi échoue en route, relancer le seul
module en échec (`:mc-26.3:publishMods`) ; une release GitHub déjà créée s'exclut par `-x :publishGithub`.

---

*Dernière vérification : 2026-10-16.*
