package org.scpc.r2.safeshare

import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Typeface
import android.net.Uri
import android.os.Bundle
import android.provider.DocumentsContract
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import org.json.JSONArray
import org.json.JSONObject
import org.scpc.r2.probe.CanonicalJson
import org.scpc.r2.probe.ProbeInputDocument
import org.scpc.r2.probe.ProbeInputParser
import java.security.MessageDigest
import java.util.UUID

/**
 * Public rehearsal entry only.
 *
 * It rejects official input and never binds to or starts ScpcProbeService.
 * The protected official path remains owned entirely by the starter AAR.
 */
class PublicProbeActivity : Activity() {
    private var inputDocument: ProbeInputDocument? = null
    private var inputRaw: String? = null
    private var result: JSONObject? = null
    private lateinit var status: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(buildContent())
        restorePublicState()
    }

    @Deprecated("Uses the platform file picker for a dependency-free demo")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (resultCode != RESULT_OK || data?.data == null) return
        when (requestCode) {
            REQUEST_IMPORT -> importPublicInput(data.data!!)
            REQUEST_EXPORT -> exportPublicResult(data.data!!)
            REQUEST_EVIDENCE_EXPORT -> exportLatestEvidence(data.data!!)
        }
    }

    private fun buildContent(): View {
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(24), dp(20), dp(32))
        }
        content.addView(TextView(this).apply {
            text = "공개 검증 도구"
            textSize = 25f
            setTypeface(typeface, Typeface.BOLD)
        })
        content.addView(TextView(this).apply {
            text = "공개 연습용 입력만 실행합니다. 공식 점수나 결과는 표시하지 않습니다."
            textSize = 15f
            setPadding(0, dp(6), 0, dp(18))
        })

        content.addView(probeButton(
            label = "1. 공개 입력 파일 가져오기",
            description = "SCPC_PROBE_IMPORT",
        ) {
            val picker = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
                addCategory(Intent.CATEGORY_OPENABLE)
                type = "application/json"
            }
            @Suppress("DEPRECATION")
            startActivityForResult(picker, REQUEST_IMPORT)
        })

        content.addView(probeButton(
            label = "2. 앱의 판단 로직으로 실행",
            description = "SCPC_PROBE_RUN",
        ) {
            runPublicProbe()
        })

        content.addView(probeButton(
            label = "3. 결과 파일 저장",
            description = "SCPC_PROBE_EXPORT",
        ) {
            if (result == null) {
                showStatus("실행을 먼저 마쳐야 합니다.")
            } else {
                val creator = Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
                    addCategory(Intent.CATEGORY_OPENABLE)
                    type = "application/json"
                    putExtra(Intent.EXTRA_TITLE, "PROBE_RESULT.json")
                }
                @Suppress("DEPRECATION")
                startActivityForResult(creator, REQUEST_EXPORT)
            }
        })

        content.addView(probeButton(
            label = "4. 실행 기록 저장",
            description = "SCPC_EVIDENCE_EXPORT",
        ) {
            if (RunEvidenceArchive.from(this).latestCompleted() == null) {
                showStatus("저장할 실행 기록이 없습니다.")
            } else {
                val picker = Intent(Intent.ACTION_OPEN_DOCUMENT_TREE).apply {
                    addFlags(
                        Intent.FLAG_GRANT_READ_URI_PERMISSION or
                            Intent.FLAG_GRANT_WRITE_URI_PERMISSION,
                    )
                }
                @Suppress("DEPRECATION")
                startActivityForResult(picker, REQUEST_EVIDENCE_EXPORT)
            }
        })

        status = TextView(this).apply {
            textSize = 14f
            typeface = Typeface.MONOSPACE
            setTextIsSelectable(true)
            setPadding(0, dp(20), 0, 0)
        }
        content.addView(status, matchWidth())
        return ScrollView(this).apply { addView(content) }
    }

    private fun importPublicInput(uri: Uri) {
        runCatching {
            val raw = contentResolver.openInputStream(uri)
                ?.bufferedReader(Charsets.UTF_8)
                ?.use { it.readText() }
                ?: error("선택한 파일을 읽을 수 없습니다.")
            require(raw.toByteArray(Charsets.UTF_8).size <= MAX_PUBLIC_INPUT_BYTES) {
                "입력 파일이 1 MiB를 넘습니다."
            }
            val parsed = ProbeInputParser.parsePublic(raw)
            inputRaw = raw
            inputDocument = parsed
            result = null
            preferences().edit()
                .putString(KEY_INPUT, raw)
                .remove(KEY_RESULT)
                .apply()
            parsed
        }.onSuccess { parsed ->
            showStatus(
                "IMPORT 성공\npack=${parsed.probePackId}\nsteps=${parsed.steps.size}",
            )
        }.onFailure {
            inputRaw = null
            inputDocument = null
            result = null
            showStatus("IMPORT 실패: ${it.message}")
        }
    }

    private fun runPublicProbe() {
        val document = inputDocument
        val raw = inputRaw
        if (document == null || raw == null) {
            showStatus("공개 입력 파일을 먼저 가져오세요.")
            return
        }

        showStatus("실행 중: 0/${document.steps.size}")
        val runId = "PUBLIC-UI-${UUID.randomUUID()}"
        val binding = SafeShareProbeBridge.publicBinding(document, runId)
        val bridge = SafeShareProbeBridge.from(this)
        runCatching {
            bridge.begin(binding)
            val stepResults = JSONArray()
            document.steps.forEachIndexed { index, step ->
                stepResults.put(bridge.execute(binding, step))
                showStatus("실행 중: ${index + 1}/${document.steps.size}")
            }
            val evidenceIds = bridge.finish(binding)
            buildPublicResult(
                input = document,
                raw = raw,
                stepResults = stepResults,
                evidenceIds = evidenceIds,
                runId = runId,
            )
        }.onSuccess { built ->
            result = built
            preferences().edit()
                .putString(KEY_RESULT, built.toString())
                .putString(KEY_LAST_RUN_ID, runId)
                .apply()
            showStatus(
                "RUN 성공\nsteps=${document.steps.size}\n" +
                    "result_digest=${built.getString("result_digest")}\n" +
                    "실제 재시작 검증은 공식 Runner가 수행합니다.",
            )
        }.onFailure {
            bridge.abort(binding, it.message ?: it.javaClass.simpleName)
            result = null
            showStatus("RUN 실패: ${it.message}")
        }
    }

    private fun buildPublicResult(
        input: ProbeInputDocument,
        raw: String,
        stepResults: JSONArray,
        evidenceIds: List<String>,
        runId: String,
    ): JSONObject =
        PublicProbeResultBuilder.build(
            input = input,
            rawInput = raw,
            stepResults = stepResults,
            evidenceIds = evidenceIds,
            missionAdapterSha256 = missionAdapterSha256(),
            releaseBinding = releaseBinding(input.releaseAttestationId),
            runId = runId,
        )

    private fun releaseBinding(releaseAttestationId: String): JSONObject {
        val packageInfo = packageManager.getPackageInfo(
            packageName,
            PackageManager.GET_SIGNING_CERTIFICATES,
        )
        val signer = packageInfo.signingInfo?.apkContentsSigners?.firstOrNull()
            ?: error("앱 서명 인증서를 읽을 수 없습니다.")
        return JSONObject()
            .put("package_name", packageName)
            .put("version_name", packageInfo.versionName ?: "UNKNOWN")
            .put("version_code", packageInfo.longVersionCode.toString())
            .put("signing_certificate_sha256", sha256(signer.toByteArray()))
            .put("release_attestation_id", releaseAttestationId)
    }

    private fun missionAdapterSha256(): String =
        assets.open("MISSION_ADAPTER.json").use { sha256(it.readBytes()) }

    private fun exportPublicResult(uri: Uri) {
        val output = result ?: return
        runCatching {
            contentResolver.openOutputStream(uri, "wt")
                ?.bufferedWriter(Charsets.UTF_8)
                ?.use { it.write(CanonicalJson.encode(output)) }
                ?: error("선택한 위치에 쓸 수 없습니다.")
        }.onSuccess {
            showStatus("EXPORT 성공\n위치=$uri")
        }.onFailure {
            showStatus("EXPORT 실패: ${it.message}")
        }
    }

    private fun exportLatestEvidence(treeUri: Uri) {
        runCatching {
            val archive = RunEvidenceArchive.from(this)
            val snapshot = archive.latestCompleted()
                ?: error("저장할 실행 기록이 없습니다.")
            val evidenceDirectory = createFreshEvidenceDirectory(treeUri)
            snapshot.records.forEach { (evidenceId, encoded) ->
                val name = "$evidenceId.json"
                val outputUri = DocumentsContract.createDocument(
                    contentResolver,
                    evidenceDirectory,
                    "application/json",
                    name,
                ) ?: error("evidence 파일을 만들 수 없습니다: $name")
                check(documentName(outputUri) == name) {
                    "저장소가 evidence 파일명을 변경했습니다: $name"
                }
                contentResolver.openOutputStream(outputUri, "wt")
                    ?.bufferedWriter(Charsets.UTF_8)
                    ?.use { it.write(encoded) }
                    ?: error("evidence 파일을 쓸 수 없습니다: $name")
            }
            verifyExportedEvidence(evidenceDirectory, snapshot)
            snapshot
        }.onSuccess { snapshot ->
            showStatus(
                "실행 기록을 저장했습니다.\nrun=${snapshot.runId}\n" +
                    "파일 ${snapshot.records.size}개\n폴더=evidence",
            )
        }.onFailure {
            showStatus("실행 기록 저장 실패: ${it.message}")
        }
    }

    private fun createFreshEvidenceDirectory(treeUri: Uri): Uri {
        val parentId = DocumentsContract.getTreeDocumentId(treeUri)
        val parentUri = DocumentsContract.buildDocumentUriUsingTree(treeUri, parentId)
        check(listChildNames(parentUri).none { it == EVIDENCE_DIRECTORY_NAME }) {
            "선택한 위치에 실행 기록 폴더가 이미 있습니다. 빈 위치를 선택하세요."
        }
        return DocumentsContract.createDocument(
            contentResolver,
            parentUri,
            DocumentsContract.Document.MIME_TYPE_DIR,
            EVIDENCE_DIRECTORY_NAME,
        ) ?: error("evidence 폴더를 만들 수 없습니다.")
    }

    private fun verifyExportedEvidence(
        evidenceDirectory: Uri,
        snapshot: EvidenceArchiveSnapshot,
    ) {
        val expectedNames = snapshot.records.keys.mapTo(linkedSetOf()) { "$it.json" }
        val actual = listChildren(evidenceDirectory)
        check(actual.keys == expectedNames) {
            "저장된 evidence 파일 집합이 run 결과와 다릅니다."
        }
        actual.forEach { (name, uri) ->
            val text = contentResolver.openInputStream(uri)
                ?.bufferedReader(Charsets.UTF_8)
                ?.use { it.readText() }
                ?: error("저장한 evidence 파일을 다시 읽을 수 없습니다: $name")
            check(text.isNotBlank()) { "빈 evidence 파일입니다: $name" }
            val value = JSONObject(text)
            val id = name.removeSuffix(".json")
            val declared = when (value.getString("artifact_kind")) {
                "local_decision_receipt" -> value.getString("receipt_id")
                "observable_step_evidence" -> value.getString("evidence_id")
                else -> error("알 수 없는 evidence JSON입니다: $name")
            }
            check(declared == id) { "evidence 파일명과 내부 ID가 다릅니다: $name" }
            check(value.getJSONObject("run_binding").getString("run_id") == snapshot.runId) {
                "다른 run의 evidence가 섞였습니다: $name"
            }
        }
    }

    private fun listChildNames(parentUri: Uri): Set<String> =
        listChildren(parentUri).keys

    private fun listChildren(parentUri: Uri): Map<String, Uri> {
        val parentId = DocumentsContract.getDocumentId(parentUri)
        val childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(parentUri, parentId)
        val children = linkedMapOf<String, Uri>()
        contentResolver.query(
            childrenUri,
            arrayOf(
                DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                DocumentsContract.Document.COLUMN_DISPLAY_NAME,
            ),
            null,
            null,
            null,
        )?.use { cursor ->
            val idColumn = cursor.getColumnIndexOrThrow(
                DocumentsContract.Document.COLUMN_DOCUMENT_ID,
            )
            val nameColumn = cursor.getColumnIndexOrThrow(
                DocumentsContract.Document.COLUMN_DISPLAY_NAME,
            )
            while (cursor.moveToNext()) {
                val id = cursor.getString(idColumn)
                val name = cursor.getString(nameColumn)
                check(children.put(name, DocumentsContract.buildDocumentUriUsingTree(parentUri, id)) == null) {
                    "동일한 이름의 문서가 중복되었습니다: $name"
                }
            }
        } ?: error("선택한 위치를 읽을 수 없습니다.")
        return children
    }

    private fun documentName(uri: Uri): String {
        contentResolver.query(
            uri,
            arrayOf(DocumentsContract.Document.COLUMN_DISPLAY_NAME),
            null,
            null,
            null,
        )?.use { cursor ->
            check(cursor.moveToFirst()) { "생성한 문서를 확인할 수 없습니다." }
            return cursor.getString(0)
        }
        error("생성한 문서 이름을 읽을 수 없습니다.")
    }

    private fun restorePublicState() {
        val raw = preferences().getString(KEY_INPUT, null)
        if (raw == null) {
            showStatus("공개 입력 파일을 가져오세요.")
            return
        }
        runCatching {
            inputDocument = ProbeInputParser.parsePublic(raw)
            inputRaw = raw
            result = preferences().getString(KEY_RESULT, null)?.let(::JSONObject)
        }.onSuccess {
            showStatus(
                "이전 공개 입력 복구\nsteps=${inputDocument?.steps?.size ?: 0}\n" +
                    if (result == null) "실행 전" else "실행 결과 있음",
            )
        }.onFailure {
            preferences().edit().clear().apply()
            showStatus("이전 공개 입력을 불러오지 못했습니다: ${it.message}")
        }
    }

    private fun preferences() =
        getSharedPreferences("safe-share-public-ui", MODE_PRIVATE)

    private fun probeButton(
        label: String,
        description: String,
        action: () -> Unit,
    ): Button = Button(this).apply {
        text = label
        isAllCaps = false
        contentDescription = description
        isFocusable = true
        setOnClickListener { action() }
    }

    private fun showStatus(message: String) {
        if (::status.isInitialized) status.text = message
    }

    private fun matchWidth(): LinearLayout.LayoutParams =
        LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT,
        )

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    private fun sha256(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256")
            .digest(bytes)
            .joinToString("") { "%02x".format(it) }

    companion object {
        private const val REQUEST_IMPORT = 101
        private const val REQUEST_EXPORT = 102
        private const val REQUEST_EVIDENCE_EXPORT = 103
        private const val MAX_PUBLIC_INPUT_BYTES = 1_048_576
        private const val KEY_INPUT = "public_input_json"
        private const val KEY_RESULT = "public_result_json"
        private const val KEY_LAST_RUN_ID = "public_last_run_id"
        private const val EVIDENCE_DIRECTORY_NAME = "evidence"
    }
}
