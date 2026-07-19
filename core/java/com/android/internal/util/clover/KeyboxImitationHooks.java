/*
 * SPDX-FileCopyrightText: 2024 Paranoid Android
 * SPDX-FileCopyrightText: 2025 Neoteric OS
 * SPDX-FileCopyrightText: 2025 The Clover Project
 * SPDX-License-Identifier: Apache-2.0
 */
package com.android.internal.util.clover;

import android.os.Build;
import android.security.KeyChain;
import android.security.keystore.KeyProperties;
import android.system.keystore2.KeyEntryResponse;
import android.util.Log;

import com.android.internal.org.bouncycastle.asn1.ASN1Boolean;
import com.android.internal.org.bouncycastle.asn1.ASN1Encodable;
import com.android.internal.org.bouncycastle.asn1.ASN1EncodableVector;
import com.android.internal.org.bouncycastle.asn1.ASN1Enumerated;
import com.android.internal.org.bouncycastle.asn1.ASN1Integer;
import com.android.internal.org.bouncycastle.asn1.ASN1ObjectIdentifier;
import com.android.internal.org.bouncycastle.asn1.ASN1OctetString;
import com.android.internal.org.bouncycastle.asn1.ASN1Sequence;
import com.android.internal.org.bouncycastle.asn1.ASN1TaggedObject;
import com.android.internal.org.bouncycastle.asn1.DERNull;
import com.android.internal.org.bouncycastle.asn1.DEROctetString;
import com.android.internal.org.bouncycastle.asn1.DERSequence;
import com.android.internal.org.bouncycastle.asn1.DERSet;
import com.android.internal.org.bouncycastle.asn1.DERTaggedObject;
import com.android.internal.org.bouncycastle.asn1.x509.Extension;
import com.android.internal.org.bouncycastle.asn1.x509.KeyUsage;
import com.android.internal.org.bouncycastle.cert.X509CertificateHolder;
import com.android.internal.org.bouncycastle.cert.X509v3CertificateBuilder;
import com.android.internal.org.bouncycastle.operator.ContentSigner;
import com.android.internal.org.bouncycastle.operator.jcajce.JcaContentSignerBuilder;

import java.security.PrivateKey;
import java.security.SecureRandom;
import java.security.cert.X509Certificate;
import java.util.Map;

/**
 * @hide
 */
public class KeyboxImitationHooks {

    private static final String TAG = "KeyboxImitationHooks";
    private static final boolean DEBUG = Log.isLoggable(TAG, Log.DEBUG);

    private static final ASN1ObjectIdentifier KEY_ATTESTATION_OID = new ASN1ObjectIdentifier(
            "1.3.6.1.4.1.11129.2.1.17");

    // KeyMint version constants for the claimed device. KeyMint 4.0
    // attestations on Android 16 carry attestationVersion/keymasterVersion =
    // 400; on Android 15 the value is 300. Our claim (akita on 16) must match
    // the Android-16 value or the version itself is incoherent.
    private static final int ATTESTATION_VERSION = 400;
    private static final int KEYMASTER_VERSION = 400;

    private static volatile byte[] sPendingChallenge;
    private static volatile byte[] sPendingApplicationId;
    // The exact ATTESTATION_ID_* set keystore was asked to attest, keyed by its
    // ASN.1 attestation tag number (710..717, 723). Captured by
    // AndroidKeyStoreKeyPairGeneratorSpi before it strips the ids for the
    // no-attestation fallback, so the forge can reproduce the attestation keystore
    // would have emitted rather than a hand-picked subset.
    private static volatile Map<Integer, byte[]> sPendingAttestIds;
    // Cached per-process attestationApplicationId (DER), built once — see
    // buildAttestationApplicationId(). Stock KeyMint always computes and embeds
    // the caller's AAID in teeEnforced; a forged attestation without it is
    // structurally incomplete.
    private static volatile byte[] sAttestationApplicationId;

    public static void setAttestationChallenge(byte[] challenge) {
        sPendingChallenge = challenge;
    }

    public static void setAttestationApplicationId(byte[] applicationId) {
        sPendingApplicationId = applicationId;
    }

