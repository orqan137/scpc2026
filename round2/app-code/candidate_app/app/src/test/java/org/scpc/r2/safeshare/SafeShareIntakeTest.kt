package org.scpc.r2.safeshare

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SafeShareIntakeTest {
    @Test
    fun linksOnTheSameHostKeepAHostOnlyPreviewButUseDistinctRawUrlFingerprints() {
        val firstRaw = "https://docs.example.invalid/alpha-q3?access_token=first-secret"
        val secondRaw = "https://docs.example.invalid/beta-q3?access_token=second-secret"

        val first = SafeShareLinkReview.review(firstRaw, "docs.example.invalid")
        val second = SafeShareLinkReview.review(secondRaw, "docs.example.invalid")

        assertEquals(first.safePreview, second.safePreview)
        assertTrue(first.safePreview.contains("docs.example.invalid"))
        assertFalse(first.safePreview.contains("alpha-q3"))
        assertFalse(first.safePreview.contains("access_token"))
        assertFalse(first.safePreview.contains("first-secret"))
        assertNotEquals(first.contentSha256, second.contentSha256)
        assertEquals(
            SafeSharePolicy.sha256(firstRaw.trim().take(SafeSharePolicy.MAX_REVIEW_CHARS)),
            first.contentSha256,
        )
        assertTrue(first.requiresLinkAccessConfirmation)
    }

    @Test
    fun linkFingerprintUsesTheSameBoundedInputLimitAsTheReviewPolicy() {
        val commonPrefix = "https://docs.example.invalid/" +
            "a".repeat(SafeSharePolicy.MAX_REVIEW_CHARS)
        val first = SafeShareLinkReview.review("${commonPrefix}first", "docs.example.invalid")
        val second = SafeShareLinkReview.review("${commonPrefix}second", "docs.example.invalid")

        assertEquals(first.contentSha256, second.contentSha256)
    }
}
