/*
 * Copyright (C) 2025 The Android Open Source Project
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

package com.android.server.am;

import static com.android.server.am.ActivityManagerDebugConfig.TAG_AM;
import static com.android.server.am.ActivityManagerDebugConfig.TAG_WITH_CLASS_NAME;

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.app.ActivityOptions.SceneTransitionInfo;
import android.app.ContentProviderHolder;
import android.app.IApplicationThread;
import android.app.IInstrumentationWatcher;
import android.app.INativeApplicationThread;
import android.app.IUiAutomationConnection;
import android.app.LoadedApk;
import android.app.ProfilerInfo;
import android.app.ReceiverInfo;
import android.app.backup.BackupAnnotations.BackupDestination;
import android.app.servertransaction.ClientTransaction;
import android.content.AutofillOptions;
import android.content.ComponentName;
import android.content.ContentCaptureOptions;
import android.content.IIntentReceiver;
import android.content.Intent;
import android.content.pm.ActivityInfo;
import android.content.pm.ApplicationInfo;
import android.content.pm.GosPackageState;
import android.content.pm.PackageManager;
import android.content.pm.ParceledListSlice;
import android.content.pm.ProviderInfo;
import android.content.pm.ProviderInfoList;
import android.content.pm.ServiceInfo;
import android.content.res.CompatibilityInfo;
import android.content.res.Configuration;
import android.os.Bundle;
import android.os.Debug;
import android.os.IBinder;
import android.os.ParcelFileDescriptor;
import android.os.RemoteCallback;
import android.os.RemoteException;
import android.os.SharedMemory;
import android.os.UserHandle;
import android.os.instrumentation.IOffsetCallback;
import android.os.instrumentation.MethodDescriptor;
import android.text.TextUtils;
import android.util.Slog;
import android.view.autofill.AutofillId;
import android.view.translation.TranslationSpec;
import android.view.translation.UiTranslationSpec;
import android.window.ITaskFragmentOrganizer;
import android.window.TaskFragmentTransaction;

import com.android.internal.app.IVoiceInteractor;

import dalvik.annotation.optimization.NeverCompile;

import java.io.FileDescriptor;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * NativeApplicationThreadWrapper is a helper class treating INativeApplicationThread like
 * IApplicationThread.
 *
 * TODO(b/431636312): Implement necessary methods and raise errors when calling unexpected
 * methods.
 */
public class NativeApplicationThreadWrapper extends IApplicationThread.Stub {
    static final String TAG = TAG_WITH_CLASS_NAME ? "ActivityManagerService" : TAG_AM;

    /**
     * The default name of the native library that will be loaded in the native process.
     */
    static final String LIB_NAME_BODY_DEFAULT = "main";

    /**
     * The default name of the entrypoint function that will run after the library is loaded.
     */
    static final String FUNC_NAME_DEFAULT = "ANativeService_onCreate";

    /**
     * Optional meta-data that can be in the manifest for this component, specifying the name of the
     * native shared library to load.
     */
    static final String META_DATA_LIB_NAME = "android.app.lib_name";

    /**
     * Optional meta-data that can be in the manifest for this component, specifying the name of the
     * main entry point for this native service in the loaded library.
     */
    static final String META_DATA_FUNC_NAME = "android.app.func_name";

    private INativeApplicationThread mNativeThread;
    private ActivityManagerService mMgr;
    private final int mUid;
    private final long mStartSeq;

    NativeApplicationThreadWrapper(INativeApplicationThread nativeThread,
            ActivityManagerService mgr, int uid, long startSeq) {
        if (!android.os.Flags.nativeFrameworkPrototype()) {
            throw new SecurityException("Invalid construction of NativeApplicationThreadWrapper");
        }
        mNativeThread = nativeThread;
        mMgr = mgr;
        mUid = uid;
        mStartSeq = startSeq;
    }

    @Override
    public final void scheduleReceiver(Intent intent, ActivityInfo info,
            CompatibilityInfo compatInfo, int resultCode, String data, Bundle extras,
            boolean ordered, boolean assumeDelivered, int sendingUser, int processState,
            int sendingUid, String sendingPackage) {}

