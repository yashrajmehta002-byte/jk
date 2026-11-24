package `in`.bachatgroup.inventory

import `in`.bachatgroup.data.identity.QrTokenPair
import `in`.bachatgroup.data.identity.Step3IdentityAndLifecycleEngine
import `in`.bachatgroup.data.security.AppRole
import `in`.bachatgroup.data.security.AuthSession
import `in`.bachatgroup.data.security.SecurityCore
import `in`.bachatgroup.finance.FinanceSuiteEngine
import `in`.bachatgroup.finance.LedgerLine

/**
 * बचत समूह प्रणाली — V1.5 प्लगइन: उत्पाद, इन्वेंट्री, Zero-PII Product QR,
 * वारंटी, Append-Only सर्विस हिस्ट्री व नियंत्रित बचत/ऋण समायोजन (`:shared:inventory-service`)।
 */
enum class ProductSettlementMode { CASH, SAVINGS_OFFSET_APPROVED, LOAN_LINKED_APPROVED }

data class ProductRegistrationResult(
    val productId: String,
    val productName: String,
    val optionalLotBatchId: String?,
    val qrTokenPair: QrTokenPair // QR में कोई संवेदनशील cost/price/margin नहीं
)

data class ProductAdjustmentLedgerResult(
    val mode: ProductSettlementMode,
    val ledgerLines: List<LedgerLine>
)

object V15InventoryWarrantyEngine {

    /** Admin-Only Product Registration + Zero-PII Product QR */
    fun registerProductAsAdmin(
        session: AuthSession,
        seqNo: Long,
        productName: String,
        optionalLotBatchId: String?,
        costPaise: Long,
        salePricePaise: Long
    ): ProductRegistrationResult {
        SecurityCore.requireAdmin(session) // Agent कभी उत्पाद रजिस्टर नहीं कर सकता
        require(productName.trim().length >= 2) { "उत्पाद का नाम अनिवार्य है।" }
        require(costPaise >= 0L && salePricePaise > 0L) { "विक्रय मूल्य शून्य से अधिक होना चाहिए।" }
        val productId = "PRD-%05d".format(seqNo)
        val qr = Step3IdentityAndLifecycleEngine.generateZeroPiiQrToken(prefix = "PRDQR")
        return ProductRegistrationResult(productId, productName.trim(), optionalLotBatchId?.trim(), qr)
    }

    /** वारंटी सक्रियता जाँच */
    fun isUnderWarranty(nowEpochSec: Long, warrantyStartEpochSec: Long, warrantyEndEpochSec: Long): Boolean {
        return nowEpochSec in warrantyStartEpochSec..warrantyEndEpochSec
    }

    /**
     * नियंत्रित बचत/ऋण उत्पाद समायोजन — बिना Admin मंजूरी के कोई ऑटो-वॉलेट कटौती नहीं!
     */
    fun buildControlledSaleAdjustment(
        mode: ProductSettlementMode,
        totalAmountPaise: Long,
        adminApproved: Boolean
    ): ProductAdjustmentLedgerResult {
        require(totalAmountPaise > 0L) { "बिक्री राशि शून्य से अधिक होनी चाहिए।" }
        if (mode != ProductSettlementMode.CASH) {
            check(adminApproved) {
                "सुरक्षा अवरोध: बचत या ऋण से उत्पाद समायोजन बिना Admin मंजूरी के संभव नहीं है।"
            }
        }
        val debitAccount = when (mode) {
            ProductSettlementMode.CASH -> "ADMIN_CASH_VAULT"
            ProductSettlementMode.SAVINGS_OFFSET_APPROVED -> "MEMBER_SAVINGS_LIABILITY"
            ProductSettlementMode.LOAN_LINKED_APPROVED -> "LOAN_PRINCIPAL_ASSET"
        }
        val lines = listOf(
            LedgerLine(debitAccount, debitPaise = totalAmountPaise, creditPaise = 0L),
            LedgerLine("PRODUCT_SALES_REVENUE", debitPaise = 0L, creditPaise = totalAmountPaise)
        )
        check(FinanceSuiteEngine.verifyDoubleEntryBalanced(lines)) { "Product Sale Double-Entry असंतुलित!" }
        return ProductAdjustmentLedgerResult(mode, lines)
    }
}
