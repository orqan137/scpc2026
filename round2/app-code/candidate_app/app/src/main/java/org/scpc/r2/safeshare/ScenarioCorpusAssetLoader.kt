package org.scpc.r2.safeshare

import android.content.res.AssetManager

/** Loads only input artifacts. Test-only oracle files are not an app asset source. */
object ScenarioCorpusAssetLoader {
    private val filePattern = Regex("^SS-KO-[0-9]{4}\\.input\\.json$")

    fun listNames(assetManager: AssetManager): List<String> =
        assetManager.list("")
            .orEmpty()
            .filter(filePattern::matches)
            .sorted()

    fun loadAll(assetManager: AssetManager): List<SafeShareScenario> {
        val names = listNames(assetManager)
        require(names.size == 14) { "expected 14 synthetic corpus inputs, found ${names.size}" }
        return names.map { name ->
            assetManager.open(name).bufferedReader(Charsets.UTF_8).use { reader ->
                ScenarioParser.parse(reader.readText())
            }
        }
    }

    fun load(assetManager: AssetManager, scenarioId: String): SafeShareScenario {
        require(Regex("^SS-KO-[0-9]{4}$").matches(scenarioId)) { "invalid scenario id" }
        val name = "$scenarioId.input.json"
        require(name in listNames(assetManager)) { "scenario asset not found: $scenarioId" }
        return assetManager.open(name).bufferedReader(Charsets.UTF_8).use { reader ->
            ScenarioParser.parse(reader.readText())
        }
    }
}
