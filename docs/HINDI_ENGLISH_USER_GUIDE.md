# बचत समूह प्रणाली — संचालन गाइड (Hindi & English Operations Guide — F-108)

## भाग A: एडमिन ऐप (Admin APK — `in.bachatgroup.admin`)
1. **प्रथम सेटअप व सुरक्षा:** Admin PIN सेट करें और स्क्रीन पर दिखने वाली **12-शब्द Master Recovery Key** को कागज़ पर लिखकर सुरक्षित रखें। सेटिंग्स से बाहरी SAF फोल्डर (`Documents/BachatVault`) चुनें।
2. **नियम व एजेंट प्रबंधन:** ब्याज दर, दैनिक निकासी सीमा (डिफ़ॉल्ट ₹2,000 या ₹0) और Cash-in-Hand सीमा (₹30,000) लॉक करें। एजेंट जोड़ने पर स्थायी `AGT-0001` ID व Zero-PII QR कार्ड बनेगा।
3. **वास्तविक वसूली (Real Dashboard — HR-12):** Admin की सीधी वसूली तुरंत `ADMIN_CASH_VAULT` में दिखती है। Agent द्वारा वसूली गई राशि तब तक `Pending` रहेगी जब तक आप नकद गिनकर Handover **Approve** न कर दें।
4. **ऑफलाइन P2P सिंक व मिरर:** सिंक बटन दबाकर 1-स्कैन Pairing QR दिखाएँ (2-मिनट वैधता)। हर सिंक पर एजेंट की Last-Sync मिरर कॉपी अपने-आप अपडेट होगी।
5. **साप्ताहिक OTG/SD बैकअप:** सप्ताह में एक बार USB OTG/SD कार्ड पर 1-टैप `.bvault` बैकअप लें।

## भाग B: एजेंट ऐप (Agent APK — `in.bachatgroup.agent`)
1. **डिवाइस बाइंडिंग व सैंडबॉक्स:** पहली बार Admin QR से डिवाइस बाइंड करें। नए एजेंट पहले **Training Sandbox** में अभ्यास करें (यह असली डेटाबेस में नहीं जुड़ता)।
2. **दैनिक फील्ड वसूली:** सदस्य का QR स्कैन करें (या हिंदी/हिंग्लिश नाम खोजें), बचत/किस्त दर्ज करें और `"अस्थायी रसीद (PROVISIONAL)"` प्रिंट/WhatsApp करें।
3. **शाम का Blind Cash Tally व हैंडओवर:** दिन के अंत में नोट गिनती (`₹500/₹200/₹100`) डालें, Admin के पास नकद जमा कर Handover अनुरोध भेजें और 1-स्कैन QR से सिंक करें (3 दिन से ज़्यादा सिंक न करने पर चेतावनी, 7 दिन पर ऑटो-लॉक)।

---

## Part C: Quick English Operations Summary
- **Admin APK (`in.bachatgroup.admin`):** Controls rules (`RulesConfig`), agent onboarding/device binding, self-group field mode, direct member collections (`ADMIN_CASH_VAULT`), loan approvals, handover verification gate, offline P2P sync host, net-profit dividend posting, and AES-256 `.bvault` backups.
- **Agent APK (`in.bachatgroup.agent`):** Strictly isolated to assigned groups, offline provisional collections, zero-collection Visit Log, Blind EOD Cash Tally, handover requests, and 1-scan P2P sync.
- **Zero-Data-Loss Upgrades:** Always sign release builds with the exact same Release Keystore (`.jks`) and never enable `fallbackToDestructiveMigration()`.
