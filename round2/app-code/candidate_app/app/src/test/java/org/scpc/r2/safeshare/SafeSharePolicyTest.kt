package org.scpc.r2.safeshare

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SafeSharePolicyTest {
    @Test
    fun namedSecretIsRedactedBeforeItCanEnterDraftState() {
        val review = SafeSharePolicy.review(
            ShareKind.CONFIG,
            "endpoint=https://example.test api_key=super-secret-value",
        )

        assertTrue(review.secretDetected)
        assertTrue(review.safePreview.contains("api_key=[REDACTED]"))
        assertFalse(review.safePreview.contains("super-secret-value"))
    }

    @Test
    fun linksNeedAnExplicitAccessConfirmation() {
        val review = SafeSharePolicy.review(ShareKind.LINK, "링크: docs.example.test")

        assertTrue(review.requiresLinkAccessConfirmation)
        assertFalse(SafeSharePolicy.linkAccessConfirmed("UNKNOWN"))
        assertTrue(SafeSharePolicy.linkAccessConfirmed("PROJECT_ONLY"))
    }

    @Test
    fun safeContextSurvivesRelaunchButIsRemovedByUserReset() {
        val store = InMemoryRelayStateStore()
        val first = SafeShareCore(store, "process-a")
        first.updateSafetyContext(
            purpose = "분기 정산",
            roomDisplay = "프로젝트 알파",
            currentRole = "작성자",
            allowedScope = "프로젝트 알파 구성원",
            linkAccess = "PROJECT_ONLY",
            contentKind = "LINK",
            reviewState = "HELD_FOR_EXCEPTION",
        )

        val relaunched = SafeShareCore(store, "process-b")
        assertEquals(
            "프로젝트 알파",
            relaunched.productSnapshot().getJSONObject("safe_share_context").getString("room_display"),
        )

        relaunched.resetAllUserData()
        assertEquals(0, relaunched.productSnapshot().getJSONObject("safe_share_context").length())
    }
}
