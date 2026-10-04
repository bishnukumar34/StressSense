@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package com.bishnu.stresswelfarebridge

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.PermissionController
import androidx.health.connect.client.permission.HealthPermission
import androidx.health.connect.client.records.*
import com.bishnu.stresswelfarebridge.auth.AccountStore
import com.bishnu.stresswelfarebridge.auth.AuthField
import com.bishnu.stresswelfarebridge.auth.AuthResult
import com.bishnu.stresswelfarebridge.auth.Session
import com.bishnu.stresswelfarebridge.auth.SharedPreferencesStore
import com.bishnu.stresswelfarebridge.camera.CameraHrvCard
import com.bishnu.stresswelfarebridge.camera.CameraHrvRepository
import com.bishnu.stresswelfarebridge.demo.DemoDashboardData
import com.bishnu.stresswelfarebridge.camera.CameraHrvScreen
import com.bishnu.stresswelfarebridge.commander.PersonnelDirectory
import com.bishnu.stresswelfarebridge.commander.PersonnelRecordFactory
import com.bishnu.stresswelfarebridge.ppg.CameraHrvResult
import com.bishnu.stresswelfarebridge.welltory.AndroidWelltoryLauncher
import com.bishnu.stresswelfarebridge.welltory.WelltoryController
import com.bishnu.stresswelfarebridge.welltory.WelltoryResults
import com.bishnu.stresswelfarebridge.ui.auth.AuthScreen
import com.bishnu.stresswelfarebridge.ui.auth.authFieldErrors
import com.bishnu.stresswelfarebridge.ui.commander.CommanderAppShell
import com.bishnu.stresswelfarebridge.ui.commander.CommanderController
import com.bishnu.stresswelfarebridge.trends.StressTrends
import com.bishnu.stresswelfarebridge.ui.DailyContextTrendChart
import com.bishnu.stresswelfarebridge.ui.PerceivedStressTrendChart
import com.bishnu.stresswelfarebridge.ui.PhysiologicalStressTrendChart
import com.bishnu.stresswelfarebridge.questionnaire.DailyContext
import com.bishnu.stresswelfarebridge.questionnaire.DailySensorDay
import com.bishnu.stresswelfarebridge.questionnaire.QuestionnaireInstrument
import com.bishnu.stresswelfarebridge.questionnaire.ui.Pss10Card
import com.bishnu.stresswelfarebridge.questionnaire.ui.Pss10FormScreen
import com.bishnu.stresswelfarebridge.questionnaire.ui.Pss10ResultScreen
import com.bishnu.stresswelfarebridge.questionnaire.ui.Pss10HistoryScreen
import com.bishnu.stresswelfarebridge.medical.MedicalHistoryForm
import com.bishnu.stresswelfarebridge.medical.MedicalHistoryRepository
import com.bishnu.stresswelfarebridge.ml.CameraStressBridge
import com.bishnu.stresswelfarebridge.ml.ObservationHistory
import com.bishnu.stresswelfarebridge.ml.PersonalBaseline
import com.bishnu.stresswelfarebridge.ml.StressMeasurementRecord
import com.bishnu.stresswelfarebridge.ml.StressMeasurementStore
import com.bishnu.stresswelfarebridge.ml.StressBaselineStore
import com.bishnu.stresswelfarebridge.ml.StressModelLoader
import com.bishnu.stresswelfarebridge.medical.MedicalHistorySummaryCard
import com.bishnu.stresswelfarebridge.questionnaire.QuestionnaireRepository
import com.bishnu.stresswelfarebridge.questionnaire.ui.DailyCheckInScreen
import com.bishnu.stresswelfarebridge.questionnaire.ui.InstrumentUnavailableCard
import com.bishnu.stresswelfarebridge.questionnaire.ui.DailyCheckInCard
import com.bishnu.stresswelfarebridge.questionnaire.ui.PerceivedTrendCard
import com.bishnu.stresswelfarebridge.questionnaire.ui.QuestionnaireController
import com.bishnu.stresswelfarebridge.questionnaire.ui.QuestionnaireUiState
import com.bishnu.stresswelfarebridge.questionnaire.ui.StressInsightScreen
import com.bishnu.stresswelfarebridge.questionnaire.ui.TriggeredCheckInCard
import com.bishnu.stresswelfarebridge.ui.theme.StressWelfareBridgeTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlin.math.max
import kotlin.math.roundToInt
import kotlin.math.roundToLong

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Health Connect is a system module on Android 14+, but on older versions it is a separate
        // app that may be missing or need an update. getOrCreate() throws in those cases, so we
        // check the SDK status first and show a helpful screen instead of crashing.
        val sdkStatus = HealthConnectClient.getSdkStatus(this)
        val client = if (sdkStatus == HealthConnectClient.SDK_AVAILABLE) HealthConnectClient.getOrCreate(this) else null
        setContent {
            StressWelfareBridgeTheme {
                if (client != null) {
                    StressSenseApp(client)
                } else {
                    HealthConnectUnavailableScreen(needsUpdate = sdkStatus == HealthConnectClient.SDK_UNAVAILABLE_PROVIDER_UPDATE_REQUIRED)
                }
            }
        }
    }
}

