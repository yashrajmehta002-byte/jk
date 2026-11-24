package `in`.bachatgroup.printing

import `in`.bachatgroup.data.security.SecurityCore
import `in`.bachatgroup.finance.QuotationSlipModule

/**
 * बचत समूह प्रणाली — Step 7: प्रिंट, 6-अंक HMAC रसीद, DUPLICATE COPY मोहर,
 * ESC/POS Raster (Bitmap हिंदी), साप्ताहिक रिमाइंडर, वॉयस अलर्ट व Hindi↔English स्विचर (`:shared:printing`)।
 */
enum class AppLanguage(val code: String) { HI("hi"), EN("en") }
enum class ThermalPaperWidth(val widthPx: Int, val bytesPerRow: Int) {
    MM_58(384, 48),
    MM_80(576, 72)
}
enum class VoiceEventType {
    SAVINGS_DEPOSIT, WITHDRAWAL, LOAN_DISBURSEMENT, INSTALLMENT_PAID, HANDOVER_APPROVED, EXIT_SETTLEMENT
}

data class ReceiptPrintPayload(
    val receiptNo: String,
    val transactionId: String,
    val isCommittedInDb: Boolean,
    val memberName: String,
    val amountPaise: Long,
    val isProvisionalOfflineAgent: Boolean,
    val previousPrintCount: Int,
    val language: AppLanguage
)

data class RenderedReceiptDocument(
    val receiptNo: String,
    val sixDigitHmac: String,
    val isDuplicateCopy: Boolean,
    val headerBanner: String,
    val bodyLines: List<String>
)

data class OverdueMemberReminder(
    val memberId: String,
    val memberName: String,
    val mobile10Digit: String,
    val overduePaise: Long,
    val dueDateFormatted: String
)

object Step7PrintingVoiceAndLocaleEngine {

    /** F-119: ऑफलाइन सत्यापन योग्य 6-अंक HMAC कोड (000000–999999) */
    fun generate6DigitReceiptHmac(
        secretKeyBytes: ByteArray,
        receiptNo: String,
        transactionId: String,
        amountPaise: Long
    ): String {
        val hex = SecurityCore.hmacSha256Hex(secretKeyBytes, "$receiptNo|$transactionId|$amountPaise")
        val numeric = hex.take(8).toLong(16) % 1_000_000L
        return "%06d".format(numeric)
    }

    fun verify6DigitReceiptHmac(
        secretKeyBytes: ByteArray,
        receiptNo: String,
        transactionId: String,
        amountPaise: Long,
        printedCode: String
    ): Boolean {
        return generate6DigitReceiptHmac(secretKeyBytes, receiptNo, transactionId, amountPaise) == printedCode.trim()
    }

    /** HR-04 & F-119: केवल COMMIT हुए लेन-देन की रसीद + print_count >= 1 पर DUPLICATE COPY मोहर */
    fun buildReceiptDocument(
        payload: ReceiptPrintPayload,
        secretKeyBytes: ByteArray
    ): RenderedReceiptDocument {
        check(payload.isCommittedInDb) {
            "HR-04 अवरोध: डेटाबेस COMMIT हुए बिना रसीद संख्या या प्रिंट जारी नहीं हो सकता।"
        }
        val hmac6 = generate6DigitReceiptHmac(secretKeyBytes, payload.receiptNo, payload.transactionId, payload.amountPaise)
        val isDup = payload.previousPrintCount >= 1
        val amtStr = QuotationSlipModule.formatIndianRupees(payload.amountPaise)

        val banner = buildString {
            if (isDup) append(if (payload.language == AppLanguage.HI) "[प्रतिलिपि / DUPLICATE COPY] " else "[DUPLICATE COPY] ")
            if (payload.isProvisionalOfflineAgent) {
                append(if (payload.language == AppLanguage.HI) "अस्थायी रसीद (Admin सत्यापन अधीन)" else "PROVISIONAL RECEIPT (Pending Admin Verification)")
            } else {
                append(if (payload.language == AppLanguage.HI) "अधिकृत रसीद" else "OFFICIAL RECEIPT")
            }
        }

        val lines = if (payload.language == AppLanguage.HI) {
            listOf(
                banner,
                "रसीद नं.: ${payload.receiptNo}",
                "सदस्य: ${payload.memberName}",
                "राशि: $amtStr",
                "सत्यापन कोड (HMAC): $hmac6"
            )
        } else {
            listOf(
                banner,
                "Receipt No: ${payload.receiptNo}",
                "Member: ${payload.memberName}",
                "Amount: $amtStr",
                "Verify Code (HMAC): $hmac6"
            )
        }
        return RenderedReceiptDocument(payload.receiptNo, hmac6, isDup, banner, lines)
    }

