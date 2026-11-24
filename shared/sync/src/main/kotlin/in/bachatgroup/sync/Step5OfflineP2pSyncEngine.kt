package `in`.bachatgroup.sync

import `in`.bachatgroup.data.security.SecurityCore
import `in`.bachatgroup.finance.RulesConfig
import java.security.SecureRandom
import java.util.Base64

/**
 * बचत समूह प्रणाली — Step 5: Offline P2P Sync इंजन (`:shared:sync`)
 * - F-155: 1-स्कैन Pairing QR (अस्थायी SSID + 2-मिनट वैधता + 256-bit सेशन कुंजी; कोई मैन्युअल IP नहीं)
 * - F-156: Wi-Fi Direct प्राथमिक + Bluetooth RFCOMM/SPP फॉलबैक प्रोटोकॉल
 * - F-117: Agent-Scoped Partitioning (एजेंट को केवल अपने आवंटित समूहों का डेटा)
 * - F-098: Chunked Batch Envelope + SHA-256 चेकसम + Idempotent ACK / Resume
 * - F-012: Conflict Queue (वित्तीय विवाद में कभी Last-Write-Wins नहीं)
 * - F-129: Admin Last-Sync Agent Mirror Snapshot (`agent_sync_snapshots`)
 * - F-131 & F-118 & F-120: Single-Device Binding, Clock-Tampering Guard व Sync Age Alert
 */
enum class P2pTransportChannel { WIFI_DIRECT, BLUETOOTH_SPP }
enum class SyncAgeStatus { OK, WARNING_3_DAYS, AUTO_LOCKED_7_DAYS }

data class PairingQrSession(
    val sessionId: String,
    val ephemeralSsid: String,
    val sessionKeyBase64: String,
    val transport: P2pTransportChannel,
    val expiresAtEpochSec: Long
)

data class SyncEventRecord(
    val idempotencyKey: String,
    val scopeGroupId: String,
    val entityId: String,
    val localSeqNo: Long,
    val payloadType: String,
    val payloadJson: String,
    val createdAtEpochSec: Long
)

data class SyncBatchChunk(
    val chunkIndex: Int,
    val totalChunks: Int,
    val events: List<SyncEventRecord>,
    val sha256Checksum: String
)

data class AgentMirrorSnapshot(
    val agentId: String,
    val boundDeviceId: String,
    val lastSyncAtEpochSec: Long,
    val lastSeqNo: Long,
    val lastCashInHandPaise: Long,
    val snapshotChecksum: String
)

object Step5OfflineP2pSyncEngine {
    private val rng = SecureRandom()
    const val DEFAULT_CHUNK_SIZE = 50
    const val PAIRING_QR_TTL_SECONDS = 120L // 2 मिनट वैधता

    /** F-155: 1-स्कैन Pairing QR सेशन बनाना */
    fun createPairingQrSession(
        nowEpochSec: Long,
        transport: P2pTransportChannel = P2pTransportChannel.WIFI_DIRECT
    ): PairingQrSession {
        val keyBytes = ByteArray(32)
        rng.nextBytes(keyBytes)
        val suffix = (1000..9999).random()
        return PairingQrSession(
            sessionId = "SYNC_${nowEpochSec}_$suffix",
            ephemeralSsid = "BACHAT_P2P_$suffix",
            sessionKeyBase64 = Base64.getUrlEncoder().withoutPadding().encodeToString(keyBytes),
            transport = transport,
            expiresAtEpochSec = nowEpochSec + PAIRING_QR_TTL_SECONDS
        )
    }

    fun isPairingSessionValid(session: PairingQrSession, nowEpochSec: Long): Boolean {
        return nowEpochSec <= session.expiresAtEpochSec
    }

