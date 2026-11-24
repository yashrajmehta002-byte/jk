package `in`.bachatgroup.agent.ops

import `in`.bachatgroup.finance.FinanceSuiteEngine
import `in`.bachatgroup.finance.LedgerLine

/**
 * बचत समूह प्रणाली — Step 4: Agent ऐप फील्ड संचालन व UI स्टेट मॉडल (`:agentApp`)
 * ध्यान दें: इसमें Admin ऐप का कोई भी कोड या आयात (import) पूरी तरह प्रतिबंधित है।
 * - F-016: सख्त Data Isolation (केवल अपने आवंटित समूह)
 * - F-019/F-020: फील्ड संग्रहण -> AGENT_CASH_IN_HAND + अस्थायी रसीद (PROVISIONAL)
 * - F-122/F-146: Visit Log (शून्य-संग्रहण कारण) व वैकल्पिक Offline GPS (बिना सिग्नल कभी न रुके)
 * - F-123: अलग Training Sandbox (`test_bachat.db`) — असली डेटाबेस व sync_outbox से 100% अलग
 */
enum class CheckInMode { QR_SCAN, MANUAL }

data class AgentFieldCollectionResult(
    val targetDatabaseName: String,
    val enqueueToProductionSyncOutbox: Boolean,
    val isProvisionalReceipt: Boolean,
    val ledgerLines: List<LedgerLine>
)

data class FieldVisitLogEntry(
    val agentId: String,
    val memberId: String,
    val checkInMode: CheckInMode,
    val reasonCode: String,
    val nextPromisedDateEpoch: Long?,
    val gpsLatLonOpt: String? // वैकल्पिक — GPS बंद या बिना सिग्नल पर null स्वीकृत
)

object AgentFieldOperationsEngine {
    const val PRODUCTION_DB = "bachat_samuh_encrypted.db"
    const val SANDBOX_DB = "test_bachat.db"

    /**
     * F-016: सख्त एजेंट स्कोप जाँच — गैर-आवंटित समूह तुरंत ब्लॉक
     */
    fun verifyGroupAssignedToAgent(requestedGroupId: String, assignedGroupIds: Set<String>) {
        check(requestedGroupId in assignedGroupIds) {
            "सुरक्षा अवरोध (F-016): यह समूह आपके खाते में आवंटित नहीं है।"
        }
    }

    /**
     * F-019, HR-12 & F-123: एजेंट संग्रहण (हमेशा AGENT_CASH_IN_HAND में; Sandbox में अलग DB)
     */
    fun buildAgentCollection(
        requestedGroupId: String,
        assignedGroupIds: Set<String>,
        amountPaise: Long,
        isTrainingSandboxMode: Boolean
    ): AgentFieldCollectionResult {
        verifyGroupAssignedToAgent(requestedGroupId, assignedGroupIds)
        require(amountPaise > 0L) { "वसूली राशि शून्य से अधिक होनी चाहिए।" }
        val lines = listOf(
            LedgerLine(accountCode = "AGENT_CASH_IN_HAND", debitPaise = amountPaise, creditPaise = 0L),
            LedgerLine(accountCode = "MEMBER_SAVINGS_LIABILITY", debitPaise = 0L, creditPaise = amountPaise)
        )
        check(FinanceSuiteEngine.verifyDoubleEntryBalanced(lines)) { "Double-Entry असंतुलित!" }
        return AgentFieldCollectionResult(
            targetDatabaseName = if (isTrainingSandboxMode) SANDBOX_DB else PRODUCTION_DB,
            enqueueToProductionSyncOutbox = !isTrainingSandboxMode,
            isProvisionalReceipt = true, // खंड 10.3: ऑफलाइन एजेंट रसीद हमेशा PROVISIONAL
            ledgerLines = lines
        )
    }

    /**
     * F-122 & F-146: Visit Log + वैकल्पिक Offline GPS (GPS न मिलने पर भी कभी ब्लॉक न हो)
     */
    fun createVisitLog(
        agentId: String,
        memberId: String,
        checkInMode: CheckInMode,
        reasonCode: String,
        nextPromisedDateEpoch: Long?,
        gpsEnabledByAdmin: Boolean,
        capturedCoordsIfAvailable: String?
    ): FieldVisitLogEntry {
        require(reasonCode.isNotBlank()) { "विज़िट या शून्य-संग्रहण का कारण चुनना अनिवार्य है।" }
        val finalGps = if (gpsEnabledByAdmin) capturedCoordsIfAvailable else null
        return FieldVisitLogEntry(
            agentId = agentId,
            memberId = memberId,
            checkInMode = checkInMode,
            reasonCode = reasonCode,
            nextPromisedDateEpoch = nextPromisedDateEpoch,
            gpsLatLonOpt = finalGps
        )
    }
}
