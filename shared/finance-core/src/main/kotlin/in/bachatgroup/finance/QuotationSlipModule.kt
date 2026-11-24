package `in`.bachatgroup.finance

import java.math.BigDecimal
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.format.DateTimeFormatter

/**
 * बचत समूह प्रणाली — P-01 पूर्ण सुधार: संपूर्ण कोटेशन पर्ची (Quotation Slip) व सख्त हिंदी वैलिडेशन
 * शामिल बुनियादी फ़ील्ड्स:
 * - यूनिक कोटेशन नंबर, दिनांक, संस्था/समूह नाम, एजेंट नाम/कोड
 * - ग्राहक/सदस्य प्रकार (Guest/Member), नाम, 10-अंक मोबाइल नंबर, गाँव/पता
 * - मूलधन, प्रोसेसिंग शुल्क, शुद्ध वितरण राशि, 365-दिन अवधि (मैन्युअल या "कहाँ से कहाँ तक" कैलेंडर)
 * - पहली किस्त की तारीख (रविवार शिफ्ट सहित), कुल ब्याज, कुल देय राशि, मासिक किस्त
 * - WhatsApp डायरेक्ट टेक्स्ट व प्रिंट-रेडी पर्ची जनरेटर
 */
data class FullQuotationSlipRequest(
    val quotationSeqNo: Long,
    val quotationDate: LocalDate,
    val organizationName: String,
    val agentNameAndCode: String,
    val isGuest: Boolean = true,
    val memberId: String? = null,
    val customerName: String,
    val customerMobile: String,
    val customerVillageAddress: String,
    val principalPaise: Long,
    val processingFeePaise: Long = 0L,
    val interestType: InterestType,
    val rateValue: BigDecimal,
    val tenorInput: TenorInput,
    val cycle: CalculationCycle = CalculationCycle.MONTHLY,
    val holidays: Set<LocalDate> = emptySet()
)

data class FullQuotationSlip(
    val quotationNumber: String,
    val quotationDate: LocalDate,
    val organizationName: String,
    val agentNameAndCode: String,
    val partyTypeLabelHindi: String,
    val customerName: String,
    val customerMobile10Digit: String,
    val customerVillageAddress: String,
    val processingFeePaise: Long,
    val netDisbursablePaise: Long,
    val firstInstallmentDate: LocalDate,
    val quotationResult: QuotationResult,
    val whatsAppMessageHindi: String,
    val directWhatsAppUrl: String
)

data class SlipValidationResult(
    val valid: Boolean,
    val errorsHindi: List<String>
)

object QuotationSlipModule {
    private val DATE_FMT = DateTimeFormatter.ofPattern("dd-MM-yyyy")
    private val NUM_DATE_FMT = DateTimeFormatter.ofPattern("yyyyMMdd")

    fun formatIndianRupees(paise: Long): String {
        val neg = paise < 0L
        val abs = kotlin.math.abs(paise)
        val rupees = abs / 100L
        val p = abs % 100L
        val rStr = rupees.toString()
        val formattedInt = if (rStr.length <= 3) {
            rStr
        } else {
            val last3 = rStr.takeLast(3)
            val rest = rStr.dropLast(3)
            val grouped = rest.reversed().chunked(2).joinToString(",").reversed()
            "$grouped,$last3"
        }
        return "${if (neg) "-" else ""}₹$formattedInt.${"%02d".format(p)}"
    }

    /**
     * सख्त हिंदी इनपुट वैलिडेशन — कोई भी खाली/अमान्य बुनियादी फ़ील्ड आगे नहीं बढ़ेगा
     */
    fun validateRequest(req: FullQuotationSlipRequest): SlipValidationResult {
        val errors = mutableListOf<String>()
        if (req.organizationName.isBlank()) errors.add("संस्था/समूह का नाम अनिवार्य है।")
        if (req.customerName.trim().length < 2) errors.add("ग्राहक/सदस्य का पूरा नाम (कम से कम 2 अक्षर) अनिवार्य है।")
        val digits = req.customerMobile.filter { it.isDigit() }
        if (digits.length != 10 || digits.first() !in '6'..'9') {
            errors.add("मान्य 10-अंक का भारतीय मोबाइल नंबर (6–9 से शुरू) दर्ज करें।")
        }
        if (req.customerVillageAddress.trim().length < 2) errors.add("गाँव/पता दर्ज करना अनिवार्य है।")
        if (req.principalPaise <= 0L) errors.add("मूलधन राशि शून्य से अधिक होनी चाहिए।")
        if (req.processingFeePaise < 0L || req.processingFeePaise >= req.principalPaise) {
            errors.add("प्रोसेसिंग शुल्क ऋणात्मक या मूलधन के बराबर/अधिक नहीं हो सकता।")
        }
        if (req.rateValue < BigDecimal.ZERO) errors.add("ब्याज दर ऋणात्मक नहीं हो सकती।")
        if (req.tenorInput is TenorInput.DateRange && !req.tenorInput.toDate.isAfter(req.tenorInput.fromDate)) {
            errors.add("अंतिम तिथि ('कहाँ तक'), प्रारंभ तिथि ('कहाँ से') के बाद होनी चाहिए।")
        }
        return SlipValidationResult(errors.isEmpty(), errors)
    }

