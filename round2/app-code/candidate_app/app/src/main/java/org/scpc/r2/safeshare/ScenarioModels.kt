package org.scpc.r2.safeshare

import org.json.JSONArray
import org.json.JSONObject
import org.scpc.r2.probe.CanonicalJson
import org.scpc.r2.probe.Sha256

enum class ScenarioAgeBand { `19_29`, `30_44`, `45_59`, `60_PLUS` }

enum class ScenarioRegionBand { CAPITAL, METRO, NON_METRO }

enum class ScenarioWorkContext {
    OFFICE_PROFESSIONAL,
    PUBLIC_SERVICE,
    FIELD_PRODUCTION,
    INDEPENDENT,
}

enum class ScenarioRegister { FORMAL_HAPSYO, POLITE_HAEYO, CASUAL_HAE }

enum class ScenarioDirectness { DIRECT, INDIRECT }

enum class ScenarioNoiseLevel { CLEAN, LIGHT }

enum class ScenarioPhase { E1_LEARN, E2_REUSE, E3_EXCEPTION, E4_RECOVER }

enum class ScenarioLinkAccess {
    NOT_APPLICABLE,
    UNKNOWN,
    PROJECT_ONLY,
    EXTERNAL_ALLOWED,
    EXPIRED,
}

enum class ScenarioAction {
    REVIEW_INTAKE,
    CONFIRM_CONTEXT,
    EVALUATE_CANDIDATES,
    PROPOSE_DRAFT,
    ADVANCE_SESSION,
    APPLY_CONTEXT_EVENT,
    REQUEST_DECISION,
    CHECKPOINT_IF_HELD,
    RELAUNCH_IF_CHECKPOINTED,
    CONFIRM_PENDING_CONTEXT,
    RESUME_DRAFT,
}

data class ScenarioPersonaStyle(
    val personaKey: String,
    val ageBand: ScenarioAgeBand,
    val regionBand: ScenarioRegionBand,
    val workContext: ScenarioWorkContext,
    val register: ScenarioRegister,
    val directness: ScenarioDirectness,
    val noiseLevel: ScenarioNoiseLevel,
)

data class ScenarioPurpose(
    val purposeId: String,
    val displayText: String,
)

enum class ScenarioSharePermission { CAN_SHARE, READ_ONLY }

/** One synthetic person in a room, with the scope group they belong to. */
data class ScenarioRoomMember(
    val displayName: String,
    val memberScope: String,
)

data class ScenarioRoom(
    val roomId: String,
    val displayName: String,
    val members: List<ScenarioRoomMember>,
)

sealed interface ScenarioIntake {
    val kind: String

    data class Text(val content: String) : ScenarioIntake {
        override val kind: String = "TEXT"
    }

    data class Link(val url: String) : ScenarioIntake {
        override val kind: String = "LINK"
    }

    data class Pdf(
        val assetRef: String,
        val mimeType: String,
    ) : ScenarioIntake {
        override val kind: String = "PDF"
    }

    data object None : ScenarioIntake {
        override val kind: String = "NONE"
    }

    fun toJson(): JSONObject = when (this) {
        is Text -> JSONObject().put("kind", kind).put("content", content)
        is Link -> JSONObject().put("kind", kind).put("url", url)
        is Pdf -> JSONObject()
            .put("kind", kind)
            .put("asset_ref", assetRef)
            .put("mime_type", mimeType)
        None -> JSONObject().put("kind", kind)
    }
}

data class ScenarioContext(
    val purposeId: String,
    val targetRoomId: String,
    val targetRoomDisplay: String,
    val currentRole: String,
    val allowedScope: String,
    val linkAccess: ScenarioLinkAccess,
    val sharePermission: ScenarioSharePermission,
) {
    fun toJson(): JSONObject = JSONObject()
        .put("purpose_id", purposeId)
        .put("target_room_id", targetRoomId)
        .put("target_room_display", targetRoomDisplay)
        .put("current_role", currentRole)
        .put("allowed_scope", allowedScope)
        .put("link_access", linkAccess.name)
        .put("share_permission", sharePermission.name)
}

data class ScenarioEpisode(
    val episodeId: String,
    val phase: ScenarioPhase,
    val session: Int,
    val userUtterance: String,
    val intake: ScenarioIntake,
    val context: ScenarioContext,
    val candidateRoomIds: List<String>,
    /** Rooms where the current role lost sharing rights for this episode. */
    val readOnlyRoomIds: List<String>,
    val actions: List<ScenarioAction>,
)

data class SafeShareScenario(
    val schemaVersion: String,
    val artifactKind: String,
    val scenarioId: String,
    val synthetic: Boolean,
    val locale: String,
    val derivationRef: String,
    val personaStyle: ScenarioPersonaStyle,
    val purpose: ScenarioPurpose,
    val rooms: List<ScenarioRoom>,
    val episodes: List<ScenarioEpisode>,
    val inputSha256: String,
)

data class ScenarioActionObservation(
    val index: Int,
    val afterAction: String,
    val episodeId: String,
    val action: ScenarioAction,
    val processMarker: String,
    val observed: JSONObject,
) {
    fun toJson(): JSONObject = JSONObject()
        .put("index", index)
        .put("after_action", afterAction)
        .put("episode_id", episodeId)
        .put("action", action.name)
        .put("process_marker", processMarker)
        .put("observed", JSONObject(CanonicalJson.encode(observed)))
}

data class ScenarioRunTrace(
    val scenarioId: String,
    val inputSha256: String,
    val episodeCount: Int,
    val observations: List<ScenarioActionObservation>,
) {
    val actionCount: Int get() = observations.size

    fun observation(afterAction: String): ScenarioActionObservation =
        observations.singleOrNull { it.afterAction == afterAction }
            ?: error("No unique observation for $afterAction in $scenarioId")

    fun toJson(): JSONObject = JSONObject()
        .put("schema_version", "safeshare.observed-trace.v1")
        .put("scenario_id", scenarioId)
        .put("input_sha256", inputSha256)
        .put("episode_count", episodeCount)
        .put("action_count", actionCount)
        .put("observations", JSONArray().apply { observations.forEach { put(it.toJson()) } })
        .put(
            "trace_sha256",
            Sha256.canonicalJson(
                JSONObject()
                    .put("scenario_id", scenarioId)
                    .put("input_sha256", inputSha256)
                    .put("observations", JSONArray().apply {
                        observations.forEach { put(it.toJson()) }
                    }),
            ),
        )
}
