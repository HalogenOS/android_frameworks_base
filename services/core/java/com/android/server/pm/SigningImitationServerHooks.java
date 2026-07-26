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

package com.android.server.pm;

import android.content.Context;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.pm.ParceledListSlice;
import android.content.pm.Signature;
import android.os.Binder;
import android.os.UserHandle;
import android.util.Base64;
import android.util.Log;

import com.android.internal.util.SigningImitationHooks;

import java.util.Arrays;
import java.util.List;

/**
 * Server-side (PMS) signing-info imitation for spoof-target binder callers.
 *
 * Spoof-target callers do not only use the PackageManager client API — they
 * also transact with PMS directly, so the client-side
 * ApplicationPackageManager hook never sees those calls. The rewrite must
 * therefore also happen at the binder response boundary in system_server.
 *
 * Query/report endpoints only. checkSignatures, install, verification and
 * signature-permission paths are NOT touched — the signature trust boundary
 * keeps seeing real certs.
 */
final class SigningImitationServerHooks {

    private static final String TAG = "SigningImitationSrv";
    private static final boolean DEBUG = Log.isLoggable(TAG, Log.DEBUG);

    private static final String PACKAGE_GMS = "com.google.android.gms";
    private static final String PACKAGE_FINSKY = "com.android.vending";
    private static final String PLATFORM_PACKAGE = "android";
    private static final String CONFORMANCE_PACKAGE = "custom.corporatecontrolsatisfier";
    private static final String CONFORMANCE_ARRAY = "control_conformance_attributes";
    private static final String PLATFORM_CERT_ITEM = "ATTEST.PLATFORM_CERT:";

    private static volatile boolean sArmed;
    private static volatile byte[] sRealDer;
    private static volatile byte[] sStockDer;
    private static volatile int sGmsAppId = -1;
    private static volatile int sVendingAppId = -1;

    private SigningImitationServerHooks() {
    }

    /** One-time arming; retries on later calls until everything is available
     *  (the conformance overlay may not be registered during early boot). */
    private static void tryArm(Computer snapshot, Context context) {
        if (sArmed) {
            return;
        }
        synchronized (SigningImitationServerHooks.class) {
            if (sArmed) {
                return;
            }
            try {
                Context cctx = context.createPackageContext(CONFORMANCE_PACKAGE, 0);
                int id = cctx.getResources().getIdentifier(
                        CONFORMANCE_ARRAY, "array", CONFORMANCE_PACKAGE);
                if (id != 0) {
                    for (String item : cctx.getResources().getStringArray(id)) {
                        if (item.startsWith(PLATFORM_CERT_ITEM)) {
                            sStockDer = Base64.decode(
                                    item.substring(PLATFORM_CERT_ITEM.length()),
                                    Base64.DEFAULT);
                        }
                    }
                }
                if (sRealDer == null) {
                    // Read the real platform cert from our own package database
                    // (system_server-local, invisible to spoof-target processes).
                    PackageInfo pi = snapshot.getPackageInfo(PLATFORM_PACKAGE,
                            PackageManager.GET_SIGNING_CERTIFICATES, UserHandle.USER_SYSTEM);
                    if (pi != null && pi.signingInfo != null
                            && pi.signingInfo.getApkContentsSigners() != null
                            && pi.signingInfo.getApkContentsSigners().length > 0) {
                        sRealDer = pi.signingInfo.getApkContentsSigners()[0].toByteArray();
                    }
                }
                if (sRealDer != null && sStockDer != null
                        && !Arrays.equals(sRealDer, sStockDer)) {
                    sGmsAppId = appIdOf(snapshot, PACKAGE_GMS);
                    sVendingAppId = appIdOf(snapshot, PACKAGE_FINSKY);
                    sArmed = true;
                    if (DEBUG) {
                        Log.d(TAG, "armed: gmsAppId=" + sGmsAppId
                                + " vendingAppId=" + sVendingAppId);
                    }
                }
            } catch (Exception e) {
                Log.w(TAG, "arm failed, will retry", e);
            }
        }
    }

