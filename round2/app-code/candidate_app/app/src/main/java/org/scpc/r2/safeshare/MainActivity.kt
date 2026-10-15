package org.scpc.r2.safeshare

import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.content.res.ColorStateList
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.os.Process
import android.view.View
import android.view.WindowInsets
import android.view.inputmethod.InputMethodManager
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import java.io.File
import java.io.FileOutputStream
import org.json.JSONArray
import org.json.JSONObject

/** Product surface. It prepares reviewable sharing content only on this device. */
class MainActivity : Activity() {
    private lateinit var scrollView: ScrollView
    private lateinit var contentInput: EditText
    private lateinit var purposeInput: EditText
    private lateinit var roomIdInput: EditText
    private lateinit var roomDisplayInput: EditText
    private lateinit var roleInput: EditText
    private lateinit var scopeInput: EditText
    private lateinit var linkAccessInput: EditText
    private lateinit var candidateRoomsInput: EditText
    private lateinit var summary: TextView
    private lateinit var technicalDetails: TextView
    private lateinit var detailsToggle: Button
    private lateinit var scenarioSummary: TextView
    private lateinit var quickFlowProgress: TextView
    private lateinit var quickDemoAction: Button
    private lateinit var recentFileList: LinearLayout
    private lateinit var agentHistory: TextView
    private lateinit var fileScreen: View
    private lateinit var chatScreen: View
    private lateinit var messengerScreen: View
    private lateinit var scenarioScreen: View
    private lateinit var judgementScreen: LinearLayout
    private lateinit var recordScreen: View
    private lateinit var messengerTimeline: LinearLayout
    private lateinit var roomList: LinearLayout
    private lateinit var agentStatus: TextView
    private lateinit var agentBusy: TextView
    private lateinit var agentWorkTitle: TextView
    private lateinit var agentWorkDetail: TextView
    private lateinit var agentWorkEvidence: TextView
    private lateinit var agentStepProgress: TextView
    private lateinit var judgementText: TextView
    private lateinit var recordText: TextView
    private lateinit var inputPanel: LinearLayout
    private lateinit var advancedPanel: LinearLayout
    private var pendingPayload: SafeSharePayload? = null
    private var syntheticScenarios: List<SafeShareScenario> = emptyList()
    private var selectedScenarioIndex: Int = 0
    private var loadedSyntheticEpisode: ScenarioEpisode? = null
    private var loadedSyntheticContent: String = ""
    private var quickDemoStage: Int = 0
    private var lastRoomEvaluation: JSONObject? = null
    private var sharePermission: String = SafeSharePolicy.PERMISSION_CAN_SHARE
    private var readOnlyRoomIds: List<String> = emptyList()
    private var busyActive: Boolean = false
    private var busyFrame: Int = 0
    private var busyLabel: String = ""
    private var activePhaseTitle: String? = null
    private var pendingAck: ((() -> Unit) -> Unit)? = null
    private var lastFailure: Throwable? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val demoPreferences = getSharedPreferences(QUICK_DEMO_PREFERENCES, MODE_PRIVATE)
        quickDemoStage = demoPreferences.getInt(KEY_QUICK_DEMO_STAGE, 0)
        selectedScenarioIndex = demoPreferences.getInt(KEY_SCENARIO_INDEX, 0).coerceAtLeast(0)
        setContentView(buildContent())
        loadSyntheticCorpus()
        pendingPayload = core().restoreReviewedPayload()
        restoreInputsFromState()
        pendingPayload?.review?.safePreview?.let(contentInput::setText)
        val restartMessage = consumeRestartMessage()
        renderState(
            restartMessage
                ?: if (pendingPayload == null) {
                    "공유할 내용과 대상을 확인한 뒤, 이 기기에서만 공유를 준비합니다."
                } else {
                    "이전에 확인한 내용과 저장된 상태를 불러왔습니다."
                },
        )
        handleShareIntent(intent)
        restoreAgentHistory()
        refreshRoomList()
        if (isAutomaticDemoRunning() && quickDemoStage == 4) {
            switchTab(Tab.ACTION)
            scrollView.postDelayed({ runSafely { runQuickDemoStep() } }, AUTO_STEP_DELAY_MS)
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleShareIntent(intent)
    }