    fun generateFullSlip(
        req: FullQuotationSlipRequest,
        config: RulesConfig = RulesConfig()
    ): FullQuotationSlip {
        val check = validateRequest(req)
        require(check.valid) { check.errorsHindi.joinToString(" | ") }

        val cleanMobile = req.customerMobile.filter { it.isDigit() }
        val qNo = "QT-${req.quotationDate.format(NUM_DATE_FMT)}-${"%04d".format(req.quotationSeqNo)}"

        val qRes = FinanceSuiteEngine.calculateQuotation(
            QuotationRequest(
                customerName = req.customerName.trim(),
                customerMobile = cleanMobile,
                isGuest = req.isGuest,
                memberId = req.memberId,
                principalPaise = req.principalPaise,
                interestType = req.interestType,
                rateValue = req.rateValue,
                tenorInput = req.tenorInput,
                cycle = req.cycle
            ),
            config
        )

        val baseFirstDate = when (req.tenorInput) {
            is TenorInput.DateRange -> req.tenorInput.fromDate.plusMonths(1)
            is TenorInput.Manual -> req.quotationDate.plusMonths(1)
        }
        val (firstInstDate, _) = Step2FinanceLedgerEngine.shiftIfHoliday(baseFirstDate, req.holidays)
        val netDisb = req.principalPaise - req.processingFeePaise
        val partyLbl = if (req.isGuest) "बाहरी ग्राहक (Guest)" else "सदस्य (${req.memberId ?: "Member"})"

        val waText = buildString {
            appendLine("*${req.organizationName.trim()} — ऋण कोटेशन पर्ची*")
            appendLine("कोटेशन नं.: $qNo | दिनांक: ${req.quotationDate.format(DATE_FMT)}")
            appendLine("एजेंट: ${req.agentNameAndCode.trim()}")
            appendLine("———————————————")
            appendLine("नाम: ${req.customerName.trim()} ($partyLbl)")
            appendLine("मोबाइल: +91-$cleanMobile")
            appendLine("गाँव/पता: ${req.customerVillageAddress.trim()}")
            appendLine("———————————————")
            appendLine("मूलधन: ${formatIndianRupees(req.principalPaise)}")
            if (req.processingFeePaise > 0L) {
                appendLine("प्रोसेसिंग शुल्क: ${formatIndianRupees(req.processingFeePaise)}")
                appendLine("शुद्ध हाथ में राशि: ${formatIndianRupees(netDisb)}")
            }
            appendLine("ब्याज दर: ${qRes.effectiveAnnualRatePercent}% वार्षिक (${req.interestType.name})")
            appendLine("अवधि: ${qRes.resolvedTenor.sourceLabel}")
            appendLine("कुल ब्याज: ${formatIndianRupees(qRes.interestPaise)}")
            appendLine("कुल देय राशि: ${formatIndianRupees(qRes.totalPayablePaise)}")
            appendLine("अनुमानित मासिक किस्त: ${formatIndianRupees(qRes.estimatedMonthlyEmiPaise)}")
            appendLine("पहली किस्त तिथि: ${firstInstDate.format(DATE_FMT)}")
            append("नोट: यह केवल अनुमानित कोटेशन पर्ची है, अंतिम ऋण स्वीकृति Admin द्वारा होगी।")
        }

        val waUrl = "https://wa.me/91$cleanMobile?text=" + java.net.URLEncoder.encode(waText, "UTF-8")

        return FullQuotationSlip(
            quotationNumber = qNo,
            quotationDate = req.quotationDate,
            organizationName = req.organizationName.trim(),
            agentNameAndCode = req.agentNameAndCode.trim(),
            partyTypeLabelHindi = partyLbl,
            customerName = req.customerName.trim(),
            customerMobile10Digit = cleanMobile,
            customerVillageAddress = req.customerVillageAddress.trim(),
            processingFeePaise = req.processingFeePaise,
            netDisbursablePaise = netDisb,
            firstInstallmentDate = firstInstDate,
            quotationResult = qRes,
            whatsAppMessageHindi = waText,
            directWhatsAppUrl = waUrl
        )
    }
}
