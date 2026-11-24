package `in`.bachatgroup.finance

import java.math.BigDecimal
import java.math.MathContext
import java.math.RoundingMode
import java.time.DayOfWeek
import java.time.LocalDate

/**
 * बचत समूह प्रणाली — Step 2: RD इंजन, लोन किस्त शेड्यूल (अवकाश शिफ्ट),
 * Savings-to-Loan Offset, Opening Balance Bulk CSV आयात, खर्च वाउचर व Period Lock।
 */
data class InstallmentRow(
    val installmentNo: Int,
    val originalDueDate: LocalDate,
    val adjustedDueDate: LocalDate,
    val wasHolidayShifted: Boolean,
    val emiPaise: Long,
    val principalComponentPaise: Long,
    val interestComponentPaise: Long,
    val closingBalancePaise: Long
)

data class RdMaturityResult(
    val monthlyDepositPaise: Long,
    val months: Int,
    val totalDepositedPaise: Long,
    val interestEarnedPaise: Long,
    val maturityAmountPaise: Long,
    val prematurePenaltyPaise: Long = 0L
)

data class OpeningCsvRow(
    val memberId: String,
    val memberName: String,
    val openingSavingsPaise: Long,
    val openingLoanPrincipalPaise: Long,
    val openingOverdueInterestPaise: Long
)

data class OpeningImportValidation(
    val valid: Boolean,
    val totalAssetsPaise: Long,
    val totalLiabilitiesAndEquityPaise: Long,
    val trialBalanceDiffPaise: Long,
    val errorHindi: String? = null
)

object Step2FinanceLedgerEngine {
    private val MC = MathContext(16, RoundingMode.HALF_EVEN)
    private val HUNDRED = BigDecimal(100)
    private val TWELVE = BigDecimal(12)

    /**
     * F-124: अवकाश दिन / रविवार पड़ने पर देय तिथि अगले कार्यदिवस पर खिसकाना
     */
    fun shiftIfHoliday(date: LocalDate, holidays: Set<LocalDate> = emptySet()): Pair<LocalDate, Boolean> {
        var d = date
        var shifted = false
        while (d.dayOfWeek == DayOfWeek.SUNDAY || d in holidays) {
            d = d.plusDays(1)
            shifted = true
        }
        return d to shifted
    }

    /**
     * मासिक किस्त तालिका (Reducing EMI / Flat Rate) + रविवार/अवकाश शिफ्ट
     */
    fun generateLoanSchedule(
        principalPaise: Long,
        annualRatePercent: BigDecimal,
        months: Int,
        startDate: LocalDate,
        isReducing: Boolean = true,
        holidays: Set<LocalDate> = emptySet(),
        config: RulesConfig = RulesConfig()
    ): List<InstallmentRow> {
        require(principalPaise > 0L && months > 0)
        val rows = mutableListOf<InstallmentRow>()
        val monthlyRate = annualRatePercent.divide(HUNDRED, MC).divide(TWELVE, MC)
        var balance = principalPaise

        if (isReducing) {
            val mr = monthlyRate.toDouble()
            val emi = if (mr == 0.0) {
                BigDecimal(principalPaise).divide(BigDecimal(months), 0, config.roundingMode).toLong()
            } else {
                val pf = Math.pow(1.0 + mr, months.toDouble())
                BigDecimal(principalPaise.toDouble() * mr * pf / (pf - 1.0), MC)
                    .setScale(0, config.roundingMode).toLong()
            }
            for (i in 1..months) {
                val origDate = startDate.plusMonths(i.toLong())
                val (adjDate, shifted) = shiftIfHoliday(origDate, holidays)
                val interest = BigDecimal(balance).multiply(monthlyRate, MC)
                    .setScale(0, config.roundingMode).toLong()
                var prin = if (i == months) balance else minOf(balance, maxOf(0L, emi - interest))
                val actualEmi = FinanceSuiteEngine.safeAdd(prin, interest)
                balance -= prin
                rows.add(InstallmentRow(i, origDate, adjDate, shifted, actualEmi, prin, interest, balance))
            }
        } else {
            val totalInterest = BigDecimal(principalPaise)
                .multiply(annualRatePercent.divide(HUNDRED, MC), MC)
                .multiply(BigDecimal(months).divide(TWELVE, MC), MC)
                .setScale(0, config.roundingMode).toLong()
            val basePrin = principalPaise / months
            val baseInt = totalInterest / months
            for (i in 1..months) {
                val origDate = startDate.plusMonths(i.toLong())
                val (adjDate, shifted) = shiftIfHoliday(origDate, holidays)
                val prin = if (i == months) balance else basePrin
                val interest = if (i == months) (totalInterest - baseInt * (months - 1)) else baseInt
                balance -= prin
                rows.add(InstallmentRow(i, origDate, adjDate, shifted, prin + interest, prin, interest, balance))
            }
        }
        return rows
    }

