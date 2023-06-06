package com.android.internal.gmscompat;

import android.app.Activity;
import android.app.compat.gms.GmsCompat;
import android.content.Intent;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;
import android.util.ArraySet;
import android.util.Log;

import com.android.internal.gmscompat.flags.GmsFlagOverrides;
import com.android.internal.gmscompat.util.GmcActivityUtils;

import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.FutureTask;

import static com.android.internal.gmscompat.GmsHooks.inPersistentGmsCoreProcess;
import static com.android.internal.gmscompat.GmsInfo.PACKAGE_GMS_CORE;

// see IGca2Gms
class BinderGca2Gms extends IGca2Gms.Stub {
    private static final String TAG = "BinderGca2Gms";
    private static final boolean DEV = false;

    @Override
    public void updateConfig(GmsCompatConfig newConfig) {
        GmsHooks.setConfig(newConfig);
    }

    @Override
    public void invalidateConfigCaches() {
        if (!inPersistentGmsCoreProcess) {
            throw new IllegalStateException();
        }

        SQLiteOpenHelper phenotypeDb = GmsHooks.getPhenotypeDb();

        if (phenotypeDb == null) {
            // shouldn't happen in practice, phenotype db is opened on startup
            Log.e(TAG, "phenotypeDb is null");
        } else {
            notifyForceDefaultFlagsChanged(phenotypeDb, GmsHooks.config());
        }

        GmsFlagOverrides.applyOverrides();
    }

    private static void notifyForceDefaultFlagsChanged(SQLiteOpenHelper phenotypeDb, GmsCompatConfig newConfig) {
        SQLiteDatabase db = phenotypeDb.getReadableDatabase();
        String[] columns = { "androidPackageName" };
        String selection = "packageName = ?";

        ArraySet<String> configPackageNames = new ArraySet<>(newConfig.forceDefaultFlagsMap.keySet());

        for (String configPackageName : configPackageNames) {
            String[] selectionArgs = { configPackageName };
            String packageName = null;
            try (Cursor c = db.query("Packages", columns, selection, selectionArgs, null, null, null, "1")) {
                if (c.moveToFirst()) {
                    packageName = c.getString(0);
                }
            }

            if (packageName == null) {
                Log.d(TAG, "unknown configPackageName " + configPackageName);
                continue;
            }

            Intent i = new Intent(PACKAGE_GMS_CORE + ".phenotype.UPDATE");
            i.putExtra(PACKAGE_GMS_CORE + ".phenotype.PACKAGE_NAME", configPackageName);
            i.setPackage(packageName);
            GmsCompat.appContext().sendBroadcast(i);
        }
    }

    @Override
    public boolean startActivityIfVisible(Intent intent) {
        Callable<Boolean> callable = () -> {
            Activity activity = GmcActivityUtils.getMostRecentVisibleActivity();
            if (activity == null) {
                return false;
            }
            activity.startActivity(intent);
            return true;
        };
        var task = new FutureTask<>(callable);
        // getMostRecentVisibleActivity() needs to be called from main thread to avoid races
        GmsCompat.appContext().getMainThreadHandler().post(task);
        try {
            return task.get().booleanValue();
        } catch (InterruptedException | ExecutionException e) {
            e.printStackTrace();
            return false;
        }
    }
}
