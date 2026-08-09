/*
 * Copyright (C) 2026 The halogenOS Project
 *
 * SPDX-License-Identifier: Apache-2.0
 */
package com.android.internal.util;

import android.Manifest;
import android.app.AppGlobals;
import android.content.Context;
import android.content.pm.PackageManager;
import android.os.Build;
import android.provider.Settings;
import android.telephony.TelephonyManager;
import android.util.Log;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

/**
 * Supplies device identifiers for key attestation without throwing when the
 * caller is not permitted to read the real ones.
 *
 * Callers running without READ_PRIVILEGED_PHONE_STATE (e.g. sandboxed GMS)
 * request {@code ID_TYPE_IMEI} attestation during key generation. Reading the
 * real IMEI there raises a {@link SecurityException}. Instead of letting that
 * exception surface, derive a stable synthetic IMEI from the SSAID so the read
 * completes normally. On an RKP-only device the identifier tags are stripped by
 * keystore anyway ({@code CANNOT_ATTEST_IDS}), so the synthetic value never
 * reaches a verifiable certificate — its only purpose is to keep the read from
 * throwing. The real IMEI is never exposed.
 *
 * @hide
 */
public final class SyntheticDeviceId {

    private static final String TAG = "SyntheticDeviceId";

    private SyntheticDeviceId() {}

    /**
     * IMEI for {@code slot}. When the caller holds READ_PRIVILEGED_PHONE_STATE
     * the real value is returned; otherwise a deterministic synthetic IMEI
     * derived from the SSAID is returned so the caller never observes a
     * {@link SecurityException}. May return {@code null} only if no seed at all
     * is available.
     */
    public static String imeiForAttestation(TelephonyManager telephony, int slot) {
        final Context ctx = AppGlobals.getInitialApplication();
        if (ctx != null && telephony != null
                && ctx.checkSelfPermission(Manifest.permission.READ_PRIVILEGED_PHONE_STATE)
                        == PackageManager.PERMISSION_GRANTED) {
            try {
                return telephony.getImei(slot);
            } catch (SecurityException e) {
                // The permission check passed but the read was still denied
                // (e.g. AppOps). Fall through to a synthetic value rather than
                // let the exception reach the caller's key generation.
                Log.w(TAG, "getImei denied despite permission; using synthetic IMEI");
            }
        }
        return deriveImei(ctx, slot);
    }

    /**
     * A stable synthetic IMEI for the current process, derived from the SSAID.
     * Gives sandboxed GMS a consistent device identifier without exposing the
     * real one and without any privileged read.
     */
    public static String synthesizeImei(int slot) {
        return deriveImei(AppGlobals.getInitialApplication(), slot);
    }

    /**
     * A stable synthetic IMSI for the current process, derived from the SSAID.
     * Same shape and determinism as the synthetic IMEI, but a distinct value,
     * so subscriber-id reads get a consistent non-real identifier.
     */
    public static String synthesizeImsi(int subId) {
        return deriveImei(AppGlobals.getInitialApplication(), ":imsi:", subId);
    }

    private static String deriveImei(Context ctx, int slot) {
        return deriveImei(ctx, ":imei:", slot);
    }

    private static String deriveImei(Context ctx, String purpose, int slot) {
        String seed = null;
        if (ctx != null) {
            seed = Settings.Secure.getString(
                    ctx.getContentResolver(), Settings.Secure.ANDROID_ID);
            // GMS-only: when identity rotation is active, mix its seed into
            // the derivation so the synthetic IMEI/IMSI shift together with
            // the rotated checkin androidId — a rotated identity must not
            // keep the old record's identifiers. Non-GMS callers (user-opted
            // apps on the native identity) keep their stable values.
            if (android.app.compat.gms.GmsCompat.isEnabled()) {
                String rotation = Settings.Secure.getString(
                        ctx.getContentResolver(),
                        Settings.Secure.ATTESTATION_ANDROID_ID_REASSIGN);
                if (rotation == null || rotation.isEmpty()) {
                    rotation = Settings.Secure.getString(
                            ctx.getContentResolver(),
                            Settings.Secure.ATTESTATION_ANDROID_ID_OVERRIDE);
                }
                if (rotation != null && !rotation.isEmpty()) {
                    seed = seed + ":rotate:" + rotation;
                }
            }
        }
        if (seed == null || seed.isEmpty()) {
            seed = Build.FINGERPRINT;
        }
        final byte[] digest;
        try {
            digest = MessageDigest.getInstance("SHA-256")
                    .digest((seed + purpose + slot).getBytes(StandardCharsets.UTF_8));
        } catch (NoSuchAlgorithmException e) {
            Log.e(TAG, "SHA-256 unavailable, cannot derive IMEI", e);
            return null;
        }
        final int[] digits = new int[15];
        for (int i = 0; i < 14; i++) {
            digits[i] = (digest[i] & 0xff) % 10;
        }
        digits[14] = luhnCheckDigit(digits);
        final StringBuilder sb = new StringBuilder(15);
        for (int d : digits) {
            sb.append((char) ('0' + d));
        }
        return sb.toString();
    }

    /**
     * Luhn check digit over the 14-digit payload in {@code digits[0..13]}, so
     * the full 15-digit IMEI validates (doubling every second digit starting
     * from the rightmost payload digit).
     */
    private static int luhnCheckDigit(int[] digits) {
        int sum = 0;
        for (int i = 0; i < 14; i++) {
            int d = digits[13 - i];
            if (i % 2 == 0) {
                d *= 2;
                if (d > 9) {
                    d -= 9;
                }
            }
            sum += d;
        }
        return (10 - (sum % 10)) % 10;
    }
}
