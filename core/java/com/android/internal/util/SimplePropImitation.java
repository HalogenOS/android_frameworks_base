/*
 * Copyright (C) 2025 The halogenOS Project
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

import android.app.ActivityTaskManager;
import android.app.Application;
import android.app.TaskStackListener;
import android.content.ComponentName;
import android.content.Context;
import android.content.res.Resources;
import android.os.Build;
import android.os.Process;
import android.os.SystemProperties;
import android.text.TextUtils;
import android.util.Log;

import com.android.internal.R;

import java.lang.reflect.Field;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

/**
 * SimplePropImitation - Simplified property imitation for GMS
 * @hide
 */
public class SimplePropImitation {

    private static final String TAG = "SimplePropImitation";
    private static final boolean DEBUG = Log.isLoggable(TAG, Log.DEBUG);

    private static final String PACKAGE_GMS = "com.google.android.gms";
    private static final String PACKAGE_FINSKY = "com.android.vending";
    private static final String PROCESS_GMS_UNSTABLE = PACKAGE_GMS + ".unstable";

    // GMS Add Account Activity - we need to skip imitation when this is on top
    private static final ComponentName GMS_ADD_ACCOUNT_ACTIVITY = ComponentName.unflattenFromString(
            "com.google.android.gms/.auth.uiflows.minutemaid.MinuteMaidActivity");

    // Build field name → system property name mapping
    private static final Map<String, String> FIELD_TO_SYSPROP = Map.ofEntries(
            Map.entry("PRODUCT", "ro.product.name"),
            Map.entry("DEVICE", "ro.product.device"),
            Map.entry("MANUFACTURER", "ro.product.manufacturer"),
            Map.entry("BRAND", "ro.product.brand"),
            Map.entry("MODEL", "ro.product.model"),
            Map.entry("FINGERPRINT", "ro.build.fingerprint"),
            Map.entry("HARDWARE", "ro.hardware"),
            Map.entry("ID", "ro.build.id"),
            Map.entry("TYPE", "ro.build.type"),
            Map.entry("TAGS", "ro.build.tags"),
            Map.entry("VERSION.INCREMENTAL", "ro.build.version.incremental"),
            Map.entry("VERSION.RELEASE", "ro.build.version.release"),
            Map.entry("VERSION.SECURITY_PATCH", "ro.build.version.security_patch"),
            Map.entry("VERSION.DEVICE_INITIAL_SDK_INT", "ro.product.first_api_level")
    );

    // Product property suffix for fields that have partition variants
    private static final Map<String, String> PRODUCT_PROP_SUFFIX = Map.of(
            "PRODUCT", "name",
            "DEVICE", "device",
            "MANUFACTURER", "manufacturer",
            "BRAND", "brand",
            "MODEL", "model"
    );

    // Partition prefixes for product properties that DroidGuard reads via native code
    private static final String[] PARTITION_PREFIXES = {
            "ro.product.vendor.", "ro.product.system.",
            "ro.product.odm.", "ro.product.system_ext.",
    };

    private static volatile List<String> sCertifiedProps = null;
    private static volatile String sProcessName = "";
    private static volatile boolean sSupportsHardwareAttestation = false;
    // Cached at first setProps() call, BEFORE any sysprop spoofing is applied.
    // True when spoofing should run — i.e. ro.boot.verifiedbootstate is anything
    // other than "green". On orange (unlocked) AVB is bypassed entirely; on
    // yellow our test key signed vbmeta but Google does not trust it as an OEM
    // root, so the attestation chain Google sees still needs the same spoof
    // treatment. Only green (Spacewar-style, real OEM-trusted key) bypasses it.
    private static volatile boolean sShouldSpoof = false;
    private static volatile boolean sBootStateChecked = false;

    private static native void nativeSpoofSysProp(String name, String value);
    private static native void nativeEnableSysPropSpoof();

    private SimplePropImitation() {
    }

