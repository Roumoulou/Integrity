import fr.moulou.integrity.buildlogic.DownloadMdtkFixtures

plugins {
    id("integrity.game-version") apply false
    id("integrity.publication")
    alias(libs.plugins.outfitter) apply false
}

// Les fichiers d'essai persistent dans le classeur ; le build ordinaire ne les télécharge pas.
val mdtkTestData = layout.projectDirectory.dir("../../02-data/00-test")
tasks.register<DownloadMdtkFixtures>("downloadMdtkFixtures") {
    group = "outfitter"
    description = "Télécharge ou vérifie les mods et resourcepacks du catalogue de tests MDTK"
    catalog.set(mdtkTestData.file("mdtk-fixtures.json"))
    directory.set(mdtkTestData)
}