@Composable
private fun HealthConnectUnavailableScreen(needsUpdate: Boolean) {
    val context = LocalContext.current
    val packageId = "com.google.android.apps.healthdata"
    Surface(Modifier.fillMaxSize()) {
        Column(
            Modifier.fillMaxSize().padding(28.dp),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Box(
                Modifier.size(82.dp).background(MaterialTheme.colorScheme.primaryContainer, CircleShape),
                contentAlignment = Alignment.Center
            ) {
                Text("S", style = MaterialTheme.typography.displaySmall, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
            }
            Spacer(Modifier.height(18.dp))
            Text("StressSense", style = MaterialTheme.typography.headlineLarge, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(12.dp))
            Text(
                if (needsUpdate)
                    "Health Connect needs an update before StressSense can read your heart rate and HRV data."
                else
                    "Health Connect isn't available on this device. Install it to let StressSense read your heart rate and HRV data.",
                textAlign = TextAlign.Center,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.height(24.dp))
            Button(onClick = {
                val market = Intent(Intent.ACTION_VIEW, Uri.parse("market://details?id=$packageId"))
                try {
                    context.startActivity(market)
                } catch (_: Exception) {
                    context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://play.google.com/store/apps/details?id=$packageId")))
                }
            }) {
                Text(if (needsUpdate) "UPDATE HEALTH CONNECT" else "INSTALL HEALTH CONNECT")
            }
        }
    }
}

/**
 * Root of the role-based app.
 *
 * The session is the single source of truth for who is signed in, and the role is read from the
 * stored account - never from a toggle on the login form - so the destination screen cannot be
 * chosen by the user. Logging in as a personnel account can never reach the commander shell, and
 * every read inside that shell is authorised again by [PersonnelDirectory].
 */
@Composable
private fun StressSenseApp(client: HealthConnectClient) {
    val context = LocalContext.current
    val prefs = remember { context.getSharedPreferences("stresssense", Context.MODE_PRIVATE) }
    val accountStore = remember { AccountStore(SharedPreferencesStore(prefs), SharedPreferencesStore(prefs)) }
    val directory = remember { PersonnelDirectory(SharedPreferencesStore(prefs), accountStore) }
    val commanderController = remember { CommanderController(directory) }
    // Questionnaire layer: created ONCE here, exactly like the other repositories. Instantiating
    // these inside a Composable body would rebuild them on every recomposition and lose state.
    val questionnaireRepository = remember {
        QuestionnaireRepository(SharedPreferencesStore(prefs), accountStore)
    }
    val questionnaireController = remember { QuestionnaireController(questionnaireRepository) }
    val medicalHistoryRepository = remember { MedicalHistoryRepository(SharedPreferencesStore(prefs)) }
    var session by remember { mutableStateOf<Session?>(accountStore.currentSession()) }
    // Deployed physiological ML model + per-account state it needs to personalise.
    val stressModel = remember { runCatching { StressModelLoader.load(context) }.getOrNull() }
    val stressBaselineStore = remember { StressBaselineStore(SharedPreferencesStore(prefs)) }
    // The personal baseline and observation history are now loaded from storage rather than starting
    // empty. Previously these were fresh objects on every launch, so the five qualifying readings
    // needed before MIN_SAMPLES is satisfied could never accumulate across a restart: a person who had
    // measured four times was told "1 more reading needed" again after every relaunch. Nothing about the
    // model changed - only the fact that it now remembers.
    val stressBaseline = remember(session?.accountId) { PersonalBaseline() }
    val stressHistory = remember(session?.accountId) { ObservationHistory() }
    val stressMeasurementStore = remember { StressMeasurementStore(SharedPreferencesStore(prefs)) }
    // Synthetic demonstration accounts and data.
    //
    // Runs once per launch and is idempotent, so it never duplicates records and never regenerates
    // data. Demo identities are ordinary accounts created through AccountStore.register and are
    // authorised by the existing role checks - there is no separate demo login path.
    //
    // SYNTHETIC DEMO DATA. Nothing here touches Health Connect and no score is written by hand: each
    // person's physiological observations are run through the existing deployed model and whatever it
    // returns is what gets persisted.
    LaunchedEffect(Unit) {
        val seeder = com.bishnu.stresswelfarebridge.demo.DemoDataSeeder(
            accounts = accountStore,
            directory = directory,
            measurements = stressMeasurementStore,
            baselines = stressBaselineStore,
            model = stressModel,
            clock = { java.time.Instant.now() },
            zone = java.time.ZoneId.systemDefault()
        )
        seeder.seed(questionnaireRepository)
    }
    var authBusy by remember { mutableStateOf(false) }
    var authError by remember { mutableStateOf<String?>(null) }
    var authIssues by remember { mutableStateOf<Map<AuthField, String>>(emptyMap()) }

    fun handleAuthResult(result: AuthResult) {
        authBusy = false
        when (result) {
            is AuthResult.Success -> {
                authError = null
                authIssues = emptyMap()
                session = result.session
                // A new sign-in must never inherit the previous user's in-memory camera result.
                CameraHrvRepository.clear()
            }

            is AuthResult.Failure -> {
                authError = result.message
                authIssues = authFieldErrors(result.issues)
            }
        }
    }

    val current = session
    if (current == null) {
        AuthScreen(
            busy = authBusy,
            formError = authError,
            fieldErrors = authIssues,
            onLogin = { username, password ->
                authBusy = true
                authError = null
                handleAuthResult(accountStore.login(username, password))
            },
            onRegister = { request ->
                authBusy = true
                authError = null
                handleAuthResult(accountStore.register(request))
            }
        )
        return
    }

    if (current.isCommander) {
        CommanderAppShell(
            session = current,
            controller = commanderController,
            onLogout = {
                accountStore.logout()
                CameraHrvRepository.clear()
                session = null
            }
        )
    } else {
        PersonnelApp(
            client = client,
            username = current.account.displayIdentifier,
            onLogout = {
                accountStore.logout()
                CameraHrvRepository.clear()
                session = null
            },
            session = current,
            directory = directory,
            questionnaireController = questionnaireController,
            medicalHistoryRepository = medicalHistoryRepository,
            stressModel = stressModel,
            stressBaseline = stressBaseline,
            stressHistory = stressHistory,
            stressMeasurementStore = stressMeasurementStore,
            stressBaselineStore = stressBaselineStore
        )
    }
}

/** Bottom-navigation destinations of the personnel app. */
private enum class Tab(val label: String) { HOME("Home"), STRESS("Stress"), TRENDS("Trends"), PROFILE("Profile") }

/**
 * The newest persisted Welltory-derived measurement, or null when there is none.
 *
 * Read from the existing [com.bishnu.stresswelfarebridge.ml.StressMeasurementStore] history rather
 * than from a separate cache, so the Home card, Trends and the Commander all show the same record and
 * it survives an app restart.
 */
private fun List<com.bishnu.stresswelfarebridge.ml.StressMeasurementRecord>?.latestWelltory() =
    this?.firstOrNull {
        it.source == com.bishnu.stresswelfarebridge.welltory.WelltoryConfig.SOURCE_WELLTORY_CAMERA
    }

@Composable
private fun PersonnelApp(
    client: HealthConnectClient,
    username: String,
    onLogout: () -> Unit,
    session: Session? = null,
    directory: PersonnelDirectory? = null,
    questionnaireController: QuestionnaireController? = null,
    medicalHistoryRepository: com.bishnu.stresswelfarebridge.medical.MedicalHistoryRepository? = null,
    stressModel: com.bishnu.stresswelfarebridge.ml.StressModel? = null,
    stressBaseline: com.bishnu.stresswelfarebridge.ml.PersonalBaseline? = null,
    stressHistory: com.bishnu.stresswelfarebridge.ml.ObservationHistory? = null,
    stressMeasurementStore: com.bishnu.stresswelfarebridge.ml.StressMeasurementStore? = null,
    stressBaselineStore: com.bishnu.stresswelfarebridge.ml.StressBaselineStore? = null
) {
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    var days by remember { mutableStateOf(emptyList<DailyHealth>()) }
    var live by remember { mutableStateOf(false) }
    var loading by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf("Connect Health Connect to load live measurements.") }
    var tab by rememberSaveable { mutableStateOf(Tab.HOME) }
    var showProfile by remember { mutableStateOf(false) }
    var showHelp by remember { mutableStateOf(false) }
    var showCameraHrv by remember { mutableStateOf(false) }
    var showDailyCheckIn by rememberSaveable { mutableStateOf(false) }
    var showMedicalHistory by rememberSaveable { mutableStateOf(false) }
    var showPss10 by rememberSaveable { mutableStateOf(false) }
    var showPss10History by rememberSaveable { mutableStateOf(false) }
    // PSS-10 form/result state. rememberSaveable so answers survive rotation, matching the
    // Daily Check-in screen.
    var pss10Answers by rememberSaveable { mutableStateOf<Map<Int, Int>>(emptyMap()) }
    var pss10SavedRecord by remember { mutableStateOf<com.bishnu.stresswelfarebridge.questionnaire.Pss10Record?>(null) }
    var showInsight by rememberSaveable { mutableStateOf(false) }
    var showTriggeredCheckIn by rememberSaveable { mutableStateOf(false) }
    var triggerDismissed by rememberSaveable { mutableStateOf(false) }
    var stepPeriodName by rememberSaveable { mutableStateOf(StepPeriod.WEEK.name) }
    var stepAnchorText by rememberSaveable { mutableStateOf(LocalDate.now().toString()) }
    var stepReload by remember { mutableStateOf(0) }
    var stepSeries by remember { mutableStateOf<StepSeries?>(null) }
    var stepLoading by remember { mutableStateOf(false) }
    var stepError by remember { mutableStateOf<String?>(null) }

    val permissions = healthPermissions()
    val essential = essentialPermissions()
    val latestCamera by CameraHrvRepository.latest.collectAsState()
    // Physiological stress score produced by the DEPLOYED ML model from the camera result.
    var cameraStress by remember { mutableStateOf<com.bishnu.stresswelfarebridge.ml.CameraStressBridge.CameraStressOutcome?>(null) }
    var persistedCameraRecord by remember { mutableStateOf<List<com.bishnu.stresswelfarebridge.ml.StressMeasurementRecord>>(emptyList()) }
    // Guards against re-running the model over the same capture. Evaluating twice would commit the
    // identical measurement into the personal baseline again and bias it, and would append a
    // duplicate history row. Keyed on the capture's own end timestamp.
// == Welltory ==================================================================================
    // A second, independent camera-HRV path. It launches the official Welltory Intent integration,
    // receives the result in a dedicated callback Activity, and feeds the real RMSSD/SDNN through the
    // SAME deployed model as the native camera. No second model, and Welltory's proprietary stress
    // score is never used as ours.
    val welltoryState by WelltoryResults.state.collectAsState()
    // The real StressSense score produced from a Welltory measurement, kept so the Commander's
    // published record and the dashboard are built from the same number. Null until one exists.
    var welltoryStressPublish by remember { mutableStateOf<Int?>(null) }
    val welltoryController = remember(session?.accountId) {
        WelltoryController(
            estimatorFactory = {
                val m = stressModel
                val baseline = stressBaseline
                val history = stressHistory
                if (m == null || baseline == null || history == null) {
                    // Without the deployed asset there is no estimator, so no score is possible.
                    throw IllegalStateException("Physiological model is not available")
                }
                CameraStressBridge.estimator(m, baseline, history) {
                    // Persist the baseline immediately after any reading that genuinely qualified,
                    // exactly as the native camera path does.
                    stressBaselineStore?.save(session, baseline, history)
                }
            },
            store = stressMeasurementStore
                // PersonnelApp receives the store from StressSenseApp. The fallback only applies when
                // the personnel shell is composed without it, and uses the same preferences so
                // behaviour is identical either way.
                ?: StressMeasurementStore(
                    SharedPreferencesStore(
                        context.getSharedPreferences("stresssense", Context.MODE_PRIVATE)
                    )
                ),
            modelVersion = stressModel?.modelVersion ?: "unavailable",
            launcher = { pending -> AndroidWelltoryLauncher.launch(context, pending) },
            installLauncher = { AndroidWelltoryLauncher.openInstallPage(context) },
            healthProvider = { days.lastOrNull() },
            onScored = { scored -> welltoryStressPublish = scored.score }
        )
    }

    // A signed-out or switched account must never leave a stale Welltory request or result behind.
    LaunchedEffect(session?.accountId) {
        if (session == null) welltoryController.reset()
    }

    // When the callback Activity publishes a validated result, run the model on it exactly once.
    LaunchedEffect(welltoryState) {
        val received = welltoryState as? WelltoryResults.State.Received ?: return@LaunchedEffect
        welltoryController.onReceived(received.measurement, session)
    }

    // After a Welltory measurement the Stress screen, Trends graph and Commander record must all
    // reflect it, so the persisted history is re-read once the attempt reaches a terminal state.
    LaunchedEffect(welltoryState) {
        if (welltoryState is WelltoryResults.State.Scored ||
            welltoryState is WelltoryResults.State.SavedWithoutScore
        ) {
            persistedCameraRecord = stressMeasurementStore?.history(session).orEmpty()
        }
    }
    var lastCameraEvaluatedMs by remember { mutableStateOf<Long?>(null) }

    // Restore this account's persisted personal baseline and observation history BEFORE any new
    // measurement is processed, so the fifth reading can actually satisfy MIN_SAMPLES across restarts.
    // Runs whenever the signed-in account changes, which also gives correct isolation: a second
    // account gets its own empty reference rather than inheriting the previous one's.
    LaunchedEffect(session?.accountId) {
        val store = stressBaselineStore
        val base = stressBaseline
        val hist = stressHistory
        if (store != null && base != null && hist != null && session != null) {
            store.load(session, base, hist)
        }
    }

    // Load this account's persisted physiological history on sign-in, independent of any camera
    // measurement. Demo accounts have no camera capture to trigger the existing refresh, so without
    // this the store would only be read as a side effect of the camera path.
    LaunchedEffect(session?.accountId, stressMeasurementStore) {
        persistedCameraRecord = stressMeasurementStore?.history(session).orEmpty()
    }

    val model = remember { StressEngine.loadModel(context) }
    var granted by remember { mutableStateOf(emptySet<String>()) }

    fun refresh() = refreshHealth(client, granted, scope, { loading = it }, { days = it; live = it.any { d -> d.steps > 0 || d.avgHr != null || d.avgHrv != null }; status = if (it.any { d -> d.steps > 0 || d.avgHr != null || d.avgHrv != null }) "Live Health Connect data updated." else "No heart rate, HRV or activity records were found in Health Connect." })

    val launcher = rememberLauncherForActivityResult(PermissionController.createRequestPermissionResultContract()) { result ->
        granted = result
        if (result.isEmpty()) {
            status = "Health Connect permissions were not granted. Tap Permissions to try again."
        } else {
            if (!result.containsAll(essential)) status = "Heart rate & HRV access is required for stress analysis - loading whatever data was granted."
            refresh()
        }
    }

    LaunchedEffect(Unit) {
        val current = client.permissionController.getGrantedPermissions()
        granted = current
        if (current.isEmpty()) status = "Connect Health Connect to load live measurements." else refresh()
    }

    val stepsGranted = HealthPermission.getReadPermission(StepsRecord::class) in granted

    // Re-queries Health Connect whenever the period, the selected date, the permission state or an
    // explicit retry changes. Keying the effect on those values guarantees we never show a graph
    // that belongs to a previously selected range.
    LaunchedEffect(stepPeriodName, stepAnchorText, stepsGranted, stepReload) {
        val period = runCatching { StepPeriod.valueOf(stepPeriodName) }.getOrDefault(StepPeriod.WEEK)
        val anchor = runCatching { LocalDate.parse(stepAnchorText) }.getOrDefault(LocalDate.now())
        if (!stepsGranted) {
            stepSeries = null
            stepError = null
            stepLoading = false
            return@LaunchedEffect
        }
        stepLoading = true
        stepError = null
        try {
            stepSeries = withContext(Dispatchers.IO) {
                HealthRepository(client, granted).readStepSeries(period, anchor, DAILY_STEP_GOAL)
            }
        } catch (e: Exception) {
            stepSeries = null
            stepError = e.message?.takeIf { it.isNotBlank() } ?: "Could not read step data from Health Connect."
        } finally {
            stepLoading = false
        }
    }

    val stepState = StepsUiState(
        period = runCatching { StepPeriod.valueOf(stepPeriodName) }.getOrDefault(StepPeriod.WEEK),
        anchor = runCatching { LocalDate.parse(stepAnchorText) }.getOrDefault(LocalDate.now()),
        series = stepSeries,
        loading = stepLoading,
        error = stepError,
        granted = stepsGranted
    )

    // -- Health Connect values -----------------------------------------------------------------------
    // healthToday/healthResult are always what Health Connect actually returned. They are kept
    // untouched so a real user's behaviour is completely unchanged by the demo fallback below.
    val healthToday = days.lastOrNull() ?: DailyHealth(LocalDate.now())
    val baseline = remember(days) { StressEngine.baseline(days.ifEmpty { listOf(healthToday) }) }
    val healthResult = remember(healthToday, baseline, model) { StressEngine.infer(healthToday, baseline, model) }

    // -- Demo fallback --------------------------------------------------------------------------------
    // A demo person has no wearable and Health Connect holds nothing for them, yet the seeder has
    // already persisted their physiological readings into the same StressMeasurementStore every other
    // measurement uses. Without this the dashboard read Health Connect only, so it showed "-" and
    // "AWAITING DATA" while the data sat unused in the store.
    //
    // So: prefer real Health Connect, and otherwise fall back to this account's OWN persisted demo
    // record. Scoped to demo usernames, so a normal user never reaches this path. The effective today
    // and result then feed Home, Stress, Trends and the Commander record alike, so those views cannot
    // disagree with one another about the same measurement.
    val demoRecord: com.bishnu.stresswelfarebridge.ml.StressMeasurementRecord? =
        remember(session?.accountId, persistedCameraRecord) {
            if (DemoDashboardData.isDemoPersonnel(session?.account?.username)) {
                DemoDashboardData.latestScored(persistedCameraRecord)
            } else {
                null
            }
        }
    val useDemoFallback = demoRecord != null && !healthResult.usingRealData
    val today = if (useDemoFallback) DemoDashboardData.dailyHealthFrom(demoRecord!!) else healthToday
    val result = if (useDemoFallback) DemoDashboardData.resultFrom(demoRecord!!) else healthResult
    val height = profileHeight(context) ?: today.heightCm
    val weight = profileWeight(context) ?: today.weightKg
    // Runs the DEPLOYED ML model whenever a camera measurement completes.
    LaunchedEffect(latestCamera, stressModel, today) {
        val result = latestCamera
        val model = stressModel
        val baseline = stressBaseline
        val history = stressHistory
        val store = stressMeasurementStore
        if (result == null) {
            cameraStress = null
            persistedCameraRecord = store?.history(session).orEmpty()
            return@LaunchedEffect
        }
        if (model == null || baseline == null || history == null || store == null) {
            // Without the deployed asset there is no score. Never substitute a default.
            cameraStress = null
            return@LaunchedEffect
        }
        if (lastCameraEvaluatedMs == result.measurementEndMs) return@LaunchedEffect
        lastCameraEvaluatedMs = result.measurementEndMs
        // Persist immediately after any qualifying measurement. The callback fires from inside the
        // estimator's existing commit points, so it runs only for readings that genuinely passed the
        // quality gates - rejected captures never reach it, and MIN_SAMPLES is untouched.
        val estimator = CameraStressBridge.estimator(model, baseline, history) {
            stressBaselineStore?.save(session, baseline, history)
        }
        val outcome = CameraStressBridge.evaluate(estimator, result, today, java.time.Instant.now())
        cameraStress = outcome
        if (outcome.score != null) {
            store.save(
                session,
                StressMeasurementRecord(
                    id = "cam-" + result.measurementEndMs,
                    userId = session?.accountId.orEmpty(),
                    measuredAtMs = result.measurementEndMs,
                    source = result.source,
                    heartRateBpm = result.heartRateBpm,
                    rmssdMs = result.rmssdMs,
                    sdnnMs = result.sdnnMs,
                    signalQuality = result.signalQuality.name,
                    stressScore = outcome.score,
                    stressZone = outcome.zone.name,
                    confidence = outcome.confidence,
                    modelVersion = model.modelVersion,
                    featureSchemaVersion = com.bishnu.stresswelfarebridge.ml.FeatureSchema.FEATURE_SCHEMA_VERSION
                )
            )
        }
        persistedCameraRecord = store.history(session)
    }
    val bmi = if (height != null && weight != null && height > 0) weight / ((height / 100.0) * (height / 100.0)) else null

    // Per-day physiological values, fed ONLY to the questionnaire trigger decision. These reuse the
    // scores the existing engine already produced for each day - no second stress calculation.
    val sensorDays = remember(days, baseline, model) {
        days.map { d ->
            val r = StressEngine.infer(d, baseline, model)
            DailySensorDay(
                date = d.date,
                stressSensor = r.score ?: 0,
                quality = if (r.usingRealData) 0.8 else 0.1
            )
        }
    }

    // Rebuild the questionnaire state whenever the underlying inputs change. The physiological score
    // is passed straight through from `result` - the questionnaire layer never recomputes it.
    var qState by remember { mutableStateOf(QuestionnaireUiState()) }
    var medicalHistory by remember { mutableStateOf<com.bishnu.stresswelfarebridge.medical.MedicalHistory?>(null) }
    LaunchedEffect(session?.accountId, result, sensorDays) {
        val controller = questionnaireController
        if (controller != null) {
            qState = controller.build(session, result, sensorDays)
        }
    }

    // Publish this personnel member's own record whenever the live inputs change. The record is
    // built from the same DailyHealth / StressEngine values the dashboard above displays, so what a
    // commander sees is exactly what the owner sees - never a separate or invented figure.
    // Camera HRV is carried in its own field and stays separate from the Health Connect values.
    //
    // [welltoryStressPublish] additionally carries the StressSense score produced from a Welltory
    // measurement. It participates here rather than in a separate write, so the Commander's record is
    // still produced by exactly one code path and cannot drift from what the owner sees.
    LaunchedEffect(session?.accountId, today, result, latestCamera, welltoryStressPublish) {
        val activeSession = session
        val target = directory
        if (activeSession != null && target != null && !activeSession.isCommander) {
            val wt = welltoryStressPublish
            target.publishOwnRecord(
                activeSession,
                PersonnelRecordFactory.from(
                    session = activeSession,
                    today = today,
                    result = result,
                    cameraHrv = latestCamera,
                    nowMs = System.currentTimeMillis()
                ).let { base ->
                    // When a Welltory measurement produced a real score, the published zone is derived
                    // from THAT score through the existing StressZoneMapping cut-points. No personnel
                    // name is ever mapped to a group by hand.
                    if (wt == null) base else base.copy(
                        stressScore = wt,
                        stressLevel = com.bishnu.stresswelfarebridge.commander.StressZoneMapping
                            .fromScore(wt).title,
                        zone = com.bishnu.stresswelfarebridge.commander.StressZoneMapping
                            .fromScore(wt),
                        usingRealData = true,
                        dataQuality = "Welltory camera HRV scored by " +
                            com.bishnu.stresswelfarebridge.welltory.WelltoryConfig.SOURCE_WELLTORY_CAMERA +
                            " via " + (stressModel?.modelVersion ?: "deployed model") + ".",
                        lastUpdatedMs = System.currentTimeMillis()
                    )
                }
            )
        }
    }

    Scaffold(
        topBar = { CenterAlignedTopAppBar(title = { Column(horizontalAlignment = Alignment.CenterHorizontally) { Text("StressSense", fontWeight = FontWeight.Bold); Text("PERSONNEL", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary) } }, colors = TopAppBarDefaults.topAppBarColors()) },
        bottomBar = { NavigationBar { Tab.entries.forEach { t -> NavigationBarItem(selected = tab == t, onClick = { tab = t }, icon = { Text(t.label.take(1)) }, label = { Text(t.label) }) } } }
    ) { padding ->
        when (tab) {
            Tab.HOME -> PersonnelHome(username, today, result, days, height, weight, bmi, live, loading, status, stepState, latestCamera, qState, cameraStress, { showTriggeredCheckIn = false; showDailyCheckIn = true }, { stepPeriodName = it.name }, { stepAnchorText = it.toString() }, { stepReload++ }, { launcher.launch(permissions) }, ::refresh, { showProfile = true }, { showHelp = true }, { showCameraHrv = true }, padding, welltoryState, persistedCameraRecord.latestWelltory(), { welltoryController.start(session) }, { welltoryController.openInstallPage() }, { WelltoryResults.clear() })
            Tab.TRENDS -> TrendsScreen(days, baseline, model, qState, persistedCameraRecord, padding)
            Tab.STRESS -> StressSection(today, result, baseline, qState, { showPss10 = true }, { showPss10History = true }, { showDailyCheckIn = true }, { showInsight = true }, padding)
            Tab.PROFILE -> ProfileScreen(username, height, weight, bmi, live, status, medicalHistory, { showMedicalHistory = true }, { showProfile = true }, { launcher.launch(permissions) }, onLogout, padding)
        }
    }
    if (showProfile) ProfileSheet(context, height, weight, onDismiss = { showProfile = false })
    if (showHelp) HelpSheet { showHelp = false }
    if (showCameraHrv) CameraHrvScreen(onClose = { showCameraHrv = false })

    // PSS-10: full prototype flow - answer all 10 items, submit, see the 0-40 result, save it.
    // Permission is still outstanding, which the screens state plainly, but the questionnaire is
    // usable so the flow can be researched and tested end to end.
    if (showPss10) {
        val definition = QuestionnaireInstrument.PSS_10
        val presentable = questionnaireController?.canStart(definition) == true
        if (!presentable) {
            Scaffold(topBar = { CenterAlignedTopAppBar(title = { Text("PSS-10") }) }) { sp ->
                Column(Modifier.fillMaxSize().padding(sp).padding(20.dp)) {
                    InstrumentUnavailableCard(
                        title = "PSS-10 Stress Questionnaire",
                        reason = questionnaireController?.unavailableReason(definition)
                            ?: "Pending verification."
                    )
                    Spacer(Modifier.height(16.dp))
                    OutlinedButton(
                        onClick = { showPss10 = false },
                        Modifier.fillMaxWidth()
                    ) { Text("Close") }
                }
            }
        } else if (pss10SavedRecord != null) {
            // Result screen for the record just written.
            Scaffold(topBar = { CenterAlignedTopAppBar(title = { Text("PSS-10") }, navigationIcon = {
                TextButton(onClick = { showPss10 = false; pss10SavedRecord = null }) { Text("Close") }
            }) }) { sp ->
                Column(Modifier.fillMaxSize().padding(sp)) {
                    Pss10ResultScreen(
                        record = pss10SavedRecord!!,
                        previous = qState.pss10Records.filter { it.id != pss10SavedRecord!!.id }
                    )
                }
            }
        } else {
            Scaffold(topBar = { CenterAlignedTopAppBar(title = { Text("PSS-10") }, navigationIcon = {
                TextButton(onClick = { showPss10 = false }) { Text("Cancel") }
            }) }) { sp ->
                Column(Modifier.fillMaxSize().padding(sp)) {
                    Pss10FormScreen(
                        answers = pss10Answers,
                        onAnswer = { item, value ->
                            pss10Answers = pss10Answers + (item to value)
                        },
                        onSubmit = {
                            val month = qState.currentMonth ?: java.time.YearMonth.now()
                            val saved = questionnaireController?.savePss10(session, month, pss10Answers)
                            if (saved != null) {
                                pss10SavedRecord = saved
                                pss10Answers = emptyMap()
                                qState = questionnaireController?.build(session, result, sensorDays) ?: qState
                            }
                        },
                        onCancel = { showPss10 = false }
                    )
                }
            }
        }
    }

    if (showPss10History) {
        Scaffold(topBar = { CenterAlignedTopAppBar(title = { Text("PSS-10 History") }) }) { sp ->
            Pss10HistoryScreen(qState.pss10Records)
        }
    }

    if (showInsight) {
        Scaffold(topBar = { CenterAlignedTopAppBar(title = { Text("Stress Insight") }) }) { sp ->
            StressInsightScreen(qState, { showInsight = false; showDailyCheckIn = true })
        }
    }

    if (showMedicalHistory) {
        Scaffold(topBar = { CenterAlignedTopAppBar(title = { Text("Medical History") }) }) { sp ->
            MedicalHistoryForm(
                existing = medicalHistoryRepository?.get(session),
                userId = session?.accountId.orEmpty(),
                onSave = { history ->
                    medicalHistoryRepository?.save(session, history)
                    medicalHistory = medicalHistoryRepository?.get(session)
                    showMedicalHistory = false
                },
                onClose = { showMedicalHistory = false }
            )
        }
    }
    if (showDailyCheckIn) {
        DailyCheckInScreen(
            existing = qState.todayPulse,
            onSave = { stress, rest, duty, requested ->
                // The repository is the single source of truth for "saved". It returns null when
                // nothing was written, so a null here must NOT be reported as a successful save,
                // and a non-null record must not depend on any stress score existing.
                val saved = questionnaireController?.saveDailyPulse(
                    session, stress, rest, duty, requested
                )
                // Re-read immediately so the card and insight screen update the moment Save is tapped.
                qState = questionnaireController?.build(session, result, sensorDays) ?: qState
                saved != null
            },
            onViewInsight = { showDailyCheckIn = false; tab = Tab.STRESS },
            onClose = { showDailyCheckIn = false }
        )
    }

    if (showTriggeredCheckIn) {
        val controller = questionnaireController
        val instrument = controller?.triggeredInstrument()
        val canStart = instrument != null && controller.canStart(instrument)
        Scaffold(topBar = { CenterAlignedTopAppBar(title = { Text("Stress Check-in") }) }) { p ->
            LazyColumn(
                Modifier.fillMaxSize().padding(p).padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                item {
                    if (canStart && instrument != null) {
                        TriggeredCheckInCard(
                            onTake = { showTriggeredCheckIn = false; showDailyCheckIn = true },
                            onDismiss = { showTriggeredCheckIn = false; triggerDismissed = true }
                        )
                    } else {
                        // The instrument exists but is not cleared for release, so it is explained
                        // rather than rendered. No placeholder questions are ever shown.
                        InstrumentUnavailableCard(
                            title = "Stress Check-in Available",
                            reason = instrument?.let { controller.unavailableReason(it) }
                                ?: "This questionnaire is not available yet."
                        )
                        OutlinedButton(
                            onClick = { showTriggeredCheckIn = false },
                            Modifier.fillMaxWidth()
                        ) { Text("Close") }
                    }
                }
            }
        }
    }
}

