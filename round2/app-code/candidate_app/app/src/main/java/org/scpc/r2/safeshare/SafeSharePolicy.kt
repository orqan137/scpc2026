package org.scpc.r2.safeshare

import java.security.MessageDigest

enum class ShareKind {
    TEXT,
    LINK,
    PDF,
    CONFIG,
}

data class SafeShareReview(
    val kind: ShareKind,
    val safePreview: String,
    val contentSha256: String,
    val secretDetected: Boolean,
    val requiresLinkAccessConfirmation: Boolean,
    val recommendation: String,
)

/** Pure policy used by manual entry and Android ACTION_SEND intake. */
object SafeSharePolicy {
    const val MAX_REVIEW_CHARS = 4_096
    const val PERMISSION_CAN_SHARE = "CAN_SHARE"
    const val PERMISSION_READ_ONLY = "READ_ONLY"

    /**
     * A downgraded role keeps read access to a room but may no longer share out
     * of it.  This is a current-authority rule: the room and the allowed scope
     * can both still be valid while the role alone blocks the draft.
     */
    fun canShare(sharePermission: String): Boolean =
        sharePermission.trim().uppercase() != PERMISSION_READ_ONLY

    /**
     * A room is outside the confirmed scope when none of its members belong to
     * that scope group.  Membership comes from the same synthetic input the
     * production core consumes, so the exclusion reason is checkable.
     */
    /** A room the current role may read but no longer share into. */
    fun roomIsReadOnly(roomId: String, readOnlyRoomIds: Collection<String>): Boolean =
        readOnlyRoomIds.any { it.trim() == roomId.trim() }

    fun roomOutsideScope(memberScopes: Collection<String>, allowedScope: String): Boolean {
        val scope = allowedScope.trim()
        if (scope.isEmpty() || memberScopes.isEmpty()) return false
        return memberScopes.none { it.trim() == scope }
    }

    private val namedSecret = Regex(
        """(?i)\b(api[_-]?key|access[_-]?token|refresh[_-]?token|password|passwd|secret)\s*[:=]\s*([^\s,;]+)""",
    )
    private val awsAccessKey = Regex("""\bAKIA[0-9A-Z]{16}\b""")

    fun review(kind: ShareKind, raw: String): SafeShareReview {
        val bounded = raw.trim().take(MAX_REVIEW_CHARS)
        val namedMatch = namedSecret.containsMatchIn(bounded)
        val awsMatch = awsAccessKey.containsMatchIn(bounded)
        val secretDetected = namedMatch || awsMatch
        val redacted = awsAccessKey.replace(
            namedSecret.replace(bounded) { match ->
                "${match.groupValues[1]}=[REDACTED]"
            },
            "[REDACTED_AWS_KEY]",
        )
        val requiresAccessConfirmation = kind == ShareKind.LINK
        return SafeShareReview(
            kind = kind,
            safePreview = redacted.ifEmpty { "(내용 없음)" },
            contentSha256 = sha256(bounded),
            secretDetected = secretDetected,
            requiresLinkAccessConfirmation = requiresAccessConfirmation,
            recommendation = when {
                secretDetected -> "민감한 값은 가린 형태로만 저장합니다. 값이 필요하면 별도 승인된 채널을 사용하세요."
                requiresAccessConfirmation -> "링크 접근 범위를 확인하기 전에는 공유를 준비하지 않습니다."
                else -> "외부 전송 없이 이 기기 안에서 공유할 내용을 준비합니다."
            },
        )
    }

    fun linkAccessConfirmed(value: String): Boolean =
        value.trim().uppercase() in setOf("CONFIRMED", "PROJECT_ONLY", "TEAM_ONLY", "PUBLIC")

    fun sha256(value: String): String =
        MessageDigest.getInstance("SHA-256")
            .digest(value.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
}