    /**
     * F-060 & F-061: RD (आवर्ती जमा) परिपक्वता एवं समयपूर्व निकासी गणना
     */
    fun calculateRdMaturity(
        monthlyDepositPaise: Long,
        months: Int,
        annualRatePercent: BigDecimal,
        isPremature: Boolean = false,
        prematurePenaltyPercent: BigDecimal = BigDecimal("1.00"),
        config: RulesConfig = RulesConfig()
    ): RdMaturityResult {
        require(monthlyDepositPaise > 0L && months > 0)
        val totalDeposited = monthlyDepositPaise * months.toLong()
        val effectiveRate = if (isPremature) {
            maxOf(BigDecimal.ZERO, annualRatePercent.subtract(prematurePenaltyPercent))
        } else {
            annualRatePercent
        }
        // Standard RD simple/monthly-product interest: I = P * n(n+1)/2 * r / (12 * 100)
        val monthProduct = BigDecimal(months.toLong() * (months + 1).toLong()).divide(BigDecimal(2), MC)
        val grossInterest = BigDecimal(monthlyDepositPaise)
            .multiply(monthProduct, MC)
            .multiply(annualRatePercent, MC)
            .divide(BigDecimal(1200), 0, config.roundingMode).toLong()
        val actualInterest = BigDecimal(monthlyDepositPaise)
            .multiply(monthProduct, MC)
            .multiply(effectiveRate, MC)
            .divide(BigDecimal(1200), 0, config.roundingMode).toLong()
        val penalty = maxOf(0L, grossInterest - actualInterest)
        return RdMaturityResult(
            monthlyDepositPaise = monthlyDepositPaise,
            months = months,
            totalDepositedPaise = totalDeposited,
            interestEarnedPaise = actualInterest,
            maturityAmountPaise = FinanceSuiteEngine.safeAdd(totalDeposited, actualInterest),
            prematurePenaltyPaise = penalty
        )
    }

    /**
     * F-116: वित्तीय अवधि बंदी (Period Lock) जाँच — लॉक तिथि से पहले की एंट्री अवरुद्ध
     */
    fun verifyNotInLockedPeriod(txEpoch: Long, lockedUntilEpoch: Long?): Boolean {
        if (lockedUntilEpoch == null) return true
        return txEpoch > lockedUntilEpoch
    }

    /**
     * F-158: Opening Balance Bulk CSV सत्यापन (100 सीमा, डुप्लीकेट ID व Trial Balance = 0 अंतर)
     */
    fun validateOpeningBulkImport(
        rows: List<OpeningCsvRow>,
        openingCashVaultPaise: Long,
        openingGroupReservePaise: Long,
        config: RulesConfig = RulesConfig()
    ): OpeningImportValidation {
        if (rows.isEmpty()) {
            return OpeningImportValidation(false, 0, 0, 0, "CSV खाली है।")
        }
        if (rows.size > config.maxActiveMembersPerGroup) {
            return OpeningImportValidation(
                false, 0, 0, 0,
                "HR-06 उल्लंघन: एक समूह में अधिकतम ${config.maxActiveMembersPerGroup} सक्रिय सदस्य ही अनुमत हैं।"
            )
        }
        val ids = rows.map { it.memberId }
        if (ids.size != ids.toSet().size) {
            return OpeningImportValidation(false, 0, 0, 0, "CSV में डुप्लीकेट Member ID मिली; पूर्ण Rollback लागू।")
        }
        var totalSavings = 0L
        var totalLoanPrincipal = 0L
        var totalOverdueInterest = 0L
        for (r in rows) {
            if (r.openingSavingsPaise < 0L || r.openingLoanPrincipalPaise < 0L || r.openingOverdueInterestPaise < 0L) {
                return OpeningImportValidation(false, 0, 0, 0, "सदस्य ${r.memberId} में ऋणात्मक राशि अमान्य है।")
            }
            totalSavings = FinanceSuiteEngine.safeAdd(totalSavings, r.openingSavingsPaise)
            totalLoanPrincipal = FinanceSuiteEngine.safeAdd(totalLoanPrincipal, r.openingLoanPrincipalPaise)
            totalOverdueInterest = FinanceSuiteEngine.safeAdd(totalOverdueInterest, r.openingOverdueInterestPaise)
        }
        // Assets = Cash Vault + Loan Principal Receivable + Overdue Interest Receivable
        val totalAssets = FinanceSuiteEngine.safeAdd(
            FinanceSuiteEngine.safeAdd(openingCashVaultPaise, totalLoanPrincipal),
            totalOverdueInterest
        )
        // Liabilities & Equity = Member Savings Liability + Group Opening Reserve
        val totalLiabEq = FinanceSuiteEngine.safeAdd(totalSavings, openingGroupReservePaise)
        val diff = totalAssets - totalLiabEq
        return if (diff == 0L) {
            OpeningImportValidation(true, totalAssets, totalLiabEq, 0L, null)
        } else {
            OpeningImportValidation(
                false, totalAssets, totalLiabEq, diff,
                "Trial Balance असंतुलित है (अंतर: $diff पैसे); पूर्ण Rollback लागू।"
            )
        }
    }
}
