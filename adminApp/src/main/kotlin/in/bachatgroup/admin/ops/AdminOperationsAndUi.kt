package `in`.bachatgroup.admin.ops

import `in`.bachatgroup.finance.FinanceSuiteEngine
import `in`.bachatgroup.finance.LedgerLine
import `in`.bachatgroup.finance.RulesConfig

/**
 * बचत समूह प्रणाली — Step 4: Admin ऐप मुख्य संचालन व UI स्टेट मॉडल (`:adminApp`)
 * - दो-भाग Real Dashboard (HR-12): वास्तविक सत्यापित वसूली (ADMIN_CASH_VAULT) बनाम लंबित (AGENT_CASH_IN_HAND)
 * - F-134: Admin स्व-समूह (Self-Group) और "Agent की तरह सीधा फील्ड कार्य"
 * - F-135: किसी भी एजेंट के सदस्य से Admin द्वारा सीधी वसूली (एजेंट के Cash-in-Hand में नहीं जुड़ेगी)
 * - F-138: एजेंट प्रबंधन स्कोरकार्ड (वसूली %, लंबित नकद, सिंक आयु व जोखिम स्तर)
 */
enum class AgentRiskLevel { LOW, MEDIUM, HIGH }

data class RealDashboardSummary(
    val realizedAdminVaultPaise: Long,      // केवल Admin सीधी वसूली + Approved Handovers
    val pendingAgentCashInHandPaise: Long,  // अभी एजेंटों के पास लंबित (Realized में शामिल नहीं)
    val totalSavingsLiabilityPaise: Long,
    val activeLoansOutstandingPaise: Long
)

data class AgentScorecardItem(
    val agentId: String,
    val agentCode: String,
    val agentName: String,
    val targetCollectionPaise: Long,
    val actualCollectionPaise: Long,
    val recoveryPercent: Double,
    val pendingCashInHandPaise: Long,
    val syncAgeDays: Int,
    val isFrozen: Boolean,
    val riskLevel: AgentRiskLevel
)

data class AdminDirectCollectionResult(
    val ledgerLines: List<LedgerLine>,
    val creditedAccountCode: String,
    val agentCashImpacted: Boolean,
    val queuePassbookSyncToAgentId: String?
)

object AdminOperationsEngine {

    /**
     * F-134 & F-135: Admin द्वारा स्व-समूह या किसी एजेंट के सदस्य से सीधी वसूली
     * नियम: राशि सीधे ADMIN_CASH_VAULT (वास्तविक वसूली) में जाएगी, एजेंट के Cash-in-Hand में कभी नहीं।
     */
    fun buildAdminDirectCollection(
        amountPaise: Long,
        assignedAgentIdIfAny: String?
    ): AdminDirectCollectionResult {
        require(amountPaise > 0L) { "जमा राशि शून्य से अधिक होनी चाहिए।" }
        val lines = listOf(
            LedgerLine(accountCode = "ADMIN_CASH_VAULT", debitPaise = amountPaise, creditPaise = 0L),
            LedgerLine(accountCode = "MEMBER_SAVINGS_LIABILITY", debitPaise = 0L, creditPaise = amountPaise)
        )
        check(FinanceSuiteEngine.verifyDoubleEntryBalanced(lines)) { "Double-Entry असंतुलित!" }
        return AdminDirectCollectionResult(
            ledgerLines = lines,
            creditedAccountCode = "ADMIN_CASH_VAULT",
            agentCashImpacted = false,
            queuePassbookSyncToAgentId = assignedAgentIdIfAny
        )
    }

    /**
     * F-138: एजेंट स्कोरकार्ड और जोखिम स्तर (Risk Level) गणना
     */
    fun computeAgentScorecard(
        agentId: String,
        agentCode: String,
        agentName: String,
        targetCollectionPaise: Long,
        actualCollectionPaise: Long,
        pendingCashInHandPaise: Long,
        syncAgeDays: Int,
        isFrozen: Boolean,
        config: RulesConfig = RulesConfig()
    ): AgentScorecardItem {
        val recoveryPct = if (targetCollectionPaise <= 0L) {
            100.0
        } else {
            (actualCollectionPaise.toDouble() * 100.0) / targetCollectionPaise.toDouble()
        }
        val risk = when {
            pendingCashInHandPaise >= config.agentMaxCashInHandPaise ||
                syncAgeDays >= config.syncAgeAutoLockDays || isFrozen -> AgentRiskLevel.HIGH
            syncAgeDays >= config.syncAgeWarningDays || recoveryPct < 70.0 -> AgentRiskLevel.MEDIUM
            else -> AgentRiskLevel.LOW
        }
        return AgentScorecardItem(
            agentId = agentId,
            agentCode = agentCode,
            agentName = agentName,
            targetCollectionPaise = targetCollectionPaise,
            actualCollectionPaise = actualCollectionPaise,
            recoveryPercent = recoveryPct,
            pendingCashInHandPaise = pendingCashInHandPaise,
            syncAgeDays = syncAgeDays,
            isFrozen = isFrozen,
            riskLevel = risk
        )
    }
}
