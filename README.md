# Integrity

Mod Fabric client et serveur. Avant d'admettre un joueur, le serveur compare l'inventaire de son installation (`mods`, `resourcepacks`,
`shaderpacks`) à une référence approuvée par l'administrateur, puis refait ce contrôle à chaque connexion, périodiquement pendant la partie et sur
commande. L'installation du serveur et le modpack client peuvent contenir des mods différents : la référence décrit le client approuvé, jamais le serveur.

Version `1.0.0`, un JAR par Minecraft : `26.1.2`, `26.2` et `26.3`. Le build et ses tests passent sur les trois versions, et les clients de développement
sont acceptés puis refusés en jeu sur chacune. Integrity vise un serveur entre amis et la triche opportuniste : un client modifié peut mentir sur son
inventaire, le chiffrement et l'obfuscation ne font que compliquer ce contournement.

## Ce qui est contrôlé

Les trois catégories sont strictes par défaut : un ajout, un retrait ou une modification est refusé. Tous les resourcepacks présents sont contrôlés, activés
ou non, en archive comme en dossier, et un fichier interne modifié change l'empreinte du resourcepack. Un simple renommage conserve la conformité, sous
réserve des règles de chemin. Un mod imbriqué est couvert par l'empreinte du JAR qui le contient.

L'identifiant et la version du modpack doivent correspondre à la référence, et la version de Minecraft à celle du serveur. Un contrôle absent, incomplet,
altéré ou tardif refuse l'accès ; un écart détecté pendant la partie déconnecte le joueur.

Les datapacks ne sont pas contrôlés : en multijoueur, ils viennent du monde du serveur, pas de l'installation du joueur. Les réglages des autres mods ne sont
pas contrôlés non plus. Le serveur intégré du solo et du LAN n'impose aucun contrôle, sauf avec `enforceSingleplayer: true` et une référence préparée.

## Installer

Sur le client comme sur le serveur : Fabric Loader, Fabric API, Fabric Language Kotlin et le JAR Integrity du même Minecraft. Le modpack client porte, dans
son dossier de jeu, un fichier `integrity-modpack.json` qui donne l'identité du modpack :

```json
{ "id": "mon-modpack", "version": "1.2.0", "minecraftVersion": "26.3" }
```

`id` et `version` sont choisis par l'auteur du modpack, indépendamment de Minecraft et d'Integrity. Sans ce fichier, le client en crée un avec l'identité
par défaut `mon-modpack`, que le serveur refusera tant que la référence ne porte pas la même.

## Préparer la référence

La référence est un manifeste JSON : l'identité du modpack et, pour chaque élément des trois catégories, son empreinte SHA-256. Deux manières de l'obtenir.

**Depuis le client approuvé.** Dans le jeu, sur l'installation à approuver, la commande client `/integrityclient export mon-pack-001` écrit
`config/integrity/export/mon-pack-001.json`. Copier ce fichier dans `config/integrity/references/` du serveur, puis l'activer (étape 3 ci-dessous).

**Depuis une copie du modpack sur le serveur.** Copier le modpack client approuvé, avec son dossier `.minecraft`, dans le dossier du serveur :

```text
serveur/
├── mods/                                       mods exécutés par le serveur
├── config/integrity/server.json5               règles d'Integrity, créé au premier démarrage
└── integrity/reference-client/MON-PACK/
    └── .minecraft/
        ├── integrity-modpack.json
        ├── mods/
        ├── resourcepacks/
        └── shaderpacks/
```

Les mods de cette copie ne sont jamais exécutés par le serveur. Puis, depuis la console du serveur (dans le jeu, les mêmes commandes avec `/` et la
permission administrateur) :

1. Régler `referenceInstance` sur `integrity/reference-client/MON-PACK` dans `config/integrity/server.json5`, puis `integrity reload`.
2. `integrity reference generate mon-pack-001`. La génération prévient si l'identité est encore celle par défaut.
3. `integrity reference activate mon-pack-001`, puis `integrity status`.

Sans référence active, tous les joueurs sont refusés et la console reste disponible. Une nouvelle version du modpack demande un nouveau manifeste, sous un
nouvel identifiant, et son activation explicite : un manifeste n'est jamais écrasé, et modifier la copie ne change pas la référence active.
`integrity reference diff ancien nouveau` montre ce qui change entre deux manifestes avant d'activer le nouveau. Sauvegarder `config/integrity`.

## Régler les écarts

`config/integrity/server.json5` est validé au chargement : une clé inconnue ou une valeur hors bornes est refusée et les règles actives sont conservées.

| Réglage | Défaut | Sens |
|---|---|---|
| `requireSameMinecraft` | `true` | même Minecraft pour le serveur, la référence et le client |
| `requireSameModpack` | `true` | mêmes `id` et `version` du modpack |
| `enforceSingleplayer` | `false` | appliquer aussi les contrôles au serveur intégré du solo et du LAN |
| `timeoutSeconds` | `120` | délai complet d'un contrôle, de 5 à 600 |
| `recheckIntervalSeconds` | `300` | contrôle périodique en partie ; `0` le désactive, sinon de 30 à 86400 |
| `maxElements` | `10000` | nombre maximal d'éléments dans les trois catégories |
| `maxScanBytes` | `68719476736` | octets lus au plus par contrôle, 64 Gio |
| `policies` | strictes | une politique par catégorie : `ignore`, `allowAdded`, `allowMissing`, `allowModified` |
| `rules` | `[]` | exceptions et interdictions ciblées |

