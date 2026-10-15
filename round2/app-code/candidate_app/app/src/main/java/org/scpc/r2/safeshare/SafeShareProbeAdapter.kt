package org.scpc.r2.safeshare

import android.content.Context
import org.json.JSONObject
import org.scpc.r2.probe.CanonicalJson
import org.scpc.r2.probe.ProbeAdapter
import org.scpc.r2.probe.ProbeInputStep
import org.scpc.r2.probe.ProbeRunContext
import org.scpc.r2.probe.ProbeRuntimeState
import java.util.UUID

class SafeShareProbeAdapter : ProbeAdapter {
    override fun runtimeState(context: Context): ProbeRuntimeState =
        ProbeRuntimeState(
            modelConfigured = false,
            cumulativeInvocations = 0,
        )

    override fun onRunStarted(context: Context, run: ProbeRunContext) {
        SafeShareProbeBridge.from(context).begin(
            SafeShareProbeBridge.protectedBinding(run),
        )
        context.getSharedPreferences(RUN_PREFERENCES, Context.MODE_PRIVATE)
            .edit()
            .putString("active_run_id", run.runId)
            .commit()
    }

    override fun executeStep(
        context: Context,
        run: ProbeRunContext,
        step: ProbeInputStep,
    ): JSONObject = SafeShareProbeBridge.from(context).execute(
        binding = SafeShareProbeBridge.protectedBinding(run),
        step = step,
    )

    override fun onRunFinished(context: Context, run: ProbeRunContext): List<String> {
        val evidenceIds = SafeShareProbeBridge.from(context).finish(
            SafeShareProbeBridge.protectedBinding(run),
        )
        context.getSharedPreferences(RUN_PREFERENCES, Context.MODE_PRIVATE)
            .edit()
            .remove("active_run_id")
            .putString("last_finished_run_id", run.runId)
            .commit()
        return evidenceIds
    }

    override fun onRunAborted(context: Context, run: ProbeRunContext, reason: String) {
        SafeShareProbeBridge.from(context).abort(
            binding = SafeShareProbeBridge.protectedBinding(run),
            reason = reason,
        )
        context.getSharedPreferences(RUN_PREFERENCES, Context.MODE_PRIVATE)
            .edit()
            .remove("active_run_id")
            .putString("last_abort_reason", reason)
            .commit()
    }

    companion object {
        private const val RUN_PREFERENCES = "safe-share-run"
    }
}

object SafeShareCoreProvider {
    private val processMarker = UUID.randomUUID().toString()

    fun core(context: Context): SafeShareCore =
        SafeShareCore(
            AndroidRelayStateStore(
                context = context.applicationContext,
                namespace = PRODUCTION_NAMESPACE,
            ),
            processMarker,
        )

    private const val PRODUCTION_NAMESPACE = "safe-share-production"
}

object SafeShareComparisonProvider {
    fun run(context: Context): JSONObject {
        val applicationContext = context.applicationContext
        val runId = UUID.randomUUID().toString()
        val sharedClock = System.currentTimeMillis()
        return SafeShareComparison(
            storeForNamespace = { namespace ->
                AndroidRelayStateStore(applicationContext, namespace)
            },
            runId = runId,
            virtualClockMillis = sharedClock,
        ).run()
    }
}

internal class AndroidRelayStateStore(
    context: Context,
    namespace: String,
) : RelayStateStore {
    private val preferences =
        context.getSharedPreferences(namespace, Context.MODE_PRIVATE)

    override fun load(): JSONObject =
        preferences.getString("state_json", null)?.let(::JSONObject) ?: JSONObject()

    override fun save(state: JSONObject) {
        check(
            preferences.edit()
                .putString("state_json", CanonicalJson.encode(state))
                .commit(),
        ) { "cannot persist SafeShare production state" }
    }
}
