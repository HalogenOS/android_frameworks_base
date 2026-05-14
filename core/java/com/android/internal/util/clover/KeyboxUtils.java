/*
 * SPDX-FileCopyrightText: 2025 Neoteric OS
 * SPDX-FileCopyrightText: 2025 The Clover Project
 * SPDX-License-Identifier: Apache-2.0
 */
package com.android.internal.util.clover;

import android.security.keystore.KeyProperties;

import com.android.internal.org.bouncycastle.asn1.ASN1Primitive;
import com.android.internal.org.bouncycastle.asn1.ASN1Sequence;
import com.android.internal.org.bouncycastle.asn1.DERNull;
import com.android.internal.org.bouncycastle.asn1.pkcs.PKCSObjectIdentifiers;
import com.android.internal.org.bouncycastle.asn1.pkcs.PrivateKeyInfo;
import com.android.internal.org.bouncycastle.asn1.pkcs.RSAPrivateKey;
import com.android.internal.org.bouncycastle.asn1.sec.ECPrivateKey;
import com.android.internal.org.bouncycastle.asn1.x509.AlgorithmIdentifier;
import com.android.internal.org.bouncycastle.asn1.x9.X9ObjectIdentifiers;
import com.android.internal.org.bouncycastle.cert.X509CertificateHolder;

import java.io.ByteArrayOutputStream;
import java.security.KeyFactory;
import java.security.PrivateKey;
import java.security.spec.PKCS8EncodedKeySpec;
import java.util.Base64;

/**
 * @hide
 */
public class KeyboxUtils {

    public static byte[] decodePemOrBase64(String input) {
        String base64 = input
                .replaceAll("-----BEGIN [^-]+-----", "")
                .replaceAll("-----END [^-]+-----", "")
                .replaceAll("\\s+", "");
        return Base64.getDecoder().decode(base64);
    }

    public static PrivateKey parsePrivateKey(String encodedKey, String algorithm) throws Exception {
        byte[] keyBytes = decodePemOrBase64(encodedKey);
        ASN1Primitive primitive = ASN1Primitive.fromByteArray(keyBytes);
        if (KeyProperties.KEY_ALGORITHM_EC.equalsIgnoreCase(algorithm)) {
            try {
                // Try parsing as PKCS#8
                PrivateKeyInfo info = PrivateKeyInfo.getInstance(primitive);
                return KeyFactory.getInstance(KeyProperties.KEY_ALGORITHM_EC).generatePrivate(new PKCS8EncodedKeySpec(info.getEncoded()));
            } catch (Exception e) {
                // Possibly SEC1 / PKCS#1 EC
                ASN1Sequence seq = ASN1Sequence.getInstance(primitive);
                ECPrivateKey ecPrivateKey = ECPrivateKey.getInstance(seq);
                AlgorithmIdentifier algId = new AlgorithmIdentifier(X9ObjectIdentifiers.id_ecPublicKey, ecPrivateKey.getParameters());
                PrivateKeyInfo privInfo = new PrivateKeyInfo(algId, ecPrivateKey);
                PKCS8EncodedKeySpec pkcs8Spec = new PKCS8EncodedKeySpec(privInfo.getEncoded());
                return KeyFactory.getInstance(KeyProperties.KEY_ALGORITHM_EC).generatePrivate(pkcs8Spec);
            }
        } else if (KeyProperties.KEY_ALGORITHM_RSA.equalsIgnoreCase(algorithm)) {
            try {
                // Try parsing as PKCS#8
                return KeyFactory.getInstance(KeyProperties.KEY_ALGORITHM_RSA).generatePrivate(new PKCS8EncodedKeySpec(keyBytes));
            } catch (Exception e) {
                // Parse as PKCS#1
                RSAPrivateKey rsaKey = RSAPrivateKey.getInstance(primitive);
                AlgorithmIdentifier algId = new AlgorithmIdentifier(PKCSObjectIdentifiers.rsaEncryption, DERNull.INSTANCE);
                PrivateKeyInfo privInfo = new PrivateKeyInfo(algId, rsaKey);
                PKCS8EncodedKeySpec pkcs8Spec = new PKCS8EncodedKeySpec(privInfo.getEncoded());
                return KeyFactory.getInstance(KeyProperties.KEY_ALGORITHM_RSA).generatePrivate(pkcs8Spec);
            }
        } else {
            throw new IllegalArgumentException("Unsupported algorithm: " + algorithm);
        }
    }

    public static byte[] getCertificateChain(String algorithm) throws Exception {
        String[] chain = getOrderedChain(algorithm);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        for (String cert : chain) out.write(decodePemOrBase64(cert));
        return out.toByteArray();
    }

    public static PrivateKey getPrivateKey(String algorithm) throws Exception {
        IKeyboxProvider provider = KeyProviderManager.getProvider();
        String privateKeyEncoded = KeyProperties.KEY_ALGORITHM_EC.equals(algorithm)
                ? provider.getEcPrivateKey()
                : provider.getRsaPrivateKey();

        return parsePrivateKey(privateKeyEncoded, algorithm);
    }

    public static X509CertificateHolder getCertificateHolder(String algorithm) throws Exception {
        // The leaf-parent — the cert whose private key matches the keybox's
        // PrivateKey — is element 0 of the leaf-first ordered chain. Its
        // subject becomes the issuer DN that our synthesised leaf claims.
        String[] chain = getOrderedChain(algorithm);
        return new X509CertificateHolder(decodePemOrBase64(chain[0]));
    }

    /**
     * Return the keybox certificate chain in X.509 leaf-first order:
     * {@code [leaf-parent, intermediate, ..., root]}.
     *
     * <p>Some keybox XML files store their chain in root-first order
     * ({@code [root, intermediate, ..., leaf-parent]}). Strict X.509
     * validators (OpenSSL, AOSP key attestation app) walk the chain
     * positionally — verifying {@code cert[i].signature} against
     * {@code cert[i+1].publicKey} — and reject a root-first chain with
     * {@code WRONG_PUBLIC_KEY_TYPE} because the leaf was signed with the
     * leaf-parent's key (typically EC) but the chain claims the next link
     * is the root (typically RSA for a Google Hardware Attestation root).
     *
     * <p>We detect root-first order by checking whether {@code chain[0]} is
     * self-signed (issuer == subject), which is the defining property of a
     * root certificate. In that case we reverse so callers always see the
     * canonical X.509 order.
     */
    private static String[] getOrderedChain(String algorithm) throws Exception {
        IKeyboxProvider provider = KeyProviderManager.getProvider();
        String[] chain = KeyProperties.KEY_ALGORITHM_EC.equals(algorithm)
                ? provider.getEcCertificateChain()
                : provider.getRsaCertificateChain();
        if (chain.length <= 1) {
            return chain;
        }
        X509CertificateHolder first = new X509CertificateHolder(
                decodePemOrBase64(chain[0]));
        if (!first.getIssuer().equals(first.getSubject())) {
            return chain;
        }
        String[] reversed = new String[chain.length];
        for (int i = 0; i < chain.length; i++) {
            reversed[i] = chain[chain.length - 1 - i];
        }
        return reversed;
    }
}
