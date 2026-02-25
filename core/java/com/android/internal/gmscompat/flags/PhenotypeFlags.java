package com.android.internal.gmscompat.flags;

import android.app.compat.gms.GmsCompat;
import android.content.Intent;
import android.ext.PackageId;
import android.util.ArrayMap;
import android.util.Log;

import com.android.internal.gmscompat.GmsCompatConfig;

import java.util.ArrayList;
import java.util.Collection;

public class PhenotypeFlags {
    private static final String TAG = "GmcPhenotypeFlags";

    public static final String ACTION_COMMITTED = "com.google.android.gms.phenotype.COMMITTED";

    public static void applyOverrides(GmsCompatConfig config) {
        ArrayMap<String, ArrayMap<String, GmsFlag>> packageFlagMap = config.flags;
        for (int packageIdx = 0; packageIdx < packageFlagMap.size(); ++packageIdx) {
            Collection<GmsFlag> configFlags = packageFlagMap.valueAt(packageIdx).values();
            var overridenFlags = new ArrayList<GmsFlag>(configFlags.size());
            for (GmsFlag flag : configFlags) {
                if (flag.shouldOverride()) {
                    overridenFlags.add(flag);
                }
            }
            int numFlags = overridenFlags.size();
            String[] flagNames = new String[numFlags];
            String[] flagValues = new String[numFlags];
            String[] flagTypes = new String[numFlags];
            for (int i = 0; i < numFlags; ++i) {
                GmsFlag flag = overridenFlags.get(i);
                flagNames[i] = flag.name;
                flagValues[i] = flag.valueAsString();
                flagTypes[i] = flag.typeAsString();
            }

            String flagPackageName = packageFlagMap.keyAt(packageIdx);

            var intent = new Intent("com.google.android.gms.phenotype.FLAG_OVERRIDE");
            intent.setPackage(PackageId.GMS_CORE_NAME);
            intent.putExtra("package", flagPackageName);
            intent.putExtra("user", "*");
            intent.putExtra("flags", flagNames);
            intent.putExtra("values", flagValues);
            intent.putExtra("types", flagTypes);
            Log.d(TAG, "sending FLAG_OVERRIDE broadcast for flagPackage " + flagPackageName
                    + ", extras: " + intent.getExtras().toStringDeep());
            GmsCompat.appContext().sendBroadcast(intent);
        }
    }
}