    public static void setAttestationIds(Map<Integer, byte[]> ids) {
        sPendingAttestIds = ids;
    }

    public static KeyEntryResponse onGetKeyEntry(KeyEntryResponse response) {
        // Forge key attestation ONLY for the packages we deliberately spoof for
        // Play Integrity (GMS/Finsky) on a non-green bootloader. Every other
        // caller — and every process on a green, OEM-verified device — keeps its
        // real attestation, so an unrelated app doing its own key attestation
        // sees the device's true state rather than a Pixel it plainly isn't.
        // isSpoofTarget is cached by SimplePropImitation at process start, before
        // any sysprop spoofing, so reading it here is safe.
        if (!com.android.internal.util.SimplePropImitation.isSpoofTarget()) {
            dlog("Not a spoof-target process — skipping key attestation spoofing");
            return response;
        }

        if (!KeyProviderManager.isKeyboxAvailable()) {
            dlog("Key attestation spoofing is disabled because no keybox is defined to spoof");
            return response;
        }

        if (response == null || response.metadata == null) return response;

        try {
            if (response.metadata.certificate == null) {
                Log.e(TAG, "Certificate is null, skipping modification");
                return response;
            }

            X509Certificate certificate = KeyChain.toCertificate(response.metadata.certificate);
            String keyAlgorithm = certificate.getPublicKey().getAlgorithm();

            if (certificate.getExtensionValue(KEY_ATTESTATION_OID.getId()) != null) {
                dlog("Modifying existing attestation extension");
                response.metadata.certificate = modifyLeafCertificate(
                        certificate, keyAlgorithm);
            } else {
                byte[] challenge = sPendingChallenge;
                if (challenge != null) {
                    sPendingChallenge = null;
                    dlog("Creating attestation extension from scratch"
                            + " (hardware attestation was unavailable)");
                    response.metadata.certificate = createLeafCertificate(
                            certificate, keyAlgorithm, challenge);
                } else {
                    return response;
                }
            }
            response.metadata.certificateChain = KeyboxUtils.getCertificateChain(keyAlgorithm);
        } catch (Exception e) {
            Log.e(TAG, "Error in onGetKeyEntry", e);
        }

        return response;
    }

    private static byte[] modifyLeafCertificate(X509Certificate leafCertificate,
            String keyAlgorithm) throws Exception {
        X509CertificateHolder certificateHolder = new X509CertificateHolder(
                leafCertificate.getEncoded());
        Extension keyAttestationExtension = certificateHolder.getExtension(KEY_ATTESTATION_OID);
        ASN1Sequence keyAttestationSequence = ASN1Sequence.getInstance(
                keyAttestationExtension.getExtnValue().getOctets());
        ASN1Encodable[] keyAttestationEncodables = keyAttestationSequence.toArray();
        ASN1Sequence teeEnforcedSequence = (ASN1Sequence) keyAttestationEncodables[7];
        ASN1EncodableVector teeEnforcedVector = new ASN1EncodableVector();

        for (ASN1Encodable teeEnforcedEncodable : teeEnforcedSequence) {
            ASN1TaggedObject taggedObject = (ASN1TaggedObject) teeEnforcedEncodable;
            int tag = taggedObject.getTagNo();
            if (tag == 704 || tag == 705 || tag == 706 || tag == 718 || tag == 719) {
                continue;
            }
            teeEnforcedVector.add(teeEnforcedEncodable);
        }

        addBootAndPatchInfo(teeEnforcedVector);

        keyAttestationEncodables[7] = new DERSequence(teeEnforcedVector);
        ASN1Sequence newKeyAttestationSequence = new DERSequence(keyAttestationEncodables);

        return buildCertificate(leafCertificate, certificateHolder, keyAlgorithm,
                new Extension(KEY_ATTESTATION_OID, false,
                        new DEROctetString(newKeyAttestationSequence)), true);
    }

