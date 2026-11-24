package `in`.bachatgroup.admin

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
import `in`.bachatgroup.admin.ops.AdminOperationsEngine
import `in`.bachatgroup.finance.*
import java.math.BigDecimal

class AdminMainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // HR-10: संवेदनशील स्क्रीन पर स्क्रीनशॉट व स्क्रीन रिकॉर्डिंग ब्लॉक
        window.setFlags(WindowManager.LayoutParams.FLAG_SECURE, WindowManager.LayoutParams.FLAG_SECURE)
        setContent { AdminRootScreen() }
    }
}

@Composable
fun AdminRootScreen() {
    var selectedTab by remember { mutableIntStateOf(0) }
    var realVaultPaise by remember { mutableLongStateOf(1_000_000L) }
    var pendingAgentPaise by remember { mutableLongStateOf(250_000L) }
    var statusMsg by remember { mutableStateOf("SQLCipher + FLAG_SECURE + 25 टेबल्स सक्रिय") }

    MaterialTheme(
        colorScheme = lightColorScheme(
            primary = Color(0xFF1A2530),
            secondary = Color(0xFFD97706),
            background = Color(0xFFF4F6FB)
        )
    ) {
        Scaffold(
            topBar = {
                Surface(color = Color(0xFF1A2530), contentColor = Color.White) {
                    Column(Modifier.fillMaxWidth().padding(16.dp)) {
                        Text("बचत समूह प्रणाली — Admin APK", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                        Text("Real Dashboard • ऑफलाइन-फर्स्ट • 365-दिन कोर", style = MaterialTheme.typography.bodySmall)
                        Spacer(Modifier.height(10.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            listOf("Real Dashboard", "कोटेशन", "हैंडओवर").forEachIndexed { idx, title ->
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
                                Text("वास्तविक वसूली (ADMIN_CASH_VAULT)", style = MaterialTheme.typography.labelMedium)
                                Text(QuotationSlipModule.formatIndianRupees(realVaultPaise), style = MaterialTheme.typography.headlineSmall, color = Color(0xFF047857), fontWeight = FontWeight.Bold)
                                Spacer(Modifier.height(8.dp))
                                Text("एजेंट के पास लंबित (AGENT_CASH_IN_HAND)", style = MaterialTheme.typography.labelMedium)
                                Text(QuotationSlipModule.formatIndianRupees(pendingAgentPaise), style = MaterialTheme.typography.titleLarge, color = Color(0xFFB45309), fontWeight = FontWeight.Bold)
                            }
                        }
                        Button(onClick = {
                            val res = AdminOperationsEngine.buildAdminDirectCollection(100_000L, null)
                            realVaultPaise += 100_000L
                            statusMsg = "Admin सीधी वसूली +₹1,000 → ${res.creditedAccountCode} (Realized)"
                        }, modifier = Modifier.fillMaxWidth()) {
                            Text("Admin सीधी वसूली (+₹1,000 → Real Vault)")
                        }
                        Text(statusMsg, style = MaterialTheme.typography.bodySmall)
                    }
                    1 -> {
                        var principalText by remember { mutableStateOf("50000") }
                        var rateText by remember { mutableStateOf("2") }
                        var yearsText by remember { mutableStateOf("1") }
                        OutlinedTextField(value = principalText, onValueChange = { principalText = it }, label = { Text("मूलधन (₹)") }, modifier = Modifier.fillMaxWidth())
                        OutlinedTextField(value = rateText, onValueChange = { rateText = it }, label = { Text("साहूकारी दर (₹/100 मासिक)") }, modifier = Modifier.fillMaxWidth())
                        OutlinedTextField(value = yearsText, onValueChange = { yearsText = it }, label = { Text("अवधि (वर्ष — 365 दिन)") }, modifier = Modifier.fillMaxWidth())
                        val p = (principalText.toLongOrNull() ?: 50000L) * 100L
                        val r = rateText.toBigDecimalOrNull() ?: BigDecimal("2")
                        val y = (yearsText.toIntOrNull() ?: 1).coerceAtLeast(1)
                        val q = FinanceSuiteEngine.calculateQuotation(
                            QuotationRequest(principalPaise = p, interestType = InterestType.SAHUKARI_SIMPLE, rateValue = r, tenorInput = TenorInput.Manual(y, 0, 0))
                        )
                        Card(Modifier.fillMaxWidth()) {
                            Column(Modifier.padding(16.dp)) {
                                Text("कुल ब्याज: ${QuotationSlipModule.formatIndianRupees(q.interestPaise)}", fontWeight = FontWeight.Bold)
                                Text("कुल देय राशि: ${QuotationSlipModule.formatIndianRupees(q.totalPayablePaise)}", color = Color(0xFF047857), fontWeight = FontWeight.Bold)
                                Text("अनुमानित मासिक किस्त: ${QuotationSlipModule.formatIndianRupees(q.estimatedMonthlyEmiPaise)}")
                            }
                        }
                    }
                    else -> {
                        Button(
                            enabled = pendingAgentPaise > 0L,
                            onClick = {
                                val res = Step6HandoverAndDividendEngine.approveCashHandover(
                                    HandoverStatus.PENDING_ADMIN, pendingAgentPaise, pendingAgentPaise
                                )
                                realVaultPaise += res.approvedAmountPaise
                                pendingAgentPaise = 0L
                                statusMsg = "हैंडओवर स्वीकृत! अब वास्तविक वसूली: ${QuotationSlipModule.formatIndianRupees(realVaultPaise)}"
                            },
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text("Pending हैंडओवर गिनकर Approve करें (${QuotationSlipModule.formatIndianRupees(pendingAgentPaise)})")
                        }
                        Text(statusMsg, style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
        }
    }
}
