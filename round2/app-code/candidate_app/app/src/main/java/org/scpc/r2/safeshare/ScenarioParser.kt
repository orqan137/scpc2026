package org.scpc.r2.safeshare

import java.net.URI
import org.json.JSONArray
import org.json.JSONObject
import org.scpc.r2.probe.Sha256

/** Strict parser for the input-only synthetic corpus shipped with the app. */
object ScenarioParser {
    private val scenarioIdPattern = Regex("^SS-KO-[0-9]{4}$")
    private val derivationPattern = Regex("^DRV-[0-9]{4}$")
    private val personaPattern = Regex("^persona-[0-9a-f]{12}$")
    private val purposePattern = Regex("^[a-z0-9][a-z0-9-]{2,63}$")
    private val roomPattern = Regex("^room-[a-z0-9-]{3,80}$")
    private val assetPattern = Regex("^synthetic/[a-z0-9._/-]+$")

    fun parse(json: String): SafeShareScenario {
        val root = JSONObject(json)
        requireKeys(
            root,
            setOf(
                "schema_version",
                "artifact_kind",
                "scenario_id",
                "synthetic",
                "locale",
                "derivation_ref",
                "persona_style",
                "purpose",
                "rooms",
                "episodes",
            ),
            "scenario",
        )
        require(root.getString("schema_version") == "safeshare.input.v1")
        require(root.getString("artifact_kind") == "safeshare_app_input")
        val scenarioId = root.getString("scenario_id")
        require(scenarioIdPattern.matches(scenarioId)) { "invalid scenario_id: $scenarioId" }
        require(root.getBoolean("synthetic")) { "only synthetic scenarios are accepted" }
        require(root.getString("locale") == "ko-KR") { "locale must be ko-KR" }
        val derivationRef = root.getString("derivation_ref")
        require(derivationPattern.matches(derivationRef)) { "invalid derivation_ref" }

        val persona = parsePersona(root.getJSONObject("persona_style"))
        val purpose = parsePurpose(root.getJSONObject("purpose"))
        val rooms = parseRooms(root.getJSONArray("rooms"))
        val roomIds = rooms.map { it.roomId }.toSet()
        val episodes = parseEpisodes(root.getJSONArray("episodes"), purpose.purposeId, roomIds)
        require(episodes.map { it.episodeId } == listOf("E1", "E2", "E3", "E4")) {
            "episodes must be ordered E1 through E4"
        }
        require(episodes.map { it.session } == listOf(1, 2, 3, 4)) {
            "sessions must be ordered 1 through 4"
        }

        return SafeShareScenario(
            schemaVersion = root.getString("schema_version"),
            artifactKind = root.getString("artifact_kind"),
            scenarioId = scenarioId,
            synthetic = true,
            locale = root.getString("locale"),
            derivationRef = derivationRef,
            personaStyle = persona,
            purpose = purpose,
            rooms = rooms,
            episodes = episodes,
            inputSha256 = Sha256.canonicalJson(root),
        )
    }

    private fun parsePersona(value: JSONObject): ScenarioPersonaStyle {
        requireKeys(
            value,
            setOf(
                "persona_key",
                "age_band",
                "region_band",
                "work_context",
                "register",
                "directness",
                "noise_level",
            ),
            "persona_style",
        )
        val personaKey = value.getString("persona_key")
        require(personaPattern.matches(personaKey)) { "invalid persona_key" }
        return ScenarioPersonaStyle(
            personaKey = personaKey,
            ageBand = enumValue(value, "age_band"),
            regionBand = enumValue(value, "region_band"),
            workContext = enumValue(value, "work_context"),
            register = enumValue(value, "register"),
            directness = enumValue(value, "directness"),
            noiseLevel = enumValue(value, "noise_level"),
        )
    }

    private fun parsePurpose(value: JSONObject): ScenarioPurpose {
        requireKeys(value, setOf("purpose_id", "display_text"), "purpose")
        val purposeId = value.getString("purpose_id")
        val displayText = value.getString("display_text")
        require(purposePattern.matches(purposeId)) { "invalid purpose_id" }
        require(displayText.isNotBlank() && displayText.length <= 100) { "invalid purpose display" }
        return ScenarioPurpose(purposeId, displayText)
    }

    private fun parseRooms(values: JSONArray): List<ScenarioRoom> {
        require(values.length() >= 3) { "at least three rooms are required" }
        val rooms = (0 until values.length()).map { index ->
            val value = values.getJSONObject(index)
            requireKeys(value, setOf("room_id", "display_name", "members"), "rooms[$index]")
            val roomId = value.getString("room_id")
            val displayName = value.getString("display_name")
            require(roomPattern.matches(roomId)) { "invalid room_id: $roomId" }
            require(displayName.isNotBlank() && displayName.length <= 100) { "invalid room display" }
            ScenarioRoom(roomId, displayName, parseMembers(value.getJSONArray("members"), index))
        }
        require(rooms.map { it.roomId }.distinct().size == rooms.size) { "duplicate room_id" }
        return rooms
    }

    private fun parseMembers(values: JSONArray, roomIndex: Int): List<ScenarioRoomMember> {
        require(values.length() in 1..8) { "rooms[$roomIndex].members must hold one to eight people" }
        val members = (0 until values.length()).map { index ->
            val value = values.getJSONObject(index)
            requireKeys(value, setOf("display_name", "member_scope"), "rooms[$roomIndex].members[$index]")
            val displayName = value.getString("display_name")
            val memberScope = value.getString("member_scope")
            require(displayName.isNotBlank() && displayName.length <= 40) { "invalid member name" }
            require(memberScope.isNotBlank() && memberScope.length <= 100) { "invalid member scope" }
            ScenarioRoomMember(displayName, memberScope)
        }
        require(members.map { it.displayName }.distinct().size == members.size) { "duplicate member" }
        return members
    }

