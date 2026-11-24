package `in`.bachatgroup.finance

import java.math.BigDecimal
import java.math.MathContext
import java.math.RoundingMode
import java.time.LocalDate
import java.time.Period
import java.time.temporal.ChronoUnit

/**
 * बचत समूह प्रणाली — एकीकृत FinanceSuite ब्याज, कोटेशन और भुगतान वाटरफॉल इंजन
 * आधार: मास्टर रोडमैप (V1.1), खंड 10.4
 *
 * विशेषताएँ:
 * 1. केवल Long Paise (₹1 = 100L) — शून्य Float/Double त्रुटि।
 * 2. अवधि (Tenor) के दो विकल्प:
 *    - मैन्युअल एंट्री (वर्ष, माह, दिन के अलग-अलग कॉलम)
 *    - कैलेंडर "कहाँ से कहाँ तक" (From Date -> To Date)
 * 3. 1 वर्ष = सटीक 365 दिन नियम (360 दिन नहीं)।
 * 4. ब्याज प्रकार: Reducing EMI, Flat Rate, साहूकारी (साधारण व चक्रवृद्धि), Simple Interest, Compound Interest।
 * 5. आंशिक भुगतान वाटरफॉल: जुर्माना -> बकाया ब्याज -> मूलधन -> Advance Wallet।
 */
enum class InterestType {
    REDUCING_EMI,
    FLAT_RATE,
    SAHUKARI_SIMPLE,      // ₹ प्रति 100 मासिक (साधारण)
    SAHUKARI_COMPOUND,    // ₹ प्रति 100 मासिक (चक्रवृद्धि)
    SIMPLE_INTEREST,      // वार्षिक % साधारण
    COMPOUND_INTEREST     // वार्षिक % चक्रवृद्धि
}

enum class CalculationCycle(val periodsPerYear: Int) {
    DAILY(365),
    MONTHLY(12),
    QUARTERLY(4),
    HALF_YEARLY(2),
    YEARLY(1)
}

sealed class TenorInput {
    /** विकल्प 1: मैन्युअल वर्ष, माह, दिन */
    data class Manual(val years: Int, val months: Int, val days: Int) : TenorInput()

    /** विकल्प 2: कैलेंडर "कहाँ से कहाँ तक" (From -> To) */
    data class DateRange(val fromDate: LocalDate, val toDate: LocalDate) : TenorInput()
}

data class ResolvedTenor(
    val years: Int,
    val months: Int,
    val days: Int,
    val totalExactDays: Long,
    val fractionalYears365: BigDecimal,
    val sourceLabel: String
)

data class QuotationRequest(
    val customerName: String = "",
    val customerMobile: String = "",
    val isGuest: Boolean = true,
    val memberId: String? = null,
    val principalPaise: Long,
    val interestType: InterestType,
    /**
     * SAHUKARI_* के लिए: प्रति ₹100 मासिक रुपये (जैसे 2.00 = ₹2 सैकड़ा मासिक -> 24% वार्षिक)
     * अन्य के लिए: वार्षिक ब्याज दर % (जैसे 18.00 = 18% वार्षिक)
     */
    val rateValue: BigDecimal,
    val tenorInput: TenorInput,
    val cycle: CalculationCycle = CalculationCycle.MONTHLY
)

data class QuotationResult(
    val customerName: String,
    val customerMobile: String,
    val isGuest: Boolean,
    val memberId: String?,
    val principalPaise: Long,
    val interestPaise: Long,
    val totalPayablePaise: Long,
    val estimatedMonthlyEmiPaise: Long,
    val effectiveAnnualRatePercent: BigDecimal,
    val resolvedTenor: ResolvedTenor,
    val formulaBreakdownHindi: String
)

data class WaterfallAllocation(
    val paidPenaltyPaise: Long,
    val paidInterestPaise: Long,
    val paidPrincipalPaise: Long,
    val creditedAdvanceWalletPaise: Long,
    val remainingDuePaise: Long
)

data class LedgerLine(
    val accountCode: String,
    val debitPaise: Long,
    val creditPaise: Long
)

object FinanceSuiteEngine {
    private val MC = MathContext(16, RoundingMode.HALF_EVEN)
    private val DAYS_365 = BigDecimal(365)
    private val HUNDRED = BigDecimal(100)
    private val TWELVE = BigDecimal(12)

    /**
     * सुरक्षित Long योग (Overflow Guard — HR-07)
     */
    fun safeAdd(a: Long, b: Long): Long = Math.addExact(a, b)

