/*
 * Copyright (C) 2021 The Android Open Source Project
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

package com.android.internal.gmscompat;

import android.Manifest;
import android.annotation.Nullable;
import android.annotation.SuppressLint;
import android.app.Activity;
import android.app.ActivityManager;
import android.app.ActivityManager.RunningAppProcessInfo;
import android.app.Application;
import android.app.ApplicationErrorReport;
import android.app.BroadcastOptions;
import android.app.PendingIntent;
import android.app.Service;
import android.app.compat.gms.GmsCompat;
import android.content.ComponentName;
import android.content.ContentValues;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.database.MatrixCursor;
import android.database.sqlite.SQLiteOpenHelper;
import android.ext.PackageId;
import android.net.Uri;
import android.os.Binder;
import android.os.Bundle;
import android.os.DeadSystemRuntimeException;
import android.os.IBinder;
import android.os.Parcel;
import android.os.PowerExemptionManager;
import android.os.Process;
import android.os.RemoteException;
import android.os.SystemClock;
import android.os.UserHandle;
import android.provider.Downloads;
import android.provider.Settings;
import android.util.ArrayMap;
import android.util.ArraySet;
import android.util.Log;
import android.util.SparseArray;
import android.webkit.WebView;

import com.android.internal.gmscompat.client.GmsCompatClientService;
import com.android.internal.gmscompat.flags.GmsFlag;
import com.android.internal.gmscompat.flags.GmsFlagOverrides;
import com.android.internal.gmscompat.gcarriersettings.GCarrierSettingsApp;
import com.android.internal.gmscompat.gcarriersettings.TestCarrierConfigService;
import com.android.internal.util.SyntheticDeviceId;
import com.android.internal.gmscompat.sysservice.GmcPackageManager;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import java.util.function.Consumer;
import java.util.regex.Pattern;

import static com.android.internal.gmscompat.GmsInfo.PACKAGE_GMS_CORE;

public final class GmsHooks {
    private static final String TAG = "GmsCompat/Hooks";

    private static volatile GmsCompatConfig config;

    public static final String PERSISTENT_GmsCore_PROCESS = PACKAGE_GMS_CORE + ".persistent";
    public static boolean inPersistentGmsCoreProcess;
    public static final String UI_GmsCore_PROCESS = PACKAGE_GMS_CORE + ".ui";

    public static GmsCompatConfig config() {
        // thread-safe: immutable after publication
        return config;
    }

    public static void init(Context ctx, String packageName, String processName) {
        if (!packageName.equals(processName)) {
            // Fix RuntimeException: Using WebView from more than one process at once with the same data
            // directory is not supported. https://crbug.com/558377
            WebView.setDataDirectorySuffix("process-shim--" + processName);
        }

        if (GmsCompat.isGmsCore()) {
            inPersistentGmsCoreProcess = processName.equals(PERSISTENT_GmsCore_PROCESS);
            maybeReassignAndroidId(ctx);
        }

        GmsCompatLib.init(ctx, processName);

        if (GmsCompat.isPlayStore()) {
            PlayStoreHooks.init();
        }

        if (GmsCompat.isGCarrierSettings()) {
            GCarrierSettingsApp.init();
        }

        configUpdateLock = new Object();
        tlPermissionsToSpoof = new ThreadLocal<>();

        // Locking is needed to prevent a race that would occur if config is updated via
        // BinderGca2Gms#updateConfig in the time window between BinderGms2Gca#connect and setConfig()
        // call below. Older GmsCompatConfig would overwrite the newer one in that case.
        synchronized (configUpdateLock) {
            GmsCompatConfig config = GmsCompatApp.connect(ctx, processName);
            setConfig(config);
        }

        Thread.setUncaughtExceptionPreHandler(new UncaughtExceptionPreHandler());

        if (inPersistentGmsCoreProcess) {
            GmsFlagOverrides.init(ctx);
        }

        GmcPackageManager.init(ctx);
    }

    static Object configUpdateLock;

    static void setConfig(GmsCompatConfig c) {
        // configUpdateLock should never be null at this point, it's initialized before GmsCompatApp
        // gets a handle to BinderGca2Gms that is used for updating GmsCompatConfig
        synchronized (configUpdateLock) {
            config = c;
        }
    }

    static class UncaughtExceptionPreHandler implements Thread.UncaughtExceptionHandler {
        final Thread.UncaughtExceptionHandler orig = Thread.getUncaughtExceptionPreHandler();

        @Override
        public void uncaughtException(Thread t, Throwable e) {
            Context ctx = GmsCompat.appContext();

            ApplicationErrorReport aer = new ApplicationErrorReport();
            aer.type = ApplicationErrorReport.TYPE_CRASH;
            aer.crashInfo = new ApplicationErrorReport.ParcelableCrashInfo(e);

            ApplicationInfo ai = ctx.getApplicationInfo();
            aer.packageName = ai.packageName;
            aer.applicationInfo = ai;
            aer.processName = Application.getProcessName();

            // In some cases, GMS kills its process when it receives an uncaught exception, which
            // bypasses the standard crash handling infrastructure.
            // Send the report to GmsCompatApp before GMS receives the uncaughtException() callback.

            if (!shouldSkipException(e)) {
                try {
                    GmsCompatApp.iGms2Gca().onUncaughtException(aer);
                } catch (RemoteException re) {
                    Log.e(TAG, "", re);
                }
            }

            if (orig != null) {
                orig.uncaughtException(t, e);
            }
        }

        private static boolean shouldSkipException(Throwable e) {
            for (;;) {
                if (e == null) {
                    return false;
                }

                boolean skip =
    // in some cases a DeadSystemRuntimeException is thrown despite the system being actually
    // still alive, likely when the Binder buffer space is full and a binder transaction with
    // system_server fails.
    // See https://cs.android.com/android/platform/superproject/+/android-13.0.0_r3:frameworks/base/core/jni/android_util_Binder.cpp;l=894
    // (DeadObjectException is rethrown as DeadSystemRuntimeException by
    // android.os.RemoteException#rethrowFromSystemServer())
                    e instanceof DeadSystemRuntimeException
                ;

                if (skip) {
                    return true;
                }

                e = e.getCause();
            }
        }
    }

    // ContextImpl#getSystemService(String)
    public static boolean isHiddenSystemService(String name) {
        // return true only for services that are null-checked
        switch (name) {
            case Context.WIFI_SCANNING_SERVICE:
                return !GmsCompat.isAndroidAuto();
            case Context.CONTEXTHUB_SERVICE:
            case Context.APP_INTEGRITY_SERVICE:
            // used for factory reset protection
            case Context.PERSISTENT_DATA_BLOCK_SERVICE:
            // used for updateable fonts
            case Context.FONT_SERVICE:
                return true;
        }
        return false;
    }

    /**
     * Use the per-app SSAID as a random serial number for SafetyNet. This doesn't necessarily make
     * pass, but at least it retusn a valid "failed" response and stops spamming device key
     * requests.
     *
     * This isn't a privacy risk because all unprivileged apps already have access to random SSAIDs.
     */
    // Build#getSerial()
    @SuppressLint("HardwareIds")
    public static String getSerial() {
        String ssaid = Settings.Secure.getString(GmsCompat.appContext().getContentResolver(),
                Settings.Secure.ANDROID_ID);
        String serial;
        String rotation = rotationSeed();
        if (rotation != null) {
            // Identity rotation is active: derive a fresh-looking serial that
            // shifts together with the rotated checkin ID (and with the
            // synthetic IMEI, which mixes the same seed in SyntheticDeviceId),
            // so the whole Google-visible identity changes as one unit. Same
            // shape as the SSAID serial (16 upper hex).
            serial = sha256Hex(ssaid + ":serial:" + rotation).substring(0, 16).toUpperCase();
        } else {
            serial = ssaid.toUpperCase();
        }
        Log.d(TAG, "Generating serial number from SSAID: " + serial);
        return serial;
    }

    /**
     * The active identity-rotation seed: the server-reassign token when set,
     * otherwise the (legacy) read-override value. Null when no rotation is
     * active, in which case the plain SSAID-derived identifiers are used.
     */
    static String rotationSeed() {
        android.content.ContentResolver cr = GmsCompat.appContext().getContentResolver();
        String token = Settings.Secure.getString(cr,
                Settings.Secure.ATTESTATION_ANDROID_ID_REASSIGN);
        if (token != null && !token.isEmpty()) {
            return token;
        }
        String override = Settings.Secure.getString(cr,
                Settings.Secure.ATTESTATION_ANDROID_ID_OVERRIDE);
        return (override == null || override.isEmpty()) ? null : override;
    }

    private static String sha256Hex(String input) {
        try {
            byte[] digest = java.security.MessageDigest.getInstance("SHA-256")
                    .digest(input.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder(digest.length * 2);
            for (byte b : digest) {
                sb.append(Character.forDigit((b >> 4) & 0xf, 16));
                sb.append(Character.forDigit(b & 0xf, 16));
            }
            return sb.toString();
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    // TelephonyManager#getImei(int)
    // Return a synthetic, SSAID-derived IMEI so device-identifier reads never
    // throw or leak the real IMEI to sandboxed GMS. Mirrors getSerial() above.
    public static String getImei(int slot) {
        return SyntheticDeviceId.synthesizeImei(slot);
    }

    static class RecentBinderPid implements Comparable<RecentBinderPid> {
        int pid;
        int uid;
        long lastSeen;
        volatile String[] packageNames; // lazily inited

        static final int MAX_MAP_SIZE = 50;
        static final int MAP_SIZE_TRIM_TO = 40;
        static final SparseArray<RecentBinderPid> map = new SparseArray(MAX_MAP_SIZE + 1);

        public int compareTo(RecentBinderPid b) {
            return Long.compare(b.lastSeen, lastSeen); // newest come first
        }
    }

    // Remember recent Binder peers to include them in the result of ActivityManager.getRunningAppProcesses()
    // Binder#execTransact(int, long, long, int)
    public static void onBinderTransaction(int pid, int uid) {
        SparseArray<RecentBinderPid> map = RecentBinderPid.map;
        synchronized (map) {
            RecentBinderPid rbp = map.get(pid);
            if (rbp != null) {
                if (rbp.uid != uid) { // pid was reused
                    rbp = null;
                }
            }
            if (rbp == null) {
                rbp = new RecentBinderPid();
                rbp.pid = pid;
                rbp.uid = uid;
                map.put(pid, rbp);
            }
            rbp.lastSeen = SystemClock.uptimeMillis();

            int mapSize = map.size();
            if (mapSize <= RecentBinderPid.MAX_MAP_SIZE) {
                return;
            }
            RecentBinderPid[] arr = new RecentBinderPid[mapSize];
            for (int i = 0; i < mapSize; ++i) {
                arr[i] = map.valueAt(i);
            }
            // sorted by lastSeen field in reverse order
            Arrays.sort(arr);
            map.clear();
            for (int i = 0; i < RecentBinderPid.MAP_SIZE_TRIM_TO; ++i) {
                RecentBinderPid e = arr[i];
                map.put(e.pid, e);
            }
        }
    }

    // In some cases (Play Games Services, Play {Asset, Feature} Delivery)
    // GMS relies on getRunningAppProcesses() to figure out whether its client is running.
    // This workaround is racy, because unprivileged apps can't know whether an arbitrary pid is alive.
    // ActivityManager#getRunningAppProcesses()
    public static ArrayList<RunningAppProcessInfo> addRecentlyBoundPids(Context context,
                                                                        List<RunningAppProcessInfo> orig) {
        final RecentBinderPid[] binderPids;
        final int binderPidsCount;
        // copy to array to avoid long lock contention with Binder.execTransact(),
        // there are expensive getPackagesForUid() calls below
        {
            SparseArray<RecentBinderPid> map = RecentBinderPid.map;
            synchronized (map) {
                binderPidsCount = map.size();
                binderPids = new RecentBinderPid[binderPidsCount];
                for (int i = 0; i < binderPidsCount; ++i) {
                    binderPids[i] = map.valueAt(i);
                }
            }
        }
        PackageManager pm = context.getPackageManager();
        ArrayList<RunningAppProcessInfo> res = new ArrayList<>(orig.size() + binderPidsCount);
        res.addAll(orig);
        for (int i = 0; i < binderPidsCount; ++i) {
            RecentBinderPid rbp = binderPids[i];
            String[] pkgs = rbp.packageNames;
            if (pkgs == null) {
                if (UserHandle.getUserId(rbp.uid) != UserHandle.myUserId()) {
                    // SystemUI from userId 0 sends callbacks to apps from all userIds via
                    // android.window.IOnBackInvokedCallback.
                    // getPackagesForUid() will fail due to missing privileged
                    // INTERACT_ACROSS_USERS permission
                    continue;
                }

                pkgs = pm.getPackagesForUid(rbp.uid);
                if (pkgs == null || pkgs.length == 0) {
                    continue;
                }
                // this field is volatile
                rbp.packageNames = pkgs;
            }
            RunningAppProcessInfo pi = new RunningAppProcessInfo();
            // these fields are immutable after publication
            pi.pid = rbp.pid;
            pi.uid = rbp.uid;
            pi.processName = pkgs[0];
            pi.pkgList = pkgs;
            pi.importance = RunningAppProcessInfo.IMPORTANCE_FOREGROUND;
            res.add(pi);
        }
        return res;
    }

    // ContentResolver#query(Uri, String[], Bundle, CancellationSignal)
    public static Cursor maybeModifyQueryResult(Uri uri,
            @Nullable String[] projection, @Nullable Bundle queryArgs, @Nullable Cursor origCursor) {
        String uriString = uri.toString();
        Log.d(TAG, "maybeModifyQueryResult for " + uriString);

        Consumer<ArrayMap<String, String>> mutator = null;
        if (uriString.startsWith(GmsFlag.PHENOTYPE_URI_PREFIX)) {
            List<String> path = uri.getPathSegments();
            if (path.size() != 1) {
                Log.e(TAG, "unknown phenotype uri " + uriString, new Throwable());
                return null;
            }

            String namespace = path.get(0);

            GmsCompatConfig config = config();

            ArrayList<String> forceDefaultFlagsRegexes = config.forceDefaultFlagsMap.get(namespace);

            if (forceDefaultFlagsRegexes == null) {
                return null;
            }

            mutator = map -> {
                if (forceDefaultFlagsRegexes != null) {
                    int patternCnt = forceDefaultFlagsRegexes.size();
                    Pattern[] patterns = new Pattern[patternCnt];
                    for (int i = 0; i < patternCnt; ++i) {
                        patterns[i] = Pattern.compile(forceDefaultFlagsRegexes.get(i));
                    }
                    ArrayMap filteredMap = new ArrayMap<>(map.size());

                    outer:
                    for (int entryIdx = 0, entryCnt = map.size(); entryIdx < entryCnt; ++entryIdx) {
                        String key = map.keyAt(entryIdx);
                        for (int patternIdx = 0; patternIdx < patternCnt; ++patternIdx) {
                            if (patterns[patternIdx].matcher(key).matches()) {
                                continue outer;
                            }
                        }
                        filteredMap.put(key, map.valueAt(entryIdx));
                    }
                    map.clear();
                    map.putAll(filteredMap);
                }
            };

            if (mutator != null) {
                return modifyKvCursor(origCursor, projection, mutator);
            }

            return null;
        }

        if (uriString.startsWith(GSERVICES_URI_PREFIX)) {
            return maybeOverrideGservicesAndroidId(origCursor);
        }

        return null;
    }

    private static final String GSERVICES_URI_PREFIX = "content://com.google.android.gsf.gservices";
    private static final String GSERVICES_ANDROID_ID_KEY = "android_id";
    private static final android.net.Uri GSERVICES_URI =
            android.net.Uri.parse(GSERVICES_URI_PREFIX);
    private static final String CHECKIN_PREFS = "Checkin";
    private static final String REASSIGN_MARKER = "reassign_token";

    /**
     * Server-side androidId reassignment, requested by the device-identity
     * rotation control through Settings.Secure.ATTESTATION_ANDROID_ID_REASSIGN.
     * Zeroes GMS's stored checkin androidId once per token — both the
     * authoritative "Checkin" shared preference and the gservices mirror (the
     * split GoogleSettingsUtils itself maintains), which makes the next
     * checkin register the device fresh and receive a server-assigned ID. A
     * client-chosen ID is NOT viable: the integrity endpoints reject
     * unregistered androidIds outright (HTTP 400). Runs inside the GMS
     * process, so both stores are writable directly; the token is recorded in
     * the Checkin prefs as the consumption marker so later process starts
     * don't re-zero the freshly assigned ID.
     */
    private static void maybeReassignAndroidId(Context ctx) {
        String token;
        try {
            token = android.provider.Settings.Secure.getString(ctx.getContentResolver(),
                    android.provider.Settings.Secure.ATTESTATION_ANDROID_ID_REASSIGN);
        } catch (Throwable t) {
            return;
        }
        if (token == null || token.isEmpty()) {
            return;
        }

        android.content.SharedPreferences prefs =
                ctx.getSharedPreferences(CHECKIN_PREFS, Context.MODE_PRIVATE);
        if (token.equals(prefs.getString(REASSIGN_MARKER, null))) {
            return; // this token's zeroing already happened
        }

        android.content.ContentValues values = new android.content.ContentValues();
        values.put(GSERVICES_ANDROID_ID_KEY, "0");
        try {
            ctx.getContentResolver().update(GSERVICES_URI, values, null, null);
        } catch (Throwable t) {
            Log.e(TAG, "failed to zero gservices android_id", t);
        }
        prefs.edit()
                .putString(GSERVICES_ANDROID_ID_KEY, "0")
                .putString(REASSIGN_MARKER, token)
                // synchronous: the checkin handshake may run in another GMS
                // process and must see the zeroed state on first read
                .commit();
        Log.i(TAG, "checkin androidId zeroed for server reassignment");

        // Trigger the registration handshake immediately (same broadcast
        // GMS's CheckinServiceImposeReceiver listens for) — without it the
        // fresh ID is only assigned whenever the next natural checkin
        // happens, which can be hours later.
        try {
            ctx.sendBroadcast(new android.content.Intent("android.server.checkin.CHECKIN_NOW"));
        } catch (Throwable t) {
            Log.e(TAG, "failed to trigger checkin after reassignment", t);
        }
    }

    /**
     * Substitute the GMS checkin androidId (gservices "android_id") with the
     * value from Settings.Secure.ATTESTATION_ANDROID_ID_OVERRIDE when set.
     * Used by the device-identity rotation control: GMS keeps its stored ID
     * untouched, but every read it makes presents the override — so checkin
     * and everything derived from it see the rotated identity.
     *
     * The gservices provider answers single-key lookups with a one-row
     * ("key", value) cursor; the row is rewritten generically so provider
     * column-name variations don't break the override.
     */
    private static Cursor maybeOverrideGservicesAndroidId(@Nullable Cursor origCursor) {
        String override = android.provider.Settings.Secure.getString(
                GmsCompat.appContext().getContentResolver(),
                android.provider.Settings.Secure.ATTESTATION_ANDROID_ID_OVERRIDE);
        if (override == null || override.isEmpty()) {
            return null;
        }

        if (origCursor == null) {
            return null;
        }

        final String[] columns = origCursor.getColumnNames();
        if (columns.length < 2) {
            // Single-column shape: one row whose only cell is the value.
            if (columns.length == 1 && origCursor.getCount() == 1
                    && GSERVICES_ANDROID_ID_KEY.equals(columns[0])) {
                MatrixCursor result = new MatrixCursor(columns, 1);
                result.addRow(new Object[] { override });
                // We replace the original — release it. (Only ever close the
                // caller's cursor when returning a replacement; returning null
                // hands the SAME cursor back to the caller.)
                origCursor.close();
                Log.d(TAG, "gservices android_id overridden (single-column)");
                return result;
            }
            return null;
        }

        // ("key", value) shape: one row per setting; rewrite the android_id row.
        boolean touched = false;
        ArrayList<Object[]> rows = new ArrayList<>(origCursor.getCount());
        Cursor orig = origCursor;
        while (orig.moveToNext()) {
            Object[] row = new Object[columns.length];
            for (int i = 0; i < columns.length; ++i) {
                row[i] = orig.getString(i);
            }
            if (GSERVICES_ANDROID_ID_KEY.equals(row[0])) {
                row[1] = override;
                touched = true;
            }
            rows.add(row);
        }

        if (!touched) {
            // Not our row — hand the ORIGINAL cursor back to the caller
            // untouched. Closing it here would surface downstream as
            // StaleDataException in the app (ContentResolver returns the same
            // wrapper when this hook returns null).
            return null;
        }

        orig.close();
        MatrixCursor result = new MatrixCursor(columns, rows.size());
        for (Object[] row : rows) {
            result.addRow(row);
        }
        Log.d(TAG, "gservices android_id overridden");
        return result;
    }

    private static Cursor modifyKvCursor(@Nullable Cursor origCursor, @Nullable String[] projection,
                                         Consumer<ArrayMap<String, String>> mutator) {
        final int keyIndex = 0;
        final int valueIndex = 1;
        final int projectionLength = 2;

        if (origCursor != null) {
            projection = origCursor.getColumnNames();
        }

        boolean expectedProjection = projection != null && projection.length == projectionLength
                && "key".equals(projection[keyIndex]) && "value".equals(projection[valueIndex]);

        if (!expectedProjection) {
            Log.e(TAG, "unexpected projection " + Arrays.toString(projection), new Throwable());
            return null;
        }

        final ArrayMap<String, String> map;
        if (origCursor == null) {
            map = new ArrayMap<>();
        } else {
            map = new ArrayMap<>(origCursor.getColumnCount() + 10);
            try (Cursor orig = origCursor) {
                while (orig.moveToNext()) {
                    String key = orig.getString(keyIndex);
                    String value = orig.getString(valueIndex);

                    map.put(key, value);
                }
            }
        }

        mutator.accept(map);

        final int mapSize = map.size();
        MatrixCursor result = new MatrixCursor(projection, mapSize);

        for (int i = 0; i < mapSize; ++i) {
            Object[] row = new Object[projectionLength];
            row[keyIndex] = map.keyAt(i);
            row[valueIndex] = map.valueAt(i);

            result.addRow(row);
        }

        return result;
    }

    // Instrumentation#execStartActivity(Context, IBinder, IBinder, Activity, Intent, int, Bundle)
    public static void onActivityStart(int resultCode, Intent intent, int requestCode, Bundle options) {
        if (resultCode != ActivityManager.START_ABORTED) {
            return;
        }

        // handle background activity starts, which normally require a privileged permission

        if (requestCode >= 0) {
            Log.d(TAG, "attempt to call startActivityForResult() from the background " + intent, new Throwable());
            return;
        }

        // needed to prevent invalid reuse of PendingIntents, see PendingIntent doc
        intent.setIdentifier(UUID.randomUUID().toString());

        Context ctx = GmsCompat.appContext();
        PendingIntent pendingIntent = PendingIntent.getActivity(ctx, 0, intent,
                PendingIntent.FLAG_IMMUTABLE, options);
        try {
            GmsCompatApp.iGms2Gca().startActivityFromTheBackground(ctx.getPackageName(), pendingIntent);
        } catch (RemoteException e) {
            GmsCompatApp.callFailed(e);
        }
    }

    // Activity#onCreate(Bundle)
    public static void activityOnCreate(Activity activity) {

    }

    // ContentResolver#insert(Uri, ContentValues, Bundle)
    public static void filterContentValues(Uri url, ContentValues values) {
        if (values != null && Downloads.Impl.CONTENT_URI.equals(url)) {
            Integer otherUid = values.getAsInteger(Downloads.Impl.COLUMN_OTHER_UID);
            if (otherUid != null) {
                if (otherUid.intValue() != Process.SYSTEM_UID) {
                    throw new IllegalStateException("unexpected COLUMN_OTHER_UID " + otherUid);
                }
                // gated by the privileged ACCESS_DOWNLOAD_MANAGER_ADVANCED permission
                values.remove(Downloads.Impl.COLUMN_OTHER_UID);
            }
        }
    }

    private static boolean hasNearbyDevicesPermission() {
        // "Nearby devices" user-facing permission grants multiple underlying permissions,
        // checking one is enough
        return GmsCompat.hasPermission(Manifest.permission.BLUETOOTH_SCAN);
    }

    // ContextImpl#sendBroadcast
    // ContextImpl#sendOrderedBroadcast
    // ContextImpl#sendBroadcastAsUser
    // ContextImpl#sendOrderedBroadcastAsUser
    public static Bundle filterBroadcastOptions(Intent intent, Bundle options) {
        if (options == null) {
            return null;
        }

        String targetPkg = intent.getPackage();

        if (targetPkg == null) {
            ComponentName cn = intent.getComponent();
            if (cn != null) {
                targetPkg = cn.getPackageName();
            }
        }

        if (targetPkg == null) {
            return options;
        }

        return filterBroadcastOptions(options, targetPkg);
    }

    // PendingIntent#send
    public static Bundle filterBroadcastOptions(Bundle options, String targetPkg) {
        BroadcastOptions bo = new BroadcastOptions(options);

        if (bo.getTemporaryAppAllowlistType() == PowerExemptionManager.TEMPORARY_ALLOW_LIST_TYPE_NONE) {
            return options;
        }
        // handle privileged BroadcastOptions#setTemporaryAppAllowlist() that is used for
        // high-priority FCM pushes, location updates via PendingIntent,
        // geofencing and activity detection notifications etc

        long duration = bo.getTemporaryAppAllowlistDuration();

        if (duration <= 0) {
            return options;
        }

        GmsCompatApp.raisePackageToForeground(targetPkg, duration,
                bo.getTemporaryAppAllowlistReason(), bo.getTemporaryAppAllowlistReasonCode());

        bo.setTemporaryAppAllowlist(0, PowerExemptionManager.TEMPORARY_ALLOW_LIST_TYPE_NONE,
                PowerExemptionManager.REASON_UNKNOWN, null);
        return bo.toBundle();
    }

    // Parcel#readException
    public static boolean interceptException(Exception e, Parcel p) {
        if (!(e instanceof SecurityException)) {
            return false;
        }

        if (p.dataAvail() != 0) {
            Log.w(TAG, "malformed Parcel: dataAvail() " + p.dataAvail() + " after exception", e);
            return false;
        }

        StubDef stub = StubDef.find(e.getStackTrace(), config(), StubDef.FIND_MODE_Parcel);

        if (stub == null) {
            return false;
        }

        boolean res = stub.stubOutMethod(p);

        String logTag = "GmcDynStub";
        if (GmsCompat.isDevBuild() || Log.isLoggable(logTag, Log.DEBUG)) {
            Log.d(logTag, res ? "intercepted" : "stubOut failed", e);
        }

        return res;
    }

    public static void onSQLiteOpenHelperConstructed(SQLiteOpenHelper h, @Nullable Context context) {
        if (context == null) {
            return;
        }

        if (GmsCompat.isGmsCore()) {
            if (inPersistentGmsCoreProcess) {
                if ("phenotype.db".equals(h.getDatabaseName()) && !context.isDeviceProtectedStorage()) {
                    if (phenotypeDb != null) {
                        Log.w(TAG, "reassigning phenotypeDb", new Throwable());
                    }
                    phenotypeDb = h;
                }
            }
        }
    }

    @Nullable
    public static Service maybeInstantiateService(String className) {
        if (GmsCompatClientService.class.getName().equals(className)) {
            return new GmsCompatClientService();
        }

        if (GmsCompat.isEnabled()) {
            if (GmsCompat.isGmsCore()) {
                if (GmcMediaProjectionService.class.getName().equals(className)) {
                    return new GmcMediaProjectionService();
                }
            }
            if (GmsCompat.isGCarrierSettings()) {
                if (TestCarrierConfigService.class.getName().equals(className)) {
                    return new TestCarrierConfigService();
                }
            }
        }

        return null;
    }

    private static volatile SQLiteOpenHelper phenotypeDb;
    public static SQLiteOpenHelper getPhenotypeDb() { return phenotypeDb; }

    private static ThreadLocal<ArraySet<String>> tlPermissionsToSpoof;

    public static boolean shouldSpoofSelfPermissionCheck(String perm) {
        ArraySet<String> set = tlPermissionsToSpoof.get();
        if (set == null) {
            return false;
        }

        return set.contains(perm);
    }

    public static final String GMS_SERVICE_BROKER_INTERFACE_DESCRIPTOR =
            "com.google.android.gms.common.internal.IGmsServiceBroker";

    public static boolean onBeginGmsServiceBrokerCall(int transactionCode, Parcel data) {
        if (transactionCode != 46) { // getService() method
            return false;
        }

        try {
            data.enforceInterface(GMS_SERVICE_BROKER_INTERFACE_DESCRIPTOR);
            // IGmsCallbacks binder
            data.readStrongBinder();

            if (data.readInt() == 1) { // GetServiceRequest is present
                // GetServiceRequest object header
                data.readInt();
                data.readInt();

                // version
                data.readInt();
                data.readInt();

                // id of serviceId property
                data.readInt();

                int serviceId = data.readInt();

                ArraySet<String> permsToSpoof = config().gmsServiceBrokerPermissionBypasses.get(serviceId);
                if (permsToSpoof != null) {
                    Log.d(TAG, "start spoofing self permission checks for getService() call for API "
                            + serviceId + ", perms: " + Arrays.toString(permsToSpoof.toArray()));
                    tlPermissionsToSpoof.set(permsToSpoof);
                    // there's a second layer of caching inside GmsCore, need to notify permission
                    // change listener used by that cache
                    GmcPackageManager.notifyPermissionsChangeListeners();
                    return true;
                }
            }
        } finally {
            data.setDataPosition(0);
        }

        return false;
    }

    public static void onEndGmsServiceBrokerCall() {
        Log.d(TAG, "end self permission check spoofing");
        tlPermissionsToSpoof.set(null);
        // invalidate the cache of permission state inside GmsCore
        GmcPackageManager.notifyPermissionsChangeListeners();
    }

    public static final String GMS_CONSTELLATION_SERVICE_INTERFACE_DESCRIPTOR =
            "com.google.android.gms.constellation.internal.IConstellationApiService";

    public static void onBeginGmsConstellationServiceCall(int transactionCode, Parcel data) {
        if (transactionCode != 3) { // verifyPhoneNumber V2 method
            return;
        }

        try {
            final var ctx = GmsCompat.appContext();
            if (ctx == null) return;
            final var callingPkg = ctx.getPackageManager().getNameForUid(Binder.getCallingUid());
            // Ensure we're only sending RCS permission notifications for Bugle phone number
            // verification attempts.
            //
            // GmsServiceBroker also does validation of allowed packages, but it doesn't seem it's
            // restricted to only Bugle. For the Constellation service (155), the
            // VerifyPhoneNumberApi__packages_allowed_to_call flag (proto list) also includes other
            // apps like com.google.android.dialer, etc. in its default value.
            if (!PackageId.BUGLE_NAME.equals(callingPkg)) {
                Log.d(TAG, "onBeginGmsConstellationServiceCall code " + transactionCode + ", unexpected callingPkg " + callingPkg);
                return;
            }

            data.enforceInterface(GMS_CONSTELLATION_SERVICE_INTERFACE_DESCRIPTOR);
            // IConstellationCallbacks binder
            data.readStrongBinder();

            if (data.readInt() == 1) { // VerifyPhoneNumberRequest is present
                IGmsCompatLib lib = GmsCompatLib.get();
                final String policyId = lib.parseVerifyPhoneNumberRequestForPolicy(data);
                final boolean isTs43Verification = policyId != null &&
                        policyId.startsWith("upi-") &&
                        policyId.contains("ts43");
                Log.d(TAG, "onBeginGmsConstellationServiceCall: policyId " + policyId);
                // We could also decode the Bundle field in VerifyPhoneNumberRequest which
                // stores the key-value "required_consumer_consent" -> "RCS", and check
                // for this. However, this is set as an "API param", so it's not as backwards
                // compatible or guaranteed as the String property in the VerifyPhoneNumberRequest
                // class
                GmsCompatApp.iGms2Gca()
                        .maybeShowRcsRequirementsNotification(isTs43Verification);
            }
        } catch (Exception e) {
            Log.e(TAG, "onBeginGmsConstellationServiceCall: failed", e);
        } finally {
            data.setDataPosition(0);
        }
    }

    public static IBinder maybeOverrideBinder(IBinder binder) {
        boolean proceed = GmsCompat.isEnabled() || GmsCompat.isClientOfGmsCore();
        if (!proceed) {
            return null;
        }

        String ifaceName = null;
        try {
            ifaceName = binder.getInterfaceDescriptor();
        } catch (RemoteException e) {
            Log.d(TAG, "", e);
        }

        if (ifaceName == null) {
            return null;
        }

        return GmcBinderDefs.maybeOverrideBinder(binder, ifaceName);
    }

    private GmsHooks() {}
}