    @Override
    public final void scheduleReceiverList(List<ReceiverInfo> info) throws RemoteException {}

    @Override
    public final void scheduleCreateBackupAgent(ApplicationInfo app, int backupMode, int userId,
            @BackupDestination int backupDestination) {}

    @Override
    public final void scheduleDestroyBackupAgent(ApplicationInfo app, int userId) {}

    @Override
    public final void scheduleCreateService(
            IBinder token, ServiceInfo info, CompatibilityInfo compatInfo, int processState) {
        String libNameBody = LIB_NAME_BODY_DEFAULT;
        String funcName = FUNC_NAME_DEFAULT;
        // Get ServiceInfo with meta-data since `info` doesn't have it.
        try {
            int userId = UserHandle.getUserId(mUid);
            ServiceInfo infoWithMeta = mMgr.getPackageManager()
                    .getServiceInfo(info.getComponentName(), PackageManager.GET_META_DATA, userId);
            if (infoWithMeta.metaData != null) {
                String ln = infoWithMeta.metaData.getString(META_DATA_LIB_NAME);
                if (!TextUtils.isEmpty(ln)) libNameBody = ln;
                String fn = infoWithMeta.metaData.getString(META_DATA_FUNC_NAME);
                if (!TextUtils.isEmpty(fn)) funcName = fn;
            }
        } catch (RemoteException e) {
            Slog.w(TAG, "getServiceInfo failed", e);
        }
        String libName = "lib" + libNameBody + ".so";

        if (!(token instanceof ServiceRecord)) {
            Slog.e(TAG, "NativeApplicationThreadWrapper: token is not a ServiceRecord");
            return;
        }
        ServiceRecord r = (ServiceRecord) token;
        List<String> zipPaths = new ArrayList(10);
        List<String> libPaths = new ArrayList(10);
        LoadedApk.makePaths(null /* activityThread, used for instrumentation */,
                false /* isBundledApp */, r.appInfo, zipPaths, libPaths);

        Slog.i(TAG, "Scheduling native service – lib: " + libName
                        + ", base symbol: " + funcName
                        + ", nativeLibraryDir: " + r.appInfo.nativeLibraryDir);
        for (String libPath : libPaths) {
            Slog.i(TAG, "libPath: " + libPath);
        }

        try {
            mNativeThread.scheduleCreateService(r, libPaths, r.appInfo.dataDir,
                    libName, funcName, processState);
        } catch (RemoteException e) {
            Slog.w(TAG, "scheduleCreateService failed", e);
        }
    }

    @Override
    public final void scheduleBindService(IBinder token, IBinder bindToken, Intent intent,
            boolean rebind, int processState, long bindSeq) {
        String data = null;
        if (intent.getData() != null) {
            data = intent.getData().toString();
        }
        try {
            mNativeThread.scheduleBindService(
                    token, bindToken, intent.hashCode(), intent.getAction(), data, rebind,
                    processState, bindSeq);
        } catch (RemoteException e) {
            Slog.w(TAG, "scheduleBindService failed", e);
        }
    }

    @Override
    public final void scheduleUnbindService(IBinder token, IBinder bindToken, Intent intent) {
        try {
            mNativeThread.scheduleUnbindService(token, bindToken, intent.hashCode());
        } catch (RemoteException e) {
            Slog.w(TAG, "scheduleUnbindService failed", e);
        }
    }

    @Override
    public final void scheduleServiceArgs(IBinder token, ParceledListSlice args) {}

    @Override
    public final void scheduleStopService(IBinder token) {
        try {
            mNativeThread.scheduleDestroyService(token);
        } catch (RemoteException e) {
            Slog.w(TAG, "scheduleDestroyService failed", e);
        }
    }

    @Override
    public final void scheduleTimeoutService(IBinder token, int startId) {}

    @Override
    public final void schedulePing(RemoteCallback pong) {}

    @Override
    public final void scheduleTimeoutServiceForType(
            IBinder token, int startId, @ServiceInfo.ForegroundServiceType int fgsType) {}

