/*
 * SPDX-FileCopyrightText: 2024 Paranoid Android
 * SPDX-FileCopyrightText: 2025 Neoteric OS
 * SPDX-FileCopyrightText: 2025 The Clover Project
 * SPDX-License-Identifier: Apache-2.0
 */
package com.android.internal.util.clover;

import android.app.ActivityThread;
import android.content.Context;
import android.os.Build;
import android.provider.Settings;
import android.security.KeyChain;
import android.security.keystore.KeyProperties;
import android.system.keystore2.KeyEntryResponse;
import android.util.Base64;
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
import com.android.internal.org.bouncycastle.cert.X509CertificateHolder;
import com.android.internal.org.bouncycastle.cert.X509v3CertificateBuilder;
import com.android.internal.org.bouncycastle.operator.ContentSigner;
import com.android.internal.org.bouncycastle.operator.jcajce.JcaContentSignerBuilder;

import java.security.PrivateKey;
import java.security.SecureRandom;
import java.security.cert.X509Certificate;

/**
 * @hide
 */
public class KeyboxImitationHooks {

    private static final String TAG = "KeyboxImitationHooks";
    private static final boolean DEBUG = Log.isLoggable(TAG, Log.DEBUG);

    private static final ASN1ObjectIdentifier KEY_ATTESTATION_OID = new ASN1ObjectIdentifier(
            "1.3.6.1.4.1.11129.2.1.17");

    private static volatile byte[] sPendingChallenge;
    private static volatile byte[] sPendingApplicationId;

    public static void setAttestationChallenge(byte[] challenge) {
        sPendingChallenge = challenge;
    }

    public static void setAttestationApplicationId(byte[] applicationId) {
        sPendingApplicationId = applicationId;
    }

    public static KeyEntryResponse onGetKeyEntry(KeyEntryResponse response) {
        // Spoof key attestation whenever the bootloader isn't OEM-verified
        // (green). On orange (no AVB) and yellow (AVB with our custom test
        // key — not trusted by Google) the native attestation chain chains
        // to a key Google does not recognise, so PI would reject it; we have
        // to substitute our keybox chain in both cases. The value is cached
        // by SimplePropImitation before any sysprop spoofing, so reading it
        // here is safe.
        if (!com.android.internal.util.SimplePropImitation.shouldSpoof()) {
            dlog("Bootloader OEM-verified (green) — skipping key attestation spoofing");
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

        // Build softwareEnforced with AAID and creationDateTime (matching 16.0 format)
        ASN1EncodableVector softwareEnforcedVector = new ASN1EncodableVector();
        softwareEnforcedVector.add(new DERTaggedObject(true, 701,
                new ASN1Integer(System.currentTimeMillis())));
        byte[] applicationId = sPendingApplicationId;
        if (applicationId != null) {
            sPendingApplicationId = null;
            softwareEnforcedVector.add(new DERTaggedObject(true, 709,
                    new DEROctetString(applicationId)));
        }

        // KeyDescription sequence
        ASN1EncodableVector keyDescription = new ASN1EncodableVector();
        keyDescription.add(new ASN1Integer(100)); // attestationVersion (KeyMint 1.0)
        keyDescription.add(new ASN1Enumerated(1)); // attestationSecurityLevel: TEE
        keyDescription.add(new ASN1Integer(100)); // keymasterVersion (KeyMint 1.0)
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

        ContentSigner contentSigner = new JcaContentSignerBuilder(
                leafCertificate.getSigAlgName()).build(privateKey);

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
                + leafCertificate.getSigAlgName()
                + ", copyExt=" + copyOriginalExtensions);
        return encoded;
    }

    private static void addBootAndPatchInfo(ASN1EncodableVector teeEnforcedVector)
            throws Exception {
        Context context = ActivityThread.currentApplication();
        if (context == null) {
            throw new IllegalStateException("Context is null");
        }
        SecureRandom secureRandom = new SecureRandom();

        String key = Settings.Secure.getString(
                context.getContentResolver(), Settings.Secure.VBOOT_KEY);
        byte[] verifiedBootKey;
        if (key == null) {
            byte[] randomBytes = new byte[32];
            secureRandom.nextBytes(randomBytes);
            String encoded = Base64.encodeToString(randomBytes, Base64.NO_WRAP);
            Settings.Secure.putString(
                    context.getContentResolver(), Settings.Secure.VBOOT_KEY, encoded);
            verifiedBootKey = randomBytes;
        } else {
            verifiedBootKey = Base64.decode(key, Base64.NO_WRAP);
        }

        String hash = Settings.Secure.getString(
                context.getContentResolver(), Settings.Secure.VBOOT_HASH);
        byte[] verifiedBootHash;
        if (hash == null) {
            byte[] randomBytes = new byte[32];
            secureRandom.nextBytes(randomBytes);
            String encoded = Base64.encodeToString(randomBytes, Base64.NO_WRAP);
            Settings.Secure.putString(
                    context.getContentResolver(), Settings.Secure.VBOOT_HASH, encoded);
            verifiedBootHash = randomBytes;
        } else {
            verifiedBootHash = Base64.decode(hash, Base64.NO_WRAP);
        }

        ASN1Encodable[] rootOfTrustEncodables = {
                new DEROctetString(verifiedBootKey),
                ASN1Boolean.TRUE,
                new ASN1Enumerated(0),
                new DEROctetString(verifiedBootHash)
        };

        teeEnforcedVector.add(new DERTaggedObject(true, 704, new DERSequence(rootOfTrustEncodables)));
        teeEnforcedVector.add(new DERTaggedObject(true, 705, new ASN1Integer(getOsVersion())));
        teeEnforcedVector.add(new DERTaggedObject(true, 706, new ASN1Integer(getPatchLevel())));
        // ATTESTATION_ID_* tags (710-717). Build.<X>_FOR_ATTESTATION is the
        // canonical source used by AndroidKeyStore attestation; we already
        // overwrite those fields via SimplePropImitation reflection. The 16.0
        // KeyboxChainGenerator included these in teeEnforced and it worked;
        // 16.2 createLeafCertificate omitted them, which leaves the cert
        // without device identity and Google's PI server can't verify.
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
        teeEnforcedVector.add(new DERTaggedObject(true, 718, new ASN1Integer(getPatchLevelLong())));
        teeEnforcedVector.add(new DERTaggedObject(true, 719, new ASN1Integer(getPatchLevelLong())));
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
