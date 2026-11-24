package `in`.bachatgroup.finance

import java.math.RoundingMode

/**
 * बचत समूह प्रणाली — Step 0: लॉक किए गए व्यापारिक एवं तकनीकी नियम (RulesConfig)
 * आधार: मास्टर रोडमैप (V1.1 — 22-11-2026), खंड 1, 9.4 और 10.4
 *
 * ध्यान दें: सभी मौद्रिक मान केवल Long Paise (₹1 = 100L) में हैं। Float/Double प्रतिबंधित है।
 */
enum class MemberActivationMode {
    /** Agent द्वारा जोड़ा गया सदस्य तुरंत PROVISIONAL (बचत जमा संभव; ऋण/निकासी केवल Admin मंजूरी के बाद) */
    PROVISIONAL_UNTIL_ADMIN_APPROVAL,
    ACTIVE_IMMEDIATELY
}

enum class DividendFormula {
    /** शुद्ध लाभ (ब्याज आय - खर्च - रिज़र्व) को सदस्य की औसत बचत के अनुपात (Pro-rata) में बाँटना */
    PRO_RATA_AVERAGE_SAVINGS,
    EQUAL_PER_ACTIVE_MEMBER
}

enum class AgentTransferBonusRule {
    /** बदलाव की तारीख तक की सत्यापित वसूली पुराने एजेंट को, उसके बाद की नए एजेंट को */
    COLLECTION_DATE_CUTOFF
}

enum class WaterfallComponent {
    PENALTY,
    DUE_INTEREST,
    PRINCIPAL,
    ADVANCE_WALLET
}

data class RulesConfig(
    // --- 12 पक्के नियम (Non-Negotiable Architecture Rules) ---
    val cloudSyncEnabled: Boolean = false,                  // HR-01: V1.0 में Cloud पूरी तरह बंद
    val allowAadhaarOrMemberBiometric: Boolean = false,     // HR-02: आधार व सदस्य बायोमेट्रिक प्रतिबंधित
    val allowHardDeleteOrEditPostedLedger: Boolean = false, // HR-03: केवल Reversal/Contra Entry
    val requireCommitBeforeReceipt: Boolean = true,         // HR-04: COMMIT के बाद ही रसीद + 6-अंक HMAC
    val zeroPiiQrOnly: Boolean = true,                      // HR-05: QR में केवल Random Token (DB में SHA-256)
    val maxActiveMembersPerGroup: Int = 100,                // HR-06: अधिकतम 100 सक्रिय सदस्य प्रति समूह
    val maxActiveMembershipsPerMember: Int = 1,             // HR-06: 1 समय में केवल 1 सक्रिय Membership
    val daysPerCalendarYear: Int = 365,                     // खंड 10.4: 12 माह / 1 वर्ष = सटीक 365 दिन (360 नहीं)
    val requireAdminApprovalForRealDashboard: Boolean = true, // HR-12: Admin Approval के बाद ही वास्तविक वसूली
    val markOfflineAgentReceiptProvisional: Boolean = true, // खंड 10.3: ऑफलाइन एजेंट रसीद पर "अस्थायी रसीद"

    // --- चरण 0 के 8 लॉक किए गए व्यापारिक नियम (खंड 9.4) ---
    // नियम 1: नया सदस्य सक्रियण
    val memberActivationMode: MemberActivationMode = MemberActivationMode.PROVISIONAL_UNTIL_ADMIN_APPROVAL,

    // नियम 2: एजेंट की दैनिक निकासी सीमा (डिफ़ॉल्ट ₹2,000 = 200_000L पैसे; Admin सेटिंग से ₹0 या अन्य बदली जा सकती है)
    val agentDailyWithdrawalLimitPaise: Long = 200_000L,

    // एजेंट अधिकतम Cash-in-Hand सीमा (डिफ़ॉल्ट ₹30,000 = 3_000_000L पैसे)
    val agentMaxCashInHandPaise: Long = 3_000_000L,

    // नियम 3: एजेंट नया ऋण नहीं बाँट सकता — केवल आवेदन व कोटेशन भेज सकता है
    val agentCanDisburseLoan: Boolean = false,

    // नियम 4: ऑफलाइन निकासी अनुमति (सदस्य की सत्यापित बचत का ≤ 50% और सिंक ≤ 2 दिन पुराना)
    val maxOfflineWithdrawalSavingsRatioPercent: Int = 50,
    val maxOfflineSyncAgeDaysForWithdrawal: Int = 2,
    val syncAgeWarningDays: Int = 3,
    val syncAgeAutoLockDays: Int = 7,

    // नियम 5: समूह लाभांश सूत्र व रिज़र्व फंड प्रतिशत
    val dividendFormula: DividendFormula = DividendFormula.PRO_RATA_AVERAGE_SAVINGS,
    val defaultReserveFundPercent: Int = 10,

    // नियम 6: एजेंट बदलने पर बोनस नियम
    val agentTransferBonusRule: AgentTransferBonusRule = AgentTransferBonusRule.COLLECTION_DATE_CUTOFF,

    // नियम 7: दशमलव पैसे की राउंडिंग (HALF_EVEN / Banker's Rounding; अंतर ROUNDING_ADJUSTMENT खाते में)
    val roundingMode: RoundingMode = RoundingMode.HALF_EVEN,
    val roundingAdjustmentAccountCode: String = "ROUNDING_ADJUSTMENT",

    // नियम 8: आंशिक किस्त (कम भुगतान) काटने का क्रम (Payment Waterfall)
    val partialPaymentWaterfall: List<WaterfallComponent> = listOf(
        WaterfallComponent.PENALTY,
        WaterfallComponent.DUE_INTEREST,
        WaterfallComponent.PRINCIPAL,
        WaterfallComponent.ADVANCE_WALLET
    ),

    // डिफ़ॉल्ट साहूकारी ब्याज दर: ₹2 प्रति सैकड़ा मासिक = 24.00% वार्षिक (2400 basis points)
    val defaultSahukariMonthlyPer100Paise: Long = 200L, // ₹2.00 प्रति ₹100 मासिक
    val defaultAnnualRateBps: Int = 2400                // 24.00% वार्षिक
) {
    init {
        require(!cloudSyncEnabled) { "HR-01 उल्लंघन: V1.0 में Cloud Sync बंद रहना अनिवार्य है।" }
        require(!allowAadhaarOrMemberBiometric) { "HR-02 उल्लंघन: आधार/सदस्य बायोमेट्रिक प्रतिबंधित है।" }
        require(!allowHardDeleteOrEditPostedLedger) { "HR-03 उल्लंघन: पोस्ट हुई लेजर एंट्री डिलीट/एडिट नहीं हो सकती।" }
        require(daysPerCalendarYear == 365) { "खंड 10.4 उल्लंघन: 1 वर्ष सटीक 365 दिन का होना चाहिए।" }
        require(maxActiveMembersPerGroup in 1..100) { "HR-06 उल्लंघन: समूह सीमा 1 से 100 के बीच होनी चाहिए।" }
        require(agentDailyWithdrawalLimitPaise >= 0L) { "निकासी सीमा ऋणात्मक नहीं हो सकती।" }
        require(agentMaxCashInHandPaise > 0L) { "Cash-in-Hand सीमा शून्य से अधिक होनी चाहिए।" }
        require(partialPaymentWaterfall.first() == WaterfallComponent.PENALTY) {
            "नियम 8 उल्लंघन: आंशिक भुगतान में सबसे पहले जुर्माना (PENALTY) कटना चाहिए।"
        }
    }
}