    /**
     * Set properties for the given context.
     * This is called from Instrumentation when a new application is created.
     */
    public static void setProps(Context context) {
        final String packageName = context.getPackageName();
        final String processName = Application.getProcessName();

        if (TextUtils.isEmpty(packageName) || TextUtils.isEmpty(processName)) {
            Log.e(TAG, "Null package or process name");
            return;
        }

        sProcessName = processName;

        // Read the real bootloader state BEFORE any spoofing is applied. This
        // value gets cached and consumed by KeyboxImitationHooks too — once
        // we spoof ro.boot.verifiedbootstate to "green", a later
        // SystemProperties.get would return the spoofed value.
        if (!sBootStateChecked) {
            String bootState = SystemProperties.get("ro.boot.verifiedbootstate", "");
            // Spoof on anything except green: orange (no AVB) and yellow (AVB
            // with a custom key Google does not recognise as an OEM root) both
            // need the full prop + keybox spoof to pass Play Integrity.
            sShouldSpoof = !"green".equals(bootState);
            sSupportsHardwareAttestation = "green".equals(bootState);
            sBootStateChecked = true;
            Log.i(TAG, "verifiedbootstate=" + bootState
                    + " (shouldSpoof=" + sShouldSpoof + ")");
        }

        // Skip spoofing only when the bootloader is genuinely OEM-verified
        // (green). On orange and yellow the chain Google sees still doesn't
        // chain to a key it trusts, so we run the full spoof path.
        if (!sShouldSpoof) {
            Log.i(TAG, "Bootloader OEM-verified (green) — skipping prop spoofing for " + processName);
            nativeEnableSysPropSpoof();
            return;
        }

        if (PACKAGE_GMS.equals(packageName)) {
            Log.i(TAG, "Spoofing props for " + processName);

            // We handle all GMS processes, but special handling for unstable
            if (PROCESS_GMS_UNSTABLE.equals(processName)) {
                setCertifiedPropsForGms(context);
            } else if (processName.startsWith(PACKAGE_GMS)) {
                loadAndSetCertifiedProps(context);
            }
        } else if (PACKAGE_FINSKY.equals(packageName)) {
            Log.i(TAG, "Spoofing props for " + processName);
            loadAndSetCertifiedProps(context);
        }

        // Lock the native spoof table for all processes
        nativeEnableSysPropSpoof();
    }

    /**
     * Special handling for GMS processes - checks for Add Account activity
     */
    private static void setCertifiedPropsForGms(Context context) {
        loadCertifiedPropsFromResources(context);

        final boolean wasAddAccountOnTop = isGmsAddAccountActivityOnTop();

        // Register a task stack listener to detect when Add Account activity changes
        final TaskStackListener taskStackListener = new TaskStackListener() {
            @Override
            public void onTaskStackChanged() {
                final boolean isAddAccountOnTop = isGmsAddAccountActivityOnTop();
                if (isAddAccountOnTop ^ wasAddAccountOnTop) {
                    dlog("GmsAddAccountActivityOnTop is:" + isAddAccountOnTop 
                            + " was:" + wasAddAccountOnTop + ", killing myself!");
                    // Process will restart automatically later
                    Process.killProcess(Process.myPid());
                }
            }
        };

        if (!wasAddAccountOnTop) {
            dlog("Spoofing build for GMS");
            applyCertifiedProps();
        } else {
            dlog("Skip spoofing build for GMS, because GmsAddAccountActivityOnTop");
        }

        try {
            ActivityTaskManager.getService().registerTaskStackListener(taskStackListener);
        } catch (Exception e) {
            Log.e(TAG, "Failed to register task stack listener!", e);
        }
    }

    /**
     * Load and set certified props without checking for Add Account activity
     */
    private static void loadAndSetCertifiedProps(Context context) {
        loadCertifiedPropsFromResources(context);
        applyCertifiedProps();
    }

    /**
     * Load certified properties from resources
     */
    private static void loadCertifiedPropsFromResources(Context context) {
        if (sCertifiedProps != null) {
            return;
        }

        final Resources res = context.getResources();
        if (res == null) {
            Log.e(TAG, "Null resources");
            return;
        }

        try {
            sCertifiedProps = Arrays.asList(
                    res.getStringArray(R.array.config_certifiedBuildProperties));
            dlog("Loaded " + sCertifiedProps.size() + " certified properties from resources");
        } catch (Exception e) {
            Log.e(TAG, "Failed to load certified properties from resources", e);
            sCertifiedProps = Arrays.asList();
        }
    }