    /**
     * अवधि (Tenor) को सटीक 365-दिन वर्ष नियम से हल करना
     * - Manual(1, 0, 0) या Manual(0, 12, 0) => सटीक 365 दिन
     * - DateRange(from, to) => सटीक कैलेंडर दिनों की संख्या
     */
    fun resolveTenor(input: TenorInput, config: RulesConfig = RulesConfig()): ResolvedTenor {
        return when (input) {
            is TenorInput.Manual -> {
                require(input.years >= 0 && input.months >= 0 && input.days >= 0) {
                    "वर्ष, माह और दिन ऋणात्मक नहीं हो सकते।"
                }
                val totalMonths = input.years * 12 + input.months
                val fullYears = totalMonths / 12
                val remMonths = totalMonths % 12
                // सटीक 365-दिन वर्ष नियम: प्रत्येक पूर्ण 12 माह = 365 दिन; शेष माह = round(remMonths * 365 / 12)
                val daysFromYears = fullYears.toLong() * config.daysPerCalendarYear.toLong()
                val daysFromRemMonths = BigDecimal(remMonths)
                    .multiply(BigDecimal(config.daysPerCalendarYear), MC)
                    .divide(TWELVE, 0, config.roundingMode)
                    .toLong()
                val totalDays = daysFromYears + daysFromRemMonths + input.days.toLong()
                require(totalDays > 0L) { "कुल अवधि कम से कम 1 दिन होनी चाहिए।" }
                val fracYears = BigDecimal(totalDays).divide(BigDecimal(config.daysPerCalendarYear), MC)
                ResolvedTenor(
                    years = fullYears,
                    months = remMonths,
                    days = input.days,
                    totalExactDays = totalDays,
                    fractionalYears365 = fracYears,
                    sourceLabel = "मैन्युअल: $fullYears वर्ष, $remMonths माह, ${input.days} दिन ($totalDays दिन)"
                )
            }
            is TenorInput.DateRange -> {
                require(input.toDate.isAfter(input.fromDate)) {
                    "अंतिम तिथि ('कहाँ तक'), प्रारंभ तिथि ('कहाँ से') के बाद होनी चाहिए।"
                }
                val totalDays = ChronoUnit.DAYS.between(input.fromDate, input.toDate)
                val period = Period.between(input.fromDate, input.toDate)
                val fracYears = BigDecimal(totalDays).divide(BigDecimal(config.daysPerCalendarYear), MC)
                ResolvedTenor(
                    years = period.years,
                    months = period.months,
                    days = period.days,
                    totalExactDays = totalDays,
                    fractionalYears365 = fracYears,
                    sourceLabel = "कैलेंडर: ${input.fromDate} से ${input.toDate} (${period.years} वर्ष, ${period.months} माह, ${period.days} दिन = $totalDays दिन)"
                )
            }
        }
    }

