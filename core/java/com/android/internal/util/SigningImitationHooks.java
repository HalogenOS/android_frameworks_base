/*
 * Copyright (C) 2026 The halogenOS Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.android.internal.util;

import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.pm.Signature;
import android.content.pm.SigningDetails;
import android.content.pm.SigningInfo;
import android.util.Log;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.cert.CertificateException;
import java.util.Arrays;
import java.util.List;

/**
 * SigningImitationHooks - report the stock platform signing certificate of the
 * claimed device to spoof-target processes (GMS/Finsky).
 *
 * Integrity probes hash the signing certificates PackageManager returns. On
 * stock the `android` package (and every other platform-signed package) is
 * signed with Google's platform cert; on a custom ROM it is the ROM's own
 * cert — a direct tell no prop-layer spoof can fix. This hook rewrites the
 * *return values* of the signing-info query APIs in spoof-target processes so
 * any signature equal to our real platform cert is reported as the stock cert
 * from the conformance attributes (ATTEST.PLATFORM_CERT).
 *
 * Report-time only: install- and verify-time paths (PackageInstaller, installd,
 * signature permission checks) run in system_server and are unreachable from
 * this in-process rewrite.
 *
 * @hide
 */
public class SigningImitationHooks {

    private static final String TAG = "SigningImitationHooks";
    private static final boolean DEBUG = Log.isLoggable(TAG, Log.DEBUG);

    /**
     * java.util.Arrays.hashCode(byte[]) of our real platform signing cert —
     * the same value PackageManager reports as the legacy signatures[] int
     * (visible in dumpsys to every app, so nothing secret is baked in).
     * Matching by hashCode lets this hook identify platform-signed packages
     * WITHOUT holding the real cert's DER bytes anywhere in the spoof-target
     * process: no live real-cert query (its PackageInfo parcel would linger
     * in dalvik), no DER static (reflection-readable).
     */
    private static final int REAL_PLATFORM_CERT_HASHCODE = 0x54311fdd;

    /** SHA-256 of our real platform cert's DER (for CERT_INPUT_SHA256 queries). */
    private static final byte[] REAL_PLATFORM_CERT_SHA256 = hex(
            "237e2d2a18f24703f621429a6ec5678ce89c54cf6ddc2ec0c1e24f51742bf150");

    /** Set while this thread issues a raw (unrewritten) signing-info query. */
    private static final ThreadLocal<Boolean> sRawQuery = new ThreadLocal<>();

    private SigningImitationHooks() {
    }

    /** Raw (never rewritten) signer query for internal use. */
    private static Signature[] rawSigners(PackageManager pm, String packageName) {
        sRawQuery.set(Boolean.TRUE);
        try {
            PackageInfo pi = pm.getPackageInfo(
                    packageName, PackageManager.GET_SIGNING_CERTIFICATES);
            return pi.signingInfo != null ? pi.signingInfo.getApkContentsSigners() : null;
        } catch (Exception e) {
            return null;
        } finally {
            sRawQuery.remove();
        }
    }

    /**
     * The stock DER when the rewrite applies (spoof-target process, cert
     * armed, not a raw internal query), else null.
     */
    private static byte[] armedStock() {
        if (sRawQuery.get() != null || !SimplePropImitation.isSpoofTarget()) {
            return null;
        }
        return SimplePropImitation.getPlatformCertDer();
    }

    /**
     * Rewrite the signer certs of a PackageInfo about to be returned to app
     * code. No-op outside spoof-target processes and for packages not signed
     * with our platform cert.
     */
    public static void maybeRewritePackageInfo(PackageManager pm, PackageInfo pi) {
        if (pi == null || pi.signingInfo == null) {
            return;
        }
        byte[] stock = armedStock();
        if (stock == null) {
            return;
        }
        if (rewritePackageInfoByHash(pi, stock) && DEBUG) {
            Log.d(TAG, "reporting stock platform cert for " + pi.packageName);
        }
    }

