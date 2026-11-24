package `in`.bachatgroup.data.identity

import `in`.bachatgroup.data.security.SecurityCore
import `in`.bachatgroup.finance.FinanceSuiteEngine
import `in`.bachatgroup.finance.RulesConfig
import java.security.SecureRandom
import java.util.Base64

/**
 * बचत समूह प्रणाली — Step 3: सदस्य व एजेंट पहचान, Zero-PII QR, हिंदी+हिंग्लिश Phonetic Search,
 * 40–60 KB फोटो नीति, 100 सदस्य सीमा, एजेंट जीवनचक्र, Group Transfer व Exit/Nominee Settlement।
 */
enum class AgentStatus { ACTIVE, INACTIVE, BLOCKED, REMOVED }
enum class TransferStatus { PENDING, APPROVED, REJECTED }

data class QrTokenPair(
    val rawTokenForPrintedCard: String,   // केवल प्रिंटेड QR कार्ड के लिए (DB में कभी सेव नहीं)
    val sha256HashForDatabase: String     // DB में केवल यही Hash सेव होगा (HR-05)
)

data class PhotoMetaValidation(
    val valid: Boolean,
    val errorHindi: String? = null
)

data class AgentLifecycleResult(
    val allowed: Boolean,
    val newStatus: AgentStatus,
    val messageHindi: String
)

data class ExitSettlementResult(
    val totalCreditsPaise: Long,
    val totalDeductionsPaise: Long,
    val netPayableToMemberOrNomineePaise: Long,
    val netRecoverableFromMemberPaise: Long
)

object Step3IdentityAndLifecycleEngine {
    private val rng = SecureRandom()

    private val DEVANAGARI_TO_ROMAN = mapOf(
        'अ' to "a", 'आ' to "a", 'इ' to "i", 'ई' to "i", 'उ' to "u", 'ऊ' to "u",
        'ए' to "e", 'ऐ' to "ai", 'ओ' to "o", 'औ' to "au",
        'क' to "k", 'ख' to "kh", 'ग' to "g", 'घ' to "gh", 'च' to "ch", 'छ' to "chh",
        'ज' to "j", 'झ' to "jh", 'ट' to "t", 'ठ' to "th", 'ड' to "d", 'ढ' to "dh",
        'त' to "t", 'थ' to "th", 'द' to "d", 'ध' to "dh", 'न' to "n", 'ण' to "n",
        'प' to "p", 'फ' to "ph", 'ब' to "b", 'भ' to "bh", 'म' to "m",
        'य' to "y", 'र' to "r", 'ल' to "l", 'व' to "v", 'श' to "sh", 'ष' to "sh",
        'स' to "s", 'ह' to "h", 'ा' to "a", 'ि' to "i", 'ी' to "i", 'ु' to "u",
        'ू' to "u", 'े' to "e", 'ै' to "ai", 'ो' to "o", 'ौ' to "au", 'ं' to "n", '्' to ""
    )

    fun generateMemberId(seqNo: Long): String = "MEM-%05d".format(seqNo)
    fun generateAgentCode(seqNo: Long): String = "AGT-%04d".format(seqNo)

    /**
     * HR-05 & F-039/F-040/F-143: Zero-PII Random QR Token (कोई नाम/मोबाइल/बैलेंस नहीं)
     */
    fun generateZeroPiiQrToken(prefix: String = "BSQR"): QrTokenPair {
        val bytes = ByteArray(24)
        rng.nextBytes(bytes)
        val raw = "${prefix}_${Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)}"
        val hash = SecurityCore.sha256Hex(raw)
        return QrTokenPair(rawTokenForPrintedCard = raw, sha256HashForDatabase = hash)
    }

    /**
     * F-121: हिंदी + हिंग्लिश दोहरी खोज (Phonetic Search: "रामलाल" == "ramlal" == "Ram")
     */
    fun toPhoneticKey(name: String): String {
        val sb = StringBuilder()
        for (ch in name.trim().lowercase()) {
            when {
                ch in DEVANAGARI_TO_ROMAN -> sb.append(DEVANAGARI_TO_ROMAN[ch])
                ch in 'a'..'z' || ch in '0'..'9' -> sb.append(ch)
            }
        }
        return sb.toString().replace(Regex("a+"), "a")
    }

