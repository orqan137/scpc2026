package org.scpc.r2.safeshare

import android.content.Context
import android.content.Intent
import android.database.Cursor
import android.net.Uri
import android.os.Build
import android.provider.OpenableColumns
import java.io.File
import java.io.FileOutputStream
import java.util.UUID

data class SafeSharePayload(
    val eventId: String,
    val review: SafeShareReview,
    val privatePdfCopied: Boolean,
    /** Opaque app-private filename, never a source URI or externally visible path. */
    val privatePdfCopyToken: String? = null,
)

sealed class SafeShareIntakeResult {
    data class Accepted(val payload: SafeSharePayload) : SafeShareIntakeResult()
    data class Rejected(val reason: String) : SafeShareIntakeResult()
    data object NotShareIntent : SafeShareIntakeResult()
}

/**
 * Reads ACTION_SEND data once, copies PDFs into app-private storage while the
 * grant is alive, and returns only a redacted review summary to durable state.
 */
object SafeShareIntake {
    private const val MAX_PDF_BYTES = 8 * 1024 * 1024

    fun fromManualText(text: String): SafeShareIntakeResult {
        if (text.isBlank()) return SafeShareIntakeResult.Rejected("검토할 텍스트를 입력하세요.")
        val kind = if (text.trim().startsWith("http://") || text.trim().startsWith("https://")) {
            ShareKind.LINK
        } else {
            ShareKind.TEXT
        }
        return SafeShareIntakeResult.Accepted(
            SafeSharePayload(
                eventId = "manual-${UUID.randomUUID()}",
                review = if (kind == ShareKind.LINK) reviewLink(text) else SafeSharePolicy.review(kind, text),
                privatePdfCopied = false,
            ),
        )
    }

    fun fromIntent(context: Context, intent: Intent?): SafeShareIntakeResult {
        if (intent?.action != Intent.ACTION_SEND) return SafeShareIntakeResult.NotShareIntent
        val mime = intent.type.orEmpty().lowercase()
        val stream = sharedStreamUri(intent)
        if (mime == "application/pdf" || stream != null && mime.contains("pdf")) {
            val uri = stream ?: return SafeShareIntakeResult.Rejected("PDF URI가 없어 검토할 수 없습니다.")
            return runCatching { copyPdf(context, uri) }
                .fold(
                    onSuccess = { copied ->
                        SafeShareIntakeResult.Accepted(
                            SafeSharePayload(
                                eventId = "pdf-${UUID.randomUUID()}",
                                review = SafeSharePolicy.review(
                                    ShareKind.PDF,
                                    "PDF: ${copied.displayName} (${copied.byteCount} bytes, app-private copy)",
                                ),
                                privatePdfCopied = true,
                                privatePdfCopyToken = copied.copyToken,
                            ),
                        )
                    },
                    onFailure = { SafeShareIntakeResult.Rejected(it.message ?: "PDF를 안전하게 복사하지 못했습니다.") },
                )
        }

        val text = intent.getStringExtra(Intent.EXTRA_TEXT)?.trim().orEmpty()
        return if (text.isEmpty()) {
            SafeShareIntakeResult.Rejected("텍스트 또는 PDF 공유만 지원합니다.")
        } else {
            fromManualText(text)
        }
    }

    private fun sharedStreamUri(intent: Intent): Uri? =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            intent.getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java)
        } else {
            @Suppress("DEPRECATION")
            intent.getParcelableExtra(Intent.EXTRA_STREAM)
        }

    private fun reviewLink(raw: String): SafeShareReview {
        val uri = Uri.parse(raw.trim())
        return SafeShareLinkReview.review(raw = raw, host = uri.host)
    }

    private fun copyPdf(context: Context, uri: Uri): CopiedPdf {
        val directory = File(context.filesDir, "safe-share-pdf").apply {
            check(exists() || mkdirs()) { "앱 내부 PDF 폴더를 만들 수 없습니다." }
        }
        val temporary = File(directory, ".incoming-${UUID.randomUUID()}.tmp")
        var copied = 0L
        try {
            context.contentResolver.openInputStream(uri)?.use { input ->
                FileOutputStream(temporary).use { output ->
                    val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                    while (true) {
                        val count = input.read(buffer)
                        if (count < 0) break
                        copied += count
                        check(copied <= MAX_PDF_BYTES) { "PDF가 8 MiB 제한을 초과했습니다." }
                        output.write(buffer, 0, count)
                    }
                    output.fd.sync()
                }
            } ?: error("PDF URI를 열 수 없습니다.")
            check(copied > 0L) { "빈 PDF는 검토할 수 없습니다." }
            val destination = File(directory, "payload-${UUID.randomUUID()}.pdf")
            check(temporary.renameTo(destination)) { "PDF private copy를 확정하지 못했습니다." }
            return CopiedPdf(displayName(context, uri), copied, destination.name)
        } finally {
            if (temporary.exists()) temporary.delete()
        }
    }

    private fun displayName(context: Context, uri: Uri): String {
        val cursor: Cursor? = context.contentResolver.query(
            uri,
            arrayOf(OpenableColumns.DISPLAY_NAME),
            null,
            null,
            null,
        )
        cursor?.use {
            if (it.moveToFirst()) return it.getString(0).orEmpty().take(160).ifEmpty { "shared.pdf" }
        }
        return "shared.pdf"
    }

    private data class CopiedPdf(
        val displayName: String,
        val byteCount: Long,
        val copyToken: String,
    )
}

/**
 * Filesystem boundary for app-private PDF copies. Only generated opaque
 * filenames are accepted, which prevents a persisted token from escaping the
 * dedicated directory even if local state is malformed.
 */
internal object SafeSharePrivatePdfFiles {
    private val TOKEN = Regex("(?:payload|synthetic)-[A-Za-z0-9-]{1,100}\\.pdf")

    fun isValidToken(token: String?): Boolean = token != null && TOKEN.matches(token)

    fun deleteBoundCopy(directory: File, token: String): Boolean {
        require(isValidToken(token)) { "유효하지 않은 private PDF copy token입니다." }
        val root = directory.canonicalFile
        val target = File(root, token).canonicalFile
        require(target.parentFile == root) { "private PDF 경로가 전용 폴더를 벗어났습니다." }
        return !target.exists() || target.delete()
    }
}

/**
 * Pure link-review boundary shared by Android intake and local JVM tests.
 *
 * Only the host is allowed into the durable preview. The fingerprint is based
 * on the same bounded original input used by [SafeSharePolicy], so two links
 * on one host do not accidentally share an access-confirmation identity.
 */
internal object SafeShareLinkReview {
    fun review(raw: String, host: String?): SafeShareReview {
        val safeHost = host
            ?.trim()
            ?.take(253)
            ?.takeIf(String::isNotEmpty)
            ?: "unknown-host"
        val boundedRaw = raw.trim().take(SafeSharePolicy.MAX_REVIEW_CHARS)
        return SafeSharePolicy.review(
            ShareKind.LINK,
            "링크: $safeHost (접근 상태 미확인)",
        ).copy(contentSha256 = SafeSharePolicy.sha256(boundedRaw))
    }
}
