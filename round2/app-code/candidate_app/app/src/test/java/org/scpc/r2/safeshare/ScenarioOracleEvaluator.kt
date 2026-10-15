package org.scpc.r2.safeshare

import org.json.JSONArray
import org.json.JSONObject
import org.scpc.r2.probe.CanonicalJson

internal data class ScenarioAssertionResult(
    val afterAction: String,
    val path: String,
    val operation: String,
    val passed: Boolean,
    val detail: String,
)

internal data class ScenarioOracleEvaluation(
    val scenarioId: String,
    val inputShaMatches: Boolean,
    val assertions: List<ScenarioAssertionResult>,
) {
    val passed: Boolean get() = inputShaMatches && assertions.all { it.passed }
}

/** Test-only evaluator. This file and its oracle inputs are never packaged in the APK. */
internal object ScenarioOracleEvaluator {
    private object Missing

    fun evaluate(trace: ScenarioRunTrace, oracleJson: String): ScenarioOracleEvaluation {
        val oracle = JSONObject(oracleJson)
        require(oracle.getString("schema_version") == "safeshare.oracle.v1")
        require(oracle.getString("artifact_kind") == "safeshare_test_oracle")
        require(oracle.getString("scenario_id") == trace.scenarioId)
        val assertions = oracle.getJSONArray("assertions")
        val results = (0 until assertions.length()).map { index ->
            evaluateAssertion(trace, assertions.getJSONObject(index))
        }
        return ScenarioOracleEvaluation(
            scenarioId = trace.scenarioId,
            inputShaMatches = oracle.getString("input_sha256") == trace.inputSha256,
            assertions = results,
        )
    }

    fun equal(left: Any?, right: Any?): Boolean {
        val normalizedLeft = normalize(left)
        val normalizedRight = normalize(right)
        return when {
            normalizedLeft === Missing || normalizedRight === Missing ->
                normalizedLeft === normalizedRight
            normalizedLeft is Number && normalizedRight is Number ->
                normalizedLeft.toDouble() == normalizedRight.toDouble()
            normalizedLeft is JSONObject && normalizedRight is JSONObject ->
                CanonicalJson.encode(normalizedLeft) == CanonicalJson.encode(normalizedRight)
            normalizedLeft is JSONArray && normalizedRight is JSONArray ->
                CanonicalJson.encode(normalizedLeft) == CanonicalJson.encode(normalizedRight)
            else -> normalizedLeft == normalizedRight
        }
    }

    fun resolve(root: JSONObject, path: String): Any? {
        require(path.startsWith("$.")) { "unsupported JSON path: $path" }
        var current: Any? = root
        path.removePrefix("$.").split('.').forEach { part ->
            current = when (val value = current) {
                is JSONObject -> if (value.has(part)) value.get(part) else Missing
                else -> Missing
            }
            if (current === Missing) return Missing
        }
        return normalize(current)
    }

    private fun evaluateAssertion(
        trace: ScenarioRunTrace,
        assertion: JSONObject,
    ): ScenarioAssertionResult {
        val afterAction = assertion.getString("after_action")
        val path = assertion.getString("path")
        val operation = assertion.getString("op")
        val actual = resolve(trace.observation(afterAction).observed, path)
        val reference = assertion.optString("reference", "")
        val expected = if (reference.isNotEmpty()) {
            val separator = reference.indexOf(':')
            require(separator > 0) { "reference must be ACTION:PATH" }
            val referencedAction = reference.substring(0, separator)
            val referencedPath = reference.substring(separator + 1)
            resolve(trace.observation(referencedAction).observed, referencedPath)
        } else if (assertion.has("value")) {
            normalize(assertion.get("value"))
        } else {
            Missing
        }
        val passed = when (operation) {
            "EQ", "SAME_AS" -> equal(actual, expected)
            "NE", "CHANGED_FROM" -> !equal(actual, expected)
            "GT" -> (actual as? Number)?.toDouble()?.let { value ->
                value > (expected as Number).toDouble()
            } ?: false
            "ABSENT" -> actual === Missing
            "COUNT_EQ" -> count(actual) == (expected as Number).toInt()
            else -> error("unsupported oracle operation: $operation")
        }
        return ScenarioAssertionResult(
            afterAction = afterAction,
            path = path,
            operation = operation,
            passed = passed,
            detail = if (passed) {
                "MATCH"
            } else {
                "actual=${render(actual)} expected=${render(expected)}"
            },
        )
    }

    private fun count(value: Any?): Int = when (value) {
        is JSONArray -> value.length()
        is JSONObject -> value.length()
        is Collection<*> -> value.size
        else -> -1
    }

    private fun normalize(value: Any?): Any? =
        if (value == null || value === JSONObject.NULL) null else value

    private fun render(value: Any?): String = when (value) {
        Missing -> "<missing>"
        null -> "null"
        is JSONObject, is JSONArray -> CanonicalJson.encode(value)
        else -> value.toString()
    }
}

internal object ScenarioCorpusTestResources {
    val scenarioIds: List<String> = (1..14).map { index ->
        "SS-KO-${index.toString().padStart(4, '0')}"
    }

    fun input(scenarioId: String): String = resource("$scenarioId.input.json")

    fun oracle(scenarioId: String): String = resource("$scenarioId.oracle.json")

    private fun resource(name: String): String =
        checkNotNull(javaClass.classLoader?.getResourceAsStream(name)) {
            "test resource not found: $name"
        }.bufferedReader(Charsets.UTF_8).use { it.readText() }
}
