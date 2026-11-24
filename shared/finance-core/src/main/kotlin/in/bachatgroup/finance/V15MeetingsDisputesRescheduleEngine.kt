package `in`.bachatgroup.finance

import java.math.BigDecimal
import java.time.LocalDate

/**
 * बचत समूह प्रणाली — V1.5 भाग-2: ऋण पुनर्गठन (F-125), बैठक उपस्थिति व विवाद समाधान इंजन।
 */
enum class AttendanceStatus { PRESENT, ABSENT, EXCUSED }
enum class DisputeStatus { OPEN, UNDER_REVIEW, RESOLVED, CLOSED }

data class LoanReschedulePlan(
    val preservedPaidInstallmentCount: Int,
    val remainingPrincipalPaise: Long,
    val newInstallments: List<InstallmentRow>
)

data class MeetingAttendanceSummary(
    val totalMembers: Int,
    val presentCount: Int,
    val absentCount: Int,
    val attendancePercent: Double
)

object V15MeetingsDisputesRescheduleEngine {

    /**
     * F-125: ऋण पुनर्गठन (Loan Reschedule)
     * नियम: पहले से भरी हुई किस्तें 100% अपरिवर्तित रहती हैं; केवल शेष मूलधन पर नई अवधि की किस्तें बनती हैं।
     */
    fun rescheduleRemainingLoan(
        paidInstallmentsCount: Int,
        remainingPrincipalPaise: Long,
        newAnnualRatePercent: BigDecimal,
        newTenureMonths: Int,
        rescheduleStartDate: LocalDate,
        adminApproved: Boolean,
        holidays: Set<LocalDate> = emptySet(),
        config: RulesConfig = RulesConfig()
    ): LoanReschedulePlan {
        check(adminApproved) { "ऋण पुनर्गठन (F-125) केवल Admin की मंजूरी से ही संभव है।" }
        require(remainingPrincipalPaise > 0L) { "शेष मूलधन शून्य से अधिक होना चाहिए।" }
        require(newTenureMonths > 0) { "नई अवधि कम से कम 1 माह होनी चाहिए।" }

        val freshSchedule = Step2FinanceLedgerEngine.generateLoanSchedule(
            principalPaise = remainingPrincipalPaise,
            annualRatePercent = newAnnualRatePercent,
            months = newTenureMonths,
            startDate = rescheduleStartDate,
            isReducing = true,
            holidays = holidays,
            config = config
        ).mapIndexed { idx, row ->
            row.copy(installmentNo = paidInstallmentsCount + idx + 1)
        }

        return LoanReschedulePlan(
            preservedPaidInstallmentCount = paidInstallmentsCount,
            remainingPrincipalPaise = remainingPrincipalPaise,
            newInstallments = freshSchedule
        )
    }

    /** बैठक उपस्थिति सारांश गणना */
    fun summarizeAttendance(statuses: List<AttendanceStatus>): MeetingAttendanceSummary {
        require(statuses.isNotEmpty()) { "उपस्थिति सूची खाली नहीं हो सकती।" }
        val present = statuses.count { it == AttendanceStatus.PRESENT }
        val absent = statuses.count { it == AttendanceStatus.ABSENT }
        val pct = (present.toDouble() * 100.0) / statuses.size.toDouble()
        return MeetingAttendanceSummary(statuses.size, present, absent, pct)
    }

    /** विवाद समाधान वैधता जाँच (RESOLVED/CLOSED के लिए समाधान टिप्पणी व Admin ID अनिवार्य) */
    fun validateDisputeResolution(
        targetStatus: DisputeStatus,
        resolutionNote: String?,
        adminId: String?
    ): Pair<Boolean, String> {
        if (targetStatus == DisputeStatus.RESOLVED || targetStatus == DisputeStatus.CLOSED) {
            if (resolutionNote.isNullOrBlank()) return false to "विवाद बंद करने के लिए समाधान टिप्पणी अनिवार्य है।"
            if (adminId.isNullOrBlank()) return false to "विवाद समाधान पर Admin सत्यापन अनिवार्य है।"
        }
        return true to "स्वीकृत।"
    }
}
