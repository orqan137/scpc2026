package org.scpc.r2.safeshare

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.Parameterized

/**
 * E3 has to notice the change before the app checkpoints and kills its own
 * process.  The product path once reviewed the new payload first, which wrote
 * the new context into the durable store and erased the very difference this
 * detection depends on.  A room change survived that because the route rule
 * carries the old target separately, but a role-only downgrade did not: the
 * change set came back empty, the checkpoint requirement failed, and the
 * restart was cancelled while the screen still showed a completed step.
 *
 * These cases pin the ordering for every shipped scenario.
 */
@RunWith(Parameterized::class)
class ScenarioE3ChangeDetectionTest(
    private val scenarioId: String,
) {
    @Test
    fun e3DetectsExactlyTheFieldsThatDifferFromTheConfirmedContext() {
        val scenario = ScenarioParser.parse(ScenarioCorpusTestResources.input(scenarioId))
        val core = confirmedThroughE2(scenario)

        val e3 = scenario.episodes.single { it.episodeId == "E3" }.context
        val held = core.beginContextChange(
            purpose = e3.purposeId,
            targetRoomId = e3.targetRoomId,
            roomDisplay = e3.targetRoomDisplay,
            currentRole = e3.currentRole,
            allowedScope = e3.allowedScope,
            linkAccess = e3.linkAccess.name,
        )

        val changed = held.getJSONObject("pending_context_change").getJSONArray("changed_fields")
        val observed = (0 until changed.length()).map(changed::getString).toSet()
        assertEquals(
            "$scenarioId must report exactly the fields that differ from E2",
            expectedChangedFields(scenario),
            observed,
        )
        assertTrue("$scenarioId must report at least one changed field", observed.isNotEmpty())
    }

    @Test
    fun writingTheNewContextBeforeE3HidesTheChangeAndBlocksTheCheckpoint() {
        val scenario = ScenarioParser.parse(ScenarioCorpusTestResources.input(scenarioId))
        val core = confirmedThroughE2(scenario)
        val e3 = scenario.episodes.single { it.episodeId == "E3" }.context

        // Reproduces the regression: persisting the E3 context first.
        core.updateSafetyContext(
            purpose = e3.purposeId,
            roomId = e3.targetRoomId,
            roomDisplay = e3.targetRoomDisplay,
            currentRole = e3.currentRole,
            allowedScope = e3.allowedScope,
            linkAccess = e3.linkAccess.name,
            contentKind = ShareKind.TEXT.name,
            reviewState = "DRAFT_REVIEWED",
        )

        val expectsRoomChange = expectedChangedFields(scenario).contains("TARGET_ROOM")
        if (expectsRoomChange) {
            // The route rule still holds the old target, so a room move survives.
            val held = core.beginContextChange(
                purpose = e3.purposeId,
                targetRoomId = e3.targetRoomId,
                roomDisplay = e3.targetRoomDisplay,
                currentRole = e3.currentRole,
                allowedScope = e3.allowedScope,
                linkAccess = e3.linkAccess.name,
            )
            val changed = held.getJSONObject("pending_context_change").getJSONArray("changed_fields")
            assertEquals(setOf("TARGET_ROOM"), (0 until changed.length()).map(changed::getString).toSet())
        } else {
            // A role-only downgrade becomes invisible, which is the failure mode.
            assertThrows(IllegalArgumentException::class.java) {
                core.beginContextChange(
                    purpose = e3.purposeId,
                    targetRoomId = e3.targetRoomId,
                    roomDisplay = e3.targetRoomDisplay,
                    currentRole = e3.currentRole,
                    allowedScope = e3.allowedScope,
                    linkAccess = e3.linkAccess.name,
                )
            }
        }
    }

    /** Replays E1 and E2 the way the product screen does. */
    private fun confirmedThroughE2(scenario: SafeShareScenario): SafeShareCore {
        val core = SafeShareCore(InMemoryRelayStateStore(), "process-e3-check")
        scenario.episodes.filter { it.episodeId == "E1" || it.episodeId == "E2" }.forEach { episode ->
            val context = episode.context
            val eventId = "share-${scenario.scenarioId}-${episode.episodeId}"
            core.updateSafetyContext(
                purpose = context.purposeId,
                roomId = context.targetRoomId,
                roomDisplay = context.targetRoomDisplay,
                currentRole = context.currentRole,
                allowedScope = context.allowedScope,
                linkAccess = context.linkAccess.name,
                contentKind = ShareKind.TEXT.name,
                reviewState = "DRAFT_REVIEWED",
                sharePermission = context.sharePermission.name,
            )
            core.receiveSharedText(eventId, "합성 검토 본문 ${episode.episodeId}")
            if (episode.episodeId == "E1") {
                core.proposeRoute(context.purposeId, context.targetRoomId)
                core.confirmPendingRoute()
            }
        }
        return core
    }

    private fun expectedChangedFields(scenario: SafeShareScenario): Set<String> {
        val before = scenario.episodes.single { it.episodeId == "E2" }.context
        val after = scenario.episodes.single { it.episodeId == "E3" }.context
        return buildSet {
            if (before.targetRoomId != after.targetRoomId) add("TARGET_ROOM")
            if (before.targetRoomDisplay != after.targetRoomDisplay) add("ROOM_PROFILE")
            if (before.currentRole != after.currentRole) add("CURRENT_ROLE")
            if (before.allowedScope != after.allowedScope) add("ALLOWED_SCOPE")
            if (before.linkAccess != after.linkAccess) add("LINK_ACCESS")
        }
    }

    companion object {
        @JvmStatic
        @Parameterized.Parameters(name = "{0}")
        fun parameters(): List<Array<String>> =
            ScenarioCorpusTestResources.scenarioIds.map { arrayOf(it) }
    }
}
