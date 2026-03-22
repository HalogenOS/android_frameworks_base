/*
 * Copyright (C) 2026 The halogenOS Project
 * SPDX-License-Identifier: Apache-2.0
 */

package com.android.server.charging;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.database.ContentObserver;
import android.os.BatteryManager;
import android.os.Handler;
import android.os.Looper;
import android.os.ServiceManager;
import android.os.UserHandle;
import android.provider.Settings;
import android.util.Slog;

import com.android.server.SystemService;

import custom.system.service.charging.IChargingControlManager;
import vendor.lineage.health.ChargingControlSupportedMode;
import vendor.lineage.health.ChargingLimitInfo;
import vendor.lineage.health.IChargingControl;

public class ChargingControlService extends SystemService {

    private static final String TAG = "ChargingControlService";
    private static final String SERVICE_NAME = "custom.system.service.charging";
    private static final String SETTING = "charging_limit";
    private static final int RECHARGE_MARGIN = 4;
    private static final int DISABLED = Integer.MAX_VALUE;

    private IChargingControl mChargingControl;
    private boolean mHasLimit;
    private boolean mHasToggle;
    private int mConfiguredLimit;

    private final IChargingControlManager.Stub mManagerService =
            new IChargingControlManager.Stub() {
                @Override
                public boolean isSupported() {
                    return mChargingControl != null;
                }
            };

    public ChargingControlService(Context context) {
        super(context);
    }

    @Override
    public void onStart() {
        publishBinderService(SERVICE_NAME, mManagerService);

        var binder = ServiceManager.waitForDeclaredService(
                IChargingControl.DESCRIPTOR + "/default");
        if (binder == null) {
            Slog.i(TAG, "No charging control HAL found, service inactive");
            return;
        }
        mChargingControl = IChargingControl.Stub.asInterface(binder);

        int modes;
        try {
            modes = mChargingControl.getSupportedMode();
        } catch (Exception e) {
            Slog.e(TAG, "Failed to query supported modes", e);
            return;
        }

        mHasLimit = (modes & ChargingControlSupportedMode.LIMIT) != 0;
        mHasToggle = (modes & ChargingControlSupportedMode.TOGGLE) != 0;

        if (!mHasLimit && !mHasToggle) {
            Slog.i(TAG, "HAL supports neither LIMIT nor TOGGLE, service inactive");
            mChargingControl = null;
            return;
        }

        Slog.i(TAG, "Charging control HAL found (limit=" + mHasLimit
                + ", toggle=" + mHasToggle + ")");
    }

    @Override
    public void onBootPhase(int phase) {
        if (phase != PHASE_BOOT_COMPLETED || mChargingControl == null) return;

        var resolver = getContext().getContentResolver();

        mConfiguredLimit = Settings.System.getIntForUser(
                resolver, SETTING, DISABLED, UserHandle.USER_CURRENT);

        resolver.registerContentObserver(
                Settings.System.getUriFor(SETTING), false,
                new ContentObserver(new Handler(Looper.getMainLooper())) {
                    @Override
                    public void onChange(boolean selfChange) {
                        mConfiguredLimit = Settings.System.getIntForUser(
                                resolver, SETTING, DISABLED, UserHandle.USER_CURRENT);
                        Slog.i(TAG, "Charging limit changed to: " + mConfiguredLimit);
                        if (isDisabled()) {
                            resetCharging();
                        } else {
                            applyLimit(mConfiguredLimit);
                        }
                    }
                });

        getContext().registerReceiver(new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                onBatteryChanged(intent);
            }
        }, new IntentFilter(Intent.ACTION_BATTERY_CHANGED), null, null);

        if (!isDisabled()) {
            applyLimit(mConfiguredLimit);
        }

        Slog.i(TAG, "Boot completed, configured limit: " + mConfiguredLimit);
    }

    private boolean isDisabled() {
        return mConfiguredLimit >= 100;
    }

    private void onBatteryChanged(Intent intent) {
        if (isDisabled()) return;

        int level = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1);
        int status = intent.getIntExtra(BatteryManager.EXTRA_STATUS, BatteryManager.BATTERY_STATUS_UNKNOWN);
        if (level < 0) return;

        // HAL LIMIT mode handles it autonomously
        if (mHasLimit) return;

        // TOGGLE mode: purely reactive, no state tracking
        if (status == BatteryManager.BATTERY_STATUS_CHARGING) {
            if (level >= mConfiguredLimit) {
                Slog.i(TAG, "Battery at " + level + "%, limit " + mConfiguredLimit
                        + "%, disabling charging");
                setChargingEnabled(false);
            } else {
                setChargingEnabled(true);
            }
        } else if (level < mConfiguredLimit - RECHARGE_MARGIN) {
            setChargingEnabled(true);
        }
    }

    private void applyLimit(int limit) {
        if (!mHasLimit) return;
        try {
            var info = new ChargingLimitInfo();
            info.max = limit;
            info.min = limit - RECHARGE_MARGIN;
            mChargingControl.setChargingLimit(info);
            Slog.i(TAG, "Applied HAL charging limit: " + (limit - RECHARGE_MARGIN)
                    + "-" + limit);
        } catch (Exception e) {
            Slog.e(TAG, "Failed to set charging limit", e);
        }
    }

    private void resetCharging() {
        if (mHasLimit) {
            try {
                var info = new ChargingLimitInfo();
                info.max = 100;
                info.min = 0;
                mChargingControl.setChargingLimit(info);
            } catch (Exception e) {
                Slog.e(TAG, "Failed to reset charging limit", e);
            }
        }
        setChargingEnabled(true);
    }

    private void setChargingEnabled(boolean enabled) {
        try {
            mChargingControl.setChargingEnabled(enabled);
        } catch (Exception e) {
            Slog.e(TAG, "Failed to set charging enabled: " + enabled, e);
        }
    }
}
