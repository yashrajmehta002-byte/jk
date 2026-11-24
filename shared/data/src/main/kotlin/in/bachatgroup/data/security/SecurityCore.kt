package `in`.bachatgroup.data.security

import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

enum class AppRole { ADMIN, AGENT }

data class AuthSession(
    val userId: String,
    val role: AppRole,
    val boundDeviceId: String
)

data class SelfAuditResult(
    val isIntegrityValid: Boolean,
    val safeModeTriggered: Boolean,
    val checkedJournalRows: Int,
    val failureReasonHindi: String? = null
)

object SecurityCore {
    private val RECOVERY_WORDS = listOf(
        "surya", "chandra", "ganga", "kamal", "shakti", "dharma",
        "bachat", "samuh", "vishwas", "pragati", "kisan", "uday",
        "pavan", "dhara", "neer", "jyoti", "mitra", "lakshya",
        "sankalp", "kiran", "amrit", "niti", "seva", "raksha"
    )

    fun sha256Hex(input: String): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(input.toByteArray(Charsets.UTF_8))
        return digest.joinToString("") { "%02x".format(it) }
    }

    fun hmacSha256Hex(secretKeyBytes: ByteArray, message: String): String {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(secretKeyBytes, "HmacSHA256"))
        return mac.doFinal(message.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
    }

    /** F-159: 12-शब्द ऑफलाइन Master Recovery Key जनरेशन */
    fun generate12WordRecoveryKey(): List<String> {
        val rng = SecureRandom()
        return List(12) { RECOVERY_WORDS[rng.nextInt(RECOVERY_WORDS.size)] }
    }

    /** Role Guard: Agent कभी भी Admin ऑपरेशन नहीं चला सकता */
    fun requireAdmin(session: AuthSession) {
        check(session.role == AppRole.ADMIN) {
            "सुरक्षा अवरोध: Agent भूमिका को Admin कार्य करने की अनुमति नहीं है।"
        }
    }

    /** HR-10 & V1.1 Sec 10.2: Targeted Hash-Chain एंट्री हैश गणना */
    fun computeIntegrityEntryHash(
        prevChainHash: String,
        targetTable: String,
        recordPk: String,
        opType: String,
        beforeHash: String,
        afterHash: String,
        createdAt: Long
    ): String {
        val payload = "$prevChainHash|$targetTable|$recordPk|$opType|$beforeHash|$afterHash|$createdAt"
        return sha256Hex(payload)
    }
}
