package la.captcha.demo

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import la.captcha.sdk.CaptchalaClient
import la.captcha.sdk.CaptchalaConfig
import la.captcha.sdk.CaptchalaError
import la.captcha.sdk.CaptchalaListener
import la.captcha.sdk.CaptchalaResult
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/**
 * Captchala SDK demo — pick options, tap "Apply & Verify".
 */
class MainActivity : ComponentActivity() {

    private val appKey: String = "demo_app"
    private val demoUid: String = "demo-user-12345"

    private var captcha: CaptchalaClient? = null

    /** Fetches server_token from the dashboard demo backend. In a real
     *  integration, this should be an HTTP call to your own backend
     *  (which holds the app credentials and proxies to the Captchala API). */
    private suspend fun fetchServerToken(): String? = withContext(Dispatchers.IO) {
        try {
            val url = URL(
                "https://demo-v1.captcha.la/demo/issue-captcha-token" +
                        "?app_key=${appKey}&action=demo&uid=${demoUid}"
            )
            val conn = url.openConnection() as HttpURLConnection
            conn.connectTimeout = 8000
            conn.readTimeout = 8000
            val body = conn.inputStream.bufferedReader().use { it.readText() }
            val json = JSONObject(body)
            val data = json.optJSONObject("data")
            data?.optString("server_token").takeIf { !it.isNullOrEmpty() }
        } catch (e: Exception) {
            null
        }
    }

    private suspend fun validatePassToken(passToken: String): String =
        withContext(Dispatchers.IO) {
            try {
                val url = URL("https://demo-v1.captcha.la/demo/validate-pass-token")
                val conn = (url.openConnection() as HttpURLConnection).apply {
                    requestMethod = "POST"
                    connectTimeout = 8000
                    readTimeout = 8000
                    doOutput = true
                    setRequestProperty("Content-Type", "application/x-www-form-urlencoded")
                }
                val form = "app_key=$appKey&pass_token=$passToken&expected_uid=$demoUid"
                conn.outputStream.use { it.write(form.toByteArray()) }
                val body = conn.inputStream.bufferedReader().use { it.readText() }
                val json = JSONObject(body)
                val data = json.optJSONObject("data")
                    ?: return@withContext "validate failed: no data"
                val valid = data.optBoolean("valid", false)
                if (!valid) return@withContext "validate: invalid — ${data.optString("error")}"
                val uid = data.optString("uid").takeIf { it.isNotEmpty() } ?: "(null)"
                val match = data.optBoolean("uid_match", false)
                "validate: valid=true, uid=$uid, match=${if (match) "✓" else "✗"}"
            } catch (e: Exception) {
                "validate failed: ${e.message}"
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    PlaygroundScreen(
                        onVerifyClick = { settings, onStatus, onResult, onDone ->
                            applyAndVerify(settings, onStatus, onResult, onDone)
                        }
                    )
                }
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        CaptchalaClient.destroy()
    }

    /**
     * (Re)initialize client with the panel settings, then verify().
     * Re-init is cheap; singleton SDK picks up new config each tap.
     *
     * onDone() fires on every terminal listener event (success / fail /
     * error / close) so the UI can clear its `isVerifying` flag.
     */
    private fun applyAndVerify(
        settings: Settings,
        onStatus: (String) -> Unit,
        onResult: (String) -> Unit,
        onDone: () -> Unit,
    ) {
        onStatus("Initializing with your settings…")

        val initialToken = runBlocking { fetchServerToken() }

        // Destroy prior instance so init() picks up fresh config.
        CaptchalaClient.destroy()
        captcha = CaptchalaClient.getClient(applicationContext).init(
            CaptchalaConfig.Builder()
                .appKey(appKey)
                .action("demo")
                .lang(settings.lang)
                .theme(settings.theme)
                .enableVoice(settings.enableVoice)
                .enableOfflineMode(settings.enableOfflineMode)
                .serverToken(initialToken)
                .onServerTokenExpired { fetchServerToken() }
                .build()
        )

        onStatus("Verifying…")
        captcha?.setListener(object : CaptchalaListener {
            override fun onReady() {
                onStatus("Interactive challenge loaded — complete it.")
            }

            override fun onSuccess(result: CaptchalaResult) {
                val flags = buildString {
                    if (result.isOffline) append(" (offline)")
                    if (result.isClientOnly) append(" (client-only)")
                }
                onResult(
                    "SUCCESS$flags\n" +
                            "pass_token : ${result.passToken}\n" +
                            "challenge  : ${result.challengeId}\n" +
                            "ttl (sec)  : ${result.ttl}\n\n" +
                            "(validating pass_token with backend...)"
                )
                onDone()
                CoroutineScope(Dispatchers.Main).launch {
                    val validateInfo = validatePassToken(result.passToken)
                    onResult(
                        "SUCCESS$flags\n" +
                                "pass_token : ${result.passToken}\n" +
                                "challenge  : ${result.challengeId}\n" +
                                "ttl (sec)  : ${result.ttl}\n\n" +
                                validateInfo
                    )
                }
            }

            override fun onFail(error: CaptchalaError) {
                onResult("FAIL: [${error.code}] ${error.message}")
                onDone()
            }

            override fun onError(error: CaptchalaError) {
                onResult("ERROR: [${error.code}] ${error.message}")
                onDone()
            }

            override fun onClose() {
                onStatus("Closed by user.")
                onDone()
            }
        })?.verify(this)
    }
}

