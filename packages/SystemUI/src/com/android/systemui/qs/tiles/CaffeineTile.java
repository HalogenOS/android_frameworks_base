/*
 * Copyright (C) 2016 The CyanogenMod Project
 * Copyright (c) 2017 The LineageOS Project
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

package com.android.systemui.qs.tiles;

import static com.android.internal.logging.MetricsLogger.VIEW_UNKNOWN;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.CountDownTimer;
import android.os.Handler;
import android.os.Looper;
import android.os.PowerManager;
import android.os.SystemClock;
import android.service.quicksettings.Tile;

import androidx.annotation.Nullable;

import com.android.internal.logging.MetricsLogger;
import com.android.systemui.animation.Expandable;
import com.android.systemui.dagger.qualifiers.Background;
import com.android.systemui.dagger.qualifiers.Main;
import com.android.systemui.plugins.ActivityStarter;
import com.android.systemui.plugins.FalsingManager;
import com.android.systemui.plugins.qs.QSTile.BooleanState;
import com.android.systemui.plugins.statusbar.StatusBarStateController;
import com.android.systemui.qs.QSHost;
import com.android.systemui.qs.QsEventLogger;
import com.android.systemui.qs.logging.QSLogger;
import com.android.systemui.qs.tileimpl.QSTileImpl;
import com.android.systemui.res.R;

import javax.inject.Inject;

/** Quick settings tile: Caffeine **/
public class CaffeineTile extends QSTileImpl<BooleanState> {

    public static final String TILE_SPEC = "caffeine";

    // Slider maps 0.0–1.0 to 1 minute–8 hours via x^1.2 curve
    private static final int MIN_SECONDS = 60;
    private static final int MAX_SECONDS = 8 * 60 * 60;
    // 0.5^3 ≈ 0.125 → maps slider midpoint to ~1 hour
    private static final double EXPONENT = 3.0;

    private static final int[] DURATIONS = new int[] {
        5 * 60,   // 5 min
        10 * 60,  // 10 min
        30 * 60,  // 30 min
        -1,       // infinity
    };
    private static final int INFINITE_DURATION_INDEX = DURATIONS.length - 1;

    @Nullable
    private Icon mIcon = null;

    private final PowerManager.WakeLock mWakeLock;
    private int mSecondsRemaining;
    private int mDuration; // current total duration in seconds, -1 = infinite
    private int mDurationIndex;
    private CountDownTimer mCountdownTimer = null;
    public long mLastClickTime = -1;
    private final Receiver mReceiver = new Receiver();

    @Inject
    public CaffeineTile(
            QSHost host,
            QsEventLogger uiEventLogger,
            @Background Looper backgroundLooper,
            @Main Handler mainHandler,
            FalsingManager falsingManager,
            MetricsLogger metricsLogger,
            StatusBarStateController statusBarStateController,
            ActivityStarter activityStarter,
            QSLogger qsLogger
    ) {
        super(host, uiEventLogger, backgroundLooper, mainHandler, falsingManager, metricsLogger,
                statusBarStateController, activityStarter, qsLogger);
        mWakeLock = mContext.getSystemService(PowerManager.class).newWakeLock(
                PowerManager.FULL_WAKE_LOCK, "CaffeineTile");
        mReceiver.init();
    }

    @Override
    public BooleanState newTileState() {
        return new BooleanState();
    }

    @Override
    protected void handleDestroy() {
        super.handleDestroy();
        stopCountDown();
        mReceiver.destroy();
        if (mWakeLock.isHeld()) {
            mWakeLock.release();
        }
    }

    @Override
    public void handleSetListening(boolean listening) {
    }

    @Override
    protected void handleClick(@Nullable Expandable expandable) {
        if (mWakeLock.isHeld() && (mLastClickTime != -1) &&
                (SystemClock.elapsedRealtime() - mLastClickTime < 5000)) {
            mDurationIndex++;
            if (mDurationIndex >= DURATIONS.length) {
                mDurationIndex = -1;
                stopCountDown();
                if (mWakeLock.isHeld()) {
                    mWakeLock.release();
                }
            } else {
                mDuration = DURATIONS[mDurationIndex];
                startCountDown(mDuration);
                if (!mWakeLock.isHeld()) {
                    mWakeLock.acquire();
                }
            }
        } else {
            if (mWakeLock.isHeld()) {
                mWakeLock.release();
                stopCountDown();
            } else {
                mWakeLock.acquire();
                mDurationIndex = 0;
                mDuration = DURATIONS[mDurationIndex];
                startCountDown(mDuration);
            }
        }
        mLastClickTime = SystemClock.elapsedRealtime();
        refreshState();
    }