    private fun buildContent(): View {
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(22), dp(18), dp(40))
            setBackgroundColor(0xfff7f8fa.toInt())
            isFocusableInTouchMode = true
            requestFocus()
        }
        content.addView(TextView(this).apply {
            text = "내 파일"
            textSize = 30f
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(0xff172033.toInt())
        })
        content.addView(TextView(this).apply {
            text = "최근 파일"
            textSize = 16f
            setTextColor(0xff4b5568.toInt())
            setPadding(0, dp(5), 0, dp(12))
        })
        content.addView(TextView(this).apply {
            text = "파일을 누르면 공유 대상과 권한을 확인합니다. 메시지는 보내지 않습니다."
            textSize = 13f
            setTextColor(0xff146c43.toInt())
            setPadding(dp(12), dp(8), dp(12), dp(8))
            background = roundedBackground(0xffe8f7ef.toInt(), 12)
        })

        content.addView(section("최근 파일"))
        recentFileList = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        content.addView(recentFileList, matchWidth())

        summary = TextView(this).apply {
            textSize = 15f
            setTextColor(0xff172033.toInt())
            setPadding(dp(14), dp(14), dp(14), dp(14))
            setTextIsSelectable(true)
            background = roundedBackground(0xffffffff.toInt(), 14, 0xffdfe3ea.toInt())
        }
        agentHistory = TextView(this).apply {
            text = "파일을 선택하면 확인 과정이 여기에 표시됩니다."
            textSize = 14f
            setTextColor(0xff374151.toInt())
            setPadding(dp(14), dp(14), dp(14), dp(14))
            background = roundedBackground(0xffffffff.toInt(), 14, 0xffdfe3ea.toInt())
        }
        scenarioSummary = TextView(this).apply {
            textSize = 14f
            setTextColor(0xff374151.toInt())
            setTextIsSelectable(true)
            setPadding(dp(12), dp(10), dp(12), dp(10))
            background = roundedBackground(0xffffffff.toInt(), 12, 0xffdfe3ea.toInt())
        }
        quickDemoAction = primaryButton("") { runQuickDemoStep() }

        inputPanel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            visibility = View.GONE
            setPadding(dp(12), dp(4), dp(12), dp(12))
            background = roundedBackground(0xffffffff.toInt(), 14, 0xffdfe3ea.toInt())
        }
        inputPanel.addView(section("공유할 내용"))
        contentInput = EditText(this).apply {
            hint = "텍스트 또는 링크를 붙여 넣으세요. PDF는 파일 탭의 합성 파일 목록에서 선택하세요."
            setText("분기 정산 자료 검토")
            minLines = 3
        }
        inputPanel.addView(contentInput, matchWidth())
        inputPanel.addView(button("내용 검토") { prepareManualPayload() })

        inputPanel.addView(section("공유 기준"))
        purposeInput = edit("공유 목적 / 프로젝트", "분기 정산 자료")
        roomIdInput = edit("대화방 ID", "room-project-alpha")
        roomDisplayInput = edit("대화방 이름과 용도", "프로젝트 알파 실무방")
        roleInput = edit("현재 역할", "작성자")
        scopeInput = edit("허용 범위", "프로젝트 알파 구성원")
        linkAccessInput = edit(
            "링크 접근: UNKNOWN / CONFIRMED / PROJECT_ONLY / NOT_APPLICABLE",
            "NOT_APPLICABLE",
        )
        candidateRoomsInput = EditText(this).apply {
            hint = "확인 가능한 대화방 ID (줄바꿈 또는 쉼표로 구분)"
            setText("room-project-alpha\nroom-private-family\nroom-project-beta\nroom-spoof-alpha")
            minLines = 3
        }
        listOf(
            purposeInput,
            roomIdInput,
            roomDisplayInput,
            roleInput,
            scopeInput,
            linkAccessInput,
            candidateRoomsInput,
        ).forEach { inputPanel.addView(it, matchWidth()) }

        inputPanel.addView(button("링크 접근을 프로젝트로 확인") {
            linkAccessInput.setText("PROJECT_ONLY")
            renderState("현재 입력의 링크 접근 범위를 PROJECT_ONLY로 표시했습니다. 공유 준비 때 입력 식별값에 함께 반영합니다.")
        })
        inputPanel.addView(button("공유 기준 확인") { proposeRoute() })
        inputPanel.addView(button("기준 저장") { confirmRoute() })
        inputPanel.addView(button("공유 준비하기") { proposeDraft() })

        advancedPanel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            visibility = View.GONE
            setPadding(dp(12), dp(4), dp(12), dp(12))
            background = roundedBackground(0xffffffff.toInt(), 14, 0xffdfe3ea.toInt())
        }
        advancedPanel.addView(section("대회 검증 도구"))
        advancedPanel.addView(TextView(this).apply {
            text = "예시 재현과 공개 검증을 실행합니다."
            textSize = 13f
            setTextColor(0xff687386.toInt())
        })
        advancedPanel.addView(button("이전 예시") { changeSyntheticScenario(-1) })
        advancedPanel.addView(button("다음 예시") { changeSyntheticScenario(1) })
        advancedPanel.addView(button("선택 시나리오 E1–E4 실행 기록") { runSelectedSyntheticTrace() })
        advancedPanel.addView(button("14개 시나리오 일괄 점검") { runSyntheticCorpusSweep() })
        advancedPanel.addView(button("저장된 상태 새로고침") {
            renderState("저장된 공유 기준과 준비한 내용을 다시 불러왔습니다.")
        })
        advancedPanel.addView(button("최근 공유 준비 삭제") { deleteLatestMemory() })
        advancedPanel.addView(button("이 기기의 데이터 초기화") { confirmReset() })
        advancedPanel.addView(button("비교 실행 (검증용)") {
            runSafely {
                val comparison = SafeShareComparisonProvider.run(this)
                val aggregate = comparison.getJSONObject("aggregate")
                renderEvidence(
                    buildString {
                        appendLine("동일 snapshot을 격리된 full / claim-off namespace에서 실행했습니다.")
                        appendLine(
                            "관찰 pair ${aggregate.getInt("pair_count")}개 · " +
                                "late 확인 full ${aggregate.getInt("full_late_route_confirmation_required_count")} / " +
                                "claim-off ${aggregate.getInt("claim_off_late_route_confirmation_required_count")}",
                        )
                        appendLine(
                            "부분복구 대체 준비 full ${aggregate.getInt("full_e4_replacement_draft_count")} / " +
                                "claim-off ${aggregate.getInt("claim_off_e4_replacement_draft_count")}",
                        )
                        append(
                            "상태 bytes full ${aggregate.getInt("full_persisted_state_utf8_bytes")} / " +
                                "claim-off ${aggregate.getInt("claim_off_persisted_state_utf8_bytes")} · " +
                                "외부 dispatch full ${aggregate.getInt("full_external_dispatch_count")} / " +
                                "claim-off ${aggregate.getInt("claim_off_external_dispatch_count")}",
                        )
                    },
                    comparison.toString(2),
                )
            }
        })
        advancedPanel.addView(button("공개 Probe 실행") {
            startActivity(Intent(this, PublicProbeActivity::class.java))
        }.apply { setPadding(dp(12), dp(16), dp(12), dp(16)) })
        detailsToggle = button("세부 상태 보기") { toggleTechnicalDetails() }
        advancedPanel.addView(detailsToggle)
        technicalDetails = TextView(this).apply {
            textSize = 12f
            typeface = Typeface.MONOSPACE
            setTextIsSelectable(true)
            setPadding(dp(8), dp(8), dp(8), dp(12))
            visibility = View.GONE
        }
        advancedPanel.addView(technicalDetails, matchWidth())
        // These views hold the manual intake and local verification paths. They are
        // kept off the consumer file screen so the visible flow begins with a real
        // file request in the Action tab, and are attached to the Settings tab
        // behind an explicit toggle so an operator can still reach the synthetic
        // traces, per-item delete, full/claim-off comparison and technical state.

        scrollView = ScrollView(this).apply { addView(content) }
        fileScreen = scrollView
        chatScreen = buildChatScreen()
        messengerScreen = buildMessengerScreen()
        scenarioScreen = buildScenarioScreen()
        judgementScreen = buildJudgementScreen()
        recordScreen = buildRecordScreen()
        val navigation = bottomNavigation()
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(0xfff7f8fa.toInt())
            addView(fileScreen, LinearLayout.LayoutParams(0, 0, 1f).apply { width = LinearLayout.LayoutParams.MATCH_PARENT })
            addView(chatScreen, LinearLayout.LayoutParams(0, 0, 1f).apply { width = LinearLayout.LayoutParams.MATCH_PARENT })
            addView(messengerScreen, LinearLayout.LayoutParams(0, 0, 1f).apply { width = LinearLayout.LayoutParams.MATCH_PARENT })
            addView(scenarioScreen, LinearLayout.LayoutParams(0, 0, 1f).apply { width = LinearLayout.LayoutParams.MATCH_PARENT })
            addView(judgementScreen, LinearLayout.LayoutParams(0, 0, 1f).apply { width = LinearLayout.LayoutParams.MATCH_PARENT })
            addView(recordScreen, LinearLayout.LayoutParams(0, 0, 1f).apply { width = LinearLayout.LayoutParams.MATCH_PARENT })
            addView(navigation, matchWidth())
        }
        // targetSdk 35 draws edge-to-edge. Keep the app's tab buttons above the
        // system gesture/navigation area instead of letting the two bars overlap.
        root.setOnApplyWindowInsetsListener { _, insets ->
            val navigationInset = insets.getInsets(WindowInsets.Type.navigationBars()).bottom
            navigation.setPadding(dp(8), dp(6), dp(8), dp(8) + navigationInset)
            insets
        }
        root.requestApplyInsets()
        switchTab(Tab.FILE)
        return root
    }

    private fun buildChatScreen(): View {
        val chatContent = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(18), dp(22), dp(18), dp(24))
        addView(TextView(this@MainActivity).apply {
            text = "메신저"
            textSize = 30f
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(0xff172033.toInt())
        })
        addView(TextView(this@MainActivity).apply {
            text = "이 기기의 대화방"
            textSize = 16f
            setTextColor(0xff4b5568.toInt())
            setPadding(0, dp(5), 0, dp(4))
        })
        addView(TextView(this@MainActivity).apply {
            text = "업무방과 개인방이 한 기기에 섞여 있습니다. 각 방의 상태는 에이전트가 실제로 계산한 후보 평가 결과입니다."
            textSize = 13f
            setTextColor(0xff146c43.toInt())
            setPadding(dp(12), dp(8), dp(12), dp(8))
            background = roundedBackground(0xffe8f7ef.toInt(), 12)
        })
        roomList = LinearLayout(this@MainActivity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, dp(12), 0, 0)
        }
        addView(roomList, matchWidth())
        addView(section("공유 요청 시작"))
        FILE_LIST_SCENARIOS.forEach { index ->
            fileItemFor(index)?.let { file ->
                addView(button("# ${file.name}\n공유 요청 · 역할과 권한 확인 필요") {
                    startAutomaticDemo(index)
                    switchTab(Tab.ACTION)
                }.apply {
                    isAllCaps = false
                    gravity = android.view.Gravity.START
                    textSize = 16f
                    setPadding(dp(16), dp(16), dp(16), dp(16))
                })
            }
        }
        addView(TextView(this@MainActivity).apply {
            text = "대화방을 선택하면 해당 파일의 공유 요청이 동작 탭에서 처리됩니다. 외부 메신저 계정과 연결하지 않습니다."
            textSize = 13f
            setTextColor(0xff687386.toInt())
            setPadding(0, dp(14), 0, 0)
        })
        }
        return ScrollView(this).apply {
            visibility = View.GONE
            addView(chatContent, matchWidth())
        }
    }

    private fun buildMessengerScreen(): View {
        val timelineContent = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(18), dp(22), dp(18), dp(24))
        addView(TextView(this@MainActivity).apply {
            text = "동작"
            textSize = 30f
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(0xff172033.toInt())
        })
        addView(TextView(this@MainActivity).apply {
            text = "에이전트 처리 흐름"
            textSize = 16f
            setTextColor(0xff4b5568.toInt())
            setPadding(0, dp(5), 0, dp(12))
        })
        agentStatus = TextView(this@MainActivity).apply {
            text = "에이전트 대기 중 · 공유 요청을 선택하면 검토를 시작합니다."
            textSize = 13f
            setTextColor(0xff146c43.toInt())
            setPadding(dp(12), dp(9), dp(12), dp(9))
            background = roundedBackground(0xffe8f7ef.toInt(), 12)
        }
        addView(agentStatus, matchWidth())
        agentBusy = TextView(this@MainActivity).apply {
            text = "대기 중"
            textSize = 15f
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(0xff1d4ed8.toInt())
            setPadding(dp(12), dp(11), dp(12), dp(11))
            background = roundedBackground(0xffe8eefc.toInt(), 12)
        }
        addView(agentBusy, matchWidth())
        addView(section("현재 작업"))
        val workCard = LinearLayout(this@MainActivity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(14), dp(14), dp(14), dp(14))
            background = roundedBackground(0xffffffff.toInt(), 14, 0xffdfe3ea.toInt())
        }
        agentWorkTitle = TextView(this@MainActivity).apply {
            textSize = 17f
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(0xff172033.toInt())
        }
        agentWorkDetail = TextView(this@MainActivity).apply {
            textSize = 15f
            setTextColor(0xff263247.toInt())
            setPadding(0, dp(6), 0, 0)
        }
        agentWorkEvidence = TextView(this@MainActivity).apply {
            textSize = 13f
            setTextColor(0xff687386.toInt())
            setPadding(0, dp(10), 0, 0)
        }
        agentStepProgress = TextView(this@MainActivity).apply {
            textSize = 14f
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(0xff1d4ed8.toInt())
            setPadding(0, dp(8), 0, 0)
            visibility = View.GONE
        }
        workCard.addView(agentWorkTitle)
        workCard.addView(agentStepProgress)
        workCard.addView(agentWorkDetail)
        workCard.addView(agentWorkEvidence)
        addView(workCard, matchWidth())
        addView(section("대화와 처리 상황"))
        messengerTimeline = LinearLayout(this@MainActivity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(10), dp(10), dp(10), dp(10))
            background = roundedBackground(0xffffffff.toInt(), 14, 0xffdfe3ea.toInt())
        }
        addView(messengerTimeline, matchWidth())
        addView(TextView(this@MainActivity).apply {
            text = "이 화면은 합성 시나리오의 역할·권한 변화를 보여 주는 앱 내부 화면입니다. 실제 메신저 계정이나 외부 대화에는 연결되지 않습니다."
            textSize = 13f
            setTextColor(0xff687386.toInt())
            setPadding(0, dp(14), 0, 0)
        })
        }
        return ScrollView(this).apply {
            visibility = View.GONE
            addView(timelineContent, matchWidth())
        }
    }

    private fun buildScenarioScreen(): View {
        val scenarioContent = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(18), dp(22), dp(18), dp(24))
        addView(TextView(this@MainActivity).apply {
            text = "시나리오"
            textSize = 30f
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(0xff172033.toInt())
        })
        addView(section("반복 업무 시나리오"))
        addView(TextView(this@MainActivity).apply {
            text = "파일을 선택하면 해당 시나리오가 실제 코어 로직으로 실행됩니다. 역할·대상·허용 범위가 바뀌면 기존 준비를 보류하고, 재시작 후 바뀐 항목만 다시 확인합니다."
            textSize = 15f
            setTextColor(0xff172033.toInt())
            setPadding(dp(14), dp(14), dp(14), dp(14))
            background = roundedBackground(0xffffffff.toInt(), 14, 0xffdfe3ea.toInt())
        })
        FILE_LIST_SCENARIOS.forEach { index ->
            fileItemFor(index)?.let { file ->
                addView(button("${file.name} 시나리오 실행") { startAutomaticDemo(index) })
            }
        }
        }
        return ScrollView(this).apply {
            visibility = View.GONE
            addView(scenarioContent, matchWidth())
        }
    }

    private fun buildJudgementScreen(): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        visibility = View.GONE
        setPadding(dp(18), dp(22), dp(18), dp(24))
        addView(TextView(this@MainActivity).apply {
            text = "에이전트 판단"
            textSize = 30f
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(0xff172033.toInt())
        })
        addView(section("현재 판단 근거"))
        addView(TextView(this@MainActivity).apply {
            text = "공유 대상, 현재 역할, 허용 범위, 링크 접근 범위를 함께 확인합니다. 하나라도 바뀌면 기존 준비를 사용하지 않고 보류합니다."
            textSize = 15f
            setTextColor(0xff172033.toInt())
            setPadding(dp(14), dp(14), dp(14), dp(14))
            background = roundedBackground(0xffffffff.toInt(), 14, 0xffdfe3ea.toInt())
        })
        addView(section("판단 결과"))
        judgementText = TextView(this@MainActivity).apply {
            text = "아직 실행한 시나리오가 없습니다."
            textSize = 14f
            setTextColor(0xff687386.toInt())
        }
        addView(judgementText)
    }

    private fun buildRecordScreen(): View {
        val recordContent = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(18), dp(22), dp(18), dp(24))
        addView(TextView(this@MainActivity).apply {
            text = "설정"
            textSize = 30f
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(0xff172033.toInt())
        })
        addView(section("이 기기의 처리 기록과 검증"))
        recordText = TextView(this@MainActivity).apply {
            text = "아직 저장된 처리 기록이 없습니다."
            textSize = 15f
            setTextColor(0xff172033.toInt())
            setPadding(dp(14), dp(14), dp(14), dp(14))
            background = roundedBackground(0xffffffff.toInt(), 14, 0xffdfe3ea.toInt())
        }
        addView(recordText)
        addView(button("이 기기의 공유 준비 기록 삭제") { confirmReset() })
        addView(button("공개 검증 도구") { startActivity(Intent(this@MainActivity, PublicProbeActivity::class.java)) })
        addView(section("직접 입력 (보조)"))
        addView(TextView(this@MainActivity).apply {
            text = "파일 흐름 대신 목적·대화방·역할·범위를 직접 넣어 같은 코어를 실행합니다."
            textSize = 13f
            setTextColor(0xff687386.toInt())
        })
        addView(button("직접 입력 패널 열기/닫기") { togglePanel(inputPanel) })
        addView(inputPanel, matchWidth())
        addView(section("대회 검증 패널"))
        addView(TextView(this@MainActivity).apply {
            text = "14개 합성 trace, 개별 삭제, full/claim-off 비교 실행과 기술 상태 JSON을 여는 검증용 영역입니다."
            textSize = 13f
            setTextColor(0xff687386.toInt())
        })
        addView(button("대회 검증 패널 열기/닫기") { togglePanel(advancedPanel) })
        addView(advancedPanel, matchWidth())
        }
        return ScrollView(this).apply {
            visibility = View.GONE
            addView(recordContent, matchWidth())
        }
    }

    private fun bottomNavigation(): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        setPadding(dp(8), dp(6), dp(8), dp(8))
        setBackgroundColor(0xffffffff.toInt())
        listOf(
            "파일" to Tab.FILE,
            "메신저" to Tab.MESSENGER,
            "동작" to Tab.ACTION,
            "시나리오" to Tab.SCENARIO,
            "설정" to Tab.SETTINGS,
        ).forEach { (label, tab) ->
            addView(button(label) { switchTab(tab) }.apply { textSize = 15f },
                LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        }
    }

    private fun switchTab(tab: Tab) {
        if (!::fileScreen.isInitialized) return
        fileScreen.visibility = if (tab == Tab.FILE) View.VISIBLE else View.GONE
        chatScreen.visibility = if (tab == Tab.MESSENGER) View.VISIBLE else View.GONE
        messengerScreen.visibility = if (tab == Tab.ACTION) View.VISIBLE else View.GONE
        scenarioScreen.visibility = if (tab == Tab.SCENARIO) View.VISIBLE else View.GONE
        judgementScreen.visibility = View.GONE
        recordScreen.visibility = if (tab == Tab.SETTINGS) View.VISIBLE else View.GONE
        if (tab == Tab.ACTION) refreshMessengerTimeline()
        if (tab == Tab.MESSENGER) refreshRoomList()
    }

    private fun refreshMessengerTimeline() {
        if (!::messengerTimeline.isInitialized) return
        val history = getSharedPreferences(QUICK_DEMO_PREFERENCES, MODE_PRIVATE)
            .getString(KEY_MESSENGER_HISTORY, "")
            .orEmpty()
        messengerTimeline.removeAllViews()
        if (history.isBlank()) {
            messengerTimeline.addView(chatBubble("안내", "파일 또는 대화방에서 공유 요청을 시작하면, 이곳에 실제 처리 단계가 표시됩니다.", false))
        } else {
            history.split("\n\n").forEach { entry ->
                val parts = entry.split("\n", limit = 2)
                val sender = parts.firstOrNull().orEmpty()
                val message = parts.getOrElse(1) { "" }
                messengerTimeline.addView(chatBubble(sender, message, sender == "나"))
            }
        }
        if (::agentStatus.isInitialized) {
            val snapshot = core().productSnapshot()
            val runningPhase = activePhaseTitle
            agentStatus.text = when {
                runningPhase != null -> "에이전트 동작 중 · $runningPhase"
                snapshot.optJSONObject("last_recovery") != null &&
                    snapshot.isNull("pending_context_change") ->
                    "검토 완료 · 변경된 대상과 역할만 다시 확인했고 외부 전송은 0회입니다."
                quickDemoStage == 0 -> "에이전트 대기 중 · 공유 요청을 선택하면 검토를 시작합니다."
                quickDemoStage == 1 -> "에이전트 검토 중 · 대상, 역할, 허용 범위를 확인하고 있습니다."
                quickDemoStage == 2 -> "에이전트 준비 완료 · 확인된 기준 안에서만 내용을 준비했습니다."
                quickDemoStage == 3 -> "에이전트 재사용 판단 · 같은 요청에 새 공유 항목을 만들지 않았습니다."
                else -> "에이전트 보류 중 · 변경된 역할 또는 대상만 다시 확인합니다."
            }
        }
        updateAgentWorkCard()
    }

    /** This card is a direct projection of the durable core state, not a scripted UI state. */
    private fun updateAgentWorkCard() {
        if (!::agentWorkTitle.isInitialized) return
        val snapshot = core().productSnapshot()
        val active = snapshot.getInt("active_draft_count")
        val held = snapshot.getInt("held_draft_count")
        val sent = snapshot.getInt("external_dispatch_count")
        val context = snapshot.optJSONObject("safe_share_context")
        val room = context?.optString("room_display").orEmpty()
        val role = context?.optString("current_role").orEmpty()
        val scope = context?.optString("allowed_scope").orEmpty()
        val recovery = snapshot.optJSONObject("last_recovery")
        val recoveryCompleted = recovery != null &&
            snapshot.isNull("pending_context_change")
        val phaseOverride = activePhaseTitle
        val state = if (recoveryCompleted) {
            Triple(
                "4/4 · 재시작 후 변경 조건만 복구",
                listOf(room, role, scope).filter { it.isNotBlank() }.joinToString(" · ")
                    .ifBlank { "변경된 공유 기준을 복구했습니다." },
                "기존 준비 ${recovery?.optInt("invalidated_draft_count", 0) ?: 0}건을 무효화하고, 새 외부 전송 없이 변경된 기준으로만 다시 준비했습니다.",
            )
        } else when (quickDemoStage) {
            0 -> Triple("공유 요청 대기", "파일을 선택하면 공유 대상과 역할을 먼저 확인합니다.", "")
            1 -> Triple(
                "1/4 · 대상과 권한 확인",
                listOf(room, role, scope).filter { it.isNotBlank() }.joinToString(" · ")
                    .ifBlank { "공유 기준을 읽는 중입니다." },
                "확인 전에는 공유 내용을 만들지 않습니다.",
            )
            2 -> Triple(
                "2/4 · 검토 가능한 공유 내용 준비",
                "확인된 기준 안에서만 이 기기에 준비했습니다.",
                "같은 요청은 새 항목을 만들지 않고 기존 준비를 재사용합니다.",
            )
            3 -> Triple(
                "3/4 · 반복 요청은 기존 준비 재사용",
                "같은 목적의 요청을 확인해 새 공유 항목을 만들지 않았습니다.",
                "반복 처리 뒤에도 외부 전송은 하지 않습니다.",
            )
            4 -> Triple(
                "조건 변경 감지 · 기존 준비 보류",
                "대상 또는 역할이 바뀌어 기존 준비를 멈췄습니다.",
                "앱을 다시 시작한 뒤 변경된 항목만 확인합니다.",
            )
            else -> Triple(
                "4/4 · 재시작 후 변경 조건만 복구",
                listOf(room, role, scope).filter { it.isNotBlank() }.joinToString(" · ")
                    .ifBlank { "변경된 공유 기준을 복구했습니다." },
                "새 외부 전송 없이 검토 가능한 내용만 다시 준비했습니다.",
            )
        }
        agentWorkTitle.text = phaseOverride ?: state.first
        agentWorkDetail.text = if (phaseOverride != null) {
            listOf(room, role, scope).filter { it.isNotBlank() }.joinToString(" · ")
                .ifBlank { state.second }
        } else {
            state.second
        }
        agentWorkEvidence.text = "실제 코어 상태 · 준비 ${active}건 · 보류 ${held}건 · 외부 전송 ${sent}회\n${state.third}"
    }

    private fun chatBubble(sender: String, message: String, mine: Boolean): View {
        val outer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = if (mine) android.view.Gravity.END else android.view.Gravity.START
            setPadding(dp(4), dp(6), dp(4), dp(6))
        }
        outer.addView(TextView(this).apply {
            text = sender
            textSize = 12f
            setTextColor(0xff687386.toInt())
            setPadding(dp(5), 0, dp(5), dp(3))
        })
        outer.addView(TextView(this).apply {
            text = message
            textSize = 15f
            setTextColor(if (mine) 0xffffffff.toInt() else 0xff172033.toInt())
            setPadding(dp(12), dp(10), dp(12), dp(10))
            background = roundedBackground(
                if (mine) 0xff2457d6.toInt() else 0xffeef1f5.toInt(),
                14,
            )
        })
        return outer
    }

    private fun appendMessenger(sender: String, message: String) {
        val preferences = getSharedPreferences(QUICK_DEMO_PREFERENCES, MODE_PRIVATE)
        val prior = preferences.getString(KEY_MESSENGER_HISTORY, "").orEmpty()
        val next = ((if (prior.isBlank()) "" else "$prior\n\n") + "$sender\n$message").takeLast(MAX_AGENT_HISTORY_CHARS)
        preferences.edit().putString(KEY_MESSENGER_HISTORY, next).commit()
        refreshMessengerTimeline()
    }

    private fun refreshJudgement() {
        if (!::judgementText.isInitialized) return
        val snapshot = core().productSnapshot()
        judgementText.text = buildString {
            appendLine("상태: ${decisionLabel(snapshot)}")
            appendLine("준비됨 ${snapshot.getInt("active_draft_count")}건 · 보류 ${snapshot.getInt("held_draft_count")}건 · 취소 ${snapshot.getInt("cancelled_draft_count")}건")
            append("외부 전송 ${snapshot.getInt("external_dispatch_count")}회")
        }
    }

    private fun runQuickDemoStep() {
        when (quickDemoStage) {
            0 -> {
                // E1 Learn. Each micro-step is one production-core call whose
                // result is appended as soon as it returns.
                runAgentSteps(
                    "1/4 · 대상과 권한 확인",
                    listOf(
                        AgentStep("파일 내용을 읽는 중", "파일 내용 확인 완료") {
                            resetAndLoadDemo("E1")
                            prepareManualPayload()
                            val kind = pendingPayload?.review?.kind?.name ?: "UNKNOWN"
                            val secret = pendingPayload?.review?.secretDetected == true
                            appendAgentHistory(
                                "1. 입력 확인",
                                "$kind 입력을 읽었습니다." + if (secret) " 민감 설정값을 감지해 저장 전에 가렸습니다." else "",
                            )
                        },
                        AgentStep("대화방 후보를 확인하는 중", "대화방 후보 평가 완료") {
                            evaluateRoomsForDisplay()
                            val candidates = lastRoomEvaluation
                                ?.optJSONArray("candidate_room_ids")?.length() ?: 0
                            appendAgentHistory(
                                "2. 대화방 후보 평가",
                                "이 기기의 대화방 ${candidates}곳을 후보로 확인했습니다. 아직 확인된 기준이 없어 어떤 방도 선택하지 않습니다.",
                            )
                            appendMessenger("SafeShare", "대화방 ${candidates}곳을 확인했습니다. 메신저 탭에서 방별 상태를 볼 수 있습니다.")
                        },
                        AgentStep("목적·역할·허용 범위를 확인하는 중", "공유 기준 제안 완료") {
                            proposeRoute()
                            val context = readContext(requireRoute = true)
                            appendAgentHistory(
                                "3. 공유 기준 제안",
                                "${context.roomDisplay} · ${context.role} · ${context.scope}를 확인 대상으로 제안했습니다. 확인 전에는 공유 내용을 만들지 않습니다.",
                            )
                            appendMessenger(
                                "SafeShare",
                                "공유 요청을 받았습니다. 대상 ‘${context.roomDisplay}’, 역할 ‘${context.role}’, 허용 범위 ‘${context.scope}’를 확인한 뒤에만 준비합니다.",
                            )
                        },
                    ),
                ) {
                    setQuickDemoStage(1)
                    scheduleNextAutomaticStep()
                }
            }
            1 -> {
                runAgentSteps(
                    "2/4 · 검토 가능한 공유 내용 준비",
                    listOf(
                        AgentStep("확인한 기준을 저장하는 중", "기준 저장 완료") {
                            confirmRoute()
                            evaluateRoomsForDisplay()
                            val selected = lastRoomEvaluation?.optString("selected_room_id").orEmpty()
                            appendAgentHistory(
                                "4. 기준 저장",
                                "사용자가 확인한 기준을 저장했습니다. 이제 이 목적에는 ‘$selected’ 한 곳만 선택됩니다.",
                            )
                        },
                        AgentStep("공유 내용을 준비하는 중", "로컬 준비 완료 · 외부 전송 0회") {
                            proposeDraft()
                            val context = readContext(requireRoute = true)
                            val active = core().productSnapshot().getInt("active_draft_count")
                            appendAgentHistory(
                                "5. 로컬 준비 완료",
                                "확인된 범위 안에서만 준비했습니다. 준비 ${active}건 · 외부 전송 0회.",
                            )
                            appendMessenger(
                                "SafeShare",
                                "기준 확인 완료: ${context.roomDisplay} / ${context.role} / ${context.scope}. 외부 전송은 하지 않았습니다.",
                            )
                        },
                    ),
                ) {
                    setQuickDemoStage(2)
                    scheduleNextAutomaticStep()
                }
            }
            2 -> {
                runAgentSteps(
                    "3/4 · 반복 요청은 기존 준비 재사용",
                    listOf(
                        AgentStep("다음 session의 반복 요청을 읽는 중", "반복 요청 수신 완료") {
                            loadDemoEpisode("E2")
                            prepareManualPayload()
                            appendAgentHistory(
                                "6. 반복 요청 수신",
                                "같은 목적의 새 요청이 다음 session에 도착했습니다. 목적·대상·허용 범위를 다시 묻지 않습니다.",
                            )
                        },
                        AgentStep("확인된 기준을 재사용하는 중", "기존 준비 재사용 완료") {
                            proposeDraft()
                            evaluateRoomsForDisplay()
                            val snapshot = core().productSnapshot()
                            appendAgentHistory(
                                "7. 기존 준비 재사용",
                                "새 공유 항목을 만들지 않고 기존 준비를 재사용했습니다. 준비 ${snapshot.getInt("active_draft_count")}건 · 외부 전송 0회.",
                            )
                            appendMessenger(
                                "SafeShare",
                                "반복 요청으로 판정했습니다. 기존 준비 내용을 재사용하고 새로운 공유 항목은 만들지 않았습니다.",
                            )
                        },
                    ),
                ) {
                    setQuickDemoStage(3)
                    scheduleNextAutomaticStep()
                }
            }
            3 -> {
                runAgentSteps(
                    "조건 변경 감지 · 기존 준비 보류",
                    listOf(
                        AgentStep("바뀐 조건을 확인하는 중", "조건 변경 감지 완료") {
                            // Only load the new inputs here.  Reviewing the payload
                            // would write the new context into the durable store and
                            // erase the difference E3 has to detect, which hides a
                            // role-only downgrade behind an unchanged room.
                            loadDemoEpisode("E3")
                            val context = readContext(requireRoute = true)
                            appendAgentHistory(
                                "8. 조건 변경 감지",
                                "새 대상 ‘${context.roomDisplay}’ 또는 역할 ‘${context.role}’이 감지되었습니다. 과거 기준을 그대로 쓰지 않습니다.",
                            )
                            appendMessenger(
                                "시스템",
                                "역할 또는 공유 대상 변경 감지: ${context.roomDisplay} / ${context.role}. 기존 준비를 보류합니다.",
                            )
                        },
                        AgentStep("바뀐 역할로 공유가 가능한지 확인하는 중", "공유 권한 재확인 완료") {
                            val context = readContext(requireRoute = true)
                            val blockedRooms = readOnlyRoomIds.mapNotNull { roomId ->
                                syntheticScenarios.getOrNull(selectedScenarioIndex)
                                    ?.rooms?.firstOrNull { it.roomId == roomId }?.displayName
                            }
                            if (blockedRooms.isNotEmpty()) {
                                appendAgentHistory(
                                    "8-1. 방별 권한 변경",
                                    "${blockedRooms.joinToString(", ")}에서 역할이 열람 전용으로 내려갔습니다. 그 방으로는 준비하지 않고, 남은 허용 범위 안의 방으로 대상을 옮깁니다.",
                                )
                                appendMessenger(
                                    "시스템",
                                    "등급 변경: ${blockedRooms.joinToString(", ")}에서 읽기만 가능합니다. 다른 확인된 방은 그대로 사용할 수 있습니다.",
                                )
                                evaluateRoomsForDisplay()
                                requireAck(
                                    "한 대화방의 공유 권한이 없어졌습니다",
                                    "${blockedRooms.joinToString(", ")}에서 현재 역할이 열람 전용으로 바뀌었습니다.\n\n" +
                                        "허용 범위와 나머지 대화방은 그대로 유효하므로, 그 방만 대상에서 빼고 남은 방으로 다시 준비합니다.",
                                )
                            } else if (!SafeSharePolicy.canShare(sharePermission)) {
                                // Do not persist the new role here.  E3's change
                                // detection diffs the incoming context against the
                                // stored one, so writing it early would erase the
                                // very difference that must trigger the checkpoint.
                                appendAgentHistory(
                                    "8-1. 열람 전용으로 등급 변경",
                                    "현재 역할 ‘${context.role}’은 이 방에서 읽기만 가능합니다. 공유 준비를 만들지 않고 보류합니다.",
                                )
                                appendMessenger(
                                    "시스템",
                                    "등급 변경: ${context.roomDisplay}에서 ‘${context.role}’로 내려가 공유 권한이 없습니다. 읽기만 가능합니다.",
                                )
                                requireAck(
                                    "공유 권한이 없어졌습니다",
                                    "${context.roomDisplay}에서 현재 역할이 ‘${context.role}’로 바뀌었습니다.\n\n" +
                                        "방과 허용 범위는 그대로 유효하지만 역할만으로 공유가 막히므로, 준비 중이던 내용을 보내지 않고 보류합니다.",
                                )
                            } else {
                                appendAgentHistory(
                                    "8-1. 공유 권한 확인",
                                    "바뀐 역할 ‘${context.role}’은 이 방에서 공유가 가능합니다.",
                                )
                            }
                        },
                        AgentStep("보류 사유를 기록하는 중", "보류 사유 기록 완료", thinkMs = 6_000L) {
                            val snapshot = core().productSnapshot()
                            val reviewState = snapshot.optJSONObject("safe_share_context")
                                ?.optString("review_state").orEmpty()
                            appendAgentHistory(
                                "8-2. 보류 기록",
                                "검토 상태 $reviewState · 준비 ${snapshot.getInt("active_draft_count")}건을 영향 대상으로 표시했습니다.",
                            )
                        },
                        AgentStep("보류 상태를 저장하고 앱을 종료하는 중", "checkpoint 저장 · process 종료") {
                            // Persist the next visible step before the intentional process kill.
                            setQuickDemoStage(4)
                            appendAgentHistory(
                                "9. checkpoint 저장 후 실제 종료",
                                "영향을 받은 준비만 보류로 저장한 뒤 main process를 실제로 종료합니다. 재시작 뒤 변경된 항목만 다시 확인합니다.",
                            )
                            holdAndRestart()
                        },
                    ),
                ) { /* the process is killed inside the last step */ }
            }
            4 -> {
                runAgentSteps(
                    "4/4 · 재시작 후 변경 조건만 복구",
                    listOf(
                        AgentStep("새 process에서 보류 작업을 불러오는 중", "보류 작업 복원 완료") {
                            populateDemoEpisode("E4")
                            val pending = core().productSnapshot().optJSONObject("pending_context_change")
                            val changed = pending?.optJSONArray("changed_fields")?.length() ?: 0
                            appendAgentHistory(
                                "10. 재시작 확인",
                                "새 process에서 보류 작업을 찾았습니다. 다시 확인할 항목 ${changed}개. 전체 기록은 초기화하지 않았습니다.",
                            )
                        },
                        AgentStep("변경된 항목만 다시 판단하는 중", "부분 복구 완료") {
                            recoverPendingContext()
                            evaluateRoomsForDisplay()
                            val context = readContext(requireRoute = true)
                            val recovery = core().productSnapshot().optJSONObject("last_recovery")
                            appendAgentHistory(
                                "11. 부분 복구 완료",
                                "${context.roomDisplay} · ${context.role}로 바뀐 준비만 무효화(${recovery?.optInt("invalidated_draft_count", 0) ?: 0}건)하고 다시 준비했습니다. 나머지 유효 기준은 유지했습니다.",
                            )
                            appendMessenger(
                                "SafeShare",
                                "재시작 후 복구 완료: 변경된 대상과 역할만 다시 확인했습니다. 새 기준으로 준비했으며 외부 전송은 0회입니다.",
                            )
                        },
                    ),
                ) {
                    setQuickDemoStage(5)
                    stopAutomaticDemo()
                    finishBusy("E1–E4 완료 · 외부 전송 0회")
                    val snapshot = core().productSnapshot()
                    val recovery = snapshot.optJSONObject("last_recovery")
                    showResultDialog(
                        "E1–E4 검토를 마쳤습니다",
                        "재시작 뒤 바뀐 조건만 다시 확인했습니다.\n\n" +
                            "· 무효화한 준비 ${recovery?.optInt("invalidated_draft_count", 0) ?: 0}건\n" +
                            "· 현재 준비 ${snapshot.getInt("active_draft_count")}건 · 보류 ${snapshot.getInt("held_draft_count")}건\n" +
                            "· 외부 전송 ${snapshot.getInt("external_dispatch_count")}회\n\n" +
                            "메신저 탭에서 방별 선택·제외 상태를 볼 수 있습니다.",
                    )
                }
            }
            else -> {
                core().resetAllUserData()
                deletePrivatePdfCopies()
                pendingPayload = null
                resetAndLoadDemo("E1")
                setQuickDemoStage(0)
                clearAgentHistory()
                lastRoomEvaluation = null
                refreshRoomList()
                finishBusy("대기 중")
                scheduleNextAutomaticStep()
            }
        }
    }

    private fun setQuickDemoStage(stage: Int) {
        quickDemoStage = stage.coerceIn(0, 5)
        check(
            getSharedPreferences(QUICK_DEMO_PREFERENCES, MODE_PRIVATE)
                .edit()
                .putInt(KEY_QUICK_DEMO_STAGE, quickDemoStage)
                .commit(),
        ) { "데모 진행 단계를 저장하지 못했습니다." }
        updateQuickDemoUi()
    }

    private fun updateQuickDemoUi() {
        if (!::quickFlowProgress.isInitialized || !::quickDemoAction.isInitialized) return
        val (progress, action) = when (quickDemoStage) {
            0 -> "1/4 · 공유할 내용과 대상을 확인합니다." to "예시 시작"
            1 -> "1/4 · 공유 기준을 확인합니다." to "기준 확인 및 준비"
            2 -> "2/4 · 확인한 기준을 같은 업무에만 적용합니다." to "다음 상황 확인"
            3 -> "3/4 · 조건이 바뀌면 준비를 멈춥니다." to "변경 사항 확인"
            4 -> "4/4 · 다시 시작한 뒤 바뀐 조건을 확인합니다." to "변경 사항 반영"
            else -> "완료 · 공유 기준 확인과 변경 대응을 마쳤습니다." to "처음부터 보기"
        }
        quickFlowProgress.text = progress
        quickDemoAction.text = action
        refreshMessengerTimeline()
    }

    private fun startAutomaticDemo(scenarioIndex: Int) = runSafely {
        check(syntheticScenarios.indices.contains(scenarioIndex)) { "선택한 파일의 확인 정보를 찾지 못했습니다." }
        setSelectedScenarioIndex(scenarioIndex)
        clearAgentHistory()
        getSharedPreferences(QUICK_DEMO_PREFERENCES, MODE_PRIVATE).edit().remove(KEY_MESSENGER_HISTORY).commit()
        val file = fileItemFor(scenarioIndex)?.name ?: "선택한 파일"
        appendMessenger("나", "$file 공유 요청")
        getSharedPreferences(QUICK_DEMO_PREFERENCES, MODE_PRIVATE)
            .edit()
            .putBoolean(KEY_AUTO_DEMO, true)
            .commit()
        setQuickDemoStage(0)
        updateScenarioSummary()
        runQuickDemoStep()
        switchTab(Tab.ACTION)
    }

    private fun scheduleNextAutomaticStep() {
        if (!isAutomaticDemoRunning() || quickDemoStage !in 1..3) return
        scrollView.postDelayed({
            if (isAutomaticDemoRunning()) runSafely { runQuickDemoStep() }
        }, AUTO_STEP_DELAY_MS)
    }

    private fun isAutomaticDemoRunning(): Boolean =
        getSharedPreferences(QUICK_DEMO_PREFERENCES, MODE_PRIVATE).getBoolean(KEY_AUTO_DEMO, false)

    private fun stopAutomaticDemo() {
        getSharedPreferences(QUICK_DEMO_PREFERENCES, MODE_PRIVATE)
            .edit()
            .putBoolean(KEY_AUTO_DEMO, false)
            .commit()
    }

    private fun clearAgentHistory() {
        getSharedPreferences(QUICK_DEMO_PREFERENCES, MODE_PRIVATE)
            .edit()
            .remove(KEY_AGENT_HISTORY)
            .remove(KEY_MESSENGER_HISTORY)
            .commit()
        if (::agentHistory.isInitialized) {
            agentHistory.text = "파일을 확인하고 있습니다…"
        }
    }

    private fun appendAgentHistory(title: String, message: String) {
        val prior = getSharedPreferences(QUICK_DEMO_PREFERENCES, MODE_PRIVATE)
            .getString(KEY_AGENT_HISTORY, "")
            .orEmpty()
        val next = ((if (prior.isBlank()) "" else "$prior\n\n") + "• $title\n$message")
            .takeLast(MAX_AGENT_HISTORY_CHARS)
        check(
            getSharedPreferences(QUICK_DEMO_PREFERENCES, MODE_PRIVATE)
                .edit()
                .putString(KEY_AGENT_HISTORY, next)
                .commit(),
        ) { "처리 기록을 저장하지 못했습니다." }
        if (::agentHistory.isInitialized) {
            agentHistory.text = next
            agentHistory.post {
                scrollView.smoothScrollTo(0, (agentHistory.top - dp(48)).coerceAtLeast(0))
            }
        }
        if (::recordText.isInitialized) recordText.text = next
    }

    private fun restoreAgentHistory() {
        val history = getSharedPreferences(QUICK_DEMO_PREFERENCES, MODE_PRIVATE)
            .getString(KEY_AGENT_HISTORY, "")
            .orEmpty()
            .ifBlank { "파일을 선택하면 확인 과정이 여기에 표시됩니다." }
        if (::agentHistory.isInitialized) agentHistory.text = history
        if (::recordText.isInitialized) recordText.text = history
    }

    private fun togglePanel(panel: View) {
        panel.visibility = if (panel.visibility == View.VISIBLE) View.GONE else View.VISIBLE
    }

    /** One visible unit of agent work. [body] performs a real production-core call. */
    private data class AgentStep(
        val label: String,
        val done: String,
        val thinkMs: Long = AGENT_STEP_THINK_MS,
        val body: () -> Unit,
    )

    private val busyTick = object : Runnable {
        override fun run() {
            if (!busyActive || !::agentBusy.isInitialized) return
            busyFrame = (busyFrame + 1) % BUSY_FRAMES.size
            agentBusy.text = "${BUSY_FRAMES[busyFrame]}  $busyLabel"
            agentBusy.postDelayed(this, BUSY_FRAME_MS)
        }
    }

    private fun startBusy(label: String) {
        if (!::agentBusy.isInitialized) return
        busyLabel = label
        busyFrame = 0
        agentBusy.text = "${BUSY_FRAMES[0]}  $label"
        agentBusy.setTextColor(0xff1d4ed8.toInt())
        agentBusy.background = roundedBackground(0xffe8eefc.toInt(), 12)
        if (!busyActive) {
            busyActive = true
            agentBusy.postDelayed(busyTick, BUSY_FRAME_MS)
        }
    }

    /** Shows the finished phase as a dismissible result card. */
    private fun showResultDialog(title: String, body: String) {
        if (isFinishing || isDestroyed) return
        AlertDialog.Builder(this)
            .setTitle(title)
            .setMessage(body)
            .setPositiveButton("확인", null)
            .show()
    }

    /**
     * Marks the running step as needing user acknowledgement.  The agent holds
     * before the next step until the person confirms, which is what a stop
     * decision should feel like: nothing continues behind their back.
     */
    private fun requireAck(title: String, body: String) {
        pendingAck = { resume ->
            if (isFinishing || isDestroyed) {
                resume()
            } else {
                AlertDialog.Builder(this)
                    .setTitle(title)
                    .setMessage(body)
                    .setCancelable(false)
                    .setPositiveButton("확인") { _, _ -> resume() }
                    .show()
            }
        }
    }

    private fun setStepProgress(text: String) {
        if (!::agentStepProgress.isInitialized) return
        agentStepProgress.text = text
        agentStepProgress.visibility = View.VISIBLE
    }

    private fun failBusy(label: String) {
        if (!::agentBusy.isInitialized) return
        busyActive = false
        agentBusy.removeCallbacks(busyTick)
        agentBusy.text = "⚠  $label"
        agentBusy.setTextColor(0xff842029.toInt())
        agentBusy.background = roundedBackground(0xfff8d7da.toInt(), 12)
    }

    private fun finishBusy(label: String) {
        if (!::agentBusy.isInitialized) return
        busyActive = false
        agentBusy.removeCallbacks(busyTick)
        agentBusy.text = "✓  $label"
        agentBusy.setTextColor(0xff146c43.toInt())
        agentBusy.background = roundedBackground(0xffe8f7ef.toInt(), 12)
    }

    /**
     * Runs [steps] one at a time so a viewer can see each production-core call
     * land.  The spinner is shown while a step is pending and the step's own
     * output is appended as soon as that call returns.
     */
    private fun runAgentSteps(phaseTitle: String, steps: List<AgentStep>, onDone: () -> Unit) {
        activePhaseTitle = phaseTitle
        updateAgentWorkCard()
        fun run(index: Int) {
            if (index >= steps.size) {
                activePhaseTitle = null
                onDone()
                return
            }
            val step = steps[index]
            startBusy(step.label)
            setStepProgress("진행 ${index + 1}/${steps.size} · ${step.label}")
            scrollView.postDelayed({
                if (!isAutomaticDemoRunning()) return@postDelayed
                lastFailure = null
                runSafely { step.body() }
                val failure = lastFailure
                lastFailure = null
                if (failure != null) {
                    val reason = failure.message ?: failure::class.java.simpleName
                    failBusy("중단 · ${step.label}")
                    setStepProgress("중단 ${index + 1}/${steps.size} · $reason")
                    appendAgentHistory("처리 중단", "‘${step.label}’에서 멈췄습니다: $reason")
                    appendMessenger("시스템", "처리를 중단했습니다: $reason")
                    activePhaseTitle = null
                    stopAutomaticDemo()
                    pendingAck = null
                    showResultDialog(
                        "처리를 중단했습니다",
                        "${step.label}\n\n$reason\n\n남은 단계는 실행하지 않았습니다. 이 상태는 완료가 아닙니다.",
                    )
                    return@postDelayed
                }
                finishBusy(step.done)
                setStepProgress("진행 ${index + 1}/${steps.size} · ${step.done}")
                val resume = { scrollView.postDelayed({ run(index + 1) }, AGENT_STEP_SETTLE_MS) }
                val gate = pendingAck
                pendingAck = null
                if (gate != null) gate { resume() } else resume()
            }, step.thinkMs)
        }
        run(0)
    }

    /**
     * Renders every room on the device with the candidate evaluation the core
     * actually produced.  Nothing here is scripted: selection and exclusion
     * reasons come from [SafeShareCore.evaluateCandidateRooms].
     */
    private fun refreshRoomList() {
        if (!::roomList.isInitialized) return
        roomList.removeAllViews()
        val scenario = syntheticScenarios.getOrNull(selectedScenarioIndex)
        if (scenario == null) {
            roomList.addView(TextView(this).apply {
                text = "합성 대화방 목록을 불러오는 중입니다."
                textSize = 14f
                setTextColor(0xff687386.toInt())
            })
            return
        }
        val evaluation = lastRoomEvaluation
        val selected = evaluation?.optString("selected_room_id").orEmpty()
        // The durable context, not the UI, decides whether the confirmed room
        // is still shareable under the current role.
        val confirmedScope = readContext(requireRoute = false).scope
        val outsideScope = roomsOutsideScope(confirmedScope)
        val excluded = mutableMapOf<String, String>()
        evaluation?.optJSONArray("excluded")?.let { array ->
            for (i in 0 until array.length()) {
                val entry = array.getJSONObject(i)
                excluded[entry.optString("room_id")] = entry.optString("reason_code")
            }
        }
        scenario.rooms.forEach { room ->
            val inScope = confirmedScope.isNotBlank() && room.roomId !in outsideScope
            val (badge, badgeColor, badgeBackground) = when {
                SafeSharePolicy.roomIsReadOnly(room.roomId, readOnlyRoomIds) ->
                    Triple("공유 불가 · 열람 전용으로 강등", 0xff842029.toInt(), 0xfff8d7da.toInt())
                room.roomId == selected && selected.isNotEmpty() ->
                    Triple("선택됨 · 이번 공유 대상", 0xff0f5132.toInt(), 0xffd1e7dd.toInt())
                excluded[room.roomId] == "REVOKED" ->
                    Triple("제외 · 승인 철회됨", 0xff842029.toInt(), 0xfff8d7da.toInt())
                excluded[room.roomId] == "MEMBER_OUTSIDE_ALLOWED_SCOPE" ->
                    Triple("제외 · 허용 범위 밖 구성원", 0xff842029.toInt(), 0xfff8d7da.toInt())
                evaluation != null && inScope ->
                    Triple("공유 가능 · 허용 범위 안", 0xff0f5132.toInt(), 0xffe4f3ea.toInt())
                excluded[room.roomId] == "NOT_CONFIRMED_FOR_PURPOSE" ->
                    Triple("제외 · 이 목적에 확인 안 됨", 0xff8a5a00.toInt(), 0xfffff0d6.toInt())
                evaluation == null ->
                    Triple("확인 전", 0xff4b5568.toInt(), 0xffeef1f6.toInt())
                else ->
                    Triple("후보 아님", 0xff4b5568.toInt(), 0xffeef1f6.toInt())
            }
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                setPadding(dp(14), dp(14), dp(14), dp(14))
                background = roundedBackground(0xffffffff.toInt(), 18, 0xffe4e8ef.toInt())
                gravity = android.view.Gravity.CENTER_VERTICAL
            }
            row.addView(
                TextView(this).apply {
                    text = room.displayName.take(1)
                    textSize = 20f
                    gravity = android.view.Gravity.CENTER
                    setTypeface(typeface, Typeface.BOLD)
                    setTextColor(0xffffffff.toInt())
                    background = circleBackground(avatarColor(room.roomId))
                },
                LinearLayout.LayoutParams(dp(52), dp(52)),
            )
            val column = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(14), 0, 0, 0)
            }
            column.addView(TextView(this@MainActivity).apply {
                text = "${room.displayName}  ·  ${room.members.size}명"
                textSize = 17f
                setTypeface(typeface, Typeface.BOLD)
                setTextColor(0xff141a24.toInt())
            })
            column.addView(TextView(this@MainActivity).apply {
                text = room.members.joinToString(", ") { it.displayName }
                textSize = 13f
                setTextColor(0xff5b6577.toInt())
                setPadding(0, dp(3), 0, 0)
                maxLines = 2
            })
            column.addView(TextView(this@MainActivity).apply {
                text = "구성원 범위 ${room.members.first().memberScope}"
                textSize = 12f
                setTextColor(0xff8892a4.toInt())
                setPadding(0, dp(2), 0, dp(8))
            })
            column.addView(
                TextView(this@MainActivity).apply {
                    text = badge
                    textSize = 12f
                    setTypeface(typeface, Typeface.BOLD)
                    setTextColor(badgeColor)
                    setPadding(dp(10), dp(5), dp(10), dp(5))
                    background = roundedBackground(badgeBackground, 20)
                },
                LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                ),
            )
            row.addView(
                column,
                LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f),
            )
            val params = matchWidth()
            params.bottomMargin = dp(10)
            roomList.addView(row, params)
        }
        roomList.addView(TextView(this).apply {
            text = if (evaluation == null) {
                "아직 후보 평가를 실행하지 않았습니다. 아래에서 공유 요청을 시작하면 방마다 상태가 채워집니다."
            } else {
                "목적 ‘${evaluation.optString("purpose")}’ 기준 · 후보 ${evaluation.optJSONArray("candidate_room_ids")?.length() ?: 0}곳 · 판단 ${evaluation.optString("decision_state")}"
            }
            textSize = 13f
            setTextColor(0xff687386.toInt())
            setPadding(0, dp(2), 0, 0)
        })
    }

    /**
     * Rooms whose member list holds nobody from the confirmed allowed scope.
     * Membership comes from the same synthetic input the core executes, so this
     * is a real exclusion reason rather than a label.
     */
    private fun roomsOutsideScope(allowedScope: String): Set<String> {
        val scenario = syntheticScenarios.getOrNull(selectedScenarioIndex) ?: return emptySet()
        return scenario.rooms
            .filter { room ->
                SafeSharePolicy.roomOutsideScope(room.members.map { it.memberScope }, allowedScope)
            }
            .map { it.roomId }
            .toSet()
    }

    /** Runs the real candidate evaluation and caches it for the room list. */
    private fun evaluateRoomsForDisplay() {
        val context = readContext(requireRoute = false)
        if (context.purpose.isBlank()) return
        lastRoomEvaluation = core().evaluateCandidateRooms(
            context.purpose,
            parseCandidateRooms(),
            roomsOutsideScope(context.scope),
            readOnlyRoomIds.toSet(),
        )
        refreshRoomList()
    }

    private fun prepareManualPayload() {
        val visibleContent = contentInput.text.toString()
        val episode = loadedSyntheticEpisode
        if (episode != null && visibleContent == loadedSyntheticContent) {
            acceptIntake(syntheticIntake(episode))
        } else {
            loadedSyntheticEpisode = null
            acceptIntake(SafeShareIntake.fromManualText(visibleContent))
        }
    }

    private fun handleShareIntent(incoming: Intent?) {
        when (val result = SafeShareIntake.fromIntent(this, incoming)) {
            SafeShareIntakeResult.NotShareIntent -> Unit
            else -> {
                loadedSyntheticEpisode = null
                loadedSyntheticContent = ""
                acceptIntake(result)
            }
        }
    }

    private fun acceptIntake(result: SafeShareIntakeResult) {
        when (result) {
            is SafeShareIntakeResult.Accepted -> runSafely {
                deleteReplacedUnboundPdfCopy(result.payload)
                pendingPayload = result.payload
                if (result.payload.review.kind == ShareKind.LINK) {
                    linkAccessInput.setText("UNKNOWN")
                } else if (linkAccessInput.text.toString().trim().uppercase() == "UNKNOWN") {
                    linkAccessInput.setText("NOT_APPLICABLE")
                }
                contentInput.setText(result.payload.review.safePreview)
                core().rememberReviewedPayload(result.payload)
                updateSafetyContext(result.payload, reviewState(result.payload.review))
                val review = result.payload.review
                renderState(
                    buildString {
                        appendLine("공유할 내용을 안전하게 검토했습니다.")
                        if (review.secretDetected) appendLine("민감 값은 가림 형태로만 저장했습니다.")
                        if (review.requiresLinkAccessConfirmation) {
                            appendLine("새 링크의 접근 가능 여부를 확인해 주세요.")
                        }
                        if (result.payload.privatePdfCopied) {
                            appendLine("PDF는 검토를 위해 기기 안에 안전하게 복사했습니다.")
                        }
                        append(review.recommendation)
                    },
                )
            }
            is SafeShareIntakeResult.Rejected -> renderState("공유 검토 보류: ${result.reason}")
            SafeShareIntakeResult.NotShareIntent -> Unit
        }
    }

    private fun proposeRoute() = runSafely {
        if (pendingPayload == null) prepareManualPayload()
        val payload = checkNotNull(pendingPayload) { "먼저 공유 내용을 검토하세요." }
        val context = readContext(requireRoute = true)
        updateSafetyContext(payload, "ROUTE_CONFIRMATION_REQUIRED")
        core().receiveSharedText(payload.eventId, payload.review.safePreview)
        core().proposeRoute(context.purpose, context.roomId)
        renderState("공유 대상·현재 역할·허용 범위를 확인해 주세요. 확인 전에는 공유를 준비하지 않습니다.")
    }

    private fun confirmRoute() = runSafely {
        val context = readContext(requireRoute = true)
        val snapshot = core().productSnapshot()
        check(!snapshot.isNull("pending_route_change")) { "먼저 E1 방 기준을 제안하세요." }
        updateSafetyContext(pendingPayload, "ROUTE_CONFIRMATION_REQUIRED")
        core().confirmPendingRoute()
        updateSafetyContext(pendingPayload, "ROUTE_CONFIRMED")
        core().evaluateCandidateRooms(context.purpose, parseCandidateRooms())
        renderState(
            "이 공유 기준을 저장했습니다. 다음 유사 공유에서도 현재 대상과 권한이 같을 때만 재사용합니다.",
        )
    }

    private fun proposeDraft() = runSafely {
        if (pendingPayload == null) prepareManualPayload()
        val payload = checkNotNull(pendingPayload) { "먼저 공유 내용을 검토하세요." }
        val context = readContext(requireRoute = true)
        if (
            payload.review.requiresLinkAccessConfirmation &&
            !SafeSharePolicy.linkAccessConfirmed(context.linkAccess)
        ) {
            updateSafetyContext(payload, "LINK_ACCESS_ASK")
            error("이 링크의 접근 상태를 CONFIRMED 또는 PROJECT_ONLY로 명시 확인하세요.")
        }
        if (!SafeSharePolicy.canShare(sharePermission)) {
            updateSafetyContext(payload, "ROLE_READ_ONLY_ASK")
            error("현재 역할 ‘${context.role}’은 열람 전용이라 이 방으로는 공유를 준비하지 않습니다.")
        }
        updateSafetyContext(payload, "DRAFT_REVIEWED")
        if (SafeSharePolicy.roomIsReadOnly(context.roomId, readOnlyRoomIds)) {
            updateSafetyContext(payload, "ROLE_READ_ONLY_ASK")
            error("‘${context.roomDisplay}’에서는 현재 역할이 열람 전용이라 공유를 준비하지 않습니다.")
        }
        val evaluation = core().evaluateCandidateRooms(
            context.purpose,
            parseCandidateRooms(),
            roomsOutsideScope(context.scope),
            readOnlyRoomIds.toSet(),
        )
        check(evaluation.getString("decision_state") == "ACT") {
            "확인된 room ID가 현재 후보에 없어 공유 준비를 보류했습니다."
        }
        check(evaluation.getString("selected_room_id") == context.roomId) {
            "입력한 room ID는 이 목적에 대해 확인된 대상이 아닙니다."
        }
        val observation = core().receiveSharedText(payload.eventId, payload.review.safePreview)
        val message = when {
            observation.optString("status") == "ASK" ->
                "확인된 공유 기준이 없어 공유를 준비하지 않았습니다."
            observation.optBoolean("duplicate") ->
                "같은 공유 요청은 다시 처리하지 않고 기존 공유 준비를 유지했습니다."
            else -> "확인한 공유 기준을 재사용해 공유할 내용을 준비했습니다."
        }
        renderState(message, observation.toString(2))
    }

    private fun holdAndRestart() = runSafely {
        val context = readContext(requireRoute = true)
        check(pendingPayload != null) { "E3 전에 검토한 payload가 없습니다." }
        val held = core().beginContextChange(
            purpose = context.purpose,
            targetRoomId = context.roomId,
            roomDisplay = context.roomDisplay,
            currentRole = context.role,
            allowedScope = context.scope,
            linkAccess = context.linkAccess,
        )
        val pending = held.getJSONObject("pending_context_change")
        check(pending.getJSONArray("changed_fields").length() > 0) {
            "변경 항목을 기록하지 못해 process를 종료하지 않았습니다."
        }
        scheduleProcessRestart()
    }

    private fun recoverPendingContext() = runSafely {
        val context = readContext(requireRoute = true)
        val before = core().productSnapshot()
        check(!before.isNull("pending_context_change")) { "복구할 보류 작업이 없습니다." }
        core().revisePendingContextChange(
            targetRoomId = context.roomId,
            roomDisplay = context.roomDisplay,
            currentRole = context.role,
            allowedScope = context.scope,
            linkAccess = context.linkAccess,
        )
        core().confirmPendingContextChange()
        core().evaluateCandidateRooms(context.purpose, parseCandidateRooms())
        renderState(
            "부분 복구를 마쳤습니다. 달라진 조건만 다시 확인하고, 영향을 받은 공유만 새 기준으로 준비했습니다.",
        )
    }

    private fun scheduleProcessRestart() {
        val currentPid = Process.myPid()
        val preferences = getSharedPreferences(ProcessRestartProtocol.PREFERENCES, MODE_PRIVATE)
        check(
            preferences.edit()
                .putBoolean(ProcessRestartProtocol.KEY_RESTART_PENDING, true)
                .putInt(ProcessRestartProtocol.KEY_PREVIOUS_PID, currentPid)
                .putLong(ProcessRestartProtocol.KEY_RESTART_SCHEDULED_AT, System.currentTimeMillis())
                .commit(),
        ) { "보류 checkpoint를 저장하지 못해 process를 종료하지 않았습니다." }
        startActivity(
            Intent(this, ProcessRestarterActivity::class.java)
                .putExtra(ProcessRestartProtocol.EXTRA_PREVIOUS_PID, currentPid),
        )
    }

    private fun updateSafetyContext(payload: SafeSharePayload?, state: String) {
        val context = readContext(requireRoute = false)
        core().updateSafetyContext(
            purpose = context.purpose,
            roomId = context.roomId,
            roomDisplay = context.roomDisplay,
            currentRole = context.role,
            allowedScope = context.scope,
            linkAccess = context.linkAccess,
            contentKind = payload?.review?.kind?.name ?: "UNKNOWN",
            reviewState = state,
            sharePermission = sharePermission,
        )
    }

    private fun restoreInputsFromState() {
        val snapshot = core().productSnapshot()
        val pending = snapshot.optJSONObject("pending_context_change")
        val pendingContext = pending?.optJSONObject("new_context")
        val safeContext = snapshot.optJSONObject("safe_share_context")
        val chosen = pendingContext ?: safeContext
        val purpose = chosen?.optString("purpose").orEmpty()
            .ifEmpty { pending?.optString("purpose").orEmpty() }
            .ifEmpty { snapshot.optString("active_purpose") }
        val roomId = pending?.optString("new_target").orEmpty()
            .ifEmpty { chosen?.optString("room_id").orEmpty() }
            .ifEmpty { snapshot.optJSONObject("pending_route_change")?.optString("new_target").orEmpty() }
            .ifEmpty { snapshot.optJSONObject("route_rules")?.optString(purpose).orEmpty() }
        if (purpose.isNotEmpty()) purposeInput.setText(purpose)
        if (roomId.isNotEmpty()) roomIdInput.setText(roomId)
        chosen?.optString("room_display")?.takeIf(String::isNotBlank)?.let(roomDisplayInput::setText)
        chosen?.optString("current_role")?.takeIf(String::isNotBlank)?.let(roleInput::setText)
        chosen?.optString("allowed_scope")?.takeIf(String::isNotBlank)?.let(scopeInput::setText)
        chosen?.optString("link_access")?.takeIf(String::isNotBlank)?.let(linkAccessInput::setText)
    }

    private fun consumeRestartMessage(): String? {
        val preferences = getSharedPreferences(ProcessRestartProtocol.PREFERENCES, MODE_PRIVATE)
        if (!preferences.getBoolean(ProcessRestartProtocol.KEY_RESTART_PENDING, false)) return null
        val previousPid = preferences.getInt(ProcessRestartProtocol.KEY_PREVIOUS_PID, -1)
        preferences.edit()
            .remove(ProcessRestartProtocol.KEY_RESTART_PENDING)
            .remove(ProcessRestartProtocol.KEY_PREVIOUS_PID)
            .remove(ProcessRestartProtocol.KEY_RESTART_SCHEDULED_AT)
            .commit()
        return if (ProcessRestartProtocol.isPidChangeVerified(previousPid, Process.myPid())) {
            "앱이 실제로 재시작되었습니다. 보류한 공유 준비와 다시 확인할 조건을 기기에서 복원했습니다."
        } else {
            "PID 변경을 검증하지 못했습니다. 완료로 표시하지 않고 보류 상태를 유지합니다."
        }
    }

    private fun loadSyntheticCorpus() {
        runCatching { ScenarioCorpusAssetLoader.loadAll(assets) }
            .onSuccess { scenarios ->
                syntheticScenarios = scenarios
                selectedScenarioIndex = selectedScenarioIndex.coerceIn(scenarios.indices)
                updateScenarioSummary()
                refreshFileList()
            }
            .onFailure { error ->
                syntheticScenarios = emptyList()
                val reason = error.message ?: error::class.java.simpleName
                scenarioSummary.text = "예시를 불러오지 못했습니다: $reason"
                if (::recentFileList.isInitialized) {
                    recentFileList.removeAllViews()
                    recentFileList.addView(
                        TextView(this).apply {
                            text = "합성 파일 목록을 불러오지 못했습니다.\n$reason"
                            textSize = 15f
                            setTextColor(0xff842029.toInt())
                            setPadding(dp(14), dp(14), dp(14), dp(14))
                            background = roundedBackground(0xfff8d7da.toInt(), 14)
                        },
                        matchWidth(),
                    )
                }
            }
    }

    /** Keeps the reviewed scenario across the intentional process kill. */
    private fun setSelectedScenarioIndex(index: Int) {
        selectedScenarioIndex = index
        check(
            getSharedPreferences(QUICK_DEMO_PREFERENCES, MODE_PRIVATE)
                .edit()
                .putInt(KEY_SCENARIO_INDEX, index)
                .commit(),
        ) { "검토 중인 예시 번호를 저장하지 못했습니다." }
    }

    private fun currentSyntheticScenario(): SafeShareScenario {
        check(syntheticScenarios.isNotEmpty()) { "합성 corpus가 준비되지 않았습니다." }
        return syntheticScenarios[selectedScenarioIndex]
    }

    private fun updateScenarioSummary() {
        if (syntheticScenarios.isEmpty()) return
        val scenario = currentSyntheticScenario()
        val file = fileItemFor(selectedScenarioIndex)
        scenarioSummary.text = buildString {
            appendLine(file?.name ?: scenario.purpose.displayText)
            appendLine(file?.subtitle ?: workContextLabel(scenario.personaStyle.workContext.name))
            append("공유 대상이나 권한이 바뀌면 멈추고 필요한 부분만 다시 확인합니다.")
        }
        updateQuickDemoUi()
    }

    private fun refreshFileList() {
        if (!::recentFileList.isInitialized) return
        recentFileList.removeAllViews()
        FILE_LIST_SCENARIOS.forEach { index ->
            if (syntheticScenarios.indices.contains(index)) {
                recentFileList.addView(fileRow(fileItemFor(index) ?: return@forEach, index))
            }
        }
    }

    private fun fileRow(item: DemoFileItem, scenarioIndex: Int): View {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = android.view.Gravity.CENTER_VERTICAL
            setPadding(dp(14), dp(12), dp(12), dp(12))
            background = roundedBackground(0xffffffff.toInt(), 16, 0xffdfe3ea.toInt())
            isClickable = true
            isFocusable = true
            setOnClickListener { startAutomaticDemo(scenarioIndex) }
            layoutParams = matchWidth().apply { bottomMargin = dp(8) }
        }
        row.addView(TextView(this).apply {
            text = item.kind
            textSize = 11f
            gravity = android.view.Gravity.CENTER
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(0xffffffff.toInt())
            background = roundedBackground(item.color, 10)
        }, LinearLayout.LayoutParams(dp(48), dp(48)))
        row.addView(LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(12), 0, dp(4), 0)
            addView(TextView(this@MainActivity).apply {
                text = item.name
                textSize = 16f
                setTypeface(typeface, Typeface.BOLD)
                setTextColor(0xff172033.toInt())
            })
            addView(TextView(this@MainActivity).apply {
                text = item.subtitle
                textSize = 13f
                setTextColor(0xff687386.toInt())
                setPadding(0, dp(3), 0, 0)
            })
        }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        row.addView(TextView(this).apply {
            text = "검토 시작"
            textSize = 12f
            setTextColor(0xff2457d6.toInt())
        })
        return row
    }

    private fun fileItemFor(index: Int): DemoFileItem? = when (index) {
        0 -> DemoFileItem("분기 예산안 검토.pdf", "업무 문서 · PDF", "PDF", 0xffd14545.toInt())
        2 -> DemoFileItem("공공행사 안전계획.pdf", "행사 자료 · PDF", "PDF", 0xffd14545.toInt())
        12 -> DemoFileItem("가족 여행 계획.txt", "개인 · 가족방 전용", "TXT", 0xff2f9e44.toInt())
        8 -> DemoFileItem("학교 자원봉사 일정.txt", "개인 일정 · 텍스트", "TXT", 0xff3974d8.toInt())
        10 -> DemoFileItem("개인 모임 행사 안내문.pdf", "개인 모임 · PDF", "PDF", 0xffd14545.toInt())
        else -> null
    }

    private fun workContextLabel(raw: String): String = when (raw) {
        "OFFICE_PROFESSIONAL" -> "사내 프로젝트 업무"
        "PUBLIC_SERVICE" -> "공공·행정 업무"
        "FIELD_PRODUCTION" -> "현장·제작 업무"
        "INDEPENDENT" -> "개인·프리랜서 업무"
        else -> "업무 공유"
    }

    private fun intakeKindsLabel(raw: String): String = raw
        .split('/')
        .mapNotNull {
            when (it) {
                "TEXT" -> "텍스트"
                "LINK" -> "링크"
                "PDF" -> "PDF"
                "NONE" -> "재시작 복구"
                else -> null
            }
        }
        .joinToString(" · ")

    private fun changeSyntheticScenario(delta: Int) = runSafely {
        check(syntheticScenarios.isNotEmpty()) { "합성 corpus가 준비되지 않았습니다." }
        core().resetAllUserData()
        deletePrivatePdfCopies()
        pendingPayload = null
        setSelectedScenarioIndex(
            Math.floorMod(
                selectedScenarioIndex + delta,
                syntheticScenarios.size,
            ),
        )
        setQuickDemoStage(0)
        updateScenarioSummary()
        populateDemoEpisode("E1")
        renderState(
            "${currentSyntheticScenario().scenarioId}로 전환하고 app-local 상태를 초기화했습니다. E1 입력부터 검토하세요.",
        )
    }

    private fun loadDemoEpisode(episodeId: String) = runSafely {
        populateDemoEpisode(episodeId)
        renderState(
            "${currentSyntheticScenario().scenarioId}의 합성 $episodeId 입력을 채웠습니다. 입력 자체로 상태가 바뀌지 않으며 아래 단계 버튼이 실제 코어를 호출합니다.",
        )
    }

    private fun populateDemoEpisode(episodeId: String) {
        val scenario = currentSyntheticScenario()
        val episode = scenario.episodes.singleOrNull { it.episodeId == episodeId }
            ?: error("합성 episode를 찾을 수 없습니다: $episodeId")
        val visibleContent = when (val intake = episode.intake) {
            is ScenarioIntake.Text -> intake.content
            is ScenarioIntake.Link -> intake.url
            is ScenarioIntake.Pdf -> "합성 PDF fixture: ${intake.assetRef} (${intake.mimeType})"
            ScenarioIntake.None -> "입력 없음 — E3 checkpoint의 app-local 상태를 복구합니다."
        }
        loadedSyntheticEpisode = episode
        loadedSyntheticContent = visibleContent
        contentInput.setText(visibleContent)
        purposeInput.setText(episode.context.purposeId)
        roomIdInput.setText(episode.context.targetRoomId)
        roomDisplayInput.setText(episode.context.targetRoomDisplay)
        roleInput.setText(episode.context.currentRole)
        scopeInput.setText(episode.context.allowedScope)
        linkAccessInput.setText(episode.context.linkAccess.name)
        sharePermission = episode.context.sharePermission.name
        readOnlyRoomIds = episode.readOnlyRoomIds
        candidateRoomsInput.setText(episode.candidateRoomIds.joinToString("\n"))
    }

    private fun resetAndLoadDemo(episodeId: String) = runSafely {
        core().resetAllUserData()
        deletePrivatePdfCopies()
        pendingPayload = null
        populateDemoEpisode(episodeId)
        renderState(
            "${currentSyntheticScenario().scenarioId}의 app-local 상태를 초기화하고 합성 $episodeId 입력을 채웠습니다.",
        )
    }

    private fun syntheticIntake(episode: ScenarioEpisode): SafeShareIntakeResult {
        val scenario = currentSyntheticScenario()
        val eventId = "synthetic-${scenario.scenarioId.lowercase()}-${episode.episodeId.lowercase()}"
        return when (val intake = episode.intake) {
            is ScenarioIntake.Text -> SafeShareIntakeResult.Accepted(
                SafeSharePayload(
                    eventId = eventId,
                    review = SafeSharePolicy.review(ShareKind.TEXT, intake.content),
                    privatePdfCopied = false,
                ),
            )
            is ScenarioIntake.Link -> SafeShareIntakeResult.Accepted(
                SafeSharePayload(
                    eventId = eventId,
                    review = SafeShareLinkReview.review(
                        raw = intake.url,
                        host = android.net.Uri.parse(intake.url).host,
                    ),
                    privatePdfCopied = false,
                ),
            )
            is ScenarioIntake.Pdf -> {
                val copied = copySyntheticPdf(intake.assetRef)
                SafeShareIntakeResult.Accepted(
                    SafeSharePayload(
                        eventId = eventId,
                        review = SafeSharePolicy.review(
                            ShareKind.PDF,
                            "PDF: ${intake.assetRef.substringAfterLast('/')} " +
                                "(${copied.byteCount} bytes, synthetic app-private copy)",
                        ),
                        privatePdfCopied = true,
                        privatePdfCopyToken = copied.copyToken,
                    ),
                )
            }
            ScenarioIntake.None -> SafeShareIntakeResult.Rejected(
                "E4에는 새 입력이 없습니다. E3에서 보류한 app-local 상태를 복구하세요.",
            )
        }
    }

    private fun copySyntheticPdf(assetRef: String): SyntheticPdfCopy {
        require(assetRef.matches(Regex("synthetic/[a-z0-9._/-]+\\.pdf")) && ".." !in assetRef) {
            "합성 PDF asset 경로가 올바르지 않습니다."
        }
        val directory = File(filesDir, "safe-share-pdf").apply {
            check(exists() || mkdirs()) { "앱 내부 PDF 폴더를 만들 수 없습니다." }
        }
        val temporary = File(directory, ".synthetic-${System.nanoTime()}.tmp")
        val destination = File(directory, "synthetic-${System.nanoTime()}.pdf")
        var copied = 0L
        try {
            assets.open(assetRef).use { input ->
                FileOutputStream(temporary).use { output ->
                    val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                    while (true) {
                        val count = input.read(buffer)
                        if (count < 0) break
                        copied += count
                        check(copied <= 8L * 1024L * 1024L) { "PDF가 8 MiB 제한을 초과했습니다." }
                        output.write(buffer, 0, count)
                    }
                    output.fd.sync()
                }
            }
            check(copied > 4L) { "합성 PDF fixture가 비어 있습니다." }
            check(temporary.inputStream().use { stream ->
                val header = ByteArray(5)
                stream.read(header) == header.size && String(header, Charsets.US_ASCII) == "%PDF-"
            }) { "합성 PDF fixture 형식이 올바르지 않습니다." }
            check(temporary.renameTo(destination)) { "합성 PDF private copy를 확정하지 못했습니다." }
            return SyntheticPdfCopy(copied, destination.name)
        } finally {
            if (temporary.exists()) temporary.delete()
        }
    }

    private data class SyntheticPdfCopy(val byteCount: Long, val copyToken: String)

    private fun runSelectedSyntheticTrace() = runSafely {
        val scenario = currentSyntheticScenario()
        val trace = SafeShareScenarioExecutor().execute(scenario)
        val finalObservation = trace.observations.last().observed
        renderEvidence(
            buildString {
                appendLine("${scenario.scenarioId} 입력이 실제 SafeShareCore를 통과했습니다.")
                appendLine("4 episodes / ${trace.actionCount} actions / process epoch ${finalObservation.getInt("process_epoch")}")
                append("관찰된 외부 전송 ${finalObservation.getInt("external_dispatch_count")}회")
            },
            trace.toJson().toString(2),
        )
    }

    private fun runSyntheticCorpusSweep() = runSafely {
        check(syntheticScenarios.isNotEmpty()) { "합성 corpus가 준비되지 않았습니다." }
        val traces = syntheticScenarios.map { scenario ->
            scenario to SafeShareScenarioExecutor().execute(scenario)
        }
        val actionCount = traces.sumOf { it.second.actionCount }
        val finalObservations = traces.map { it.second.observations.last().observed }
        val intakeKinds = traces
            .flatMap { it.first.episodes }
            .groupingBy { it.intake.kind }
            .eachCount()
            .toSortedMap()
        val rawSummary = JSONObject()
            .put("schema_version", "safeshare.corpus-observation.v1")
            .put("scenario_count", traces.size)
            .put("episode_count", traces.sumOf { it.first.episodes.size })
            .put("action_count", actionCount)
            .put("intake_kind_counts", JSONObject(intakeKinds))
            .put(
                "scenario_observations",
                JSONArray().apply {
                    traces.forEach { (scenario, trace) ->
                        val final = trace.observations.last().observed
                        put(
                            JSONObject()
                                .put("scenario_id", scenario.scenarioId)
                                .put("input_sha256", scenario.inputSha256)
                                .put("trace_sha256", trace.toJson().getString("trace_sha256"))
                                .put("action_count", trace.actionCount)
                                .put("process_epoch", final.getInt("process_epoch"))
                                .put("external_dispatch_count", final.getInt("external_dispatch_count")),
                        )
                    }
                },
            )
        renderEvidence(
            "합성 입력 ${traces.size}개·episode ${traces.sumOf { it.first.episodes.size }}개·action ${actionCount}개를 실제 코어에서 관찰했습니다. " +
                "외부 전송 합계 ${finalObservations.sumOf { it.getInt("external_dispatch_count") }}회입니다.",
            rawSummary.toString(2),
        )
    }

    private fun deleteLatestMemory() = runSafely {
        val requestedEventId = pendingPayload?.eventId
            ?: core().restoreReviewedPayload()?.eventId
        val boundCopyToken = core().privatePdfCopyToken(requestedEventId)
        if (boundCopyToken != null) {
            check(
                SafeSharePrivatePdfFiles.deleteBoundCopy(
                    File(filesDir, "safe-share-pdf"),
                    boundCopyToken,
                ),
            ) { "연결된 app-private PDF 사본을 삭제하지 못했습니다." }
        } else if (pendingPayload?.privatePdfCopied == true) {
            // Privacy-safe migration path for state created before copy tokens
            // were bound to share events.
            deletePrivatePdfCopies()
        }
        core().deleteShareMemory(requestedEventId)
        pendingPayload = core().restoreReviewedPayload()
        if (pendingPayload == null) contentInput.setText("")
        renderState("최신 공유 요약과 연결된 공유 준비를 삭제하고 삭제 기록을 남겼습니다.")
    }

    private fun deleteReplacedUnboundPdfCopy(nextPayload: SafeSharePayload) {
        val previous = pendingPayload ?: core().restoreReviewedPayload()
        if (
            previous == null ||
            previous.eventId == nextPayload.eventId ||
            !previous.privatePdfCopied ||
            core().hasShareEnvelope(previous.eventId)
        ) {
            return
        }
        val token = core().privatePdfCopyToken(previous.eventId)
        if (token != null) {
            check(
                SafeSharePrivatePdfFiles.deleteBoundCopy(
                    File(filesDir, "safe-share-pdf"),
                    token,
                ),
            ) { "교체된 app-private PDF 사본을 삭제하지 못했습니다." }
        } else {
            // Fail closed for legacy state that predates per-event copy tokens.
            deletePrivatePdfCopiesExcept(nextPayload.privatePdfCopyToken)
        }
    }

    private fun deletePrivatePdfCopiesExcept(preservedToken: String?) {
        val directory = File(filesDir, "safe-share-pdf")
        if (!directory.exists()) return
        val preserved = preservedToken?.takeIf(SafeSharePrivatePdfFiles::isValidToken)
        directory.listFiles().orEmpty().forEach { file ->
            if (file.name != preserved) {
                check(file.deleteRecursively()) {
                    "교체된 legacy app-private PDF 사본을 삭제하지 못했습니다."
                }
            }
        }
    }

    private fun confirmReset() {
        AlertDialog.Builder(this)
            .setTitle("이 기기의 데이터 삭제")
            .setMessage("공유 준비, 공유 기준, 검토 상태와 앱에 저장한 PDF 사본을 삭제합니다.")
            .setNegativeButton("취소", null)
            .setPositiveButton("전체 삭제") { _, _ ->
                runSafely {
                    core().resetAllUserData()
                    deletePrivatePdfCopies()
                    pendingPayload = null
                    contentInput.setText("")
                    setQuickDemoStage(0)
                    renderState("이 기기에 저장한 공유 준비와 PDF 사본을 삭제했습니다.")
                }
            }
            .show()
    }

    private fun deletePrivatePdfCopies() {
        val directory = File(filesDir, "safe-share-pdf")
        check(!directory.exists() || directory.deleteRecursively()) {
            "app-private PDF 복사본을 모두 삭제하지 못했습니다."
        }
    }

    private fun readContext(requireRoute: Boolean): UiContext {
        val context = UiContext(
            purpose = purposeInput.text.toString().trim(),
            roomId = roomIdInput.text.toString().trim(),
            roomDisplay = roomDisplayInput.text.toString().trim(),
            role = roleInput.text.toString().trim(),
            scope = scopeInput.text.toString().trim(),
            linkAccess = linkAccessInput.text.toString().trim().uppercase(),
        )
        if (requireRoute) {
            require(context.purpose.isNotBlank()) { "공유 목적을 입력하세요." }
            require(validRoomId(context.roomId)) {
                "대화방 ID는 영문, 숫자, 마침표, 하이픈, 밑줄만 사용해 3~80자로 입력하세요."
            }
            require(context.roomDisplay.isNotBlank()) { "대화방 표시명과 용도를 입력하세요." }
            require(context.role.isNotBlank()) { "현재 역할을 입력하세요." }
            require(context.scope.isNotBlank()) { "허용 범위를 입력하세요." }
            require(
                context.linkAccess in setOf("UNKNOWN", "CONFIRMED", "PROJECT_ONLY", "NOT_APPLICABLE"),
            ) { "지원하는 링크 접근 상태를 입력하세요." }
        }
        return context
    }

    private fun parseCandidateRooms(): List<String> {
        val candidates = candidateRoomsInput.text.toString()
            .split(Regex("[,\\r\\n]+"))
            .map(String::trim)
            .filter(String::isNotEmpty)
            .distinct()
        require(candidates.isNotEmpty()) { "현재 후보 room ID를 하나 이상 입력하세요." }
        require(candidates.all(::validRoomId)) { "후보 room ID 형식이 올바르지 않습니다." }
        return candidates
    }

    private fun validRoomId(value: String): Boolean =
        value.matches(Regex("[A-Za-z0-9._-]{3,80}"))

    private fun reviewState(review: SafeShareReview): String = when {
        review.secretDetected -> "REDACTED"
        review.requiresLinkAccessConfirmation -> "LINK_ACCESS_ASK"
        else -> "REVIEWED"
    }

    /**
     * Product actions keep the app alive when a safety guard rejects them, but
     * the rejection must stay visible.  The throwable is remembered so a caller
     * such as the agent step chain can stop instead of reporting success.
     */
    private fun runSafely(block: () -> Unit) {
        hideKeyboard()
        runCatching(block).onFailure { error ->
            lastFailure = error
            renderFailure(error)
        }
    }

    private fun renderFailure(error: Throwable) {
        renderState("안전하게 보류했습니다: ${error.message ?: error::class.java.simpleName}")
    }

    private fun renderState(message: String, detailsOverride: String? = null) {
        if (!::summary.isInitialized) return
        val snapshot = core().productSnapshot()
        val pending = snapshot.optJSONObject("pending_context_change")
        val recovery = snapshot.optJSONObject("last_recovery")
        val context = snapshot.optJSONObject("safe_share_context")
        summary.text = buildString {
            appendLine(message.trim())
            appendLine()
            appendLine("상태  ${decisionLabel(snapshot)}")
            appendLine("대상  ${context?.optString("room_display").orEmpty().ifEmpty { "아직 확인하지 않음" }}")
            if (
                context != null &&
                listOf(
                    context.optString("current_role"),
                    context.optString("allowed_scope"),
                ).any(String::isNotBlank)
            ) {
                appendLine("역할·범위  ${context.optString("current_role", "미확인")} · ${context.optString("allowed_scope", "미확인")}")
            }
            if (pending != null) appendLine("다시 확인할 것  ${humanFields(pending.optJSONArray("changed_fields"))}")
            if (recovery != null) {
                appendLine("방금 다시 확인함  ${humanFields(recovery.optJSONArray("confirmed_question_fields"))}")
                appendLine("실제 변경됨  ${humanFields(recovery.optJSONArray("changed_fields"))}")
            }
            val activeDrafts = snapshot.getInt("active_draft_count")
            val heldDrafts = snapshot.getInt("held_draft_count")
            val cancelledDrafts = snapshot.getInt("cancelled_draft_count")
            if (activeDrafts + heldDrafts + cancelledDrafts > 0) {
                appendLine("준비됨 ${activeDrafts}건 · 보류 ${heldDrafts}건 · 취소 ${cancelledDrafts}건")
            }
            append("외부 전송  ${snapshot.getInt("external_dispatch_count")}회")
        }
        technicalDetails.text = detailsOverride ?: snapshot.toString(2)
        if (detailsOverride != null) {
            technicalDetails.visibility = View.VISIBLE
            detailsToggle.text = "세부 상태 닫기"
        }
        updateQuickDemoUi()
        scrollView.post { scrollView.smoothScrollTo(0, 0) }
    }

    private fun decisionLabel(snapshot: JSONObject): String = when {
        !snapshot.isNull("pending_context_change") -> "달라진 조건을 확인해 주세요"
        snapshot.getInt("held_draft_count") > 0 -> "조건 변경으로 안전하게 보류했습니다"
        snapshot.getInt("active_draft_count") > 0 -> "공유할 내용을 준비했습니다"
        snapshot.optString("decision_state") == "ASK" -> "확인이 필요합니다"
        snapshot.optString("decision_state") == "CANCELLED" -> "취소했습니다"
        else -> "아직 판단 전입니다"
    }

    private fun humanFields(fields: JSONArray?): String {
        if (fields == null || fields.length() == 0) return "없음"
        return (0 until fields.length())
            .map { index ->
                when (fields.optString(index)) {
                    "TARGET_ROOM" -> "공유 대상"
                    "ROOM_DISPLAY" -> "대화방 용도"
                    "CURRENT_ROLE" -> "현재 역할"
                    "ALLOWED_SCOPE" -> "허용 범위"
                    "LINK_ACCESS" -> "링크 접근"
                    else -> "공유 조건"
                }
            }
            .distinct()
            .joinToString(" · ")
    }

    /**
     * Render an isolated experiment result without appending the unrelated
     * interactive-demo snapshot.  Mixing the two made a completed corpus run
     * look like it was still NOT_STARTED even though the production core had
     * already emitted the trace shown below.
     */
    private fun renderEvidence(message: String, details: String) {
        if (!::summary.isInitialized) return
        summary.text = buildString {
            appendLine(message.trim())
            appendLine()
            append("아래 기술 JSON은 이 격리 실행에서 직접 관찰한 결과이며, 기대값·오라클·점수를 포함하지 않습니다.")
        }
        technicalDetails.text = details
        technicalDetails.visibility = View.VISIBLE
        detailsToggle.text = "관찰 결과 접기"
        scrollView.post { scrollView.smoothScrollTo(0, 0) }
    }

    private fun toggleTechnicalDetails() {
        val show = technicalDetails.visibility != View.VISIBLE
        technicalDetails.visibility = if (show) View.VISIBLE else View.GONE
        detailsToggle.text = if (show) "세부 상태 닫기" else "세부 상태 보기"
    }

    private fun hideKeyboard() {
        currentFocus?.let { focused ->
            (getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager)
                .hideSoftInputFromWindow(focused.windowToken, 0)
            focused.clearFocus()
        }
    }

    private fun core(): SafeShareCore = SafeShareCoreProvider.core(this)

    private fun section(title: String): TextView = TextView(this).apply {
        text = title
        textSize = 18f
        setTypeface(typeface, Typeface.BOLD)
        setPadding(0, dp(20), 0, dp(4))
    }

    private fun edit(hint: String, value: String): EditText = EditText(this).apply {
        this.hint = hint
        setText(value)
        minLines = 1
    }

    private fun button(label: String, onClick: () -> Unit): Button = Button(this).apply {
        text = label
        isAllCaps = false
        setOnClickListener { onClick() }
    }

    private fun primaryButton(label: String, onClick: () -> Unit): Button =
        button(label, onClick).apply {
            textSize = 16f
            setTextColor(0xffffffff.toInt())
            backgroundTintList = ColorStateList.valueOf(0xff2457d6.toInt())
            minHeight = dp(54)
        }

    private fun roundedBackground(
        fillColor: Int,
        radiusDp: Int,
        strokeColor: Int? = null,
    ): GradientDrawable = GradientDrawable().apply {
        shape = GradientDrawable.RECTANGLE
        cornerRadius = dp(radiusDp).toFloat()
        setColor(fillColor)
        if (strokeColor != null) setStroke(dp(1), strokeColor)
    }

    private fun circleBackground(color: Int): GradientDrawable = GradientDrawable().apply {
        shape = GradientDrawable.OVAL
        setColor(color)
    }

    /** Stable per-room avatar tint derived from the opaque room ID. */
    private fun avatarColor(roomId: String): Int {
        val palette = listOf(
            0xff4c6ef5.toInt(), 0xff12b886.toInt(), 0xffe8590c.toInt(),
            0xff7048e8.toInt(), 0xff1098ad.toInt(), 0xffd6336c.toInt(),
        )
        return palette[(roomId.hashCode().let { if (it < 0) -it else it }) % palette.size]
    }

    private fun matchWidth(): LinearLayout.LayoutParams = LinearLayout.LayoutParams(
        LinearLayout.LayoutParams.MATCH_PARENT,
        LinearLayout.LayoutParams.WRAP_CONTENT,
    )

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    private data class UiContext(
        val purpose: String,
        val roomId: String,
        val roomDisplay: String,
        val role: String,
        val scope: String,
        val linkAccess: String,
    )

    private data class DemoFileItem(
        val name: String,
        val subtitle: String,
        val kind: String,
        val color: Int,
    )

    private enum class Tab { FILE, MESSENGER, ACTION, SCENARIO, SETTINGS }

    private companion object {
        const val QUICK_DEMO_PREFERENCES = "safe_share_quick_demo"
        const val KEY_QUICK_DEMO_STAGE = "stage"
        const val KEY_SCENARIO_INDEX = "scenario_index"
        const val KEY_AUTO_DEMO = "automatic_demo"
        const val KEY_AGENT_HISTORY = "agent_history"
        const val KEY_MESSENGER_HISTORY = "messenger_history"
        const val AUTO_STEP_DELAY_MS = 1_100L

        /** Spinner shown while one production-core call is pending. */
        val BUSY_FRAMES = listOf("◐", "◓", "◑", "◒")
        const val BUSY_FRAME_MS = 130L
        const val AGENT_STEP_THINK_MS = 700L
        const val AGENT_STEP_SETTLE_MS = 400L
        const val MAX_AGENT_HISTORY_CHARS = 4_000
        val FILE_LIST_SCENARIOS = listOf(0, 2, 12, 8, 10)
    }
}