    /**
     * Pure rewrite (DER match) for the server-side hook: replace every
     * signature equal to {@code realDer} with {@code stockDer}. The server
     * knows the real DER from its own package database (system_server-local,
     * invisible to spoof-target processes).
     */
    public static boolean rewritePackageInfo(PackageInfo pi, byte[] realDer, byte[] stockDer) {
        return rewritePackageInfoInternal(pi, stockDer,
                (sigs) -> rewrite(sigs, realDer, stockDer));
    }

    /**
     * Pure rewrite (hashCode match) for the client-side hook: replace every
     * signature whose DER hashes to our real platform cert's hashCode with
     * {@code stockDer} — no real-cert bytes needed in this process.
     */
    public static boolean rewritePackageInfoByHash(PackageInfo pi, byte[] stockDer) {
        return rewritePackageInfoInternal(pi, stockDer,
                (sigs) -> rewriteByHash(sigs, stockDer));
    }

    private interface SigRewriter {
        Signature[] apply(Signature[] sigs);
    }

    private static boolean rewritePackageInfoInternal(PackageInfo pi, byte[] stockDer,
            SigRewriter rw) {
        if (pi == null || pi.signingInfo == null) {
            return false;
        }
        SigningDetails sd = pi.signingInfo.getSigningDetails();
        if (sd == null) {
            return false;
        }
        Signature[] sigs = sd.getSignatures();
        if (sigs == null) {
            return false;
        }
        Signature[] newSigs = rw.apply(sigs);
        Signature[] newPast = rw.apply(sd.getPastSigningCertificates());
        if (newSigs == null && newPast == null) {
            return false; // not signed with our platform cert
        }
        try {
            // SigningDetails derives the public keys from the certs, so the
            // stock cert's public key comes along consistently.
            pi.signingInfo = new SigningInfo(new SigningDetails(
                    newSigs != null ? newSigs : sigs,
                    sd.getSignatureSchemeVersion(),
                    newPast != null ? newPast : sd.getPastSigningCertificates()));
        } catch (CertificateException e) {
            return false; // leave the real info untouched
        }
        if (pi.signatures != null) {
            Signature[] legacy = rw.apply(pi.signatures);
            if (legacy != null) {
                pi.signatures = legacy;
            }
        }
        return true;
    }

    /**
     * Bulk variant for the installed-packages queries — the probe enumerates
     * the app set, and a bulk result must not leak the real cert either.
     */
    public static void maybeRewritePackageInfoList(PackageManager pm, List<PackageInfo> pis) {
        if (pis == null || !SimplePropImitation.isSpoofTarget()
                || SimplePropImitation.getPlatformCertDer() == null) {
            return;
        }
        for (PackageInfo pi : pis) {
            maybeRewritePackageInfo(pm, pi);
        }
    }

    /**
     * Replace signatures whose bytes equal {@code real} with {@code stock}.
     * Returns null when nothing matched (caller keeps the original array).
     */
    private static Signature[] rewrite(Signature[] sigs, byte[] real, byte[] stock) {
        if (sigs == null) {
            return null;
        }
        Signature[] out = null;
        for (int i = 0; i < sigs.length; i++) {
            if (Arrays.equals(sigs[i].toByteArray(), real)) {
                if (out == null) {
                    out = sigs.clone();
                }
                out[i] = new Signature(stock);
            }
        }
        return out;
    }

    /**
     * Replace signatures whose DER hashes to our real platform cert's
     * hashCode with {@code stock}. Returns null when nothing matched.
     */
    private static Signature[] rewriteByHash(Signature[] sigs, byte[] stock) {
        if (sigs == null) {
            return null;
        }
        Signature[] out = null;
        for (int i = 0; i < sigs.length; i++) {
            if (javaHashCode(sigs[i].toByteArray()) == REAL_PLATFORM_CERT_HASHCODE) {
                if (out == null) {
                    out = sigs.clone();
                }
                out[i] = new Signature(stock);
            }
        }
        return out;
    }

