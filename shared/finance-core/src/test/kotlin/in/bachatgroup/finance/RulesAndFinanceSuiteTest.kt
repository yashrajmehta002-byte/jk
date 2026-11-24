package `in`.bachatgroup.finance

import java.math.BigDecimal
import java.time.LocalDate
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

/**
 * Step 0: RulesConfig और FinanceSuiteEngine के स्वचालित यूनिट टेस्ट (JUnit 5)
 * Android Studio / GitHub Actions में चलाने हेतु: ./gradlew :shared:finance-core:test
 */
class RulesAndFinanceSuiteTest {

    private val config = RulesConfig()

    @Test
    fun `T01_non_negotiable_rules_are_locked`() {
        assertFalse(config.cloudSyncEnabled)
        assertFalse(config.allowAadhaarOrMemberBiometric)
        assertFalse(config.allowHardDeleteOrEditPostedLedger)
        assertEquals(365, config.daysPerCalendarYear)
        assertEquals(100, config.maxActiveMembersPerGroup)
        assertTrue(config.markOfflineAgentReceiptProvisional)
        assertFalse(config.agentCanDisburseLoan)
    }

    @Test
    fun `T02_manual_12_months_equals_exact_365_days`() {
        val tenor12m = FinanceSuiteEngine.resolveTenor(TenorInput.Manual(0, 12, 0), config)
        val tenor1y = FinanceSuiteEngine.resolveTenor(TenorInput.Manual(1, 0, 0), config)
        assertEquals(365L, tenor12m.totalExactDays)
        assertEquals(365L, tenor1y.totalExactDays)
    }

    @Test
    fun `T03_calendar_date_range_from_to_calculation`() {
        val tenor = FinanceSuiteEngine.resolveTenor(
            TenorInput.DateRange(LocalDate.of(2026, 1, 1), LocalDate.of(2027, 1, 1)),
            config
        )
        assertEquals(365L, tenor.totalExactDays)
        assertEquals(1, tenor.years)
        assertEquals(0, tenor.months)
        assertEquals(0, tenor.days)
    }

    @Test
    fun `T04_sahukari_2_rupee_per_100_on_10000_for_1_year_equals_2400`() {
        val res = FinanceSuiteEngine.calculateQuotation(
            QuotationRequest(
                principalPaise = 1_000_000L, // ₹10,000
                interestType = InterestType.SAHUKARI_SIMPLE,
                rateValue = BigDecimal("2.00"), // ₹2 सैकड़ा मासिक = 24% वार्षिक
                tenorInput = TenorInput.Manual(1, 0, 0)
            ),
            config
        )
        assertEquals(240_000L, res.interestPaise) // ₹2,400.00
        assertEquals(1_240_000L, res.totalPayablePaise) // ₹12,400.00
    }

    @Test
    fun `T05_partial_payment_waterfall_penalty_then_interest_then_principal_then_wallet`() {
        val alloc = FinanceSuiteEngine.allocatePartialPayment(
            paymentPaise = 50_000L,      // ₹500 भुगतान
            duePenaltyPaise = 5_000L,    // ₹50 जुर्माना
            dueInterestPaise = 20_000L,  // ₹200 ब्याज
            duePrincipalPaise = 100_000L // ₹1,000 मूलधन
        )
        assertEquals(5_000L, alloc.paidPenaltyPaise)
        assertEquals(20_000L, alloc.paidInterestPaise)
        assertEquals(25_000L, alloc.paidPrincipalPaise)
        assertEquals(0L, alloc.creditedAdvanceWalletPaise)
        assertEquals(75_000L, alloc.remainingDuePaise)
    }

    @Test
    fun `T06_offline_withdrawal_policy_guard_blocks_provisional_and_over_ratio`() {
        val provisionalCheck = PolicyGuard.checkAgentOfflineWithdrawal(
            config, memberIsAdminApproved = false, requestedPaise = 50_000L,
            verifiedSavingsPaise = 500_000L, alreadyWithdrawnTodayPaise = 0L, syncAgeDays = 0
        )
        assertFalse(provisionalCheck.allowed)

        val validCheck = PolicyGuard.checkAgentOfflineWithdrawal(
            config, memberIsAdminApproved = true, requestedPaise = 150_000L,
            verifiedSavingsPaise = 500_000L, alreadyWithdrawnTodayPaise = 0L, syncAgeDays = 1
        )
        assertTrue(validCheck.allowed)
    }

    @Test
    fun `T07_double_entry_balance_check`() {
        val balanced = FinanceSuiteEngine.verifyDoubleEntryBalanced(
            listOf(
                LedgerLine("AGENT_CASH_IN_HAND", debitPaise = 100_000L, creditPaise = 0L),
                LedgerLine("MEMBER_SAVINGS_LIABILITY", debitPaise = 0L, creditPaise = 100_000L)
            )
        )
        assertTrue(balanced)
    }
}
