package org.scpc.r2.safeshare

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.Parameterized

@RunWith(Parameterized::class)
class ScenarioCorpusParameterizedTest(
    private val scenarioId: String,
) {
    @Test
    fun syntheticInputExecutesEveryEpisodeAndActionThroughProductionCore() {
        val scenario = ScenarioParser.parse(ScenarioCorpusTestResources.input(scenarioId))
        assertEquals(scenarioId, scenario.scenarioId)
        assertEquals(4, scenario.episodes.size)
        assertTrue(scenario.synthetic)

        val trace = SafeShareScenarioExecutor().execute(scenario)
        val declaredActions = scenario.episodes.sumOf { it.actions.size }
        assertEquals(declaredActions, trace.actionCount)
        assertEquals((1..declaredActions).toList(), trace.observations.map { it.index })
        assertEquals(
            scenario.episodes.flatMap { episode ->
                episode.actions.map { action -> "${episode.episodeId}.${action.name}" }
            },
            trace.observations.map { it.afterAction },
        )
        assertEquals(
            scenario.episodes.flatMap { episode -> List(episode.actions.size) { episode.intake.kind } },
            trace.observations.map {
                it.observed.getJSONObject("input_binding").getString("intake_kind")
            },
        )
        assertTrue(trace.observations.all { it.observed.getInt("external_dispatch_count") == 0 })
        assertEquals(0, trace.observations.first().observed.getInt("process_epoch"))
        assertEquals(1, trace.observations.last().observed.getInt("process_epoch"))

        val evaluation = ScenarioOracleEvaluator.evaluate(
            trace,
            ScenarioCorpusTestResources.oracle(scenarioId),
        )
        assertTrue("input SHA mismatch for $scenarioId", evaluation.inputShaMatches)
        assertTrue(
            evaluation.assertions.filterNot { it.passed }.joinToString { result ->
                "${result.afterAction}:${result.path} ${result.detail}"
            },
            evaluation.passed,
        )
    }

    companion object {
        @JvmStatic
        @Parameterized.Parameters(name = "{0}")
        fun parameters(): List<Array<String>> =
            ScenarioCorpusTestResources.scenarioIds.map { arrayOf(it) }
    }
}