    private static int appIdOf(Computer snapshot, String packageName) {
        try {
            int uid = snapshot.getPackageUid(packageName, 0, UserHandle.USER_SYSTEM);
            return uid >= 0 ? UserHandle.getAppId(uid) : -1;
        } catch (Exception e) {
            return -1;
        }
    }

    /** True when armed and the current binder caller is a spoof target. */
    private static boolean spoofCaller(Computer snapshot, Context context) {
        if (!sArmed) {
            tryArm(snapshot, context);
        }
        if (!sArmed) {
            return false;
        }
        final int appId = UserHandle.getAppId(Binder.getCallingUid());
        return appId == sGmsAppId || appId == sVendingAppId;
    }

    static PackageInfo onPackageInfo(Computer snapshot, Context context, PackageInfo pi) {
        if (pi == null || !spoofCaller(snapshot, context)) {
            return pi;
        }
        if (SigningImitationHooks.rewritePackageInfo(pi, sRealDer, sStockDer) && DEBUG) {
            Log.d(TAG, "reporting stock platform cert for " + pi.packageName
                    + " to uid " + Binder.getCallingUid());
        }
        return pi;
    }

    static ParceledListSlice<PackageInfo> onPackageInfoList(Computer snapshot,
            Context context, ParceledListSlice<PackageInfo> slice) {
        if (slice == null || !spoofCaller(snapshot, context)) {
            return slice;
        }
        List<PackageInfo> list = slice.getList();
        if (list != null) {
            for (PackageInfo pi : list) {
                SigningImitationHooks.rewritePackageInfo(pi, sRealDer, sStockDer);
            }
        }
        return slice;
    }

    static boolean onHasSigningCertificate(Computer snapshot, Context context,
            String packageName, byte[] certificate, int type, boolean realResult) {
        if (certificate == null || !spoofCaller(snapshot, context)) {
            return realResult;
        }
        Boolean queriedStock = matchesEither(certificate, type);
        if (queriedStock == null) {
            return realResult;
        }
        PackageInfo pi = snapshot.getPackageInfo(packageName,
                PackageManager.GET_SIGNING_CERTIFICATES,
                UserHandle.getUserId(Binder.getCallingUid()));
        if (!signedWithReal(pi)) {
            return realResult;
        }
        // Platform-signed package: under the spoofed view its signer is the
        // stock cert, so stock queries answer true and real-cert queries false.
        return queriedStock;
    }

    static boolean onHasUidSigningCertificate(Computer snapshot, Context context,
            int uid, byte[] certificate, int type, boolean realResult) {
        if (certificate == null || !spoofCaller(snapshot, context)) {
            return realResult;
        }
        Boolean queriedStock = matchesEither(certificate, type);
        if (queriedStock == null) {
            return realResult;
        }
        String[] names = snapshot.getPackagesForUid(uid);
        if (names != null) {
            for (String name : names) {
                PackageInfo pi = snapshot.getPackageInfo(name,
                        PackageManager.GET_SIGNING_CERTIFICATES,
                        UserHandle.getUserId(uid));
                if (signedWithReal(pi)) {
                    return queriedStock;
                }
            }
        }
        return realResult;
    }

    /** True if the queried cert is the stock cert, false if our real cert,
     *  null if neither (in the queried input type). */
    private static Boolean matchesEither(byte[] queried, int type) {
        if (SigningImitationHooks.certEquals(queried, type, sStockDer)) {
            return Boolean.TRUE;
        }
        if (SigningImitationHooks.certEquals(queried, type, sRealDer)) {
            return Boolean.FALSE;
        }
        return null;
    }

    private static boolean signedWithReal(PackageInfo pi) {
        if (pi == null || pi.signingInfo == null
                || pi.signingInfo.getApkContentsSigners() == null) {
            return false;
        }
        for (Signature s : pi.signingInfo.getApkContentsSigners()) {
            if (Arrays.equals(s.toByteArray(), sRealDer)) {
                return true;
            }
        }
        return false;
    }
}
