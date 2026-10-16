# Changelog

Les versions d'Integrity, de la plus récente à la plus ancienne. Chaque JAR porte la version suivie de son Minecraft : `1.0.0+26.3`.

## 1.1.0

Durcissement du client contre le bricoleur qui sait patcher un JAR. Le protocole passe en `2` : un client `1.0.x` est refusé avec un message qui nomme la
version à installer.

- Le rapport porte un relevé d'exécution : les mods que Fabric Loader a chargés et leurs origines, les resourcepacks sélectionnés et leur dossier, les
  arguments de la JVM et les propriétés `fabric.*`, les jars du jeu et leur SHA-1, les versions d'Integrity, du protocole et de Loader, un instantané des
  mods pris avant les entrypoints des autres mods, les mixins et les classes des mods voisins qui visent Integrity, et une empreinte du JAR Integrity liée
  au contrôle.
- Le serveur refuse un mod chargé hors de `mods` (`fabric.addMods`), un dossier de resourcepacks déplacé, un agent Java, un jar du jeu qui n'est pas celui
  de Mojang, un mod voisin dont un mixin ou une classe vise Integrity, un JAR Integrity qui n'est pas le sien, et toute autre version d'Integrity que la
  sienne. Réglages : `allowDevelopmentClients`, `rejectedLaunchOptions`, `recheckJitterSeconds`.
- Les contrôles périodiques tirent leur échéance au sort autour de l'intervalle, et un contrôle suit chaque réapparition et chaque changement de dimension.
- `integrity history <joueur>` : les derniers contrôles d'un joueur, avec les versions vues et les canaux qu'il annonçait, sous `config/integrity/history`.
- Le JAR distribué ne garde en clair que les points d'entrée, le pont de version et les classes de données ; le reste est renommé, et le JAR reste
  reproductible.

## 1.0.1

Version de publication, sans changement du mod lui-même.

- Le JAR distribué est reproductible : un build du même tag, dans n'importe quel environnement, donne le même fichier octet pour octet.
- Les correspondances ProGuard de chaque JAR et les sommes SHA-256 sont jointes à la release GitHub, que GitHub Actions vérifie en reconstruisant le tag.

## 1.0.0

Première version, pour Minecraft `26.1.2`, `26.2` et `26.3`.

- Référence du modpack client : manifeste JSON avec l'identité du modpack et les empreintes SHA-256 des `mods`, `resourcepacks` et `shaderpacks`,
  généré depuis une copie du modpack sur le serveur ou exporté depuis le client approuvé (`/integrityclient export <id>`).
- Contrôle avant l'entrée en jeu, à chaque connexion, périodiquement pendant la partie et sur commande ; refus motivé et déconnexion sur écart.
- Règles : politiques par catégorie, exceptions et interdictions par chemin, motif, identifiant de mod et joueur.
- Rapport chiffré et lié au contrôle (X25519 éphémère, HKDF-SHA256, AES-256-GCM) ; JAR distribué obfusqué par ProGuard.
- Administration : `integrity status`, `reload`, `check`, `reference list`, `generate`, `activate` et `diff`.
- Client : cache des empreintes entre deux contrôles, toast d'avancement de la vérification.