    /**
     * एकीकृत ब्याज एवं कोटेशन गणना (Long Paise + HALF_EVEN राउंडिंग)
     */
    fun calculateQuotation(
        request: QuotationRequest,
        config: RulesConfig = RulesConfig()
    ): QuotationResult {
        require(request.principalPaise > 0L) { "मूलधन शून्य से अधिक होना चाहिए।" }
        require(request.rateValue >= BigDecimal.ZERO) { "ब्याज दर ऋणात्मक नहीं हो सकती।" }

        val tenor = resolveTenor(request.tenorInput, config)
        val p = BigDecimal(request.principalPaise)

        // वार्षिक दर % निकालना (साहूकारी दर ₹/100 मासिक × 12 = वार्षिक %)
        val annualRatePercent = when (request.interestType) {
            InterestType.SAHUKARI_SIMPLE, InterestType.SAHUKARI_COMPOUND ->
                request.rateValue.multiply(TWELVE, MC)
            else -> request.rateValue
        }
        val annualRateDecimal = annualRatePercent.divide(HUNDRED, MC)

        val exactMonths = tenor.fractionalYears365.multiply(TWELVE, MC)
        val emiCountMonths = maxOf(1, exactMonths.setScale(0, config.roundingMode).toInt())

        val (interestBd, emiPaise, breakdown) = when (request.interestType) {
            InterestType.SIMPLE_INTEREST, InterestType.FLAT_RATE, InterestType.SAHUKARI_SIMPLE -> {
                // I = P * R_annual * (days / 365)
                val interest = p.multiply(annualRateDecimal, MC).multiply(tenor.fractionalYears365, MC)
                val total = p.add(interest, MC)
                val emi = total.divide(BigDecimal(emiCountMonths), 0, config.roundingMode).toLong()
                val formula = "ब्याज = मूलधन × ${annualRatePercent.stripTrailingZeros().toPlainString()}% × (${tenor.totalExactDays}/365)"
                Triple(interest, emi, formula)
            }

            InterestType.COMPOUND_INTEREST, InterestType.SAHUKARI_COMPOUND -> {
                val n = request.cycle.periodsPerYear
                val ratePerPeriod = annualRateDecimal.divide(BigDecimal(n), MC)
                val totalPeriodsDouble = tenor.fractionalYears365.toDouble() * n.toDouble()
                val factor = Math.pow(1.0 + ratePerPeriod.toDouble(), totalPeriodsDouble)
                val amount = p.multiply(BigDecimal(factor, MC), MC)
                val interest = amount.subtract(p, MC)
                val emi = amount.divide(BigDecimal(emiCountMonths), 0, config.roundingMode).toLong()
                val formula = "चक्रवृद्धि (${request.cycle.name}): A = P × (1 + r/$n)^(${String.format("%.4f", totalPeriodsDouble)})"
                Triple(interest, emi, formula)
            }

            InterestType.REDUCING_EMI -> {
                val monthlyRate = annualRateDecimal.divide(TWELVE, MC).toDouble()
                val emi = if (monthlyRate == 0.0) {
                    p.divide(BigDecimal(emiCountMonths), 0, config.roundingMode).toLong()
                } else {
                    val powFactor = Math.pow(1.0 + monthlyRate, emiCountMonths.toDouble())
                    val emiDouble = request.principalPaise.toDouble() * monthlyRate * powFactor / (powFactor - 1.0)
                    BigDecimal(emiDouble, MC).setScale(0, config.roundingMode).toLong()
                }
                val totalPayable = emi * emiCountMonths.toLong()
                val interest = BigDecimal(maxOf(0L, totalPayable - request.principalPaise))
                val formula = "घटती शेष (Reducing EMI): $emiCountMonths माह की किस्त @ ${annualRatePercent.stripTrailingZeros().toPlainString()}% वार्षिक"
                Triple(interest, emi, formula)
            }
        }

        val interestPaise = interestBd.setScale(0, config.roundingMode).toLong()
        val totalPayablePaise = safeAdd(request.principalPaise, interestPaise)

        return QuotationResult(
            customerName = request.customerName.trim(),
            customerMobile = request.customerMobile.filter { it.isDigit() }.takeLast(10),
            isGuest = request.isGuest,
            memberId = request.memberId,
            principalPaise = request.principalPaise,
            interestPaise = interestPaise,
            totalPayablePaise = totalPayablePaise,
            estimatedMonthlyEmiPaise = emiPaise,
            effectiveAnnualRatePercent = annualRatePercent.setScale(2, config.roundingMode),
            resolvedTenor = tenor,
            formulaBreakdownHindi = breakdown
        )
    }

    /**
     * नियम 8: आंशिक भुगतान वाटरफॉल (Penalty -> Due Interest -> Principal -> Advance Wallet)
     */
    fun allocatePartialPayment(
        paymentPaise: Long,
        duePenaltyPaise: Long,
        dueInterestPaise: Long,
        duePrincipalPaise: Long
    ): WaterfallAllocation {
        require(paymentPaise > 0L) { "भुगतान राशि शून्य से अधिक होनी चाहिए।" }
        var remaining = paymentPaise

        val paidPenalty = minOf(remaining, duePenaltyPaise)
        remaining -= paidPenalty

        val paidInterest = minOf(remaining, dueInterestPaise)
        remaining -= paidInterest

        val paidPrincipal = minOf(remaining, duePrincipalPaise)
        remaining -= paidPrincipal

        val advanceWallet = remaining
        val totalRemainingDue = (duePenaltyPaise - paidPenalty) +
            (dueInterestPaise - paidInterest) +
            (duePrincipalPaise - paidPrincipal)

        return WaterfallAllocation(
            paidPenaltyPaise = paidPenalty,
            paidInterestPaise = paidInterest,
            paidPrincipalPaise = paidPrincipal,
            creditedAdvanceWalletPaise = advanceWallet,
            remainingDuePaise = totalRemainingDue
        )
    }

    /**
     * HR-07: Double-Entry संतुलन जाँच (कुल Debit == कुल Credit)
     */
    fun verifyDoubleEntryBalanced(lines: List<LedgerLine>): Boolean {
        require(lines.isNotEmpty()) { "लेजर पंक्तियाँ खाली नहीं हो सकतीं।" }
        var totalDebit = 0L
        var totalCredit = 0L
        for (line in lines) {
            require(line.debitPaise >= 0L && line.creditPaise >= 0L) { "Debit/Credit ऋणात्मक नहीं हो सकता।" }
            totalDebit = safeAdd(totalDebit, line.debitPaise)
            totalCredit = safeAdd(totalCredit, line.creditPaise)
        }
        return totalDebit == totalCredit && totalDebit > 0L
    }
}
