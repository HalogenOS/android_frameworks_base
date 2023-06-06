package com.android.internal.gmscompat.flags;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.util.Log;

import com.android.internal.gmscompat.GmsCompatConfig;
import com.android.internal.gmscompat.GmsHooks;

public class GmsFlagOverrides {
    private static final String TAG = "GmsFlagOverrides";

    public static void init(Context ctx) {
        var receiver = new BroadcastReceiver() {
            private boolean receivedPhenotypeCommittedBroadcast;

            @Override
            public void onReceive(Context context, Intent intent) {
                if (PhenotypeFlags.ACTION_COMMITTED.equals(intent.getAction())) {
                    if (receivedPhenotypeCommittedBroadcast) {
                        return;
                    }
                    receivedPhenotypeCommittedBroadcast = true;
                }
                Log.d(TAG, "received " + intent);
                applyOverrides();
            }
        };
        // Most phenotype flags and all Gservices flags are stored on user-encrypted storage,
        // i.e. they can't be updated while the device is in Direct Boot state
        var filter = new IntentFilter(Intent.ACTION_USER_UNLOCKED);
        // In some cases phenotype service isn't ready to accept overrides at user_unlocked time and
        // at process init time. It's always ready by the time phenotype ACTION_COMMITTED is sent.
        filter.addAction(PhenotypeFlags.ACTION_COMMITTED);
        ctx.registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED);

        applyOverrides();
    }

    public static void applyOverrides() {
        GmsCompatConfig config = GmsHooks.config();
        GservicesFlags.applyOverrides(config);
        PhenotypeFlags.applyOverrides(config);
    }
}