    @Override
    public final void bindApplication(
            String processName,
            ApplicationInfo appInfo,
            String sdkSandboxClientAppVolumeUuid,
            String sdkSandboxClientAppPackage,
            boolean isSdkInSandbox,
            ProviderInfoList providerList,
            ComponentName instrumentationName,
            ProfilerInfo profilerInfo,
            Bundle instrumentationArgs,
            IInstrumentationWatcher instrumentationWatcher,
            IUiAutomationConnection instrumentationUiConnection,
            int debugMode,
            boolean enableBinderTracking,
            boolean trackAllocation,
            boolean isRestrictedBackupMode,
            boolean persistent,
            Configuration config,
            CompatibilityInfo compatInfo,
            Map services,
            Bundle coreSettings,
            String buildSerial,
            AutofillOptions autofillOptions,
            ContentCaptureOptions contentCaptureOptions,
            long[] disabledCompatChanges,
            long[] loggableCompatChanges,
            boolean logChangeChecksToStatsD,
            SharedMemory serializedSystemFontMap,
            FileDescriptor applicationSharedMemoryFd,
            long startRequestedElapsedTime,
            long startRequestedUptime) {
        try {
            // TODO(b/431634361): Send necessary or useful information to native processes.
            mNativeThread.bindApplication();
        } catch (RemoteException e) {
            Slog.w(TAG, "bindApplication failed", e);
        }
    }

    @Override
    public final void runIsolatedEntryPoint(String entryPoint, String[] entryPointArgs) {}

    @Override
    public final void scheduleExit() {}

    @Override
    public final void scheduleSuicide() {}

    @Override
    public void scheduleApplicationInfoChanged(ApplicationInfo ai) {}

    @Override
    public void updateTimeZone() {}

    @Override
    public void clearDnsCache() {}

    @Override
    public void updateHttpProxy() {}

    @Override
    public void processInBackground() {}

    @Override
    public void dumpService(ParcelFileDescriptor pfd, IBinder servicetoken, String[] args) {}

    @Override
    public void scheduleRegisteredReceiver(IIntentReceiver receiver, Intent intent, int resultCode,
            String dataStr, Bundle extras, boolean ordered, boolean sticky, boolean assumeDelivered,
            int sendingUser, int processState, int sendingUid, String sendingPackage)
            throws RemoteException {}

    @Override
    public void scheduleLowMemory() {}

    @Override
    public void profilerControl(boolean start, ProfilerInfo profilerInfo, int profileType) {}

    @Override
    public void dumpHeap(boolean managed, boolean mallocInfo, boolean runGc, String dumpBitmaps,
            String path, ParcelFileDescriptor fd, RemoteCallback finishCallback) {}

    @Override
    public void attachAgent(String agent) {}

    @Override
    public void attachStartupAgents(String dataDir) {}

    @Override
    public void setSchedulingGroup(int group) {}

    @Override
    public void dispatchPackageBroadcast(int cmd, String[] packages) {}

    @Override
    public void scheduleCrash(String msg, int typeId, @Nullable Bundle extras) {}

    @Override
    public void dumpResources(ParcelFileDescriptor fd, RemoteCallback callback) {}

    @Override
    public void dumpActivity(
            ParcelFileDescriptor pfd, IBinder activitytoken, String prefix, String[] args) {}

    @Override
    public void dumpProvider(ParcelFileDescriptor pfd, IBinder providertoken, String[] args) {}

    @NeverCompile
    @Override
    public void dumpMemInfo(ParcelFileDescriptor pfd, Debug.MemoryInfo mem, boolean checkin,
            boolean dumpFullInfo, boolean dumpDalvik, boolean dumpSummaryOnly,
            boolean dumpUnreachable, boolean dumpAllocatorStats, String[] args) {}

    @NeverCompile
    @Override
    public void dumpMemInfoProto(ParcelFileDescriptor pfd, Debug.MemoryInfo mem,
            boolean dumpFullInfo, boolean dumpDalvik, boolean dumpSummaryOnly,
            boolean dumpUnreachable, String[] args) {}

    @Override
    public void dumpGfxInfo(ParcelFileDescriptor pfd, String[] args) {}