@Composable
private fun PersonnelHome(
    username: String, today: DailyHealth, result: StressEngine.Result, days: List<DailyHealth>, height: Double?, weight: Double?, bmi: Double?, live: Boolean,
    loading: Boolean, status: String, steps: StepsUiState, cameraResult: CameraHrvResult?,
    qState: QuestionnaireUiState, cameraStress: com.bishnu.stresswelfarebridge.ml.CameraStressBridge.CameraStressOutcome?,
    onStartCheckIn: () -> Unit,
    onStepPeriod: (StepPeriod) -> Unit, onStepAnchor: (LocalDate) -> Unit, onStepRetry: () -> Unit, onConnect: () -> Unit, onRefresh: () -> Unit,
    onProfile: () -> Unit, onHelp: () -> Unit, onCameraHrv: () -> Unit, padding: PaddingValues,
    // Welltory: a second, distinct camera-HRV path shown on the same card.
    welltoryState: WelltoryResults.State? = null,
    welltoryRecord: com.bishnu.stresswelfarebridge.ml.StressMeasurementRecord? = null,
    onMeasureWithWelltory: () -> Unit = {},
    onOpenWelltoryInstall: () -> Unit = {},
    onDismissWelltory: () -> Unit = {}
) {
    LazyColumn(Modifier.fillMaxSize().padding(padding), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
                Column(Modifier.weight(1f)) {
                    Text("Good day, $username", style = MaterialTheme.typography.bodyMedium)
                    Text("Personnel dashboard", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                    Text(LocalDate.now().format(DateTimeFormatter.ofPattern("EEEE, dd MMM yyyy")), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Button(onClick = onProfile) { Text("Profile") }
            }
        }
        if (loading) item { LinearProgressIndicator(Modifier.fillMaxWidth()) }
        if (!loading && !live) item {
            Text(
                "No heart rate or HRV data found in Health Connect yet. Wear your device and sync, then tap Refresh - or tap Permissions to grant access.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        // Card order is deliberate and asserted by tests: Heart Rate, then Stress Level, then the
        // Camera HRV card immediately below them. The camera PPG result stays real - it is never
        // replaced by a sample value, and with no measurement the card keeps its honest
        // "measure your pulse" state.
        item {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                MetricCard(painterResource(R.drawable.ic_heart), "HEART RATE", today.avgHr?.let { "%.0f BPM".format(it) } ?: EM_DASH, "RMSSD  ${today.avgHrv?.let { "%.0f ms".format(it) } ?: EM_DASH}", Modifier.weight(1f))
                StressCard(result, Modifier.weight(1f))
            }
        }
        item {
            CameraHrvCard(
                result = cameraResult,
                stress = cameraStress,
                onOpen = onCameraHrv,
                modifier = Modifier.fillMaxWidth(),
                welltoryState = welltoryState,
                welltoryRecord = welltoryRecord,
                welltoryBusy = welltoryState is WelltoryResults.State.InProgress ||
                    welltoryState is WelltoryResults.State.Launching,
                onMeasureWithWelltory = onMeasureWithWelltory,
                onOpenWelltoryInstall = onOpenWelltoryInstall,
                onDismissWelltory = onDismissWelltory
            )
        }
        item { DailyCheckInCard(qState, onStartCheckIn) }
        item { StepsCard(steps, onStepPeriod, onStepAnchor, onStepRetry, onConnect) }
        item {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                DataCard("CALORIES", painterResource(R.drawable.ic_calories), if (today.calories > 0) "%.0f kcal".format(today.calories) else "-", "Health Connect", Modifier.weight(1f))
                DataCard("DISTANCE", painterResource(R.drawable.ic_distance), if (today.distanceMeters > 0) "%.2f km".format(today.distanceMeters / 1000.0) else "-", "Health Connect", Modifier.weight(1f))
            }
        }
        item {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                DataCard("SLEEP", painterResource(R.drawable.ic_sleep), if (today.sleepMinutes > 0) formatMinutes(today.sleepMinutes) else "-", "Health Connect", Modifier.weight(1f))
                DataCard("ACTIVE", painterResource(R.drawable.ic_activity), if (today.exerciseMinutes > 0) "${today.exerciseMinutes} min" else "-", "Exercise sessions", Modifier.weight(1f))
            }
        }
        item {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                DataCard("RESPIRATION", painterResource(R.drawable.ic_respiration), today.respiratoryRate?.let { "%.1f /min".format(it) } ?: "-", "Health Connect", Modifier.weight(1f))
                DataCard("SpO2", painterResource(R.drawable.ic_spo2), today.spo2?.let { "%.0f%%".format(it) } ?: "Not available", "Health Connect", Modifier.weight(1f))
            }
        }
        // Triggered questionnaire offer. Private to this person and shown once the persistent
        // physiological trend warrants it - never because of a single elevated day.
        if (qState.checkInAvailable) {
            item {
                TriggeredCheckInCard(
                    onTake = onStartCheckIn,
                    onDismiss = { /* dismissal is recorded in the episode, not the UI */ }
                )
            }
        }
        item {
            Card(shape = RoundedCornerShape(20.dp), colors = CardDefaults.cardColors(containerColor = if (live) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant)) {
                Column(Modifier.padding(16.dp)) {
                    IconWithTitle(painterResource(if (live) R.drawable.ic_health_live else R.drawable.ic_health_offline), if (live) "Health Connect live" else "Health Connect not loaded")
                    Spacer(Modifier.height(4.dp)); Text(status, style = MaterialTheme.typography.bodySmall)
                    Spacer(Modifier.height(10.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(onClick = onConnect) {
                            Icon(
                                painter = painterResource(R.drawable.ic_permissions),
                                contentDescription = null,
                                modifier = Modifier.size(18.dp)
                            )
                            Spacer(Modifier.size(8.dp))
                            Text("Permissions")
                        }
                        OutlinedButton(onClick = onRefresh, enabled = !loading) {
                            Icon(
                                painter = painterResource(R.drawable.ic_refresh),
                                contentDescription = null,
                                modifier = Modifier.size(18.dp)
                            )
                            Spacer(Modifier.size(8.dp))
                            Text(if (loading) "Loading" else "Refresh")
                        }
                    }
                }
            }
        }
    }
}