    /** F-099: ESC/POS GS v 0 Raster Bitmap Header (सस्ते 58mm/80mm प्रिंटर पर साफ हिंदी हेतु) */
    fun buildEscPosRasterHeader(paper: ThermalPaperWidth, heightPx: Int): ByteArray {
        require(heightPx > 0) { "Bitmap ऊँचाई शून्य से अधिक होनी चाहिए।" }
        val xL = (paper.bytesPerRow and 0xFF).toByte()
        val xH = ((paper.bytesPerRow shr 8) and 0xFF).toByte()
        val yL = (heightPx and 0xFF).toByte()
        val yH = ((heightPx shr 8) and 0xFF).toByte()
        // GS v 0 m xL xH yL yH
        return byteArrayOf(0x1D, 0x76, 0x30, 0x00, xL, xH, yL, yH)
    }

    /** F-102 & F-157: वास्तविक समय वॉयस अलर्ट (केवल COMMIT के बाद, ON/OFF टॉगल व भाषा के अनुसार) */
    fun buildVoiceConfirmationText(
        isCommittedInDb: Boolean,
        voiceMasterEnabled: Boolean,
        eventType: VoiceEventType,
        amountPaise: Long,
        language: AppLanguage
    ): String? {
        if (!isCommittedInDb || !voiceMasterEnabled) return null
        val rupees = amountPaise / 100L
        return if (language == AppLanguage.HI) {
            when (eventType) {
                VoiceEventType.SAVINGS_DEPOSIT -> "बचत जमा $rupees रुपये सफलतापूर्वक दर्ज हुए।"
                VoiceEventType.WITHDRAWAL -> "निकासी $rupees रुपये दर्ज की गई।"
                VoiceEventType.LOAN_DISBURSEMENT -> "ऋण वितरण $rupees रुपये स्वीकृत हुआ।"
                VoiceEventType.INSTALLMENT_PAID -> "किस्त भुगतान $rupees रुपये प्राप्त हुए।"
                VoiceEventType.HANDOVER_APPROVED -> "नकद हैंडओवर $rupees रुपये Admin द्वारा स्वीकृत हुआ।"
                VoiceEventType.EXIT_SETTLEMENT -> "अंतिम निपटान $rupees रुपये पूर्ण हुआ।"
            }
        } else {
            when (eventType) {
                VoiceEventType.SAVINGS_DEPOSIT -> "Savings deposit of $rupees rupees recorded successfully."
                VoiceEventType.WITHDRAWAL -> "Withdrawal of $rupees rupees recorded."
                VoiceEventType.LOAN_DISBURSEMENT -> "Loan disbursement of $rupees rupees approved."
                VoiceEventType.INSTALLMENT_PAID -> "Installment payment of $rupees rupees received."
                VoiceEventType.HANDOVER_APPROVED -> "Cash handover of $rupees rupees approved by Admin."
                VoiceEventType.EXIT_SETTLEMENT -> "Final exit settlement of $rupees rupees completed."
            }
        }
    }

    /** F-149: साप्ताहिक/बैठक-पूर्व रिमाइंडर बैच कतार (केवल बकाया > 0 सदस्य) */
    fun buildOverdueReminderBatch(
        candidates: List<OverdueMemberReminder>,
        language: AppLanguage
    ): List<Pair<String, String>> {
        return candidates.filter { it.overduePaise > 0L && it.mobile10Digit.length == 10 }.map { m ->
            val amt = QuotationSlipModule.formatIndianRupees(m.overduePaise)
            val msg = if (language == AppLanguage.HI) {
                "नमस्ते ${m.memberName}, आपकी किस्त राशि $amt दिनांक ${m.dueDateFormatted} से बकाया है। कृपया समय पर जमा करें।"
            } else {
                "Hello ${m.memberName}, your installment of $amt is due since ${m.dueDateFormatted}. Please pay on time."
            }
            val waUrl = "https://wa.me/91${m.mobile10Digit}?text=" + java.net.URLEncoder.encode(msg, "UTF-8")
            m.memberId to waUrl
        }
    }
}
