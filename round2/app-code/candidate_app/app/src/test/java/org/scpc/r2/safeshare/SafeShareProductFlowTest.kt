package org.scpc.r2.safeshare

import java.io.File
import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class SafeShareProductFlowTest {
    @Test
    fun linkRouteConfirmationWaitsForAccessBoundToCurrentFingerprint() {
        val core = SafeShareCore(InMemoryRelayStateStore(), "process-a")
        val payload = SafeSharePayload(
            eventId = "share-link-e1",
            review = SafeShareLinkReview.review(
                "https://docs.example.invalid/alpha/e1",
                "docs.example.invalid",
            ),
            privatePdfCopied = false,
        )
        core.rememberReviewedPayload(payload)
        core.updateSafetyContext(
            purpose = PURPOSE,
            roomId = ROOM_ALPHA,
            roomDisplay = DISPLAY_ALPHA,
            currentRole = ROLE_AUTHOR,
            allowedScope = SCOPE_ALPHA,
            linkAccess = "UNKNOWN",
            contentKind = ShareKind.LINK.name,
            reviewState = "LINK_ACCESS_ASK",
        )
        core.receiveSharedText(payload.eventId, payload.review.safePreview)
        core.proposeRoute(PURPOSE, ROOM_ALPHA)

        assertThrows(IllegalArgumentException::class.java) { core.confirmPendingRoute() }
        val blocked = core.productSnapshot()
        assertEquals("ASK", blocked.getString("decision_state"))
        assertEquals(0, blocked.getInt("active_draft_count"))

        core.updateSafetyContext(
            purpose = PURPOSE,
            roomId = ROOM_ALPHA,
            roomDisplay = DISPLAY_ALPHA,
            currentRole = ROLE_AUTHOR,
            allowedScope = SCOPE_ALPHA,
            linkAccess = "PROJECT_ONLY",
            contentKind = ShareKind.LINK.name,
            reviewState = "ROUTE_CONFIRMATION_REQUIRED",
        )
        val confirmed = core.confirmPendingRoute()
        assertEquals(1, confirmed.getInt("active_draft_count"))
        assertEquals(0, confirmed.getInt("external_dispatch_count"))
    }

    @Test
    fun confirmedRoomDoesNotBypassAccessCheckForNewLinkFingerprint() {
        val core = SafeShareCore(InMemoryRelayStateStore(), "process-link-reuse-boundary")
        establishInitialDraft(core, textPayload("share-established", "합성 확정 경로 메모"))

        val newLink = SafeSharePayload(
            eventId = "share-new-link",
            review = SafeShareLinkReview.review(
                "https://docs.example.invalid/alpha/new-link",
                "docs.example.invalid",
            ),
            privatePdfCopied = false,
        )
        core.rememberReviewedPayload(newLink)
        val unknown = core.receiveSharedText(newLink.eventId, newLink.review.safePreview)

        assertFalse(unknown.getBoolean("route_reused"))
        assertEquals(1, unknown.getInt("route_confirmation_required_count"))
        assertEquals("ASK", unknown.getString("status"))

        core.updateSafetyContext(
            purpose = PURPOSE,
            roomId = ROOM_ALPHA,
            roomDisplay = DISPLAY_ALPHA,
            currentRole = ROLE_AUTHOR,
            allowedScope = SCOPE_ALPHA,
            linkAccess = "PROJECT_ONLY",
            contentKind = ShareKind.LINK.name,
            reviewState = "ROUTE_CONFIRMATION_REQUIRED",
        )
        core.proposeRoute(PURPOSE, ROOM_ALPHA)
        val confirmed = core.confirmPendingRoute()

        assertEquals(2, confirmed.getInt("active_draft_count"))
        assertEquals(0, confirmed.getInt("external_dispatch_count"))
    }

    @Test
    fun roleOnlyChangeIsHeldUntilRealProcessEpochThenReplacesAffectedDraft() {
        val store = InMemoryRelayStateStore()
        val first = SafeShareCore(store, "process-a")
        val payload = textPayload("share-role", "합성 정산 메모")
        establishInitialDraft(first, payload)
        val oldDraftId = store.load().getJSONArray("draft_order").getString(0)

        val held = first.beginContextChange(
            purpose = PURPOSE,
            targetRoomId = ROOM_ALPHA,
            roomDisplay = DISPLAY_ALPHA,
            currentRole = "내부 승인 검토자",
            allowedScope = SCOPE_ALPHA,
            linkAccess = "NOT_APPLICABLE",
        )
        val pending = held.getJSONObject("pending_context_change")

        assertEquals("[\"CURRENT_ROLE\"]", pending.getJSONArray("changed_fields").toString())
        assertEquals("ASK", held.getString("decision_state"))
        assertEquals(1, held.getInt("held_draft_count"))
        assertThrows(IllegalArgumentException::class.java) {
            first.confirmPendingContextChange()
        }

        val relaunched = SafeShareCore(store, "process-b")
        assertEquals(1, relaunched.productSnapshot().getInt("process_epoch"))
        val recovered = relaunched.confirmPendingContextChange()
        val recovery = recovered.getJSONObject("last_recovery")
        val newDraftId = recovery.getJSONArray("replacement_draft_ids").getString(0)

        assertNotEquals(oldDraftId, newDraftId)
        assertEquals(1, recovered.getInt("active_draft_count"))
        assertEquals(0, recovered.getInt("held_draft_count"))
        assertEquals(1, recovered.getInt("cancelled_draft_count"))
        assertEquals("E4_RECOVERED", recovered.getString("episode"))
        assertEquals(0, recovered.getInt("external_dispatch_count"))
        assertTrue(recovered.isNull("pending_context_change"))
    }

    @Test
    fun shareArrivingDuringHeldContextChangeWaitsThenUsesRecoveredContext() {
        val store = InMemoryRelayStateStore()
        val first = SafeShareCore(store, "process-held-admission-a")
        establishInitialDraft(first, textPayload("share-held-existing", "합성 기존 초안"))

        val held = first.beginContextChange(
            purpose = PURPOSE,
            targetRoomId = ROOM_ALPHA,
            roomDisplay = DISPLAY_ALPHA,
            currentRole = "변경된 검토 역할",
            allowedScope = SCOPE_ALPHA,
            linkAccess = "NOT_APPLICABLE",
        )
        assertEquals(1, held.getInt("held_draft_count"))

        val incomingPayload = textPayload("share-held-incoming", "합성 보류 중 신규 초안")
        first.rememberReviewedPayload(incomingPayload)
        val incoming = first.receiveSharedText(
            incomingPayload.eventId,
            incomingPayload.review.safePreview,
        )

        assertFalse(incoming.getBoolean("route_reused"))
        assertEquals("ASK", incoming.getString("status"))
        assertTrue(incoming.isNull("draft_id"))
        assertEquals(1, first.productSnapshot().getInt("active_draft_count"))

        val relaunched = SafeShareCore(store, "process-held-admission-b")
        relaunched.productSnapshot()
        val recovered = relaunched.confirmPendingContextChange()
        val recovery = recovered.getJSONObject("last_recovery")

        assertEquals(1, recovery.getInt("invalidated_draft_count"))
        assertEquals(2, recovery.getInt("replacement_draft_count"))
        assertEquals(2, recovered.getInt("active_draft_count"))
        assertEquals(0, recovered.getInt("held_draft_count"))
        assertEquals(0, recovered.getInt("external_dispatch_count"))
    }

    @Test
    fun shareAdmittedDuringHoldUsesOriginalContextWhenE4ResolvesNoChange() {
        val store = InMemoryRelayStateStore()
        val first = SafeShareCore(store, "process-held-no-change-a")
        establishInitialDraft(first, textPayload("share-held-no-change-existing", "합성 기존 초안"))

        first.beginContextChange(
            purpose = PURPOSE,
            targetRoomId = ROOM_ALPHA,
            roomDisplay = DISPLAY_ALPHA,
            currentRole = "임시 변경 역할",
            allowedScope = SCOPE_ALPHA,
            linkAccess = "NOT_APPLICABLE",
        )
        val incomingPayload = textPayload("share-held-no-change-incoming", "합성 보류 신규 초안")
        first.rememberReviewedPayload(incomingPayload)
        val incoming = first.receiveSharedText(
            incomingPayload.eventId,
            incomingPayload.review.safePreview,
        )
        assertEquals("ASK", incoming.getString("status"))

        val relaunched = SafeShareCore(store, "process-held-no-change-b")
        relaunched.productSnapshot()
        val revised = relaunched.revisePendingContextChange(
            targetRoomId = ROOM_ALPHA,
            roomDisplay = DISPLAY_ALPHA,
            currentRole = ROLE_AUTHOR,
            allowedScope = SCOPE_ALPHA,
            linkAccess = "NOT_APPLICABLE",
        )
        assertEquals(0, revised.getJSONObject("pending_context_change")
            .getJSONArray("changed_fields").length())

        val recovered = relaunched.confirmPendingContextChange()
        val recovery = recovered.getJSONObject("last_recovery")
        assertEquals(0, recovery.getInt("invalidated_draft_count"))
        assertEquals(1, recovery.getInt("replacement_draft_count"))
        assertEquals(2, recovered.getInt("active_draft_count"))
        assertEquals(0, recovered.getInt("cancelled_draft_count"))
        assertEquals(0, recovered.getInt("external_dispatch_count"))
    }

    @Test
    fun roleChangeHoldsOnlyDraftBoundToItsPriorRole() {
        val store = InMemoryRelayStateStore()
        val core = SafeShareCore(store, "process-context-binding-a")
        establishInitialDraft(core, textPayload("share-role-old", "합성 이전 역할 메모"))
        val oldDraftId = store.load().getJSONArray("draft_order").getString(0)

        val currentRole = "현재 검토 역할"
        core.updateSafetyContext(
            purpose = PURPOSE,
            roomId = ROOM_ALPHA,
            roomDisplay = DISPLAY_ALPHA,
            currentRole = currentRole,
            allowedScope = SCOPE_ALPHA,
            linkAccess = "NOT_APPLICABLE",
            contentKind = ShareKind.TEXT.name,
            reviewState = "ROUTE_CONFIRMED",
        )
        val currentPayload = textPayload("share-role-current", "합성 현재 역할 메모")
        core.rememberReviewedPayload(currentPayload)
        core.receiveSharedText(currentPayload.eventId, currentPayload.review.safePreview)
        val currentDraftId = store.load().getJSONArray("draft_order").getString(1)

        val held = core.beginContextChange(
            purpose = PURPOSE,
            targetRoomId = ROOM_ALPHA,
            roomDisplay = DISPLAY_ALPHA,
            currentRole = "변경된 검토 역할",
            allowedScope = SCOPE_ALPHA,
            linkAccess = "NOT_APPLICABLE",
        )

        assertEquals(1, held.getInt("held_draft_count"))
        assertEquals(
            "HELD",
            store.load().getJSONObject("drafts").getJSONObject(currentDraftId).getString("hold_state"),
        )
        assertFalse(
            store.load().getJSONObject("drafts").getJSONObject(oldDraftId).has("hold_state"),
        )
        assertEquals(
            currentRole,
            store.load().getJSONObject("drafts").getJSONObject(currentDraftId)
                .getJSONObject("context_binding").getString("current_role"),
        )
    }

    @Test
    fun e4TargetRevisionExpandsAnEarlierRoleOnlyHoldToAllOldTargetDrafts() {
        val store = InMemoryRelayStateStore()
        val first = SafeShareCore(store, "process-e4-expansion-a")
        establishInitialDraft(first, textPayload("share-e4-old", "합성 이전 대상 초안"))
        val oldDraftId = store.load().getJSONArray("draft_order").getString(0)

        val currentRole = "현재 검토 역할"
        first.updateSafetyContext(
            purpose = PURPOSE,
            roomId = ROOM_ALPHA,
            roomDisplay = DISPLAY_ALPHA,
            currentRole = currentRole,
            allowedScope = SCOPE_ALPHA,
            linkAccess = "NOT_APPLICABLE",
            contentKind = ShareKind.TEXT.name,
            reviewState = "ROUTE_CONFIRMED",
        )
        val currentPayload = textPayload("share-e4-current", "합성 현재 대상 초안")
        first.rememberReviewedPayload(currentPayload)
        first.receiveSharedText(currentPayload.eventId, currentPayload.review.safePreview)
        val currentDraftId = store.load().getJSONArray("draft_order").getString(1)

        val held = first.beginContextChange(
            purpose = PURPOSE,
            targetRoomId = ROOM_ALPHA,
            roomDisplay = DISPLAY_ALPHA,
            currentRole = "변경된 검토 역할",
            allowedScope = SCOPE_ALPHA,
            linkAccess = "NOT_APPLICABLE",
        )
        assertEquals(1, held.getInt("held_draft_count"))
        assertEquals(
            currentDraftId,
            held.getJSONObject("pending_context_change").getJSONArray("affected_draft_ids").getString(0),
        )

        val relaunched = SafeShareCore(store, "process-e4-expansion-b")
        relaunched.productSnapshot()
        val revised = relaunched.revisePendingContextChange(
            targetRoomId = "room-project-beta",
            roomDisplay = "프로젝트 베타 실무방",
            currentRole = "변경된 검토 역할",
            allowedScope = SCOPE_ALPHA,
            linkAccess = "NOT_APPLICABLE",
        )
        val pending = revised.getJSONObject("pending_context_change")
        val affected = pending.getJSONArray("affected_draft_ids").toString()
        assertEquals(2, pending.getInt("affected_draft_count"))
        assertTrue(affected.contains(oldDraftId))
        assertTrue(affected.contains(currentDraftId))
        assertEquals(
            "HELD",
            store.load().getJSONObject("drafts").getJSONObject(oldDraftId).getString("hold_state"),
        )

        val recovered = relaunched.confirmPendingContextChange()
        val recovery = recovered.getJSONObject("last_recovery")
        assertEquals(2, recovery.getInt("invalidated_draft_count"))
        assertEquals(2, recovery.getInt("replacement_draft_count"))
        assertEquals("room-project-beta", recovered.getJSONObject("route_rules").getString(PURPOSE))
        assertEquals(0, recovered.getInt("external_dispatch_count"))
    }

    @Test
    fun e4RevisionBackToOriginalTargetReleasesDraftNoLongerDependentOnCurrentChange() {
        val store = InMemoryRelayStateStore()
        val first = SafeShareCore(store, "process-e4-narrowing-a")
        establishInitialDraft(first, textPayload("share-e4-narrow-old", "합성 이전 역할 초안"))
        val oldDraftId = store.load().getJSONArray("draft_order").getString(0)

        val currentRole = "현재 검토 역할"
        first.updateSafetyContext(
            purpose = PURPOSE,
            roomId = ROOM_ALPHA,
            roomDisplay = DISPLAY_ALPHA,
            currentRole = currentRole,
            allowedScope = SCOPE_ALPHA,
            linkAccess = "NOT_APPLICABLE",
            contentKind = ShareKind.TEXT.name,
            reviewState = "ROUTE_CONFIRMED",
        )
        val currentPayload = textPayload("share-e4-narrow-current", "합성 현재 역할 초안")
        first.rememberReviewedPayload(currentPayload)
        first.receiveSharedText(currentPayload.eventId, currentPayload.review.safePreview)
        val currentDraftId = store.load().getJSONArray("draft_order").getString(1)

        val heldForTarget = first.beginContextChange(
            purpose = PURPOSE,
            targetRoomId = "room-project-beta",
            roomDisplay = "프로젝트 베타 실무방",
            currentRole = currentRole,
            allowedScope = SCOPE_ALPHA,
            linkAccess = "NOT_APPLICABLE",
        )
        assertEquals(2, heldForTarget.getInt("held_draft_count"))

        val relaunched = SafeShareCore(store, "process-e4-narrowing-b")
        relaunched.productSnapshot()
        val revised = relaunched.revisePendingContextChange(
            targetRoomId = ROOM_ALPHA,
            roomDisplay = DISPLAY_ALPHA,
            currentRole = "변경된 검토 역할",
            allowedScope = SCOPE_ALPHA,
            linkAccess = "NOT_APPLICABLE",
        )
        val pending = revised.getJSONObject("pending_context_change")

        assertEquals(1, pending.getInt("affected_draft_count"))
        assertEquals(currentDraftId, pending.getJSONArray("affected_draft_ids").getString(0))
        assertFalse(store.load().getJSONObject("drafts").getJSONObject(oldDraftId).has("hold_state"))
        assertEquals(1, revised.getInt("held_draft_count"))

        val recovered = relaunched.confirmPendingContextChange()
        val recovery = recovered.getJSONObject("last_recovery")
        assertEquals(1, recovery.getInt("invalidated_draft_count"))
        assertEquals(1, recovery.getInt("replacement_draft_count"))
        assertEquals(2, recovered.getInt("active_draft_count"))
        assertEquals(0, recovered.getInt("external_dispatch_count"))
    }

    @Test
    fun linkUncertaintyCanResolveToOldBoundaryWithoutReplacingDraft() {
        val store = InMemoryRelayStateStore()
        val first = SafeShareCore(store, "process-a")
        val rawLink = "https://docs.example.invalid/alpha/settlement-v2"
        val payload = SafeSharePayload(
            eventId = "share-link",
            review = SafeShareLinkReview.review(rawLink, "docs.example.invalid"),
            privatePdfCopied = false,
        )
        establishInitialDraft(first, payload, linkAccess = "PROJECT_ONLY")

        first.beginContextChange(
            purpose = PURPOSE,
            targetRoomId = ROOM_ALPHA,
            roomDisplay = DISPLAY_ALPHA,
            currentRole = ROLE_AUTHOR,
            allowedScope = SCOPE_ALPHA,
            linkAccess = "UNKNOWN",
        )
        val relaunched = SafeShareCore(store, "process-b")
        relaunched.productSnapshot()
        assertThrows(IllegalStateException::class.java) {
            relaunched.confirmPendingContextChange()
        }

        val revised = relaunched.revisePendingContextChange(
            targetRoomId = ROOM_ALPHA,
            roomDisplay = DISPLAY_ALPHA,
            currentRole = ROLE_AUTHOR,
            allowedScope = SCOPE_ALPHA,
            linkAccess = "PROJECT_ONLY",
        )
        assertEquals(0, revised.getJSONObject("pending_context_change")
            .getJSONArray("changed_fields").length())
        val recovered = relaunched.confirmPendingContextChange()
        val recovery = recovered.getJSONObject("last_recovery")
        val descriptor = recovered.getJSONObject("active_payload_descriptor")

        assertEquals(1, recovered.getInt("active_draft_count"))
        assertEquals(0, recovered.getInt("cancelled_draft_count"))
        assertEquals(0, recovery.getJSONArray("invalidated_draft_ids").length())
        assertTrue(recovery.getJSONArray("confirmed_question_fields").toString().contains("LINK_ACCESS"))
        assertEquals("PROJECT_ONLY", descriptor.getString("link_access"))
        assertEquals(
            descriptor.getString("content_sha256"),
            descriptor.getString("link_access_for_sha256"),
        )
        assertEquals(0, recovered.getInt("external_dispatch_count"))
    }

    @Test
    fun candidateSelectionUsesConfirmedOpaqueIdAndRejectsSpoofAndRevokedTarget() {
        val core = SafeShareCore(InMemoryRelayStateStore(), "process-a")
        core.setPurposeRoute(PURPOSE, ROOM_ALPHA)

        val selected = core.evaluateCandidateRooms(
            PURPOSE,
            listOf("room-spoof-alpha", "room-private-family", ROOM_ALPHA),
        )

        assertEquals("ACT", selected.getString("decision_state"))
        assertEquals(ROOM_ALPHA, selected.getString("selected_room_id"))
        assertTrue(selected.getJSONArray("excluded").toString().contains("room-spoof-alpha"))

        core.revokeTarget(ROOM_ALPHA)
        val revoked = core.evaluateCandidateRooms(PURPOSE, listOf(ROOM_ALPHA, "room-spoof-alpha"))
        assertEquals("ASK", revoked.getString("decision_state"))
        assertTrue(revoked.isNull("selected_room_id"))
        assertTrue(revoked.getJSONArray("excluded").toString().contains("REVOKED"))
    }

    @Test
    fun reviewedPayloadRestoresOnlyRedactedPreviewAndDeletionClearsDescriptor() {
        val store = InMemoryRelayStateStore()
        val first = SafeShareCore(store, "process-a")
        val raw = "정산 설정 api_key=SCPC_SYNTHETIC_ONLY_SECRET"
        val payload = textPayload("share-secret", raw)

        first.rememberReviewedPayload(payload)
        val relaunched = SafeShareCore(store, "process-b")
        val restored = relaunched.restoreReviewedPayload()

        assertEquals(payload.eventId, restored?.eventId)
        assertTrue(restored?.review?.safePreview.orEmpty().contains("[REDACTED]"))
        assertFalse(store.load().toString().contains("SCPC_SYNTHETIC_ONLY_SECRET"))

        relaunched.setPurposeRoute(PURPOSE, ROOM_ALPHA)
        relaunched.receiveSharedText(payload.eventId, payload.review.safePreview)
        relaunched.deleteShareMemory(payload.eventId)

        assertNull(relaunched.restoreReviewedPayload())
        assertFalse(store.load().has("active_payload_descriptor"))
    }

    @Test
    fun privatePdfCopyTokenSurvivesRelaunchButNeverAppearsInPublicSnapshot() {
        val store = InMemoryRelayStateStore()
        val token = "payload-12345678-1234-1234-1234-123456789abc.pdf"
        val payload = SafeSharePayload(
            eventId = "share-private-pdf",
            review = SafeSharePolicy.review(ShareKind.PDF, "synthetic private PDF"),
            privatePdfCopied = true,
            privatePdfCopyToken = token,
        )
        val first = SafeShareCore(store, "process-pdf-a")
        first.rememberReviewedPayload(payload)
        first.receiveSharedText(payload.eventId, payload.review.safePreview)

        assertEquals(token, first.privatePdfCopyToken(payload.eventId))
        val snapshot = first.memorySnapshot()
        assertFalse(snapshot.toString().contains(token))
        assertTrue(snapshot.toString().contains("private_pdf_copy_bound"))

        val relaunched = SafeShareCore(store, "process-pdf-b")
        assertEquals(token, relaunched.privatePdfCopyToken())
        relaunched.deleteShareMemory(payload.eventId)
        assertNull(relaunched.privatePdfCopyToken(payload.eventId))
        assertFalse(relaunched.memorySnapshot().toString().contains(token))
    }

    @Test
    fun pendingDescriptorDeletionTargetsItsEventBeforeAnyEnvelopeExists() {
        val store = InMemoryRelayStateStore()
        val core = SafeShareCore(store, "process-pending-delete")
        val payload = SafeSharePayload(
            eventId = "pending-pdf-event",
            review = SafeSharePolicy.review(ShareKind.PDF, "synthetic pending PDF"),
            privatePdfCopied = true,
            privatePdfCopyToken = "payload-aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee.pdf",
        )
        core.rememberReviewedPayload(payload)

        assertFalse(core.hasShareEnvelope(payload.eventId))
        assertEquals(payload.privatePdfCopyToken, core.privatePdfCopyToken(payload.eventId))
        val deleted = core.deleteShareMemory(payload.eventId)

        assertTrue(deleted.getBoolean("deleted"))
        assertNull(core.restoreReviewedPayload())
        assertNull(core.privatePdfCopyToken(payload.eventId))
        assertEquals(
            "DELETED",
            core.receiveSharedText(payload.eventId, payload.review.safePreview).getString("status"),
        )
    }

    @Test
    fun pendingEventDeletionCannotDeleteAnOlderEnvelope() {
        val store = InMemoryRelayStateStore()
        val core = SafeShareCore(store, "process-delete-order")
        val older = textPayload("older-envelope-event", "older synthetic memo")
        core.rememberReviewedPayload(older)
        core.receiveSharedText(older.eventId, older.review.safePreview)
        val pending = textPayload("new-pending-event", "new pending synthetic memo")
        core.rememberReviewedPayload(pending)

        val deleted = core.deleteShareMemory(pending.eventId)

        assertTrue(deleted.getBoolean("deleted"))
        assertEquals(1, deleted.getJSONArray("shares").length())
        assertEquals(
            older.eventId,
            deleted.getJSONArray("shares").getJSONObject(0).getString("event_id"),
        )
        assertTrue(core.hasShareEnvelope(older.eventId))
        assertFalse(core.hasShareEnvelope(pending.eventId))
        assertNull(core.restoreReviewedPayload())
    }

    @Test
    fun privatePdfFilesystemBoundaryDeletesOnlyValidatedBoundFilename() {
        val root = Files.createTempDirectory("safe-share-pdf-test").toFile()
        try {
            val token = "synthetic-123456789.pdf"
            val bound = File(root, token).apply { writeBytes(byteArrayOf(1, 2, 3)) }
            val neighbor = File(root, "do-not-delete.txt").apply { writeText("keep") }

            assertTrue(SafeSharePrivatePdfFiles.deleteBoundCopy(root, token))
            assertFalse(bound.exists())
            assertTrue(neighbor.exists())
            assertThrows(IllegalArgumentException::class.java) {
                SafeSharePrivatePdfFiles.deleteBoundCopy(root, "../do-not-delete.txt")
            }
            assertTrue(neighbor.exists())
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun selectiveImpactRecoveryReplansOnlyLinkedDraftWhileComparisonArmUsesPurposeWideSet() {
        fun runArm(selectiveImpactRecoveryEnabled: Boolean): org.json.JSONObject {
            val store = InMemoryRelayStateStore()
            val first = SafeShareCore(
                store = store,
                processMarker = "process-impact-a",
                selectiveImpactRecoveryEnabled = selectiveImpactRecoveryEnabled,
            )
            val firstPayload = SafeSharePayload(
                eventId = "share-impact-1",
                review = SafeShareLinkReview.review(
                    "https://docs.example.invalid/alpha/impact-1",
                    "docs.example.invalid",
                ),
                privatePdfCopied = false,
            )
            establishInitialDraft(first, firstPayload, linkAccess = "PROJECT_ONLY")

            val activePayload = SafeSharePayload(
                eventId = "share-impact-2",
                review = SafeShareLinkReview.review(
                    "https://docs.example.invalid/alpha/impact-2",
                    "docs.example.invalid",
                ),
                privatePdfCopied = false,
            )
            first.rememberReviewedPayload(activePayload)
            first.updateSafetyContext(
                purpose = PURPOSE,
                roomId = ROOM_ALPHA,
                roomDisplay = DISPLAY_ALPHA,
                currentRole = ROLE_AUTHOR,
                allowedScope = SCOPE_ALPHA,
                linkAccess = "PROJECT_ONLY",
                contentKind = ShareKind.LINK.name,
                reviewState = "ROUTE_CONFIRMED",
            )
            first.receiveSharedText(activePayload.eventId, activePayload.review.safePreview)
            val held = first.beginContextChange(
                purpose = PURPOSE,
                targetRoomId = ROOM_ALPHA,
                roomDisplay = DISPLAY_ALPHA,
                currentRole = ROLE_AUTHOR,
                allowedScope = SCOPE_ALPHA,
                linkAccess = "PUBLIC",
            )
            assertEquals(
                if (selectiveImpactRecoveryEnabled) "DEPENDENCY_SCOPED" else "PURPOSE_WIDE",
                held.getJSONObject("pending_context_change").getString("impact_selection_mode"),
            )

            val relaunched = SafeShareCore(
                store = store,
                processMarker = "process-impact-b",
                selectiveImpactRecoveryEnabled = selectiveImpactRecoveryEnabled,
            )
            relaunched.productSnapshot()
            return relaunched.confirmPendingContextChange()
        }

        val full = runArm(selectiveImpactRecoveryEnabled = true)
        val broad = runArm(selectiveImpactRecoveryEnabled = false)
        val fullRecovery = full.getJSONObject("last_recovery")
        val broadRecovery = broad.getJSONObject("last_recovery")

        assertEquals(1, fullRecovery.getInt("invalidated_draft_count"))
        assertEquals(1, fullRecovery.getInt("replacement_draft_count"))
        assertEquals(2, broadRecovery.getInt("invalidated_draft_count"))
        assertEquals(2, broadRecovery.getInt("replacement_draft_count"))
        assertEquals(0, full.getInt("external_dispatch_count"))
        assertEquals(0, broad.getInt("external_dispatch_count"))
    }

    private fun establishInitialDraft(
        core: SafeShareCore,
        payload: SafeSharePayload,
        linkAccess: String = "NOT_APPLICABLE",
    ) {
        core.rememberReviewedPayload(payload)
        core.updateSafetyContext(
            purpose = PURPOSE,
            roomId = ROOM_ALPHA,
            roomDisplay = DISPLAY_ALPHA,
            currentRole = ROLE_AUTHOR,
            allowedScope = SCOPE_ALPHA,
            linkAccess = linkAccess,
            contentKind = payload.review.kind.name,
            reviewState = "REVIEWED",
        )
        core.receiveSharedText(payload.eventId, payload.review.safePreview)
        core.proposeRoute(PURPOSE, ROOM_ALPHA)
        core.confirmPendingRoute()
        core.updateSafetyContext(
            purpose = PURPOSE,
            roomId = ROOM_ALPHA,
            roomDisplay = DISPLAY_ALPHA,
            currentRole = ROLE_AUTHOR,
            allowedScope = SCOPE_ALPHA,
            linkAccess = linkAccess,
            contentKind = payload.review.kind.name,
            reviewState = "ROUTE_CONFIRMED",
        )
    }

    private fun textPayload(eventId: String, text: String): SafeSharePayload = SafeSharePayload(
        eventId = eventId,
        review = SafeSharePolicy.review(ShareKind.TEXT, text),
        privatePdfCopied = false,
    )

    private companion object {
        const val PURPOSE = "알파 3분기 정산 공유"
        const val ROOM_ALPHA = "room-project-alpha"
        const val DISPLAY_ALPHA = "프로젝트 알파 실무방"
        const val ROLE_AUTHOR = "정산 작성자"
        const val SCOPE_ALPHA = "알파 내부 구성원"
    }
}