    /**
     * Consistency filter for {@code hasSigningCertificate(String, ...)}: under
     * the spoofed view a platform-signed package is signed with the stock
     * cert, so queries for the stock cert must answer true and queries for our
     * real cert false. Handles both raw-X509 and SHA-256 cert input types.
     */
    public static boolean filterHasSigningCertificate(PackageManager pm, String packageName,
            byte[] certificate, int type, boolean realResult) {
        byte[] stock = armedStock();
        if (stock == null || certificate == null) {
            return realResult;
        }
        Boolean queriedStock = matchesEither(certificate, type, stock);
        if (queriedStock == null) {
            return realResult;
        }
        Signature[] signers = rawSigners(pm, packageName);
        if (signers == null) {
            return realResult;
        }
        for (Signature s : signers) {
            if (javaHashCode(s.toByteArray()) == REAL_PLATFORM_CERT_HASHCODE) {
                // Platform-signed package: its spoofed signer is the stock cert.
                return queriedStock;
            }
        }
        return realResult;
    }

    /**
     * Consistency filter for {@code hasSigningCertificate(int uid, ...)} —
     * same as the package variant, over every package of the uid.
     */
    public static boolean filterHasUidSigningCertificate(PackageManager pm, int uid,
            byte[] certificate, int type, boolean realResult) {
        byte[] stock = armedStock();
        if (stock == null || certificate == null) {
            return realResult;
        }
        Boolean queriedStock = matchesEither(certificate, type, stock);
        if (queriedStock == null) {
            return realResult;
        }
        String[] names = pm.getPackagesForUid(uid);
        if (names == null) {
            return realResult;
        }
        for (String name : names) {
            Signature[] signers = rawSigners(pm, name);
            if (signers == null) {
                continue;
            }
            for (Signature s : signers) {
                if (javaHashCode(s.toByteArray()) == REAL_PLATFORM_CERT_HASHCODE) {
                    return queriedStock;
                }
            }
        }
        return realResult;
    }

    /**
     * True if {@code queried} matches the stock cert, false if it matches our
     * real cert (by hashCode/sha256 constants — no real DER in this process),
     * null if it matches neither.
     */
    private static Boolean matchesEither(byte[] queried, int type, byte[] stock) {
        if (certEquals(queried, type, stock)) {
            return Boolean.TRUE;
        }
        if (type == PackageManager.CERT_INPUT_SHA256) {
            if (Arrays.equals(queried, REAL_PLATFORM_CERT_SHA256)) {
                return Boolean.FALSE;
            }
        } else if (javaHashCode(queried) == REAL_PLATFORM_CERT_HASHCODE) {
            return Boolean.FALSE;
        }
        return null;
    }

    /**
     * Type-aware certificate comparison (raw X.509 DER or SHA-256 digest,
     * per PackageManager.CertificateInputType). Shared by client- and
     * server-side consistency filters.
     */
    public static boolean certEquals(byte[] a, int type, byte[] b) {
        if (type == PackageManager.CERT_INPUT_SHA256) {
            return Arrays.equals(a, sha256(b));
        }
        return Arrays.equals(a, b);
    }

    private static byte[] sha256(byte[] der) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(der);
        } catch (NoSuchAlgorithmException e) {
            return new byte[0];
        }
    }

    /** java.util.Arrays.hashCode(byte[]) semantics. */
    private static int javaHashCode(byte[] data) {
        int h = 1;
        for (byte b : data) {
            h = 31 * h + b;
        }
        return h;
    }

    private static byte[] hex(String s) {
        byte[] out = new byte[s.length() / 2];
        for (int i = 0; i < out.length; i++) {
            out[i] = (byte) Integer.parseInt(s.substring(i * 2, i * 2 + 2), 16);
        }
        return out;
    }
}
