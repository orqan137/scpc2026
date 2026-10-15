package org.scpc.r2.safeshare

import java.io.File
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.scpc.r2.probe.CanonicalJson
import org.scpc.r2.probe.Sha256

class ScenarioCorpusReportTest {
    @Test
    fun writesDeterministicObservedCorpusReportAndChecksPairs() {
        val scenarios = ScenarioCorpusTestResources.scenarioIds.associateWith { scenarioId ->
            ScenarioParser.parse(ScenarioCorpusTestResources.input(scenarioId))
        }.toSortedMap()
        val traces = scenarios.mapValues { (_, scenario) ->
            SafeShareScenarioExecutor().execute(scenario)
        }.toSortedMap()
        val evaluations = traces.mapValues { (scenarioId, trace) ->
            ScenarioOracleEvaluator.evaluate(
                trace,
                ScenarioCorpusTestResources.oracle(scenarioId),
            )
        }.toSortedMap()

        assertEquals(14, scenarios.size)
        assertEquals(56, scenarios.values.sumOf { it.episodes.size })
        assertEquals(182, traces.values.sumOf { it.actionCount })
        assertEquals(
            setOf("TEXT", "LINK", "PDF", "NONE"),
            scenarios.values.flatMap { scenario -> scenario.episodes.map { it.intake.kind } }.toSet(),
        )
        assertTrue(evaluations.values.all { it.passed })

        val pairResults = evaluateUniquePairs(traces)
        assertEquals(7, pairResults.length())
        assertTrue((0 until pairResults.length()).all { pairResults.getJSONObject(it).getBoolean("passed") })

        val reportDirectory = File(
            checkNotNull(System.getProperty("safeshare.synthetic.report.dir")),
        ).apply { mkdirs() }
        traces.forEach { (scenarioId, trace) ->
            File(reportDirectory, "$scenarioId.observed.json").writeText(
                CanonicalJson.encode(trace.toJson()),
                Charsets.UTF_8,
            )
        }

        val assertionCount = evaluations.values.sumOf { it.assertions.size }
        val summary = JSONObject()
            .put("schema_version", "safeshare.synthetic-e2e-report.v1")
            .put(
                "totals",
                JSONObject()
                    .put("scenarios", scenarios.size)
                    .put("episodes", scenarios.values.sumOf { it.episodes.size })
                    .put("actions", traces.values.sumOf { it.actionCount })
                    .put("assertions_ready", assertionCount)
                    .put("assertions_passed", evaluations.values.sumOf { evaluation ->
                        evaluation.assertions.count { it.passed }
                    })
                    .put("counterfactual_pairs", pairResults.length()),
            )
            .put(
                "coverage",
                JSONObject()
                    .put("intake_kinds", JSONArray(listOf("LINK", "NONE", "PDF", "TEXT")))
                    .put("production_core", true)
                    .put("relaunch_with_same_store", true)
                    .put("external_dispatch_count", 0),
            )
            .put("input_sha256", JSONObject().apply {
                scenarios.forEach { (scenarioId, scenario) -> put(scenarioId, scenario.inputSha256) }
            })
            .put("observed_trace_sha256", JSONObject().apply {
                traces.forEach { (scenarioId, trace) ->
                    put(scenarioId, Sha256.canonicalJson(trace.toJson()))
                }
            })
            .put("pair_results", pairResults)
            .put("test_oracle_packaged_in_release", false)
            .put("all_passed", evaluations.values.all { it.passed })
        File(reportDirectory, "summary.json").writeText(
            CanonicalJson.encode(summary),
            Charsets.UTF_8,
        )

        assertFalse(CanonicalJson.encode(traces.values.first().toJson()).contains("oracle"))
        assertTrue(File(reportDirectory, "summary.json").isFile)
    }

    private fun evaluateUniquePairs(traces: Map<String, ScenarioRunTrace>): JSONArray {
        val results = JSONArray()
        ScenarioCorpusTestResources.scenarioIds.forEach { scenarioId ->
            val oracle = JSONObject(ScenarioCorpusTestResources.oracle(scenarioId))
            val relations = oracle.getJSONArray("pair_relations")
            (0 until relations.length()).forEach relationLoop@{ index ->
                val relation = relations.getJSONObject(index)
                val counterpart = relation.getString("counterpart_scenario_id")
                if (scenarioId >= counterpart) return@relationLoop
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
                val expectedRelation = relation.getString("relation")
                val passed = when (expectedRelation) {
                    "SAME" -> comparisons.all { it }
                    "DIFFERENT" -> comparisons.any { !it }
                    else -> error("unsupported pair relation")
                }
                results.put(
                    JSONObject()
                        .put("pair_id", relation.getString("pair_id"))
                        .put("left_scenario_id", scenarioId)
                        .put("right_scenario_id", counterpart)
                        .put("relation", expectedRelation)
                        .put("observed_paths", JSONArray((0 until paths.length()).map(paths::getString)))
                        .put("passed", passed),
                )
            }
        }
        return results
    }
}
