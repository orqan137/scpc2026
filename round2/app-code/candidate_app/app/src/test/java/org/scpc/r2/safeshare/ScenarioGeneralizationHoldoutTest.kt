package org.scpc.r2.safeshare

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Regression guard for inputs that are never packaged in the release APK.
 *
 * The 24 scenarios are generated from holdout-only purpose and room templates;
 * they exercise the same production parser and executor as the release corpus.
 */
class ScenarioGeneralizationHoldoutTest {
    @Test
    fun independentHoldoutCorpusPassesProductionExecutorAndPairRelations() {
        val scenarios = ScenarioGeneralizationResources.scenarioIds.associateWith { scenarioId ->
            ScenarioParser.parse(ScenarioGeneralizationResources.input(scenarioId))
        }.toSortedMap()
        val traces = scenarios.mapValues { (_, scenario) ->
            SafeShareScenarioExecutor().execute(scenario)
        }.toSortedMap()
        val evaluations = traces.mapValues { (scenarioId, trace) ->
            ScenarioOracleEvaluator.evaluate(trace, ScenarioGeneralizationResources.oracle(scenarioId))
        }

        assertEquals(24, scenarios.size)
        assertEquals(96, scenarios.values.sumOf { it.episodes.size })
        assertEquals(312, traces.values.sumOf { it.actionCount })
        assertTrue(evaluations.values.all { it.passed })

        val checkedPairs = mutableSetOf<String>()
        ScenarioGeneralizationResources.scenarioIds.forEach { scenarioId ->
            val relations = JSONObject(ScenarioGeneralizationResources.oracle(scenarioId))
                .getJSONArray("pair_relations")
            (0 until relations.length()).forEach { index ->
                val relation = relations.getJSONObject(index)
                val pairId = relation.getString("pair_id")
                if (!checkedPairs.add(pairId)) return@forEach

                val counterpart = relation.getString("counterpart_scenario_id")
                val left = traces.getValue(scenarioId).observations.last().observed
                val right = traces.getValue(counterpart).observations.last().observed
                val paths = relation.getJSONArray("observed_paths")
                val comparisons = (0 until paths.length()).map { pathIndex ->
                    val path = paths.getString(pathIndex)
                    ScenarioOracleEvaluator.equal(
                        ScenarioOracleEvaluator.resolve(left, path),
                        ScenarioOracleEvaluator.resolve(right, path),
                    )
                }
                val expected = relation.getString("relation")
                val passed = when (expected) {
                    "SAME" -> comparisons.all { it }
                    "DIFFERENT" -> comparisons.any { !it }
                    else -> error("unsupported pair relation: $expected")
                }
                assertTrue("holdout pair $pairId expected $expected", passed)
            }
        }
        assertEquals(12, checkedPairs.size)
    }
}

internal object ScenarioGeneralizationResources {
    val scenarioIds: List<String> = (101..124).map { index ->
        "SS-KO-${index.toString().padStart(4, '0')}"
    }

    fun input(scenarioId: String): String = resource("holdout-input/$scenarioId.input.json")

    fun oracle(scenarioId: String): String = resource("holdout-oracle/$scenarioId.oracle.json")

    private fun resource(name: String): String =
        checkNotNull(javaClass.classLoader?.getResourceAsStream(name)) {
            "test-only generalization resource not found: $name"
        }.bufferedReader(Charsets.UTF_8).use { it.readText() }
}
