# Privacy Policy for Camera Vault (خزنة الكاميرا)

**Last Updated:** September 30, 2026  
**Developer:** Eng. Huthaifa Aref (`dev-huthaifa`)  
**Contact Email:** eng.huthaifa.aref.1611@gmail.com  

---

## 1. Overview
**Camera Vault** ("we", "our", or "the App") is committed to protecting your personal privacy. The App is designed as an offline-first, highly secure privacy vault disguised as a standard HD camera. All your sensitive photos, videos, audio, and documents are encrypted locally on your device.

---

## 2. Information Collection and Use

### A. Offline Storage and Local Encryption
* **Zero Data Collection:** We do not collect, transmit, store, or sell any of your personal data, media files, or PIN codes on external servers.
* **On-Device Military-Grade Encryption:** All files imported into Camera Vault are encrypted using **AES-256-GCM** encryption and stored exclusively within the private internal app storage of your Android device.
* **Decryption On Demand:** Files are only decrypted locally when you unlock the vault using your personal Master PIN or configured biometric authentication.

### B. Device Permissions Requested
The App requests specific permissions only to provide its core features:
1. **Camera (`android.permission.CAMERA`):**
   * Used to provide the functional HD camera disguise interface.
   * Used for the **Intruder Selfie** feature to capture a photo locally when an unauthorized person enters an incorrect PIN multiple times.
2. **Microphone (`android.permission.RECORD_AUDIO`):**
   * Used only if you record video within the camera interface.
3. **Storage / Media Access (`READ_MEDIA_IMAGES`, `READ_MEDIA_VIDEO`, `READ_MEDIA_AUDIO`, `MANAGE_EXTERNAL_STORAGE`):**
   * Required strictly to allow you to select and import your files into the encrypted vault, and optionally remove the original unencrypted file from your public gallery so it remains truly private.
4. **Biometric (`android.permission.USE_BIOMETRIC`):**
   * Used to allow quick fingerprint or facial recognition unlock. Biometric authentication is handled by Android's secure hardware Keystore; the App never has access to your raw biometric data.

---

## 3. Advertising (Google AdMob)
The App uses Google Mobile Ads SDK (AdMob) to display advertisements. Google AdMob may collect and process certain non-personally identifiable device data (such as advertising IDs, coarse location, and ad interaction metrics) in accordance with Google's Privacy Policy:
* [Google Privacy Policy](https://policies.google.com/privacy)
* [How Google uses information from apps](https://policies.google.com/technologies/partner-sites)

---

## 4. Children's Privacy
Camera Vault does not address anyone under the age of 13. We do not knowingly collect personal identifiable information from children under 13.

---

## 5. Security
We value your trust in providing us your files, thus we use commercially acceptable means of protecting them. All data encryption is performed locally on your device with industry-standard cryptographic algorithms.

---

## 6. Contact Us
If you have any questions or suggestions regarding this Privacy Policy, please contact:
* **Developer:** Eng. Huthaifa Aref
* **Email:** eng.huthaifa.aref.1611@gmail.com
* **GitHub:** [https://github.com/dev-huthaifa](https://github.com/dev-huthaifa)