    @Override
    protected void handleSliderChanged(float value) {
        mDuration = sliderToDuration(value);
        if (!mWakeLock.isHeld()) {
            mWakeLock.acquire();
        }
        startCountDown(mDuration);
        refreshState();
    }

    @Override
    protected void handleLongClick(@Nullable Expandable expandable) {
        if (mWakeLock.isHeld() && mDuration == -1) {
            return;
        }
        if (!mWakeLock.isHeld()) {
            mWakeLock.acquire();
        }
        mDurationIndex = INFINITE_DURATION_INDEX;
        mDuration = -1;
        startCountDown(-1);
        refreshState();
    }

    @Override
    public Intent getLongClickIntent() {
        return null;
    }

    @Override
    public CharSequence getTileLabel() {
        return mContext.getString(R.string.quick_settings_caffeine_label);
    }

    @Override
    public int getMetricsCategory() {
        return VIEW_UNKNOWN;
    }

    private static int sliderToDuration(float value) {
        double curved = Math.pow(value, EXPONENT);
        return (int) (MIN_SECONDS + curved * (MAX_SECONDS - MIN_SECONDS));
    }

    private static float durationToSlider(int seconds) {
        if (seconds <= MIN_SECONDS) return 0f;
        if (seconds >= MAX_SECONDS) return 1f;
        double normalized = (double) (seconds - MIN_SECONDS) / (MAX_SECONDS - MIN_SECONDS);
        return (float) Math.pow(normalized, 1.0 / EXPONENT);
    }

    private void startCountDown(long duration) {
        stopCountDown();
        mSecondsRemaining = (int) duration;
        if (duration == -1) {
            return;
        }
        mCountdownTimer = new CountDownTimer(duration * 1000, 1000) {
            @Override
            public void onTick(long millisUntilFinished) {
                mSecondsRemaining = (int) (millisUntilFinished / 1000);
                refreshState();
            }

            @Override
            public void onFinish() {
                if (mWakeLock.isHeld()) {
                    mWakeLock.release();
                }
                refreshState();
            }

        }.start();
    }

    private void stopCountDown() {
        if (mCountdownTimer != null) {
            mCountdownTimer.cancel();
            mCountdownTimer = null;
        }
    }

    private String formatValueWithRemainingTime() {
        if (mSecondsRemaining == -1) {
            return "\u221E"; // infinity
        }
        if (mSecondsRemaining >= 3600) {
            return String.format("%d:%02d",
                    mSecondsRemaining / 3600,
                    mSecondsRemaining / 60 % 60);
        }
        return String.format("%d:%02d", mSecondsRemaining / 60, mSecondsRemaining % 60);
    }

    @Override
    protected void handleUpdateState(BooleanState state, Object arg) {
        state.value = mWakeLock.isHeld();
        state.sliderEnabled = true;
        if (mIcon == null) {
            mIcon = maybeLoadResourceIcon(R.drawable.ic_qs_caffeine);
        }
        state.icon = mIcon;
        state.label = mContext.getString(R.string.quick_settings_caffeine_label);
        state.state = Tile.STATE_INACTIVE;
        if (state.value) {
            String timeLabel = formatValueWithRemainingTime();
            state.secondaryLabel = timeLabel;
            state.sliderShortLabel = timeLabel;
            state.contentDescription = mContext.getString(
                    R.string.accessibility_quick_settings_caffeine_on);
            state.sliderValue = mDuration == -1 ? 1f : durationToSlider(mSecondsRemaining);
        } else {
            state.secondaryLabel = null;
            state.sliderShortLabel = null;
            state.contentDescription = mContext.getString(
                    R.string.accessibility_quick_settings_caffeine_off);
            state.sliderValue = 0f;
        }
    }

    private final class Receiver extends BroadcastReceiver {
        public void init() {
            IntentFilter filter = new IntentFilter();
            filter.addAction(Intent.ACTION_SCREEN_OFF);
            mContext.registerReceiver(this, filter, null, mHandler);
        }

        public void destroy() {
            mContext.unregisterReceiver(this);
        }

        @Override
        public void onReceive(Context context, Intent intent) {
            String action = intent.getAction();
            if (Intent.ACTION_SCREEN_OFF.equals(action)) {
                stopCountDown();
                if (mWakeLock.isHeld()) {
                    mWakeLock.release();
                }
                refreshState();
            }
        }
    }
}