    /**
     * Apply the certified properties
     */
    private static void applyCertifiedProps() {
        if (sCertifiedProps == null || sCertifiedProps.isEmpty()) {
            Log.e(TAG, "No certified props loaded");
            return;
        }

        Log.i(TAG, "Applying " + sCertifiedProps.size() + " certified props");
        String firstApiLevel = null;
        for (String entry : sCertifiedProps) {
            final String[] parts = entry.split(":", 2);
            if (parts.length != 2) {
                Log.e(TAG, "Invalid entry in certified props: " + entry);
                continue;
            }
            setPropValue(parts[0], parts[1]);
            if ("VERSION.DEVICE_INITIAL_SDK_INT".equals(parts[0])) {
                firstApiLevel = parts[1];
            }
            // Spoof the primary system property at native level
            final String sysProp = FIELD_TO_SYSPROP.get(parts[0]);
            if (sysProp != null) {
                nativeSpoofSysProp(sysProp, parts[1]);
            }
            // For product properties, also spoof all partition variants
            final String suffix = PRODUCT_PROP_SUFFIX.get(parts[0]);
            if (suffix != null) {
                for (String prefix : PARTITION_PREFIXES) {
                    nativeSpoofSysProp(prefix + suffix, parts[1]);
                }
                nativeSpoofSysProp("ro.product." + suffix + "_for_attestation", parts[1]);
                // Build.<X>_FOR_ATTESTATION is a static final field initialised
                // from ro.product.<x>_for_attestation at Build class load time —
                // long before our JNI sysprop spoof is active. AndroidKeyStore
                // attestation reads Build.<X>_FOR_ATTESTATION directly (preferred
                // over Build.<X>), so we must reflectively overwrite the cached
                // field too, otherwise keystore2 gets the real device IDs in
                // ATTESTATION_ID_* and Google's PI server rejects as inconsistent.
                setPropValue(parts[0] + "_FOR_ATTESTATION", parts[1]);
            }
        }

        // Spoof boot attestation properties (migrated from system/core init)
        nativeSpoofSysProp("ro.boot.flash.locked", "1");
        nativeSpoofSysProp("ro.boot.verifiedbootstate", "green");
        nativeSpoofSysProp("ro.boot.veritymode", "enforcing");
        nativeSpoofSysProp("ro.boot.vbmeta.device_state", "locked");

        // Spoof properties that DroidGuard checks for device integrity
        if (firstApiLevel != null) {
            nativeSpoofSysProp("ro.vendor.api_level", firstApiLevel);
        }
        nativeSpoofSysProp("ro.revision", "");
        nativeSpoofSysProp("init.svc.adbd", "stopped");
        nativeSpoofSysProp("persist.sys.usb.config", "none");
        nativeSpoofSysProp("sys.usb.config", "none");

        // Spoof build fingerprint across all partition variants
        String fp = Build.FINGERPRINT;
        if (fp != null && !fp.isEmpty()) {
            for (String partition : new String[]{
                    "vendor", "system", "system_ext", "odm", "bootimage"}) {
                nativeSpoofSysProp("ro." + partition + ".build.fingerprint", fp);
            }
        }
        Log.i(TAG, "Build.FINGERPRINT = " + Build.FINGERPRINT);
        Log.i(TAG, "Build.PRODUCT = " + Build.PRODUCT);
        Log.i(TAG, "Build.DEVICE = " + Build.DEVICE);
        Log.i(TAG, "Build.MANUFACTURER = " + Build.MANUFACTURER);
        Log.i(TAG, "Build.BRAND = " + Build.BRAND);
        Log.i(TAG, "Build.MODEL = " + Build.MODEL);
        Log.i(TAG, "Build.ID = " + Build.ID);
        Log.i(TAG, "Build.TYPE = " + Build.TYPE);
        Log.i(TAG, "Build.TAGS = " + Build.TAGS);
        Log.i(TAG, "Build.VERSION.INCREMENTAL = " + Build.VERSION.INCREMENTAL);
        Log.i(TAG, "Build.VERSION.RELEASE = " + Build.VERSION.RELEASE);
        Log.i(TAG, "Build.VERSION.SECURITY_PATCH = " + Build.VERSION.SECURITY_PATCH);
        Log.i(TAG, "Build.VERSION.DEVICE_INITIAL_SDK_INT = "
                + Build.VERSION.DEVICE_INITIAL_SDK_INT);
    }

    /**
     * Set a single property value using reflection
     */
    private static void setPropValue(String key, String value) {
        try {
            dlog("Setting prop " + key + " to " + value);

            Class<?> clazz = Build.class;
            String fieldName = key;

            if (key.startsWith("VERSION.")) {
                clazz = Build.VERSION.class;
                fieldName = key.substring(8);
            }

            Field field = clazz.getDeclaredField(fieldName);
            field.setAccessible(true);

            // Cast the value to int if it's an integer field, otherwise string
            if (field.getType().equals(Integer.TYPE)) {
                field.set(null, Integer.parseInt(value));
            } else {
                field.set(null, value);
            }

            field.setAccessible(false);
        } catch (Exception e) {
            Log.e(TAG, "Failed to set prop " + key, e);
        }
    }

    /**
     * Check if GMS Add Account activity is on top
     */
    private static boolean isGmsAddAccountActivityOnTop() {
        try {
            final ActivityTaskManager.RootTaskInfo focusedTask =
                    ActivityTaskManager.getService().getFocusedRootTaskInfo();
            return focusedTask != null && focusedTask.topActivity != null
                    && GMS_ADD_ACCOUNT_ACTIVITY.equals(focusedTask.topActivity);
        } catch (Exception e) {
            Log.e(TAG, "Unable to get top activity!", e);
        }
        return false;
    }

    /**
     * Whether the device has a locked bootloader and supports hardware attestation.
     * When true, spoofing is unnecessary — stock behavior suffices.
     */
    public static boolean supportsHardwareAttestation() {
        return sSupportsHardwareAttestation;
    }

    /**
     * True when spoofing should run, i.e. ro.boot.verifiedbootstate is not
     * "green". Orange (unlocked) and yellow (locked with a non-OEM key) both
     * return true because, in either case, Google's Play Integrity backend
     * does not trust the device's native attestation chain and the full prop
     * + keybox spoof is required. Captured at the first setProps() call,
     * before any sysprop spoofing.
     */
    public static boolean shouldSpoof() {
        return sShouldSpoof;
    }

    /**
     * Debug logging
     */
    private static void dlog(String msg) {
        if (DEBUG) {
            Log.d(TAG, "[" + sProcessName + "] " + msg);
        }
    }
}