    /**
     * F-033: Minimal Photo Storage Optimization (≤ 60 KB लक्ष्य, कठोर सीमा ≤ 100 KB, 512x512, EXIF स्ट्रिप)
     */
    fun validateCompressedPhoto(
        sizeBytes: Long,
        widthPx: Int,
        heightPx: Int,
        exifStripped: Boolean,
        rawCacheDeleted: Boolean
    ): PhotoMetaValidation {
        if (widthPx > 512 || heightPx > 512) {
            return PhotoMetaValidation(false, "फोटो का आकार अधिकतम 512×512 px होना चाहिए।")
        }
        if (sizeBytes > 100 * 1024L) {
            return PhotoMetaValidation(false, "फोटो 100 KB की कठोर सीमा से बड़ी है (लक्ष्य 40–60 KB WebP)।")
        }
        if (!exifStripped) {
            return PhotoMetaValidation(false, "सुरक्षा नियम: फोटो से GPS/EXIF मेटाडेटा हटाना अनिवार्य है।")
        }
        if (!rawCacheDeleted) {
            return PhotoMetaValidation(false, "अस्थायी Raw कैमरा फ़ाइल डिलीट करना अनिवार्य है।")
        }
        return PhotoMetaValidation(true, null)
    }

    /**
     * HR-06 & F-044: एक समूह में अधिकतम 100 सक्रिय सदस्यों की सख्त सीमा
     */
    fun canAddMemberToGroup(activeCountInGroup: Int, config: RulesConfig = RulesConfig()): Pair<Boolean, String?> {
        return if (activeCountInGroup >= config.maxActiveMembersPerGroup) {
            false to "HR-06 अवरोध: इस समूह में पहले से ${config.maxActiveMembersPerGroup} सक्रिय सदस्य पूरे हैं।"
        } else {
            true to null
        }
    }

    /**
     * F-144: एजेंट जीवनचक्र (ब्लॉक करने पर कारण अनिवार्य; Soft Remove केवल Cash-in-Hand == 0 पर)
     */
    fun transitionAgentStatus(
        targetStatus: AgentStatus,
        currentCashInHandPaise: Long,
        reason: String?
    ): AgentLifecycleResult {
        return when (targetStatus) {
            AgentStatus.BLOCKED -> {
                if (reason.isNullOrBlank()) {
                    AgentLifecycleResult(false, AgentStatus.ACTIVE, "एजेंट को ब्लॉक करने के लिए कारण लिखना अनिवार्य है।")
                } else {
                    AgentLifecycleResult(true, AgentStatus.BLOCKED, "एजेंट ब्लॉक किया गया (लॉगिन, सिंक व वसूली बंद)।")
                }
            }
            AgentStatus.REMOVED -> {
                if (currentCashInHandPaise != 0L) {
                    AgentLifecycleResult(
                        false,
                        AgentStatus.ACTIVE,
                        "अवरोध: एजेंट के पास ₹${currentCashInHandPaise / 100.0} नकद बकाया है। Cash = 0 होने पर ही Soft Remove संभव है।"
                    )
                } else {
                    AgentLifecycleResult(true, AgentStatus.REMOVED, "एजेंट Soft Remove हुआ (पूरा पुराना इतिहास सुरक्षित)।")
                }
            }
            else -> AgentLifecycleResult(true, targetStatus, "एजेंट की स्थिति $targetStatus अपडेट हुई।")
        }
    }

    /**
     * F-114: सदस्य का अंतिम निपटान (Full & Final Exit + Nominee Settlement)
     */
    fun calculateExitSettlement(
        totalSavingsPaise: Long,
        accruedInterestPaise: Long,
        approvedDividendPaise: Long,
        advanceWalletPaise: Long,
        outstandingLoanPrincipalPaise: Long,
        dueLoanInterestPaise: Long,
        duePenaltyPaise: Long
    ): ExitSettlementResult {
        val credits = FinanceSuiteEngine.safeAdd(
            FinanceSuiteEngine.safeAdd(totalSavingsPaise, accruedInterestPaise),
            FinanceSuiteEngine.safeAdd(approvedDividendPaise, advanceWalletPaise)
        )
        val deductions = FinanceSuiteEngine.safeAdd(
            FinanceSuiteEngine.safeAdd(outstandingLoanPrincipalPaise, dueLoanInterestPaise),
            duePenaltyPaise
        )
        return if (credits >= deductions) {
            ExitSettlementResult(credits, deductions, credits - deductions, 0L)
        } else {
            ExitSettlementResult(credits, deductions, 0L, deductions - credits)
        }
    }
}
