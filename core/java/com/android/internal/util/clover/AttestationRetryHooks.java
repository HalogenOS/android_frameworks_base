/*
 * Copyright (C) 2026 The halogenOS Project
 *
 * SPDX-License-Identifier: Apache-2.0
 */
package com.android.internal.util.clover;

import android.hardware.security.keymint.KeyParameter;
import android.hardware.security.keymint.Tag;
import android.security.KeyStoreException;
import android.security.keymaster.KeymasterDefs;
import android.system.keystore2.KeyDescriptor;
import android.system.keystore2.KeyMetadata;
import android.security.KeyStoreSecurityLevel;
import android.util.Log;

import com.android.internal.util.SimplePropImitation;

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
 *     (which is what Pong does);
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
 * Without this fallback the Play Integrity BASIC verdict cannot pass on Pong
 * unlocked, because the cert chain sent to Google's PI server has no attestation
 * extension at all.
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

        // Only fire when spoofing is active (bootloader unlocked). On locked
        // devices the caller would otherwise lose legitimate device-ID
        // attestation by falling into the strip path — leave the original
        // failure to surface so normal Android security flow takes over.
        if (!SimplePropImitation.isBootloaderUnlocked()) {
            return null;
        }

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
            return iSecurityLevel.generateKey(descriptor, attestKeyDescriptor,
                    stripped, flags, additionalEntropy);
        } catch (KeyStoreException retryErr) {
            Log.w(TAG, "ID-stripped retry failed: " + retryErr.getErrorCode());
            return null;
        }
    }
}