    private fun parseEpisodes(
        values: JSONArray,
        purposeId: String,
        roomIds: Set<String>,
    ): List<ScenarioEpisode> {
        require(values.length() == 4) { "exactly four episodes are required" }
        return (0 until values.length()).map { index ->
            val value = values.getJSONObject(index)
            requireKeys(
                value,
                setOf(
                    "episode_id",
                    "phase",
                    "session",
                    "user_utterance",
                    "intake",
                    "context",
                    "candidate_room_ids",
                    "read_only_rooms",
                    "actions",
                ),
                "episodes[$index]",
            )
            val episodeId = value.getString("episode_id")
            require(episodeId == "E${index + 1}") { "episode order mismatch" }
            val phase = enumValue<ScenarioPhase>(value, "phase")
            require(
                phase == listOf(
                    ScenarioPhase.E1_LEARN,
                    ScenarioPhase.E2_REUSE,
                    ScenarioPhase.E3_EXCEPTION,
                    ScenarioPhase.E4_RECOVER,
                )[index],
            ) { "phase does not match episode" }
            val session = value.getInt("session")
            require(session == index + 1) { "session does not match episode" }
            val utterance = value.getString("user_utterance")
            require(utterance.isNotBlank() && utterance.length <= 500) { "invalid user_utterance" }
            val intake = parseIntake(value.getJSONObject("intake"))
            val context = parseContext(value.getJSONObject("context"))
            require(context.purposeId == purposeId) { "episode purpose does not match scenario" }
            require(context.targetRoomId in roomIds) { "target room is not declared" }
            val candidates = strings(value.getJSONArray("candidate_room_ids"))
            require(candidates.isNotEmpty() && candidates.distinct().size == candidates.size) {
                "candidate rooms must be non-empty and unique"
            }
            require(candidates.all { roomPattern.matches(it) && it in roomIds }) {
                "candidate room is not declared"
            }
            val readOnlyRooms = strings(value.getJSONArray("read_only_rooms"))
            require(readOnlyRooms.distinct().size == readOnlyRooms.size) {
                "read-only rooms must be unique"
            }
            require(readOnlyRooms.all { it in roomIds }) { "read-only room is not declared" }
            val actions = strings(value.getJSONArray("actions")).map(ScenarioAction::valueOf)
            require(actions.isNotEmpty() && actions.distinct().size == actions.size) {
                "actions must be non-empty and unique"
            }
            ScenarioEpisode(
                episodeId = episodeId,
                phase = phase,
                session = session,
                userUtterance = utterance,
                intake = intake,
                context = context,
                candidateRoomIds = candidates,
                readOnlyRoomIds = readOnlyRooms,
                actions = actions,
            )
        }
    }

    private fun parseIntake(value: JSONObject): ScenarioIntake = when (value.getString("kind")) {
        "TEXT" -> {
            requireKeys(value, setOf("kind", "content"), "TEXT intake")
            val content = value.getString("content")
            require(content.isNotBlank() && content.length <= 2_000) { "invalid text intake" }
            ScenarioIntake.Text(content)
        }
        "LINK" -> {
            requireKeys(value, setOf("kind", "url"), "LINK intake")
            val url = value.getString("url")
            val uri = runCatching { URI(url) }.getOrNull()
            require(url.length <= 500 && uri?.isAbsolute == true && !uri.host.isNullOrBlank()) {
                "invalid link intake"
            }
            ScenarioIntake.Link(url)
        }
        "PDF" -> {
            requireKeys(value, setOf("kind", "asset_ref", "mime_type"), "PDF intake")
            val assetRef = value.getString("asset_ref")
            val mimeType = value.getString("mime_type")
            require(assetPattern.matches(assetRef) && ".." !in assetRef) { "invalid PDF asset_ref" }
            require(mimeType == "application/pdf") { "PDF intake must use application/pdf" }
            ScenarioIntake.Pdf(assetRef, mimeType)
        }
        "NONE" -> {
            requireKeys(value, setOf("kind"), "NONE intake")
            ScenarioIntake.None
        }
        else -> error("unsupported intake kind")
    }

    private fun parseContext(value: JSONObject): ScenarioContext {
        requireKeys(
            value,
            setOf(
                "purpose_id",
                "target_room_id",
                "target_room_display",
                "current_role",
                "allowed_scope",
                "link_access",
                "share_permission",
            ),
            "context",
        )
        val purposeId = value.getString("purpose_id")
        val target = value.getString("target_room_id")
        val display = value.getString("target_room_display")
        val role = value.getString("current_role")
        val scope = value.getString("allowed_scope")
        require(purposePattern.matches(purposeId)) { "invalid context purpose" }
        require(roomPattern.matches(target)) { "invalid context room" }
        require(listOf(display, role, scope).all { it.isNotBlank() && it.length <= 100 }) {
            "blank or oversized context field"
        }
        return ScenarioContext(
            purposeId = purposeId,
            targetRoomId = target,
            targetRoomDisplay = display,
            currentRole = role,
            allowedScope = scope,
            linkAccess = enumValue(value, "link_access"),
            sharePermission = enumValue(value, "share_permission"),
        )
    }

    private inline fun <reified T : Enum<T>> enumValue(value: JSONObject, key: String): T =
        enumValueOf(value.getString(key))

    private fun strings(value: JSONArray): List<String> =
        (0 until value.length()).map(value::getString)

    private fun requireKeys(value: JSONObject, expected: Set<String>, location: String) {
        val actual = value.keys().asSequence().toSet()
        require(actual == expected) {
            "$location fields differ; missing=${expected - actual}, extra=${actual - expected}"
        }
    }
}