    private static byte[] createLeafCertificate(X509Certificate leafCertificate,
            String keyAlgorithm, byte[] challenge) throws Exception {
        X509CertificateHolder certificateHolder = new X509CertificateHolder(
                leafCertificate.getEncoded());

        // Build teeEnforced with purpose, algorithm, keySize, digest, noAuthRequired
        ASN1EncodableVector teeEnforcedVector = new ASN1EncodableVector();
        // Tag 1: purpose SET (SIGN=2)
        ASN1EncodableVector purposeSet = new ASN1EncodableVector();
        purposeSet.add(new ASN1Integer(2));
        teeEnforcedVector.add(new DERTaggedObject(true, 1, new DERSet(purposeSet)));
        // Tag 2: algorithm
        int algValue = KeyProperties.KEY_ALGORITHM_EC.equals(keyAlgorithm) ? 3 : 1;
        teeEnforcedVector.add(new DERTaggedObject(true, 2, new ASN1Integer(algValue)));
        // Tag 3: keySize
        int keySize = KeyProperties.KEY_ALGORITHM_EC.equals(keyAlgorithm) ? 256 : 2048;
        teeEnforcedVector.add(new DERTaggedObject(true, 3, new ASN1Integer(keySize)));
        // Tag 5: digest SET (SHA-256=4)
        ASN1EncodableVector digestSet = new ASN1EncodableVector();
        digestSet.add(new ASN1Integer(4));
        teeEnforcedVector.add(new DERTaggedObject(true, 5, new DERSet(digestSet)));
        // Tag 10: ecCurve (P-256=1) for EC keys
        if (KeyProperties.KEY_ALGORITHM_EC.equals(keyAlgorithm)) {
            teeEnforcedVector.add(new DERTaggedObject(true, 10, new ASN1Integer(1)));
        }
        // Tag 503: noAuthRequired
        teeEnforcedVector.add(new DERTaggedObject(true, 503, DERNull.INSTANCE));
        // Tag 702: origin (GENERATED=0)
        teeEnforcedVector.add(new DERTaggedObject(true, 702, new ASN1Integer(0)));

        addBootAndPatchInfo(teeEnforcedVector);

        // softwareEnforced: creationDateTime then attestationApplicationId — both
        // present on stock KeyMint attestations, in this order, in THIS list
        // (not teeEnforced).
        ASN1EncodableVector softwareEnforcedVector = new ASN1EncodableVector();
        softwareEnforcedVector.add(new DERTaggedObject(true, 701,
                new ASN1Integer(System.currentTimeMillis())));
        byte[] aaid = getAttestationApplicationId();
        if (aaid != null) {
            softwareEnforcedVector.add(new DERTaggedObject(true, 709,
                    new DEROctetString(aaid)));
        }

        // KeyDescription sequence
        ASN1EncodableVector keyDescription = new ASN1EncodableVector();
        keyDescription.add(new ASN1Integer(ATTESTATION_VERSION));
        keyDescription.add(new ASN1Enumerated(1)); // attestationSecurityLevel: TEE
        keyDescription.add(new ASN1Integer(KEYMASTER_VERSION));
        keyDescription.add(new ASN1Enumerated(1)); // keymasterSecurityLevel: TEE
        keyDescription.add(new DEROctetString(challenge)); // attestationChallenge
        keyDescription.add(new DEROctetString(new byte[0])); // uniqueId
        keyDescription.add(new DERSequence(softwareEnforcedVector)); // softwareEnforced
        keyDescription.add(new DERSequence(teeEnforcedVector)); // teeEnforced

        return buildCertificate(leafCertificate, certificateHolder, keyAlgorithm,
                new Extension(KEY_ATTESTATION_OID, false,
                        new DEROctetString(new DERSequence(keyDescription))), false);
    }

