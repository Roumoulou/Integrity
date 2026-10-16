# Références et règles d'Integrity

> *Type : doc technique.*
> *Modèle : les règles générales de documents-markdown.md (The Human Readme).*

## 1. L'identité du modpack

Le dossier de jeu du modpack client porte `integrity-modpack.json` :

```json
{
  "id": "mon-modpack",
  "version": "1.2.0",
  "minecraftVersion": "26.3"
}
```

`id` et `version` sont choisis par l'auteur du modpack, indépendamment de Minecraft et d'Integrity. Le client confronte `minecraftVersion` au jeu qu'il
exécute et refuse lui-même une déclaration différente. Sans ce fichier, le client en crée un avec l'identité par défaut `mon-modpack`, que la génération
d'une référence signale : elle doit être remplacée par celle du modpack, sur le client distribué comme dans la copie approuvée.

## 2. Obtenir un manifeste

Le manifeste porte l'identité du modpack et, pour chaque élément de `mods`, `resourcepacks` et `shaderpacks`, son empreinte SHA-256, sa taille et, pour un
mod, son identifiant et sa version Fabric. Les manifestes du serveur vivent dans `config\integrity\references\<id>.json`, un identifiant de `1` à `64`
caractères (lettres, chiffres, points, traits d'union et underscores, le premier alphanumérique). Aucun manifeste n'est jamais écrasé : une nouvelle
version du modpack prend un nouvel identifiant.

### 2.1 Exporter depuis le client approuvé

Sur l'installation à approuver, dans le jeu : `/integrityclient export mon-pack-001`. Le manifeste est écrit dans `config\integrity\export\mon-pack-001.json`
du dossier de jeu, avec l'identité du fichier `integrity-modpack.json`, qui doit exister. Copier ce fichier dans `config\integrity\references\` du serveur.
Il inclut les JAR installés dans le client, Integrity, Fabric API et Fabric Language Kotlin compris.

### 2.2 Générer depuis une copie sur le serveur

Copier la racine du modpack client, avec `.minecraft` et ses dossiers `mods`, `resourcepacks`, `shaderpacks` et le fichier d'identité, dans le dossier du
serveur, par exemple sous `integrity\reference-client\MON-PACK`. Les logs, mondes, caches et préférences sont inutiles. Les mods de cette copie ne sont
jamais exécutés par le serveur.

Le premier démarrage crée `config\integrity\server.json5`. Y régler `referenceInstance`, relatif au dossier de jeu du serveur ou absolu, un étage au-dessus
de `.minecraft`, avec des `/` (dans JSON5, un `\` s'échappe) :

```json5
referenceInstance: "integrity/reference-client/MON-PACK"
```

Puis, depuis la console du serveur :

1. `integrity reload`.
2. `integrity reference generate mon-pack-001`, qui calcule en arrière-plan et prévient si l'identité est celle par défaut.
3. `integrity reference activate mon-pack-001`.
4. `integrity status`.

Dans le jeu, les commandes portent un `/` et demandent la permission administrateur des commandes Minecraft. Sans référence active, les joueurs sont
refusés ; la console reste disponible.

### 2.3 Activer et comparer

L'activation charge le manifeste, vérifie son Minecraft contre celui du serveur, persiste son identifiant dans `activeReference` et revérifie les joueurs
connectés. Modifier la copie ou le client approuvé n'approuve rien : la référence active est l'instantané chargé.

`integrity reference diff mon-pack-001 mon-pack-002` montre ce qui change entre deux manifestes : les éléments ajoutés (`+`), retirés (`-`), modifiés
(`~`, avec l'ancienne et la nouvelle version d'un mod) et renommés (`>`), et les changements d'identité. Un contenu identique sous un autre nom est un
renommage, pas une modification. Sauvegarder tout `config\integrity`.

## 3. Politiques de catégorie

Chaque catégorie `mods`, `resourcepacks`, `shaderpacks` possède quatre booléens :

| Clé | Sens |
|---|---|
| `ignore` | autoriser tous les écarts de la catégorie |
| `allowAdded` | autoriser les éléments supplémentaires |
| `allowMissing` | autoriser l'absence d'éléments attendus |
| `allowModified` | autoriser le changement de contenu d'un élément attendu |

Tous valent `false` par défaut. Une catégorie ignorée sans règle explicite n'est pas scannée par le client ; avec une règle explicite, elle l'est. Le
manifeste garde toutes les catégories, pour resserrer les règles sans recalcul.

Pour laisser chacun choisir ses shaders, dans `policies` :

```json5
shaderpacks: {
  ignore: true,
  allowAdded: false,
  allowMissing: false,
  allowModified: false
}
```

Les autres catégories conservent leurs règles. Après l'édition, `integrity reload`.

## 4. Règles explicites

Une règle sélectionne une catégorie, un chemin ou un identifiant de mod, et peut se limiter à un `playerUuid`. `path` et `modId` s'excluent. Le chemin est
relatif au dossier de jeu, commence par sa catégorie et utilise `/` ; il sélectionne un élément entier, comme `resourcepacks/MonPack`, jamais un fichier
interne d'un resourcepack en dossier.

| Action | Effet |
|---|---|
| `ALLOW` | autoriser l'écart |
| `DENY` | refuser l'écart |
| `IGNORE` | ignorer l'écart, donc l'autoriser |

Les écarts sont `ADDED`, `MISSING` et `MODIFIED`. Sans `deviations`, les trois sont sélectionnés ; sans `playerUuid`, tous les joueurs.

Un ajout précis pour un joueur, dans le tableau `rules` :

```json5
{
  category: "mods",
  modId: "example_mod",
  playerUuid: "00000000-0000-0000-0000-000000000001",
  action: "ALLOW",
  deviations: ["ADDED"]
}
```

Cette règle ne vaut que pour ce joueur et n'autorise pas la modification d'un mod attendu. Un ajout de resourcepacks par motif :

```json5
{
  category: "resourcepacks",
  path: "resourcepacks/approved-*.zip",
  action: "ALLOW",
  deviations: ["ADDED"]
}
```

Un motif accepte n'importe quel contenu sous les noms correspondants ; pour exiger un contenu précis, l'inclure dans la référence et garder la politique
stricte. `*` ne traverse pas `/` ; `**` traverse les segments ; `**/` peut correspondre à zéro segment.

## 5. Priorité et rechargement

Un chemin exact ou un identifiant de mod prime sur un motif, un motif sur la catégorie, et un motif est d'autant plus précis qu'il porte de caractères
littéraux. À spécificité égale, la règle du joueur prime sur la règle générale. Deux règles contradictoires de même priorité refusent le joueur, quel que
soit leur ordre ; les contradictions évidentes sur un même sélecteur sont rejetées dès le chargement.

Un rechargement invalide (clé inconnue, valeur hors bornes, référence introuvable) conserve l'état actif et le signale. Un rechargement valide ou une
activation invalide les contrôles en cours et revérifie les joueurs en partie ; un joueur dont la connexion était en cours se reconnecte.

Les versions restent requises par défaut. Désactiver `requireSameMinecraft` ne rend pas deux protocoles Minecraft compatibles : Integrity livre un JAR par
version et n'ajoute aucune passerelle entre jeux.

## 6. Solo et LAN

Le serveur intégré n'applique pas les contrôles par défaut. Pour contrôler aussi le solo ou le LAN, régler `enforceSingleplayer` sur `true` et préparer une
référence active dans le dossier de jeu du serveur intégré avant la connexion.

## 7. Le relevé d'exécution

Le rapport du client porte, à côté de l'inventaire, ce que Fabric Loader a chargé et comment le jeu a été lancé. Le serveur en tire ses refus :

| Ce que le client déclare | Ce que le serveur refuse |
|---|---|
| les mods chargés, leur origine et leur chemin | un mod chargé hors de `mods`, un JAR de `mods` qui n'est pas chargé ou dont l'identifiant ou la version diffèrent, une liste de mods qui a changé depuis le lancement |
| les propriétés `fabric.*` et les arguments de la JVM | `fabric.addMods`, `fabric.modsFolder`, `fabric.skipMcProvider`, `fabric.systemLibraries`, `fabric.debug.*`, toute propriété `fabric.*` inconnue, et les arguments qui commencent par un préfixe de `rejectedLaunchOptions` |
| les resourcepacks sélectionnés et leur dossier | un pack sélectionné depuis un fichier absent de `resourcepacks`, un dossier de packs déplacé (`--resourcePackDir`) |
| le jar du jeu et son SHA-1 | un jar qui n'est pas le `client.jar` que Mojang publie pour ce Minecraft |
| les versions d'Integrity, du protocole, de Loader et du jeu | toute version d'Integrity autre que celle du serveur, un Minecraft autre que celui de la référence |
| les mixins et les classes des autres mods qui visent `fr.moulou.integrity` | tout mod voisin dont un mixin vise Integrity ou dont une classe nomme son package |
| une empreinte de son JAR Integrity, liée au nonce du contrôle | un JAR qui n'est pas celui du serveur, ou un client qui ne l'authentifie pas |

`allowDevelopmentClients: true` tolère ce qu'un run Loom fait : `fabric.development` et les propriétés que Loom pose, des mods chargés hors de `mods`, des
jars du jeu qui ne sont pas ceux de Mojang, l'absence de JAR à authentifier. Il ne tolère jamais `fabric.addMods`, un agent, un mixin ou une référence
visant Integrity. Un serveur de production le laisse à `false`.

`rejectedLaunchOptions` vaut par défaut `-javaagent:`, `-agentlib:`, `-agentpath:`, `-Xbootclasspath`, `-Xrunjdwp` et `-Xdebug`. Un serveur hors ligne
dont le launcher injecte un agent (authlib-injector) retire `-javaagent:` de la liste.

Un mod `environment: server` livré dans `mods` du client n'est pas chargé par Loader : le serveur le refuse comme présent mais non chargé, et le modpack
doit cesser de le livrer au client.

Les contrôles périodiques tirent leur échéance au sort dans `recheckJitterSeconds` autour de `recheckIntervalSeconds`, et un contrôle suit chaque
réapparition et chaque changement de dimension tant que les contrôles périodiques sont actifs.

`integrity history <joueur>` lit les derniers contrôles d'un joueur, par son nom s'il est connecté, sinon par son UUID : verdict, résumé, versions vues et
canaux annoncés. L'historique vit sous `config\integrity\history\<uuid>.json`, borné aux `50` derniers contrôles et écrit atomiquement. Les canaux sont
notés, jamais refusés.

---

*Dernière vérification : 2026-10-16.*