data class WithdrawalEligibilityResult(
    val allowed: Boolean,
    val requiresAdminApproval: Boolean,
    val reasonHindi: String
)

object PolicyGuard {
    /**
     * नियम 2 और नियम 4 के अनुसार एजेंट द्वारा ऑफलाइन निकासी की जाँच
     */
    fun checkAgentOfflineWithdrawal(
        config: RulesConfig,
        memberIsAdminApproved: Boolean,
        requestedPaise: Long,
        verifiedSavingsPaise: Long,
        alreadyWithdrawnTodayPaise: Long,
        syncAgeDays: Int
    ): WithdrawalEligibilityResult {
        if (!memberIsAdminApproved) {
            return WithdrawalEligibilityResult(
                allowed = false,
                requiresAdminApproval = true,
                reasonHindi = "नियम 1: PROVISIONAL सदस्य की निकासी Admin मंजूरी के बिना नहीं हो सकती।"
            )
        }
        if (requestedPaise <= 0L) {
            return WithdrawalEligibilityResult(false, false, "निकासी राशि शून्य से अधिक होनी चाहिए।")
        }
        if (config.agentDailyWithdrawalLimitPaise == 0L) {
            return WithdrawalEligibilityResult(
                allowed = false,
                requiresAdminApproval = true,
                reasonHindi = "एजेंट निकासी सीमा ₹0 है; निकासी केवल Admin द्वारा होगी।"
            )
        }
        if (alreadyWithdrawnTodayPaise + requestedPaise > config.agentDailyWithdrawalLimitPaise) {
            return WithdrawalEligibilityResult(
                allowed = false,
                requiresAdminApproval = true,
                reasonHindi = "दैनिक एजेंट निकासी सीमा पार हो गई है; Admin मंजूरी आवश्यक है।"
            )
        }
        val maxAllowedByRatio = (verifiedSavingsPaise * config.maxOfflineWithdrawalSavingsRatioPercent) / 100L
        if (requestedPaise > maxAllowedByRatio) {
            return WithdrawalEligibilityResult(
                allowed = false,
                requiresAdminApproval = true,
                reasonHindi = "नियम 4: ऑफलाइन निकासी सत्यापित बचत के ${config.maxOfflineWithdrawalSavingsRatioPercent}% से अधिक नहीं हो सकती।"
            )
        }
        if (syncAgeDays > config.maxOfflineSyncAgeDaysForWithdrawal) {
            return WithdrawalEligibilityResult(
                allowed = false,
                requiresAdminApproval = true,
                reasonHindi = "नियम 4: फोन का सिंक ${config.maxOfflineSyncAgeDaysForWithdrawal} दिन से अधिक पुराना है; पहले Admin से सिंक करें।"
            )
        }
        return WithdrawalEligibilityResult(
            allowed = true,
            requiresAdminApproval = false,
            reasonHindi = "अनुमति स्वीकृत (अस्थायी रसीद जारी करें)।"
        )
    }
}