    private static byte[] buildCertificate(X509Certificate leafCertificate,
            X509CertificateHolder certificateHolder, String keyAlgorithm,
            Extension attestationExtension, boolean copyOriginalExtensions) throws Exception {
        PrivateKey privateKey = KeyboxUtils.getPrivateKey(keyAlgorithm);
        X509CertificateHolder providerCertHolder = KeyboxUtils.getCertificateHolder(keyAlgorithm);

        X509v3CertificateBuilder certificateBuilder = new X509v3CertificateBuilder(
                providerCertHolder.getSubject(),
                certificateHolder.getSerialNumber(),
                certificateHolder.getNotBefore(),
                certificateHolder.getNotAfter(),
                certificateHolder.getSubject(),
                certificateHolder.getSubjectPublicKeyInfo()
        );

        // The original leaf's sigAlgName describes how its real HW-attestation
        // parent signed it — that parent could be EC even when the leaf's own
        // public key is RSA (and vice versa). Our re-sign uses the keybox's
        // private key, so the signature algorithm must match the keybox key's
        // type, otherwise Conscrypt's OpenSSLSignature.checkEngineType throws
        // "Signature initialized as EC (not EC)" (it compares native pkey
        // type IDs internally).
        String sigAlg = KeyProperties.KEY_ALGORITHM_EC.equals(keyAlgorithm)
                ? "SHA256withECDSA"
                : "SHA256withRSA";
        ContentSigner contentSigner = new JcaContentSignerBuilder(sigAlg)
                .build(privateKey);

        if (!copyOriginalExtensions) {
            // A real KeyMint attestation leaf carries a critical KeyUsage
            // (digitalSignature) — and it comes BEFORE the attestation
            // extension in the extension set. The
            // from-scratch path builds on a plain, unattested key certificate
            // and would otherwise emit ONLY the attestation extension, so add
            // KeyUsage explicitly first. The modify path (copyOriginalExtensions
            // == true) already carries the real leaf's extensions over below.
            certificateBuilder.addExtension(Extension.keyUsage, true,
                    new KeyUsage(KeyUsage.digitalSignature));
        }

        certificateBuilder.addExtension(attestationExtension);

        if (certificateHolder.getExtensions() != null) {
            for (ASN1ObjectIdentifier extensionOID :
                    certificateHolder.getExtensions().getExtensionOIDs()) {
                Log.i(TAG, "Original cert has extension: " + extensionOID.getId());
            }
            if (copyOriginalExtensions) {
                for (ASN1ObjectIdentifier extensionOID :
                        certificateHolder.getExtensions().getExtensionOIDs()) {
                    if (KEY_ATTESTATION_OID.getId().equals(extensionOID.getId())) continue;
                    if (Extension.authorityKeyIdentifier.equals(extensionOID)) continue;
                    certificateBuilder.addExtension(
                            certificateHolder.getExtension(extensionOID));
                }
            }
        }

        byte[] encoded = certificateBuilder.build(contentSigner).getEncoded();
        Log.i(TAG, "Built leaf cert (" + encoded.length + " bytes), sigAlg="
                + sigAlg + " (orig=" + leafCertificate.getSigAlgName() + ")"
                + ", copyExt=" + copyOriginalExtensions);
        if (DEBUG) {
            // Offline-parse dumps for the from-scratch fidelity diff: the plain
            // keystore2 leaf we started from and the forged leaf we emit. Single
            // line each (~1-2KB b64, under the logger payload cap). Enable with:
            // setprop log.tag.KeyboxImitationHooks DEBUG (+ force-stop the app
            // so its next process re-evaluates isLoggable).
            Log.i(TAG, "LEAF-ORIG-B64:" + android.util.Base64.encodeToString(
                    leafCertificate.getEncoded(), android.util.Base64.NO_WRAP));
            Log.i(TAG, "LEAF-BUILT-B64:" + android.util.Base64.encodeToString(
                    encoded, android.util.Base64.NO_WRAP));
        }
        return encoded;
    }

