# Changelog

Les versions d'Integrity, de la plus récente à la plus ancienne. Chaque JAR porte la version suivie de son Minecraft : `1.0.0+26.3`.

## 1.0.0

Première version, pour Minecraft `26.1.2`, `26.2` et `26.3`.

- Référence du modpack client : manifeste JSON avec l'identité du modpack et les empreintes SHA-256 des `mods`, `resourcepacks` et `shaderpacks`,
  généré depuis une copie du modpack sur le serveur ou exporté depuis le client approuvé (`/integrityclient export <id>`).
- Contrôle avant l'entrée en jeu, à chaque connexion, périodiquement pendant la partie et sur commande ; refus motivé et déconnexion sur écart.
- Règles : politiques par catégorie, exceptions et interdictions par chemin, motif, identifiant de mod et joueur.
- Rapport chiffré et lié au contrôle (X25519 éphémère, HKDF-SHA256, AES-256-GCM) ; JAR distribué obfusqué par ProGuard.
- Administration : `integrity status`, `reload`, `check`, `reference list`, `generate`, `activate` et `diff`.
- Client : cache des empreintes entre deux contrôles, toast d'avancement de la vérification.