// ─── Settings model ──────────────────────────────────────────────────
data class Settings(
    val lang: String,
    val theme: String,
    val enableVoice: Boolean,
    val enableOfflineMode: Boolean,
)

private val LANGS = listOf(
    ""      to "Auto (system)",
    "zh-CN" to "简体中文 (zh-CN)",
    "zh-TW" to "繁體中文 (zh-TW)",
    "en"    to "English (en)",
    "ja"    to "日本語 (ja)",
    "ko"    to "한국어 (ko)",
    "ms"    to "Bahasa Melayu (ms)",
    "vi"    to "Tiếng Việt (vi)",
    "id"    to "Bahasa Indonesia (id)",
)

private val THEMES = listOf(
    "light" to "Light",
    "dark"  to "Dark",
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun PlaygroundScreen(
    onVerifyClick: (Settings, onStatus: (String) -> Unit, onResult: (String) -> Unit, onDone: () -> Unit) -> Unit,
) {
    var lang by remember { mutableStateOf("") } // Auto (system)
    var theme by remember { mutableStateOf("light") }
    var enableVoice by remember { mutableStateOf(true) }
    var enableOffline by remember { mutableStateOf(true) }

    var status by remember { mutableStateOf<String?>(null) }
    var result by remember { mutableStateOf<String?>(null) }

    // Mirror the iOS demo (ContentView.swift:136): keep a Compose-side
    // isVerifying flag so the button shows a spinner + becomes disabled while
    // a verify is in flight. Also defends against the user-rage-tap case where
    // each tap would otherwise tear down + re-init the SDK + WebView.
    var isVerifying by remember { mutableStateOf(false) }

    Scaffold(
        topBar = { TopAppBar(title = { Text("CaptchaLa Playground") }) }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(16.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {

            // ── Settings card ──────────────────────────────────────
            Card(elevation = CardDefaults.cardElevation(2.dp)) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Text("Settings", style = MaterialTheme.typography.titleMedium)

                    LabelDropdown(
                        label = "Language",
                        options = LANGS.map { (k, v) -> k as String? to v },
                        selected = lang,
                        onSelect = { lang = it ?: "" }
                    )
                    LabelDropdown(
                        label = "Theme",
                        options = THEMES.map { (k, v) -> k as String? to v },
                        selected = theme,
                        onSelect = { theme = it ?: "light" }
                    )

                    ToggleRow("Enable voice captcha", enableVoice) { enableVoice = it }
                    ToggleRow("Enable offline fallback", enableOffline) { enableOffline = it }
                }
            }

            // ── Actions ────────────────────────────────────────────
            Button(
                onClick = {
                    result = null
                    isVerifying = true
                    onVerifyClick(
                        Settings(
                            lang = lang,
                            theme = theme,
                            enableVoice = enableVoice,
                            enableOfflineMode = enableOffline,
                        ),
                        { s -> status = s },
                        { r ->
                            status = null
                            result = r
                        },
                        { isVerifying = false }
                    )
                },
                enabled = !isVerifying,
                modifier = Modifier.fillMaxWidth()
            ) {
                if (isVerifying) {
                    CircularProgressIndicator(
                        modifier = Modifier.height(18.dp),
                        strokeWidth = 2.dp
                    )
                    Spacer(Modifier.height(0.dp))
                    Text("  Verifying…")
                } else {
                    Text("Apply & Verify")
                }
            }

            Spacer(Modifier.height(4.dp))

            // ── Output ────────────────────────────────────────────
            Text("Status", style = MaterialTheme.typography.titleSmall)
            Text(
                text = status ?: "Tap a button to begin.",
                style = MaterialTheme.typography.bodyMedium
            )

            result?.let {
                Spacer(Modifier.height(8.dp))
                Text("Verify result", style = MaterialTheme.typography.titleSmall)
                Text(
                    text = it,
                    style = MaterialTheme.typography.bodyMedium,
                    fontFamily = FontFamily.Monospace
                )
            }

        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun LabelDropdown(
    label: String,
    options: List<Pair<String?, String>>,
    selected: String?,
    onSelect: (String?) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    val currentLabel = options.firstOrNull { it.first == selected }?.second
        ?: options.first().second

    ExposedDropdownMenuBox(
        expanded = expanded,
        onExpandedChange = { expanded = it }
    ) {
        OutlinedTextField(
            value = currentLabel,
            onValueChange = {},
            readOnly = true,
            label = { Text(label) },
            trailingIcon = {
                ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded)
            },
            modifier = Modifier
                .fillMaxWidth()
                .menuAnchor()
        )
        DropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false }
        ) {
            options.forEach { (value, display) ->
                DropdownMenuItem(
                    text = { Text(display) },
                    onClick = {
                        onSelect(value)
                        expanded = false
                    }
                )
            }
        }
    }
}

@Composable
private fun ToggleRow(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(label, modifier = Modifier.weight(1f))
        Switch(checked = checked, onCheckedChange = onChange)
    }
}