/**
 * Single icon source of truth for the dashboard.
 *
 * Icons are vector drawables (`res/drawable/ic_*.xml`) or Material icons - never emoji. Emoji depend
 * on the device's emoji font, render inconsistently across manufacturers and can appear as
 * tofu boxes, whereas a tinted vector drawable is identical on every phone and follows the app theme
 * in both light and dark mode. [DashboardIconSize] keeps every icon the same visual size.
 */
private val DashboardIconSize = 18.dp

/** Em dash used wherever a value is genuinely absent. */
private const val EM_DASH = "-"

@Composable
private fun MetricCard(icon: Painter, title: String, value: String, subtitle: String, modifier: Modifier) {
    Card(modifier, shape = RoundedCornerShape(20.dp)) { Column(Modifier.padding(15.dp)) { IconWithTitle(icon, title); Spacer(Modifier.height(10.dp)); Text(value, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold); Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) } }
}

/** Icon + uppercase label, tinted from the theme so it stays legible in dark mode. */
@Composable
private fun IconWithTitle(icon: Painter, title: String, modifier: Modifier = Modifier) {
    Row(modifier, verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        Icon(
            painter = icon,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(DashboardIconSize)
        )
        Text(
            title,
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onSurface
        )
    }
}

@Composable
private fun StressCard(result: StressEngine.Result, modifier: Modifier) {
    val color = when (result.level) { "Low" -> MaterialTheme.colorScheme.primary; "Moderate" -> MaterialTheme.colorScheme.tertiary; "High" -> MaterialTheme.colorScheme.error; else -> MaterialTheme.colorScheme.outline }
    Card(modifier, shape = RoundedCornerShape(20.dp)) { Column(Modifier.padding(15.dp)) { Text("STRESS LEVEL", style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold); Spacer(Modifier.height(8.dp)); Text(result.score?.let { "$it/100" } ?: "-", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold, color = color); Text(result.level.uppercase(), fontWeight = FontWeight.Bold, color = color); Spacer(Modifier.height(7.dp)); Text(if (result.usingRealData) "On-device ML  |  real data" else "Waiting for HR + HRV", style = MaterialTheme.typography.labelSmall) } }
}