    private static void addBootAndPatchInfo(ASN1EncodableVector teeEnforcedVector)
            throws Exception {
        // Fill the RootOfTrust with the REAL verified-boot values of the
        // claimed device, sourced from its factory image and delivered through
        // the certified-props overlay (SimplePropImitation). verifiedBootHash is
        // the same value published as ro.boot.vbmeta.digest, and verifiedBootKey
        // is the SHA-256 of the device's AVB signing key — so the forged
        // attestation agrees with the spoofed props and with what Google knows
        // for that fingerprint. A stock locked device reports exactly these;
        // the previous ephemeral-random values (from an unpersisted
        // Settings.Secure read) were internally incoherent.
        byte[] verifiedBootKey = decodeHexOrRandom(
                com.android.internal.util.SimplePropImitation.getVerifiedBootKey());
        byte[] verifiedBootHash = decodeHexOrRandom(
                com.android.internal.util.SimplePropImitation.getVerifiedBootHash());

        ASN1Encodable[] rootOfTrustEncodables = {
                new DEROctetString(verifiedBootKey),
                ASN1Boolean.TRUE,        // deviceLocked
                new ASN1Enumerated(0),   // verifiedBootState: Verified (green)
                new DEROctetString(verifiedBootHash)
        };

        teeEnforcedVector.add(new DERTaggedObject(true, 704, new DERSequence(rootOfTrustEncodables)));
        teeEnforcedVector.add(new DERTaggedObject(true, 705, new ASN1Integer(getOsVersion())));
        teeEnforcedVector.add(new DERTaggedObject(true, 706, new ASN1Integer(getPatchLevel())));
        // ATTESTATION_ID_* tags. Reproduce EXACTLY the set keystore was asked to
        // attest — captured by the SPI before its no-attestation fallback strips
        // them — so the forged cert is a protocol-faithful copy of what a stock
        // KeyMint attestation would carry for this (spoofed) device, INCLUDING the
        // synthetic serial/IMEI/MEID (713/714/715). Tags 710..717 sit between
        // osPatchLevel (706) and vendorPatchLevel (718); emit ascending to keep
        // the DER SEQUENCE sorted. Fall back to the Build.*_FOR_ATTESTATION subset
        // when no captured set is present (e.g. the modify path or a non-keygen
        // retrieval), which is what the previous implementation always did.
        final Map<Integer, byte[]> attestIds = sPendingAttestIds;
        sPendingAttestIds = null;
        if (attestIds != null && !attestIds.isEmpty()) {
            for (int tag = 710; tag <= 717; tag++) {
                byte[] value = attestIds.get(tag);
                if (value != null) {
                    teeEnforcedVector.add(new DERTaggedObject(true, tag,
                            new DEROctetString(value)));
                }
            }
        } else {
            teeEnforcedVector.add(new DERTaggedObject(true, 710,
                    new DEROctetString(Build.BRAND_FOR_ATTESTATION.getBytes())));
            teeEnforcedVector.add(new DERTaggedObject(true, 711,
                    new DEROctetString(Build.DEVICE_FOR_ATTESTATION.getBytes())));
            teeEnforcedVector.add(new DERTaggedObject(true, 712,
                    new DEROctetString(Build.PRODUCT_FOR_ATTESTATION.getBytes())));
            teeEnforcedVector.add(new DERTaggedObject(true, 716,
                    new DEROctetString(Build.MANUFACTURER_FOR_ATTESTATION.getBytes())));
            teeEnforcedVector.add(new DERTaggedObject(true, 717,
                    new DEROctetString(Build.MODEL_FOR_ATTESTATION.getBytes())));
        }
        teeEnforcedVector.add(new DERTaggedObject(true, 718, new ASN1Integer(getPatchLevelLong())));
        teeEnforcedVector.add(new DERTaggedObject(true, 719, new ASN1Integer(getPatchLevelLong())));
        // attestationIdSecondImei [723] comes after bootPatchLevel [719].
        if (attestIds != null) {
            byte[] secondImei = attestIds.get(723);
            if (secondImei != null) {
                teeEnforcedVector.add(new DERTaggedObject(true, 723,
                        new DEROctetString(secondImei)));
            }
        }
    }