    @Override
    public void dumpCacheInfo(ParcelFileDescriptor pfd, String[] args) {}

    @Override
    public void dumpDbInfo(final ParcelFileDescriptor pfd, final String[] args) {}

    @Override
    public void unstableProviderDied(IBinder provider) {}

    @Override
    public void requestAssistContextExtras(IBinder activityToken, IBinder requestToken,
            int requestType, int sessionId, int flags) {}

    @Override
    public void setCoreSettings(Bundle coreSettings) {}

    @Override
    public void updatePackageCompatibilityInfo(String pkg, CompatibilityInfo info) {}

    @Override
    public void scheduleTrimMemory(int level) {
        try {
            mNativeThread.scheduleTrimMemory(level);
        } catch (RemoteException e) {
            Slog.w(TAG, "scheduleTrimMemory failed", e);
        }
    }

    @Override
    public void scheduleTranslucentConversionComplete(IBinder token, boolean drawComplete) {}

    @Override
    public void scheduleOnNewSceneTransitionInfo(IBinder token, SceneTransitionInfo info) {}

    @Override
    public void setProcessState(int state) {
        try {
            mNativeThread.setProcessState(state);
        } catch (RemoteException e) {
            Slog.w(TAG, "setProcessState failed", e);
        }
    }

    @Override
    public void setNetworkBlockSeq(long procStateSeq) {}

    @Override
    public void scheduleInstallProvider(ProviderInfo provider) {}

    @Override
    public final void updateTimePrefs(int timeFormatPreference) {}

    @Override
    public void scheduleEnterAnimationComplete(IBinder token) {}

    @Override
    public void notifyCleartextNetwork(byte[] firstPacket) {}

    @Override
    public void startBinderTracking() {}

    @Override
    public void stopBinderTrackingAndDump(ParcelFileDescriptor pfd) {}

    @Override
    public void scheduleLocalVoiceInteractionStarted(
            IBinder token, IVoiceInteractor voiceInteractor) throws RemoteException {}

    @Override
    public void handleTrustStorageUpdate() {}

    @Override
    public void scheduleTransaction(ClientTransaction transaction) throws RemoteException {}

    @Override
    public void scheduleTaskFragmentTransaction(@NonNull ITaskFragmentOrganizer organizer,
            @NonNull TaskFragmentTransaction transaction) throws RemoteException {}

    @Override
    public void requestDirectActions(@NonNull IBinder activityToken,
            @NonNull IVoiceInteractor interactor, @Nullable RemoteCallback cancellationCallback,
            @NonNull RemoteCallback callback) {}

    @Override
    public void performDirectAction(@NonNull IBinder activityToken, @NonNull String actionId,
            @Nullable Bundle arguments, @Nullable RemoteCallback cancellationCallback,
            @NonNull RemoteCallback resultCallback) {}

    @Override
    public void notifyContentProviderPublishStatus(@NonNull ContentProviderHolder holder,
            @NonNull String authorities, int userId, boolean published) {}

    @Override
    public void instrumentWithoutRestart(ComponentName instrumentationName,
            Bundle instrumentationArgs, IInstrumentationWatcher instrumentationWatcher,
            IUiAutomationConnection instrumentationUiConnection, ApplicationInfo targetInfo) {}

    @Override
    public void updateUiTranslationState(IBinder activityToken, int state,
            TranslationSpec sourceSpec, TranslationSpec targetSpec, List<AutofillId> viewIds,
            UiTranslationSpec uiTranslationSpec) {}

    @Override
    public void getExecutableMethodFileOffsets(
            @NonNull MethodDescriptor methodDescriptor, @NonNull IOffsetCallback resultCallback) {}

    @Override
    public void onGosPackageStateChanged(GosPackageState state) {
        Slog.e(TAG, "onGosPackageStateChanged");
        System.exit(1);
    }

    @NeverCompile
    @Override
    public void dumpBitmapsProto(ParcelFileDescriptor pfd, String dumpFormat) {}

    @Override
    public void requestHandoffActivityData(IBinder requestToken, List<IBinder> activityTokens) {}
}
