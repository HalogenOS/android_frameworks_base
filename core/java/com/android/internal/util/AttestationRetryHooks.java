/*
 * Copyright (C) 2026 The halogenOS Project
 *
 * SPDX-License-Identifier: Apache-2.0
 */
package com.android.internal.util;

import android.hardware.security.keymint.KeyParameter;
import android.hardware.security.keymint.Tag;
import android.security.KeyStoreException;
import android.security.keymaster.KeymasterDefs;
import android.system.keystore2.KeyDescriptor;
import android.system.keystore2.KeyMetadata;
import android.security.KeyStoreSecurityLevel;
import android.util.Log;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

/**
 * Helper for keystore2 attestation retry strategies that depend on XOS-specific
 * runtime conditions.
 *
 * On stock Android, AndroidKeyStoreKeyPairGeneratorSpi falls back to a no-attestation
 * key generation when the first attempt fails — losing the attestation extension
 * entirely. That is fine if hardware attestation simply isn't available, but on
 * builds where:
 *
 *   - The bootloader is unlocked and OEM-provisioned attestation keys are gated
 *     (the RKP-only case — no factory batch keys to fall back on);
 *   - Remote Key Provisioning (RKP) is enabled and can still mint hardware-backed
 *     attestation keys;
 *   - The caller (e.g. sandboxed GMS / Finsky on GmsCompat) lacks the
 *     READ_PRIVILEGED_PHONE_STATE permission needed for ATTESTATION_ID_*
 *     validation, producing KM_ERROR_CANNOT_ATTEST_IDS,
 *
 * keystore2 *could* still emit a valid hardware-attested cert chain if we
 * dropped only the ID tags and kept the challenge. This helper attempts that
 * intermediate retry before the SPI gives up and falls back to no-attestation.
 *
 * Without this fallback the BASIC integrity verdict cannot pass on such devices,
 * because the cert chain sent for evaluation has no attestation extension at all.
 *
 * @hide
 */
public final class AttestationRetryHooks {

    private static final String TAG = "AttestationRetryHooks";

    private AttestationRetryHooks() {}

    /**
     * If {@code e} indicates the device IDs couldn't be attested (caller lacks
     * the privileged permission), retry key generation with the ID tags removed
     * but the attestation challenge kept. Returns the resulting KeyMetadata on
     * success, or null if this fallback is not applicable or also fails — in
     * which case the caller should proceed with its existing
     * no-attestation fallback.
     */
    public static KeyMetadata maybeRetryWithoutIds(
            KeyStoreException originalError,
            KeyStoreSecurityLevel iSecurityLevel,
            KeyDescriptor descriptor,
            KeyDescriptor attestKeyDescriptor,
            Collection<KeyParameter> originalArgs,
            int flags,
            byte[] additionalEntropy) {
        if (originalError.getErrorCode() != KeymasterDefs.KM_ERROR_CANNOT_ATTEST_IDS) {
            return null;
        }

        // Always salvage a real, Google-rooted RKP attestation on
        // CANNOT_ATTEST_IDS by dropping the ID tags and keeping the challenge.
        // On an RKP-only device the remote-provisioned key cannot attest ANY
        // device IDs (real or spoofed), so without this the key loses its
        // attestation extension entirely and falls back to no-attestation.
        //
        // Keeping a real attestation here is what BOTH downstream paths need:
        //   - No keybox  -> KeyboxImitationHooks passes the salvaged attestation
        //     through untouched; its real RootOfTrust yields
        //     MEETS_BASIC_INTEGRITY on an unlocked device.
        //   - Keybox present -> KeyboxImitationHooks.onGetKeyEntry *patches*
        //     this real attestation (swaps RootOfTrust to verified/locked and
        //     the device IDs, re-signs with the keybox) — the modify path,
        //     which preserves the fields Google's verifier expects. Falling
        //     back to no-attestation would instead build a minimal cert from
        //     scratch, which the verifier rejects.
        Log.w(TAG, "Attestation IDs not permitted, retrying without IDs");
        List<KeyParameter> stripped = new ArrayList<>(originalArgs);
        stripped.removeIf(p ->
                p.tag == Tag.ATTESTATION_ID_BRAND
                || p.tag == Tag.ATTESTATION_ID_DEVICE
                || p.tag == Tag.ATTESTATION_ID_PRODUCT
                || p.tag == Tag.ATTESTATION_ID_MANUFACTURER
                || p.tag == Tag.ATTESTATION_ID_MODEL
                || p.tag == Tag.ATTESTATION_ID_SERIAL
                || p.tag == Tag.ATTESTATION_ID_IMEI
                || p.tag == Tag.ATTESTATION_ID_SECOND_IMEI
                || p.tag == Tag.ATTESTATION_ID_MEID);

        try {
            KeyMetadata metadata = iSecurityLevel.generateKey(descriptor,
                    attestKeyDescriptor, stripped, flags, additionalEntropy);
            Log.i(TAG, "ID-stripped RKP attestation succeeded (BASIC path)");
            return metadata;
        } catch (KeyStoreException retryErr) {
            Log.w(TAG, "ID-stripped retry failed: " + retryErr.getErrorCode());
            return null;
        }
    }
}