    /**
     * Build this process's attestationApplicationId (DER) exactly as KeyMint
     * emits it:
     *   SEQUENCE {
     *     SET OF PackageInfo { OCTET STRING packageName, INTEGER versionCode },
     *     SET OF OCTET STRING signatureDigests (SHA-256 of signing certs)
     *   }
     * Note the encoding: packageName is an OCTET STRING (not
     * UTF8String) and the digests form a top-level second SET, they are NOT
     * nested inside the PackageInfo. Cached — constant per process.
     */
    private static byte[] getAttestationApplicationId() {
        if (sAttestationApplicationId != null) return sAttestationApplicationId;
        try {
            android.content.Context ctx = android.app.ActivityThread.currentApplication();
            if (ctx == null) return null;
            String pkg = ctx.getPackageName();
            android.content.pm.PackageInfo pi = ctx.getPackageManager().getPackageInfo(pkg,
                    android.content.pm.PackageManager.GET_SIGNING_CERTIFICATES);

            ASN1EncodableVector pkgInfo = new ASN1EncodableVector();
            pkgInfo.add(new DEROctetString(pkg.getBytes("UTF-8")));
            pkgInfo.add(new ASN1Integer(pi.getLongVersionCode()));
            ASN1EncodableVector pkgInfos = new ASN1EncodableVector();
            pkgInfos.add(new DERSequence(pkgInfo));

            java.security.MessageDigest md = java.security.MessageDigest.getInstance("SHA-256");
            ASN1EncodableVector sigs = new ASN1EncodableVector();
            for (android.content.pm.Signature s : pi.signingInfo.getApkContentsSigners()) {
                sigs.add(new DEROctetString(md.digest(s.toByteArray())));
            }

            ASN1EncodableVector aaid = new ASN1EncodableVector();
            aaid.add(new DERSet(pkgInfos));
            aaid.add(new DERSet(sigs));
            sAttestationApplicationId = new DERSequence(aaid).getEncoded();
        } catch (Exception e) {
            Log.e(TAG, "Failed to build attestationApplicationId", e);
        }
        return sAttestationApplicationId;
    }

    /**
     * Decode a hex string into bytes; on null/blank/malformed input fall back
     * to 32 random bytes so a corner case (e.g. attestation issued before the
     * certified props are applied in this process) still yields a well-formed
     * certificate rather than throwing. A spoofed Play Integrity attestation
     * always has the real overlay values by the time it runs.
     */
    private static byte[] decodeHexOrRandom(String hex) {
        if (hex != null && !hex.isEmpty()) {
            try {
                return hexToBytes(hex);
            } catch (RuntimeException e) {
                Log.w(TAG, "Malformed verified-boot hex; using random fallback");
            }
        }
        byte[] fallback = new byte[32];
        new SecureRandom().nextBytes(fallback);
        return fallback;
    }

    private static byte[] hexToBytes(String hex) {
        final int len = hex.length();
        if ((len & 1) != 0) {
            throw new IllegalArgumentException("odd-length hex");
        }
        final byte[] out = new byte[len / 2];
        for (int i = 0; i < len; i += 2) {
            final int hi = Character.digit(hex.charAt(i), 16);
            final int lo = Character.digit(hex.charAt(i + 1), 16);
            if (hi < 0 || lo < 0) {
                throw new IllegalArgumentException("non-hex char");
            }
            out[i / 2] = (byte) ((hi << 4) | lo);
        }
        return out;
    }

    private static int getOsVersion() {
        String release = Build.VERSION.RELEASE;
        int major = 0, minor = 0, patch = 0;

        try {
            String[] parts = release.split("\\.");
            if (parts.length > 0) major = Integer.parseInt(parts[0]);
            if (parts.length > 1) minor = Integer.parseInt(parts[1]);
            if (parts.length > 2) patch = Integer.parseInt(parts[2]);
        } catch (NumberFormatException e) {
            major = 17;
        }

        return major * 10000 + minor * 100 + patch;
    }

    private static int getPatchLevel() {
        return convertPatchLevel(Build.VERSION.SECURITY_PATCH, false);
    }

    private static int getPatchLevelLong() {
        return convertPatchLevel(Build.VERSION.SECURITY_PATCH, true);
    }

    private static int convertPatchLevel(String patchLevel, boolean longFormat) {
        try {
            String[] parts = patchLevel.split("-");
            int year = Integer.parseInt(parts[0]);
            int month = Integer.parseInt(parts[1]);
            if (longFormat) {
                int day = Integer.parseInt(parts[2]);
                return year * 10000 + month * 100 + day;
            } else {
                return year * 100 + month;
            }
        } catch (Exception e) {
            Log.e(TAG, "Invalid patch level: " + patchLevel, e);
            return 202404;
        }
    }

    private static void dlog(String msg) {
        if (DEBUG) Log.d(TAG, msg);
    }
}
