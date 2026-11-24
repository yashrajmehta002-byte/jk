package `in`.bachatgroup.finance

import java.math.BigDecimal
import java.math.MathContext
import java.math.RoundingMode

/**
 * बचत समूह प्रणाली — Step 6: Blind EOD Cash Tally, नकद हैंडओवर Approval Gate (HR-12),
 * शुद्ध लाभ लाभांश (Pro-rata) व Post-Only एजेंट बोनस इंजन (`:shared:finance-core`)।
 */
enum class HandoverStatus { PENDING_ADMIN, ACCEPTED, REJECTED }
enum class IncentiveStage { DRAFT, REVIEW, APPROVED, POSTED }

data class CashDenominationCount(
    val notes500: Int = 0,
    val notes200: Int = 0,
    val notes100: Int = 0,
    val notes50: Int = 0,
    val notes20: Int = 0,
    val notes10: Int = 0,
    val coinsRupees: Int = 0
) {
    fun totalPaise(): Long {
        val rs = notes500 * 500L + notes200 * 200L + notes100 * 100L +
            notes50 * 50L + notes20 * 20L + notes10 * 10L + coinsRupees.toLong()
        require(rs >= 0L) { "नोट गिनती ऋणात्मक नहीं हो सकती।" }
        return rs * 100L
    }
}

data class BlindTallyResult(
    val physicalCountedPaise: Long,
    val expectedCashInHandPaise: Long,
    val variancePaise: Long, // + = अधिकता (Excess), - = कमी (Shortage)
    val isExactMatch: Boolean
)

data class HandoverApprovalLedgerResult(
    val approvedAmountPaise: Long,
    val variancePaise: Long,
    val ledgerLines: List<LedgerLine>
)

data class MemberSavingsBasis(
    val memberId: String,
    val averageSavingsPaise: Long
)

data class DividendAllocationPlan(
    val grossRealizedInterestPaise: Long,
    val groupExpensesPaise: Long,
    val reserveFundPaise: Long,
    val netDistributableProfitPaise: Long,
    val memberDividendsPaise: Map<String, Long>,
    val roundingAdjustmentPaise: Long
)

data class AgentIncentiveRecord(
    val incentiveId: String,
    val agentId: String,
    val cycleLabel: String,
    val amountPaise: Long,
    val stage: IncentiveStage
)

object Step6HandoverAndDividendEngine {
    private val MC = MathContext(16, RoundingMode.HALF_EVEN)

    /** F-132: Blind EOD Cash Tally (अपेक्षित राशि दिखाने से पहले भौतिक नोट गिनती) */
    fun evaluateBlindCashTally(
        denominations: CashDenominationCount,
        expectedCashInHandPaise: Long
    ): BlindTallyResult {
        val counted = denominations.totalPaise()
        val variance = counted - expectedCashInHandPaise
        return BlindTallyResult(
            physicalCountedPaise = counted,
            expectedCashInHandPaise = expectedCashInHandPaise,
            variancePaise = variance,
            isExactMatch = (variance == 0L)
        )
    }

    /**
     * HR-12 & F-008/F-090/F-091: केवल Admin द्वारा ACCEPTED करने पर ही राशि
     * AGENT_CASH_IN_HAND से ADMIN_CASH_VAULT (Real Dashboard) में जाएगी।
     */
    fun approveCashHandover(
        currentStatus: HandoverStatus,
        requestedAmountPaise: Long,
        adminCountedPaise: Long
    ): HandoverApprovalLedgerResult {
        check(currentStatus == HandoverStatus.PENDING_ADMIN) {
            "Idempotency अवरोध: केवल PENDING_ADMIN हैंडओवर ही स्वीकार किया जा सकता है (वर्तमान: $currentStatus)।"
        }
        require(requestedAmountPaise > 0L && adminCountedPaise > 0L) { "हैंडओवर राशि शून्य से अधिक होनी चाहिए।" }
        val variance = adminCountedPaise - requestedAmountPaise
        val lines = mutableListOf(
            LedgerLine("ADMIN_CASH_VAULT", debitPaise = adminCountedPaise, creditPaise = 0L),
            LedgerLine("AGENT_CASH_IN_HAND", debitPaise = 0L, creditPaise = requestedAmountPaise)
        )
        if (variance < 0L) {
            // नकद कमी (Shortage) — Agent Receivable / Shortage Debit
            lines.add(LedgerLine("CASH_SHORTAGE_RECEIVABLE", debitPaise = -variance, creditPaise = 0L))
        } else if (variance > 0L) {
            // नकद अधिकता (Excess) — Cash Excess Liability Credit
            lines.add(LedgerLine("CASH_EXCESS_LIABILITY", debitPaise = 0L, creditPaise = variance))
        }
        check(FinanceSuiteEngine.verifyDoubleEntryBalanced(lines)) { "Handover Double-Entry असंतुलित!" }
        return HandoverApprovalLedgerResult(adminCountedPaise, variance, lines)
    }