    /** F-131: Single-Device Hardware Binding जाँच (क्लोन/दूसरा फोन तुरंत ब्लॉक) */
    fun verifyDeviceBinding(expectedBoundDeviceId: String?, incomingDeviceId: String): Pair<Boolean, String> {
        if (expectedBoundDeviceId.isNullOrBlank()) {
            return false to "एजेंट का डिवाइस अभी Admin द्वारा बाइंड नहीं किया गया है।"
        }
        return if (expectedBoundDeviceId == incomingDeviceId) {
            true to "डिवाइस सत्यापित।"
        } else {
            false to "सुरक्षा अवरोध (F-131): यह Agent ID किसी अन्य फोन ($expectedBoundDeviceId) से बाइंड है।"
        }
    }

    /** HR-08 & F-118: Clock-Tampering Guard (तारीख/घड़ी पीछे करने पर तुरंत ब्लॉक) */
    fun verifyClockNotTampered(
        lastAcceptedEpochSec: Long,
        incomingEpochSec: Long,
        maxAllowedBackwardDriftSec: Long = 60L
    ): Pair<Boolean, String> {
        return if (incomingEpochSec + maxAllowedBackwardDriftSec < lastAcceptedEpochSec) {
            false to "Clock-Tampering अलर्ट (F-118): फोन की तारीख/समय पीछे किया गया है; सिंक अवरुद्ध।"
        } else {
            true to "समय सत्यापित।"
        }
    }

    /** F-117: Agent-Scoped Partitioning — केवल एजेंट के आवंटित समूह ही भेजें */
    fun filterEventsForAgentScope(
        allEvents: List<SyncEventRecord>,
        agentAssignedGroupIds: Set<String>
    ): List<SyncEventRecord> {
        return allEvents.filter { it.scopeGroupId in agentAssignedGroupIds }
    }

    /** F-098: 50-रिकॉर्ड Chunked Batch + SHA-256 Checksum पैकिंग */
    fun createVerifiedChunks(
        events: List<SyncEventRecord>,
        chunkSize: Int = DEFAULT_CHUNK_SIZE
    ): List<SyncBatchChunk> {
        if (events.isEmpty()) return emptyList()
        val split = events.chunked(chunkSize)
        return split.mapIndexed { idx, batch ->
            val raw = batch.joinToString("|") { "${it.idempotencyKey}:${it.localSeqNo}:${it.payloadJson}" }
            SyncBatchChunk(
                chunkIndex = idx + 1,
                totalChunks = split.size,
                events = batch,
                sha256Checksum = SecurityCore.sha256Hex(raw)
            )
        }
    }

    fun verifyChunkIntegrity(chunk: SyncBatchChunk): Boolean {
        val raw = chunk.events.joinToString("|") { "${it.idempotencyKey}:${it.localSeqNo}:${it.payloadJson}" }
        return SecurityCore.sha256Hex(raw) == chunk.sha256Checksum
    }

    /** F-129: Admin Last-Sync Agent Mirror Snapshot निर्माण */
    fun buildAdminMirrorSnapshot(
        agentId: String,
        boundDeviceId: String,
        nowEpochSec: Long,
        lastSeqNo: Long,
        lastCashInHandPaise: Long
    ): AgentMirrorSnapshot {
        val checksum = SecurityCore.sha256Hex("$agentId|$boundDeviceId|$nowEpochSec|$lastSeqNo|$lastCashInHandPaise")
        return AgentMirrorSnapshot(agentId, boundDeviceId, nowEpochSec, lastSeqNo, lastCashInHandPaise, checksum)
    }

    /** F-120: Pending Sync Age Alert (3 दिन चेतावनी, 7 दिन ऑटो-लॉक) */
    fun evaluateSyncAge(daysSinceLastSync: Int, config: RulesConfig = RulesConfig()): SyncAgeStatus {
        return when {
            daysSinceLastSync >= config.syncAgeAutoLockDays -> SyncAgeStatus.AUTO_LOCKED_7_DAYS
            daysSinceLastSync >= config.syncAgeWarningDays -> SyncAgeStatus.WARNING_3_DAYS
            else -> SyncAgeStatus.OK
        }
    }
}