Laisser chacun choisir ses shaders :

```json5
shaderpacks: { ignore: true, allowAdded: false, allowMissing: false, allowModified: false }
```

Autoriser un mod supplémentaire à un seul joueur :

```json5
{ category: "mods", modId: "example_mod", playerUuid: "00000000-0000-0000-0000-000000000001", action: "ALLOW", deviations: ["ADDED"] }
```

Une règle vise une catégorie, un `modId` ou un `path` relatif au dossier de jeu (`resourcepacks/approved-*.zip` ; `*` reste dans un segment, `**` les
traverse). Les actions sont `ALLOW`, `DENY` et `IGNORE`, les écarts `ADDED`, `MISSING` et `MODIFIED`. Le chemin exact ou le `modId` prime sur un motif, le
motif sur la catégorie ; à égalité, la règle du joueur prime, et deux règles contradictoires de même priorité refusent le joueur. Le détail est dans
[docs/user-docs/references-et-regles.md](docs/user-docs/references-et-regles.md).

## Administrer

Ces commandes demandent la permission administrateur des commandes Minecraft. Dans le jeu, ajouter `/`.

| Commande | Action |
|---|---|
| `integrity status` | référence active, contrôles en cours, derniers résultats par joueur |
| `integrity reference list` | manifestes présents |
| `integrity reference generate <id>` | calcul en arrière-plan depuis la copie, sans activation |
| `integrity reference activate <id>` | activation persistée, puis nouveau contrôle des joueurs connectés |
| `integrity reference diff <avant> <après>` | ajouts, retraits, modifications et renommages entre deux manifestes |
| `integrity reload` | rechargement validé des règles et de la référence |
| `integrity check` | nouveau contrôle des joueurs connectés |

Côté client, `/integrityclient export <id>` écrit le manifeste de l'installation locale (voir Préparer la référence). Pendant un contrôle, un toast
affiche l'avancement de la vérification.

## Limites

Le rapport du client est chiffré (X25519 éphémère, HKDF-SHA256, AES-256-GCM) et lié au contrôle et à la connexion : un rapport rejoué, altéré ou destiné à
un autre contrôle est refusé. En mode en ligne, la connexion Minecraft est déjà chiffrée ; cette couche sert surtout à l'anti-rejeu et oblige un
tricheur à comprendre le protocole. Le vrai rempart contre un client modifié est l'obfuscation du JAR, et il n'est pas infranchissable : Integrity ne
fournit pas une attestation indépendante de l'installation. Un fichier modifié puis restauré entre deux contrôles échappe à la détection.

## Construire

Java `25`, Kotlin `2.4.21`, Gradle `9.8.1` (wrapper fourni), Fabric Loom `1.18.2`, Fabric Loader `0.19.5`, Fabric Language Kotlin `1.14.2+kotlin.2.4.21`.
Storify et Outfitter viennent du dépôt Repsy déclaré par le build.

```powershell
.\gradlew.bat build --console=plain
```

| Module | Minecraft | JAR distribué |
|---|---|---|
| `mc-26.1` | `26.1.2` | `mc-26.1\build\libs\integrity-1.0.0+26.1.2.jar` |
| `mc-26.2` | `26.2` | `mc-26.2\build\libs\integrity-1.0.0+26.2.jar` |
| `mc-26.3` | `26.3` | `mc-26.3\build\libs\integrity-1.0.0+26.3.jar` |

`common` porte la logique partagée et compile contre `26.3` ; chaque module de version vérifie que ce code ne nomme que des symboles de son jeu, embarque
Storify, tomlkt et json5, et produit son JAR obfusqué par ProGuard. Le `-dev.jar` sert au développement ; `<module>\build\proguard\mapping.txt` donne les
correspondances des noms. Le build exécute les tests du cœur, quatre tests du JAR obfusqué et deux GameTests par version. Les environnements de
développement, les essais avec le modpack MDTK et la connexion directe d'un client de test sont décrits dans
[docs/technical-docs/environnement-de-developpement.md](docs/technical-docs/environnement-de-developpement.md).

## Le dépôt

Ce dépôt est la racine Gradle d'Integrity : le code, `docs/`, les tags et les releases. Le cahier des charges, la doc d'architecture et le guide des essais
manuels vivent dans le classeur du projet, hors dépôt. Une release GitHub par version publiée, sur le tag `v<version>`, porte les trois JAR et le
changelog de la version ; les mêmes JAR partent sur Modrinth et CurseForge, une version par Minecraft. `publishMods` fait les sept envois d'un coup, à
blanc sans `-Ppublish_live=true` ; la marche à suivre est dans
[docs/technical-docs/environnement-de-developpement.md](docs/technical-docs/environnement-de-developpement.md).

Licence `LGPL-3.0-only`, textes dans `LICENSE` et `LICENSE.GPL`.

*Dernière vérification : 2026-10-14.*
