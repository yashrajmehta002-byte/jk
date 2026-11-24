package `in`.bachatgroup.agent

import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import `in`.bachatgroup.agent.ops.AgentFieldOperationsEngine
import `in`.bachatgroup.finance.*

class AgentMainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // HR-10: संवेदनशील स्क्रीन पर स्क्रीनशॉट व स्क्रीन रिकॉर्डिंग ब्लॉक
        window.setFlags(WindowManager.LayoutParams.FLAG_SECURE, WindowManager.LayoutParams.FLAG_SECURE)
        setContent { AgentRootScreen() }
    }
}

@Composable
fun AgentRootScreen() {
    var selectedTab by remember { mutableIntStateOf(0) }
    var cashInHandPaise by remember { mutableLongStateOf(50_000L) }
    var isSandbox by remember { mutableStateOf(false) }
    var statusMsg by remember { mutableStateOf("ऑफलाइन एजेंट मोड • केवल आवंटित समूह • FLAG_SECURE ON") }

    MaterialTheme(
        colorScheme = lightColorScheme(
            primary = Color(0xFF1E3A8A),
            secondary = Color(0xFF059669),
            background = Color(0xFFF4F6FB)
        )
    ) {
        Scaffold(
            topBar = {
                Surface(color = Color(0xFF1E3A8A), contentColor = Color.White) {
                    Column(Modifier.fillMaxWidth().padding(16.dp)) {
                        Text("बचत समूह प्रणाली — Agent APK", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                        Text("फील्ड संग्रहण • अस्थायी रसीद • Blind Tally", style = MaterialTheme.typography.bodySmall)
                        Spacer(Modifier.height(10.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            listOf("फील्ड वसूली", "Blind Tally", "सैंडबॉक्स").forEachIndexed { idx, title ->
                                FilterChip(
                                    selected = selectedTab == idx,
                                    onClick = { selectedTab = idx },
                                    label = { Text(title) }
                                )
                            }
                        }
                    }
                }
            }
        ) { pad ->
            Column(
                Modifier.padding(pad).padding(16.dp).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                when (selectedTab) {
                    0 -> {
                        Card(Modifier.fillMaxWidth()) {
                            Column(Modifier.padding(16.dp)) {
                                Text("एजेंट Cash-in-Hand (लंबित हैंडओवर)", style = MaterialTheme.typography.labelMedium)
                                Text(QuotationSlipModule.formatIndianRupees(cashInHandPaise), style = MaterialTheme.typography.headlineSmall, color = Color(0xFFB45309), fontWeight = FontWeight.Bold)
                            }
                        }
                        Button(onClick = {
                            val res = AgentFieldOperationsEngine.buildAgentCollection("g1", setOf("g1"), 50_000L, isSandbox)
                            if (!isSandbox) cashInHandPaise += 50_000L
                            statusMsg = "₹500 वसूली दर्ज (${res.targetDatabaseName}) • अस्थायी रसीद (PROVISIONAL)"
                        }, modifier = Modifier.fillMaxWidth()) {
                            Text("बचत वसूली दर्ज करें (+₹500)")
                        }
                        Text(statusMsg, style = MaterialTheme.typography.bodySmall)
                    }
                    1 -> {
                        var n500 by remember { mutableStateOf("1") }
                        OutlinedTextField(value = n500, onValueChange = { n500 = it }, label = { Text("₹500 नोट गिनती") }, modifier = Modifier.fillMaxWidth())
                        val tally = Step6HandoverAndDividendEngine.evaluateBlindCashTally(
                            CashDenominationCount(notes500 = n500.toIntOrNull() ?: 0), cashInHandPaise
                        )
                        Card(Modifier.fillMaxWidth()) {
                            Column(Modifier.padding(16.dp)) {
                                Text("भौतिक गिनती: ${QuotationSlipModule.formatIndianRupees(tally.physicalCountedPaise)}", fontWeight = FontWeight.Bold)
                                Text(if (tally.isExactMatch) "सटीक मिलान (₹0 अंतर)" else "अंतर: ${QuotationSlipModule.formatIndianRupees(tally.variancePaise)}")
                            }
                        }
                    }
                    else -> {
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            Text("Training Sandbox मोड (test_bachat.db)")
                            Switch(checked = isSandbox, onCheckedChange = { isSandbox = it })
                        }
                        Text(if (isSandbox) "अभ्यास मोड चालू — कोई भी एंट्री असली डेटाबेस या सिंक में नहीं जाएगी।" else "असली फील्ड मोड चालू।")
                    }
                }
            }
        }
    }
}
