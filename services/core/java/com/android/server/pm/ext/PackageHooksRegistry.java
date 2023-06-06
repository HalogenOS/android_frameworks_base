package com.android.server.pm.ext;

import android.ext.PackageId;

import com.android.internal.pm.pkg.parsing.PackageParsingHooks;

public class PackageHooksRegistry {

    public static PackageParsingHooks getParsingHooks(String pkgName) {
        PackageParsingHooks gmsCompatHooks = GmsCompatPkgParsingHooks.maybeGet(pkgName);
        if (gmsCompatHooks != null) {
            return gmsCompatHooks;
        }

        return switch (pkgName) {
            case PackageId.GSF_NAME -> new GsfParsingHooks();
            default -> PackageParsingHooks.DEFAULT;
        };
    }

    public static PackageHooks getHooks(int packageId) {
        return switch (packageId) {
            case PackageId.G_CARRIER_SETTINGS -> new GCarrierSettingsHooks();
            case PackageId.ANDROID_AUTO -> new AndroidAutoHooks();
            default -> PackageHooks.DEFAULT;
        };
    }
}
