# बचत समूह प्रणाली — 12-बिंदु पायलट UAT व Release चेकलिस्ट (Step 9)

## 1. Release Keystore एवं Zero-Data-Loss प्रोटोकॉल (HR-09)
- [ ] एक ही सुरक्षित Release Signing Keystore (`bachat_release.jks`) बनाएँ और इसकी 3 ऑफलाइन प्रतियाँ (2 USB + सेफ) सुरक्षित रखें।
- [ ] `in.bachatgroup.admin` व `in.bachatgroup.agent` के `applicationId` कभी न बदलें।
- [ ] पुराने डेटा वाले फोन पर `v1 → v2` APK ओवर-इंस्टॉल (`adb install -r`) कर 100% सदस्य, 40–60 KB फोटो, लेजर व हैश-चेन की सुरक्षा सत्यापित करें।

## 2. 7-दिवसीय असली डिवाइस पायलट (1 Admin + 2 Agent फोन — T-01 से T-12)
- [ ] **T-01 (DB & Upgrade):** 25 टेबल्स + `v1 → v2` ओवर-इंस्टॉल पर `0` डेटा हानि।
- [ ] **T-02 (Validation):** खाली नाम, अमान्य मोबाइल, >100 KB फोटो, लॉक अवधि व 101वाँ सदस्य ब्लॉक।
- [ ] **T-03 (Atomic Rollback):** अधूरा Transfer, Exit Settlement या असंतुलित Opening CSV पूरा Rollback।
- [ ] **T-04 (Duplicate):** समान Idempotency Key पर `0` डबल एंट्री; 2nd प्रिंट पर `DUPLICATE COPY` मोहर।
- [ ] **T-05 (Offline & Clock):** Airplane Mode संचालन + तारीख पीछे करने पर Clock Guard ब्लॉक।
- [ ] **T-06 (P2P Sync & Mirror):** 1-स्कैन Pairing QR से Wi-Fi Direct/Bluetooth सिंक, `0` क्रॉस-एजेंट लीकेज, Admin मिरर अपडेट व क्लोन फोन ब्लॉक।
- [ ] **T-07 (Tamper Security):** SQLCipher एन्क्रिप्शन, ABORT ट्रिगर्स, ₹1 छेड़छाड़ पर Read-Only Safe Mode व `FLAG_SECURE` स्क्रीनशॉट ब्लॉक।
- [ ] **T-08 (AES-256 Backup/Restore):** अनइंस्टॉल-रीइंस्टॉल पर SAF/SD/OTG `.bvault` व Admin मिरर से 100% डेटा वापसी।
- [ ] **T-09 (Real Dashboard):** Handover Approve से पहले Agent नकद Real Vault में न जुड़े; Trial Balance = `0` अंतर।
- [ ] **T-10 (Print, Voice & Locale):** 58mm थर्मल प्रिंटर Bitmap हिंदी, 6-अंक HMAC सत्यापन, वॉयस अलर्ट ON/OFF व Hindi↔English स्विच।
- [ ] **T-11 (Phonetic Search):** `"Ram"` और `"राम"` दोनों से एक ही सदस्य मिले।
- [ ] **T-12 (Release APKs):** दोनों Signed APK (Android 8–15) पर 7 दिन बिना क्रैश संचालन + SHA-256 चेकसम रिकॉर्ड।