    /** F-092 & F-139: एजेंट कैश सीमा या फ्रीज़ स्थिति जाँच */
    fun canAgentAcceptNewCollection(
        currentCashInHandPaise: Long,
        newCollectionPaise: Long,
        isFrozen: Boolean,
        config: RulesConfig = RulesConfig()
    ): Pair<Boolean, String> {
        if (isFrozen) return false to "अवरोध (F-139): Admin द्वारा आपका संग्रहण फ्रीज़ (is_frozen) किया गया है।"
        if (FinanceSuiteEngine.safeAdd(currentCashInHandPaise, newCollectionPaise) > config.agentMaxCashInHandPaise) {
            return false to "अवरोध (F-092): नकद सीमा (₹${config.agentMaxCashInHandPaise / 100}) पार हो रही है; पहले Admin को हैंडओवर करें।"
        }
        return true to "स्वीकृत।"
    }

    /**
     * F-093 & F-094 (नियम 5): शुद्ध लाभ लाभांश गणना (ब्याज आय − खर्च − रिज़र्व %) व Pro-Rata वितरण
     */
    fun calculateProRataDividend(
        realizedInterestIncomePaise: Long,
        groupExpensesPaise: Long,
        members: List<MemberSavingsBasis>,
        reserveFundPercent: Int = 10,
        config: RulesConfig = RulesConfig()
    ): DividendAllocationPlan {
        require(members.isNotEmpty()) { "कम से कम 1 सक्रिय सदस्य अनिवार्य है।" }
        val operatingProfit = maxOf(0L, realizedInterestIncomePaise - groupExpensesPaise)
        val reservePaise = BigDecimal(operatingProfit)
            .multiply(BigDecimal(reserveFundPercent), MC)
            .divide(BigDecimal(100), 0, config.roundingMode).toLong()
        val netProfit = maxOf(0L, operatingProfit - reservePaise)

        val totalAvgSavings = members.fold(0L) { acc, m -> FinanceSuiteEngine.safeAdd(acc, m.averageSavingsPaise) }
        require(totalAvgSavings > 0L) { "लाभांश वितरण हेतु कुल औसत बचत शून्य से अधिक होनी चाहिए।" }

        val allocations = LinkedHashMap<String, Long>()
        var distributedSum = 0L
        for (m in members) {
            val share = BigDecimal(netProfit)
                .multiply(BigDecimal(m.averageSavingsPaise), MC)
                .divide(BigDecimal(totalAvgSavings), 0, config.roundingMode).toLong()
            allocations[m.memberId] = share
            distributedSum = FinanceSuiteEngine.safeAdd(distributedSum, share)
        }
        val roundingAdj = netProfit - distributedSum
        return DividendAllocationPlan(
            grossRealizedInterestPaise = realizedInterestIncomePaise,
            groupExpensesPaise = groupExpensesPaise,
            reserveFundPaise = reservePaise,
            netDistributableProfitPaise = netProfit,
            memberDividendsPaise = allocations,
            roundingAdjustmentPaise = roundingAdj
        )
    }

    /** F-028 & F-096: Agent ऐप में केवल POSTED बोनस ही दिखाई देगा */
    fun filterVisibleIncentivesForAgent(all: List<AgentIncentiveRecord>, agentId: String): List<AgentIncentiveRecord> {
        return all.filter { it.agentId == agentId && it.stage == IncentiveStage.POSTED }
    }
}
