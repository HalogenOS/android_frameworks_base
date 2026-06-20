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

package com.android.settingslib.audiostate

import android.bluetooth.BluetoothAdapter
import android.util.Log
import com.android.settingslib.bluetooth.LocalBluetoothManager

/**
 * Resolves an accessory's battery level from a Bluetooth device address using the same
 * [com.android.settingslib.bluetooth.CachedBluetoothDevice] path that drives the status-bar
 * Bluetooth battery indicator.
 *
 * IMPORTANT: this takes the host's existing, properly-initialized [LocalBluetoothManager] rather
 * than obtaining one itself. [LocalBluetoothManager] is a process-wide singleton; calling
 * `getInstance`/`create` from here would race or clobber the instance the host's own Bluetooth
 * stack (e.g. SystemUI's BluetoothController) depends on, breaking the status-bar icon and QS tile.
 * Hosts must pass the Dagger-provided (SystemUI) or Utils.getLocalBtManager (Settings) instance.
 *
 * Returns null when Bluetooth is off, the device is unknown/disconnected, or it reports no level —
 * never the phone's own battery.
 *
 * @param bluetoothManager the host's shared manager, or null when Bluetooth is unsupported/off.
 */
class LocalBluetoothBatteryProvider(
    private val bluetoothManager: LocalBluetoothManager?,
) : AudioStateRepository.DeviceBatteryProvider {

    override fun batteryPercentFor(address: String): Int? {
        val manager = bluetoothManager ?: return null
        return runCatching {
            if (!BluetoothAdapter.checkBluetoothAddress(address)) return null
            val adapter = manager.bluetoothAdapter ?: return null
            val remote = adapter.getRemoteDevice(address) ?: return null
            val cached = manager.cachedDeviceManager?.findDevice(remote) ?: return null
            // CachedBluetoothDevice.getBatteryLevel() returns BATTERY_LEVEL_UNKNOWN (-1) /
            // BATTERY_LEVEL_BLUETOOTH_OFF (-100) when unavailable; surface only real 0–100 values.
            cached.batteryLevel.takeIf { it in 0..100 }
        }.getOrElse {
            Log.w(TAG, "battery lookup failed for $address", it)
            null
        }
    }

    private companion object {
        const val TAG = "AudioStateBtBattery"
    }
}