private data class StepsUiState(
    val period: StepPeriod,
    val anchor: LocalDate,
    val series: StepSeries?,
    val loading: Boolean,
    val error: String?,
    val granted: Boolean
)

@Composable
private fun StepsCard(
    state: StepsUiState,
    onPeriod: (StepPeriod) -> Unit,
    onAnchor: (LocalDate) -> Unit,
    onRetry: () -> Unit,
    onPermission: () -> Unit
) {
    val zone = remember { ZoneId.systemDefault() }
    val series = state.series
    val today = LocalDate.now()
    val headerValue = when {
        state.loading -> "..."
        series != null -> "%,d".format(series.total)
        else -> "-"
    }
    Card(shape = RoundedCornerShape(20.dp)) {
        Column(Modifier.fillMaxWidth().padding(16.dp)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.Top) {
                Column(Modifier.weight(1f)) {
                    Text("STEPS", style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold)
                    Text(headerValue, style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
                    Text(series?.title ?: periodWord(state.period), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Column(horizontalAlignment = Alignment.End) {
                    Text("DAILY GOAL", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text("%,d".format(DAILY_STEP_GOAL), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                }
            }
            Spacer(Modifier.height(10.dp))
            if (state.period == StepPeriod.DAY) {
                LinearProgressIndicator(
                    progress = { series?.goalProgress ?: 0f },
                    modifier = Modifier.fillMaxWidth().height(8.dp),
                    strokeCap = StrokeCap.Round
                )
                Spacer(Modifier.height(4.dp))
                Text("${((series?.goalProgress ?: 0f) * 100).roundToInt()}% of daily goal", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            } else {
                val avg = series?.averagePerBucket?.roundToLong() ?: 0L
                Text("Average ${"%,d".format(avg)} steps per ${series?.bucketNoun ?: "day"}", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }

            Spacer(Modifier.height(12.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                StepPeriod.entries.forEach { p ->
                    FilterChip(selected = state.period == p, onClick = { onPeriod(p) }, label = { Text(p.name, style = MaterialTheme.typography.labelSmall) }, modifier = Modifier.weight(1f))
                }
            }

            Spacer(Modifier.height(8.dp))
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
                OutlinedButton(onClick = { onAnchor(shiftAnchor(state.period, state.anchor, -1)) }, contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp)) { Icon(painterResource(R.drawable.ic_chevron_left), contentDescription = "Previous period", modifier = Modifier.size(18.dp), tint = MaterialTheme.colorScheme.onSurface) }
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(series?.title ?: periodWord(state.period), fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleSmall)
                    if (!isCurrentPeriod(state.period, state.anchor, today)) TextButton(onClick = { onAnchor(today) }) { Text("Today") }
                }
                OutlinedButton(onClick = { onAnchor(shiftAnchor(state.period, state.anchor, 1)) }, enabled = !isCurrentPeriod(state.period, state.anchor, today), contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp)) { Icon(painterResource(R.drawable.ic_chevron_right), contentDescription = "Next period", modifier = Modifier.size(18.dp), tint = MaterialTheme.colorScheme.onSurface) }
            }

            Spacer(Modifier.height(12.dp))
            when {
                !state.granted -> {
                    Text("Step permission is not granted. StressSense needs Health Connect step access to draw this graph.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Spacer(Modifier.height(8.dp))
                    Button(onClick = onPermission) { Text("GRANT STEP PERMISSION") }
                }
                state.loading -> Box(Modifier.fillMaxWidth().height(170.dp), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
                state.error != null -> {
                    Text("Could not load steps: ${state.error}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                    Spacer(Modifier.height(8.dp))
                    Button(onClick = onRetry) { Text("REFRESH") }
                }
                series == null -> Box(Modifier.fillMaxWidth().height(170.dp), contentAlignment = Alignment.Center) { Text("...") }
                !series.hasData -> Column(Modifier.fillMaxWidth().height(170.dp), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("No step data available for this period", style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center)
                    Spacer(Modifier.height(4.dp))
                    Text("Health Connect returned no step records for ${series.title}.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center)
                }
                else -> {
                    StepBarChart(series.points, series.highlightIndex, Modifier.fillMaxWidth())
                    Spacer(Modifier.height(12.dp))
                    StepsSummary(series)
                }
            }

            Spacer(Modifier.height(10.dp))
            Text(
                "Range ${formatStepRange(series?.rangeStart, series?.rangeEnd, zone)}  |  source: Health Connect (de-duplicated aggregation)",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

private fun periodWord(p: StepPeriod): String = when (p) {
    StepPeriod.DAY -> "Today"
    StepPeriod.WEEK -> "This week"
    StepPeriod.MONTH -> "This month"
    StepPeriod.YEAR -> "This year"
}

private fun formatStepRange(start: Instant?, end: Instant?, zone: ZoneId): String {
    if (start == null || end == null) return "-"
    val f = DateTimeFormatter.ofPattern("dd MMM HH:mm").withZone(zone)
    return "${f.format(start)} ' ${f.format(end)}"
}

/** Chooses a label stride so x-axis labels never overlap for 7/12/24/28-31 buckets. */
private fun tickStep(n: Int): Int = when {
    n <= 8 -> 1
    n <= 12 -> 2
    n <= 16 -> 3
    n <= 24 -> 4
    else -> 5
}

@Composable
private fun StepBarChart(points: List<StepPoint>, highlightIndex: Int, modifier: Modifier) {
    val maxCount = (points.maxOfOrNull { it.count } ?: 0L).coerceAtLeast(1L)
    val barColor = MaterialTheme.colorScheme.primary
    val highlightColor = MaterialTheme.colorScheme.tertiary
    val trackColor = MaterialTheme.colorScheme.surfaceVariant
    val labelColor = MaterialTheme.colorScheme.onSurfaceVariant
    Column(modifier) {
        Row(Modifier.fillMaxWidth().height(150.dp), verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(2.dp)) {
            points.forEachIndexed { index, point ->
                Box(Modifier.weight(1f).fillMaxHeight(), contentAlignment = Alignment.BottomCenter) {
                    Box(Modifier.fillMaxWidth().fillMaxHeight().background(trackColor, RoundedCornerShape(4.dp)))
                    if (point.count > 0) {
                        val fraction = (point.count.toFloat() / maxCount).coerceIn(0.02f, 1f)
                        Box(Modifier.fillMaxWidth().fillMaxHeight(fraction).background(if (index == highlightIndex) highlightColor else barColor, RoundedCornerShape(4.dp)))
                    }
                }
            }
        }
        Spacer(Modifier.height(4.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(2.dp)) {
            val stride = tickStep(points.size)
            points.forEachIndexed { index, point ->
                Box(Modifier.weight(1f), contentAlignment = Alignment.Center) {
                    if (index % stride == 0 || index == points.lastIndex) {
                        Text(point.label, style = MaterialTheme.typography.labelSmall, color = labelColor, maxLines = 1)
                    }
                }
            }
        }
    }
}

@Composable
private fun StepsSummary(series: StepSeries) {
    val rows = listOf(
        "Total" to "%,d steps".format(series.total),
        "Goal" to "%,d".format(series.goal),
        "Average" to "%,d / %s".format(series.averagePerBucket.roundToLong(), series.bucketNoun),
        "Best ${series.bucketNoun}" to (series.bestLabel?.let { "%s  |  %,d".format(it, series.bestCount) } ?: "-")
    )
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        rows.forEach { (label, value) ->
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(label, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(value, style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.Bold)
            }
        }
    }
}

@Composable
private fun DataCard(title: String, icon: Painter, value: String, subtitle: String, modifier: Modifier) {
    Card(modifier, shape = RoundedCornerShape(18.dp)) { Column(Modifier.padding(14.dp)) { IconWithTitle(icon, title); Spacer(Modifier.height(5.dp)); Text(value, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold); Text(subtitle, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant) } }
}

@Composable
private fun ActionCard(title: String, icon: Painter, subtitle: String, onClick: () -> Unit, modifier: Modifier) {
    Card(onClick = onClick, modifier = modifier, shape = RoundedCornerShape(18.dp)) { Column(Modifier.padding(15.dp)) { IconWithTitle(icon, title); Spacer(Modifier.height(4.dp)); Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) } }
}

@Composable
private fun TrendsScreen(days: List<DailyHealth>, baseline: StressEngine.Baseline, model: StressEngine.Model,
    qState: QuestionnaireUiState,
    cameraRecords: List<com.bishnu.stresswelfarebridge.ml.StressMeasurementRecord>,
    padding: PaddingValues) {
    LazyColumn(Modifier.fillMaxSize().padding(padding), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        item { Text("Trends", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold) }
        // -- the three stress graphs ----------------------------------------------------------------
        // Three SEPARATE cards, each on its own scale, each fed only by records that really exist:
        //   physiological 0-100 (real persisted ML scores, x = measured date + time)
        //   PSS-10 0-40 (completed months only, never interpolated, x = month)
        //   Daily Context Low/Medium/High (completed check-ins only, x = date)
        // They are never combined into one score and never rescaled onto a shared axis. Each builder
        // drops anything absent rather than inventing a point, so an empty graph says so.
        item {
            PhysiologicalStressTrendChart(StressTrends.physiological(cameraRecords))
        }
        item { PerceivedStressTrendChart(StressTrends.perceived(qState.pss10Records)) }
        item { DailyContextTrendChart(StressTrends.dailyContext(qState.history)) }
        item { Card(shape = RoundedCornerShape(20.dp)) { Column(Modifier.padding(16.dp)) { Text("Heart rate / HRV", fontWeight = FontWeight.Bold); val hr = days.mapNotNull { it.avgHr?.toFloat() }; if (hr.size > 1) LineChart(hr, Modifier.fillMaxWidth().height(110.dp)); val hrv = days.mapNotNull { it.avgHrv?.toFloat() }; if (hrv.size > 1) LineChart(hrv, Modifier.fillMaxWidth().height(110.dp)) } } }
        // Perceived stress is a SEPARATE card on purpose. Overlaying it on the physiological chart
        // would invite a comparison between two constructs that are not the same measurement.
        item { PerceivedTrendCard(qState.history) }
        // Persisted physiological stress measurements (camera PPG), newest first.
        item { CameraStressHistoryCard(cameraRecords) }
        items(days.reversed()) { d -> val r = StressEngine.infer(d, baseline, model); OutlinedCard(shape = RoundedCornerShape(16.dp)) { Column(Modifier.padding(14.dp)) { Text(d.date.toString(), fontWeight = FontWeight.Bold); Text("HR ${d.avgHr?.let { "%.0f".format(it) } ?: "-"}  |  RMSSD ${d.avgHrv?.let { "%.0f ms".format(it) } ?: "-"}  |  Steps ${d.steps}"); Text("Stress ${r.score?.toString() ?: "-"} ${r.level}") } } }
    }
}

/**
 * The Stress section: the single place for stress assessment.
 *
 * Three independent readings, deliberately never combined into one number:
 *  - physiological stress (0-100) from the existing sensor/ML pipeline;
 *  - PSS-10 perceived stress (0-40) from the monthly questionnaire;
 *  - today's optional daily check-in, which is context only and produces no score.
 */
@Composable
private fun CameraStressHistoryCard(records: List<com.bishnu.stresswelfarebridge.ml.StressMeasurementRecord>) {
    Card(shape = RoundedCornerShape(20.dp)) {
        Column(Modifier.fillMaxWidth().padding(16.dp)) {
            Text("Physiological stress history", fontWeight = FontWeight.Bold)
            if (records.isEmpty()) {
                Text(
                    "No stored stress measurements yet.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            } else {
                for (r in records.take(10)) {
                    Text(
                        "" + (r.stressScore?.let { "$it / 100" } ?: "no score") +
                            " - " + (r.stressZone ?: "-") + " - HR " +
                            (r.heartRateBpm?.let { "%.0f BPM".format(it) } ?: "-") +
                            " - source " + r.source,
                        style = MaterialTheme.typography.bodyMedium
                    )
                }
            }
        }
    }
}

@Composable
private fun StressSection(
    day: DailyHealth,
    result: StressEngine.Result,
    baseline: StressEngine.Baseline,
    qState: QuestionnaireUiState,
    onStartPss10: () -> Unit,
    onPss10History: () -> Unit,
    onDailyCheckIn: () -> Unit,
    onInsight: () -> Unit,
    padding: PaddingValues
) {
    LazyColumn(Modifier.fillMaxSize().padding(padding), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item { Text("Stress", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold) }

        // -- 1. Physiological -----------------------------------------------------------------
        // Reads the existing StressEngine/ML pipeline. No new formula, and the three daily
        // answers never feed it. When there is no real reading it says so rather than guessing.
        item {
            Card(shape = RoundedCornerShape(20.dp)) {
                Column(Modifier.fillMaxWidth().padding(16.dp)) {
                    Text(
                        "HOW YOUR BODY IS RESPONDING",
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.primary
                    )
                    Text("Physiological Stress", style = MaterialTheme.typography.titleMedium)
                    val physScore = result.score?.takeIf { result.usingRealData }
                    Text(
                        physScore?.let { "$it / 100" } ?: "Not available",
                        style = MaterialTheme.typography.headlineSmall,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        physScore?.let { result.level } ?: "Awaiting valid data",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold
                    )
                    Spacer(Modifier.height(6.dp))
                    Text(
                        "Based on your heart rate, HRV and other body signals.",
                        style = MaterialTheme.typography.bodySmall
                    )
                    if (physScore == null) {
                        Text(
                            "Not enough sensor data yet. This reading is not estimated.",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Text(
                        "This reflects physiological signals only. It does not detect what you are feeling or thinking.",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }

        // -- 2. Perceived (PSS-10) --------------------------------------------------------------
        // Monthly self-report on 0-40. Never merged with the physiological 0-100 above.
        item {
            Pss10Card(
                latest = qState.latestPss10,
                available = qState.pss10Available,
                prototypePendingLicence = true,
                historyCount = qState.pss10Records.count { it.completed },
                onStart = onStartPss10,
                onHistory = onPss10History
            )
        }

        // NOTE: the three historical trend graphs deliberately live on the TRENDS tab only.
        // This section shows the CURRENT reading for each of the three constructs; plotting history
        // here as well duplicated the same graphs on two tabs for no added information.
        // See TrendsScreen for the graphs, and StressTrends for how their points are sourced.

        // -- 3. Daily context ------------------------------------------------------------------
        // The three check-in answers, shown as a plain category. No number, because there is
        // no validated daily stress score to show.
        item {
            val pulse = qState.todayPulse
            val category = DailyContext.category(pulse)
            Card(shape = RoundedCornerShape(20.dp)) {
                Column(Modifier.fillMaxWidth().padding(16.dp)) {
                    Text(
                        "TODAY'S SITUATION",
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.primary
                    )
                    Text("Daily Context", style = MaterialTheme.typography.titleMedium)
                    Text(
                        category?.label ?: "Not completed today",
                        style = MaterialTheme.typography.headlineSmall,
                        fontWeight = FontWeight.Bold
                    )
                    Spacer(Modifier.height(6.dp))
                    Text(
                        "Based on today's stress, rest and duty context.",
                        style = MaterialTheme.typography.bodySmall
                    )
                    Text(
                        "A simple summary of your answers. It is not a stress score.",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    if (pulse != null) {
                        Spacer(Modifier.height(4.dp))
                        Text(
                            "\u2713 Completed today",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.primary,
                            fontWeight = FontWeight.Bold
                        )
                    }
                    Spacer(Modifier.height(10.dp))
                    Button(onClick = onDailyCheckIn, Modifier.fillMaxWidth()) {
                        Text(if (pulse == null) "Today's Check-in" else "Update Today's Check-in")
                    }
                }
            }
        }

        item {
            Card(shape = RoundedCornerShape(20.dp)) {
                Column(Modifier.fillMaxWidth().padding(16.dp)) {
                    Text("STRESS INSIGHT", style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
                    Text("Compare how you feel with how your body is responding", style = MaterialTheme.typography.bodyMedium)
                    Spacer(Modifier.height(10.dp))
                    OutlinedButton(onClick = onInsight, Modifier.fillMaxWidth()) {
                        Text("View Combined Insight")
                    }
                }
            }
        }

        item {
            Card(shape = RoundedCornerShape(16.dp), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
                Column(Modifier.padding(14.dp)) {
                    Text("Not a medical diagnosis", fontWeight = FontWeight.Bold, style = MaterialTheme.typography.labelLarge)
                    Spacer(Modifier.height(4.dp))
                    Text(
                        "These are three independent measures: physiological (0-100), perceived (0-40) and daily context. They are not combined into a single score and none is clinically validated.",
                        style = MaterialTheme.typography.bodySmall
                    )
                }
            }
        }
    }
}

@Composable
private fun ProfileScreen(username: String, height: Double?, weight: Double?, bmi: Double?, live: Boolean, status: String, medicalHistory: com.bishnu.stresswelfarebridge.medical.MedicalHistory?, onMedicalHistory: () -> Unit, onEdit: () -> Unit, onConnect: () -> Unit, onLogout: () -> Unit, padding: PaddingValues) {
    LazyColumn(Modifier.fillMaxSize().padding(padding), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item { Text("Profile", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold) }

        item {
            MedicalHistorySummaryCard(
                history = medicalHistory,
                onOpen = onMedicalHistory
            )
        }
        item { Button(onClick = onEdit, Modifier.fillMaxWidth()) { Text("Edit height / weight") } }
        item { OutlinedButton(onClick = onConnect, Modifier.fillMaxWidth()) { Text(if (live) "Health Connect connected" else "Connect Health Connect") } }
        item { Text(status, style = MaterialTheme.typography.bodySmall) }
        item { HorizontalDivider() }
        item { OutlinedButton(onClick = onLogout, Modifier.fillMaxWidth()) { Text("LOG OUT") } }
    }
}

@Composable
private fun ProfileSheet(context: Context, currentHeight: Double?, currentWeight: Double?, onDismiss: () -> Unit) {
    var height by rememberSaveable { mutableStateOf(currentHeight?.toString() ?: "") }
    var weight by rememberSaveable { mutableStateOf(currentWeight?.toString() ?: "") }
    var saved by rememberSaveable { mutableStateOf(false) }
    val bmi = calculateBmi(height.toDoubleOrNull(), weight.toDoubleOrNull())
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(Modifier.fillMaxWidth().padding(20.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("Personal details", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
            OutlinedTextField(height, { height = it }, Modifier.fillMaxWidth(), label = { Text("Height (cm)") })
            OutlinedTextField(weight, { weight = it }, Modifier.fillMaxWidth(), label = { Text("Weight (kg)") })
            if (bmi != null) DataCard("CALCULATED BMI", painterResource(R.drawable.ic_medical), "%.1f".format(bmi), bmiCategory(bmi), Modifier.fillMaxWidth())
            Button(onClick = { saveProfile(context, height.toDoubleOrNull(), weight.toDoubleOrNull()); saved = true }, modifier = Modifier.fillMaxWidth(), enabled = bmi != null) { Text("SUBMIT & UPDATE BMI") }
            if (saved) Text("Saved. BMI is now updated across the dashboard.", color = MaterialTheme.colorScheme.primary)
            Spacer(Modifier.height(12.dp))
        }
    }
}

@Composable
private fun HelpSheet(onDismiss: () -> Unit) { var q by remember { mutableStateOf("") }; ModalBottomSheet(onDismissRequest = onDismiss) { Column(Modifier.fillMaxWidth().padding(20.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) { Text("Help & Query", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold); OutlinedTextField(q, { q = it }, Modifier.fillMaxWidth(), minLines = 4, label = { Text("Your query") }); Button(onClick = onDismiss, modifier = Modifier.fillMaxWidth(), enabled = q.isNotBlank()) { Text("SUBMIT QUERY") }; Spacer(Modifier.height(16.dp)) } } }

/** Daily step goal used for the progress indicator. This is a target, never measured health data. */
private const val DAILY_STEP_GOAL = 10_000L

private fun healthPermissions(): Set<String> = setOf(
    HealthPermission.getReadPermission(HeartRateRecord::class),
    HealthPermission.getReadPermission(HeartRateVariabilityRmssdRecord::class),
    HealthPermission.getReadPermission(RestingHeartRateRecord::class),
    HealthPermission.getReadPermission(SleepSessionRecord::class),
    HealthPermission.getReadPermission(StepsRecord::class),
    HealthPermission.getReadPermission(RespiratoryRateRecord::class),
    HealthPermission.getReadPermission(OxygenSaturationRecord::class),
    HealthPermission.getReadPermission(DistanceRecord::class),
    HealthPermission.getReadPermission(TotalCaloriesBurnedRecord::class),
    HealthPermission.getReadPermission(ExerciseSessionRecord::class),
    HealthPermission.getReadPermission(FloorsClimbedRecord::class),
    HealthPermission.getReadPermission(HeightRecord::class),
    HealthPermission.getReadPermission(WeightRecord::class)
)

private fun essentialPermissions(): Set<String> = setOf(
    HealthPermission.getReadPermission(HeartRateRecord::class),
    HealthPermission.getReadPermission(HeartRateVariabilityRmssdRecord::class)
)

private fun refreshHealth(
    client: HealthConnectClient,
    granted: Set<String>,
    scope: kotlinx.coroutines.CoroutineScope,
    setLoading: (Boolean) -> Unit,
    onResult: (List<DailyHealth>) -> Unit
) {
    scope.launch {
        setLoading(true)
        val days = try {
            withContext(Dispatchers.IO) { HealthRepository(client, granted).readSevenDays() }
        } catch (_: Exception) {
            emptyList()
        }
        onResult(days)
        setLoading(false)
    }
}

private fun saveProfile(context: Context, height: Double?, weight: Double?) { context.getSharedPreferences("stresssense", Context.MODE_PRIVATE).edit().putString("height", height?.toString()).putString("weight", weight?.toString()).apply() }
private fun profileHeight(context: Context): Double? = context.getSharedPreferences("stresssense", Context.MODE_PRIVATE).getString("height", null)?.toDoubleOrNull()
private fun profileWeight(context: Context): Double? = context.getSharedPreferences("stresssense", Context.MODE_PRIVATE).getString("weight", null)?.toDoubleOrNull()
private fun calculateBmi(h: Double?, w: Double?): Double? = if (h != null && w != null && h > 0 && w > 0) w / ((h / 100.0) * (h / 100.0)) else null
private fun bmiCategory(bmi: Double) = when { bmi < 18.5 -> "Underweight"; bmi < 25 -> "Normal range"; bmi < 30 -> "Overweight"; else -> "Obesity range" }
private fun formatMinutes(m: Long): String = "${m / 60}h ${m % 60}m"

@Composable private fun BarChart(values: List<Float>, modifier: Modifier) { val maxValue = max(values.maxOrNull() ?: 1f, 1f); val barColor = MaterialTheme.colorScheme.primary; Canvas(modifier) { val n = values.size.coerceAtLeast(1); val gap = size.width / (n * 6f); val w = (size.width - gap * (n + 1)) / n; values.forEachIndexed { i, v -> val left = gap + i * (w + gap); val h = v / maxValue * (size.height - 8f); drawRoundRect(barColor, Offset(left, size.height - h), androidx.compose.ui.geometry.Size(w, h), androidx.compose.ui.geometry.CornerRadius(8f)) } } }
@Composable private fun LineChart(values: List<Float>, modifier: Modifier) { if (values.size < 2) return; val lineColor = MaterialTheme.colorScheme.primary; val minV = values.minOrNull() ?: 0f; val maxV = max(values.maxOrNull() ?: 1f, minV + 1f); Canvas(modifier) { val path = Path(); values.forEachIndexed { i, v -> val x = i.toFloat() / (values.lastIndex) * size.width; val y = size.height - ((v - minV) / (maxV - minV) * size.height); if (i == 0) path.moveTo(x, y) else path.lineTo(x, y) }; drawPath(path, lineColor, style = androidx.compose.ui.graphics.drawscope.Stroke(5f, cap = StrokeCap.Round)) } }
