package com.android.internal.gmscompat.flags;

import android.app.compat.gms.GmsCompat;
import android.content.Intent;
import android.ext.PackageId;
import android.util.ArrayMap;
import android.util.Log;

import com.android.internal.gmscompat.GmsCompatConfig;

public class GservicesFlags {
    private static final String TAG = "GmcGservicesFlags";

    public static void applyOverrides(GmsCompatConfig config) {
        ArrayMap<String, GmsFlag> gservicesFlags = config.gservicesFlags;
        if (gservicesFlags == null) {
            return;
        }

        var overriddenFlags = new ArrayMap<String, String>(gservicesFlags.size());
        for (int i = 0; i < gservicesFlags.size(); ++i) {
            GmsFlag flag = gservicesFlags.valueAt(i);
            flag.applyToGservicesMap(overriddenFlags);
        }

        if (!overriddenFlags.isEmpty()) {
            var intent = new Intent("com.google.gservices.intent.action.GSERVICES_OVERRIDE");
            intent.setPackage(PackageId.GMS_CORE_NAME);
            overriddenFlags.forEach(intent::putExtra);
            Log.d(TAG, "sending GSERVICES_OVERRIDE broadcast, extras: " + intent.getExtras().toStringDeep());
            GmsCompat.appContext().sendBroadcast(intent);
        }
    }
}
