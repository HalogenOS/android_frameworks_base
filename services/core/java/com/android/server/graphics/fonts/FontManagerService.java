/*
 * Copyright (C) 2020 The Android Open Source Project
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

package com.android.server.graphics.fonts;

import android.Manifest;
import android.annotation.NonNull;
import android.annotation.Nullable;
import android.annotation.RequiresPermission;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.om.FabricatedOverlay;
import android.content.om.OverlayIdentifier;
import android.content.om.OverlayManagerTransaction;
import android.graphics.Typeface;
import android.graphics.fonts.FontFamily;
import android.graphics.fonts.FontFileUtil;
import android.graphics.fonts.FontManager;
import android.graphics.fonts.FontUpdateRequest;
import android.graphics.fonts.FontStyle;
import android.graphics.fonts.SystemFonts;
import android.os.Binder;
import android.os.Build;
import android.os.ServiceSpecificException;
import android.os.ParcelFileDescriptor;
import android.os.ResultReceiver;
import android.os.UserHandle;
import android.os.UserManager;
import android.os.SharedMemory;
import android.os.ShellCallback;
import android.system.ErrnoException;
import android.text.FontConfig;
import android.util.AndroidException;
import android.util.ArrayMap;
import android.util.IndentingPrintWriter;
import android.util.Log;
import android.util.Slog;
import android.util.TypedValue;

import com.android.internal.R;
import com.android.internal.annotations.GuardedBy;
import com.android.internal.graphics.fonts.IFontManager;
import com.android.internal.security.VerityUtils;
import com.android.internal.util.DumpUtils;
import com.android.internal.util.Preconditions;
import com.android.server.LocalServices;
import com.android.server.SystemServerInitThreadPool;
import com.android.server.om.OverlayManagerInternal;
import com.android.server.SystemService;
import com.android.text.flags.Flags;

import java.io.File;
import java.io.FileDescriptor;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.PrintWriter;
import java.nio.ByteBuffer;
import java.nio.DirectByteBuffer;
import java.nio.NioUtils;
import java.nio.channels.FileChannel;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CompletableFuture;

/** A service for managing system fonts. */
public final class FontManagerService extends IFontManager.Stub {
    private static final String TAG = "FontManagerService";

    private static final String FONT_FILES_DIR = "/data/fonts/files";
    private static final String CONFIG_XML_FILE = "/data/fonts/config/config.xml";

    // 'wght' as a big-endian 4-byte tag, matching FontFileUtil.getSupportedAxes().
    private static final int WGHT_AXIS_TAG =
            ('w' << 24) | ('g' << 16) | ('h' << 8) | 't';

    /** Pre-installed named font families discovered from the system image. */
    @NonNull
    private final Set<String> mPreinstalledFontFamilies = new HashSet<>();

    @android.annotation.EnforcePermission(android.Manifest.permission.UPDATE_FONTS)
    @RequiresPermission(Manifest.permission.UPDATE_FONTS)
    @Override
    public FontConfig getFontConfig() {
        super.getFontConfig_enforcePermission();

        return getSystemFontConfig();
    }

    @RequiresPermission(Manifest.permission.UPDATE_FONTS)
    @Override
    public int updateFontFamily(@NonNull List<FontUpdateRequest> requests, int baseVersion) {
        try {
            Preconditions.checkArgumentNonnegative(baseVersion);
            Objects.requireNonNull(requests);
            getContext().enforceCallingPermission(Manifest.permission.UPDATE_FONTS,
                    "UPDATE_FONTS permission required.");
            try {
                update(baseVersion, requests);
                return FontManager.RESULT_SUCCESS;
            } catch (SystemFontException e) {
                Slog.e(TAG, "Failed to update font family", e);
                return e.getErrorCode();
            }
        } finally {
            closeFileDescriptors(requests);
        }
    }

    @android.annotation.EnforcePermission(android.Manifest.permission.INSTALL_CUSTOM_FONTS)
    @Override
    public int installCustomFontFile(@NonNull ParcelFileDescriptor fd) {
        super.installCustomFontFile_enforcePermission();
        try {
            synchronized (mUpdatableFontDirLock) {
                if (mUpdatableFontDir == null) {
                    return FontManager.RESULT_ERROR_FONT_UPDATER_DISABLED;
                }
                mUpdatableFontDir.installCustomFontFile(fd.getFileDescriptor());
                updateSerializedFontMap();
                return FontManager.RESULT_SUCCESS;
            }
        } catch (SystemFontException e) {
            Slog.e(TAG, "Failed to install custom font file", e);
            return e.getErrorCode();
        } finally {
            try {
                fd.close();
            } catch (IOException e) {
                Slog.w(TAG, "Failed to close fd", e);
            }
        }
    }

    @android.annotation.EnforcePermission(android.Manifest.permission.INSTALL_CUSTOM_FONTS)
    @Override
    public int installCustomFontFamily(@NonNull List<FontUpdateRequest> familyRequests) {
        super.installCustomFontFamily_enforcePermission();
        try {
            synchronized (mUpdatableFontDirLock) {
                if (mUpdatableFontDir == null) {
                    return FontManager.RESULT_ERROR_FONT_UPDATER_DISABLED;
                }
                mUpdatableFontDir.updateCustomFonts(familyRequests);
                updateSerializedFontMap();
                return FontManager.RESULT_SUCCESS;
            }
        } catch (SystemFontException e) {
            Slog.e(TAG, "Failed to install custom font family", e);
            return e.getErrorCode();
        } finally {
            closeFileDescriptors(familyRequests);
        }
    }

    @android.annotation.EnforcePermission(android.Manifest.permission.INSTALL_CUSTOM_FONTS)
    @Override
    public int removeCustomFontFamily(@NonNull String familyName) {
        super.removeCustomFontFamily_enforcePermission();
        if (mPreinstalledFontFamilies.contains(familyName)) {
            Slog.w(TAG, "Cannot remove pre-installed font family: " + familyName);
            return FontManager.RESULT_ERROR_INVALID_FONT_NAME;
        }
        final List<Integer> affectedUsers = new ArrayList<>();
        try {
            synchronized (mUpdatableFontDirLock) {
                if (mUpdatableFontDir == null) {
                    return FontManager.RESULT_ERROR_FONT_UPDATER_DISABLED;
                }
                for (Map.Entry<Integer, String> entry :
                        mUpdatableFontDir.getActiveCustomFontFamiliesByUser().entrySet()) {
                    if (familyName.equals(entry.getValue())) {
                        affectedUsers.add(entry.getKey());
                    }
                }
                mUpdatableFontDir.removeCustomFontFamily(familyName);
                for (int userId : affectedUsers) {
                    mUpdatableFontDir.setActiveCustomFontFamily(userId, null);
                }
                updateSerializedFontMap();
            }
        } catch (SystemFontException e) {
            Slog.e(TAG, "Failed to remove custom font family", e);
            return e.getErrorCode();
        }
        for (int userId : affectedUsers) {
            try {
                applyCustomFontOverlayForUser(null, userId);
            } catch (RuntimeException e) {
                Slog.e(TAG, "Failed to auto-disable font overlay after removal for user "
                        + userId, e);
            }
        }
        return FontManager.RESULT_SUCCESS;
    }

    @android.annotation.EnforcePermission(android.Manifest.permission.INSTALL_CUSTOM_FONTS)
    @Override
    public @NonNull List<String> getCustomFontFamilyNames() {
        super.getCustomFontFamilyNames_enforcePermission();
        synchronized (mUpdatableFontDirLock) {
            if (mUpdatableFontDir == null) {
                return new ArrayList<>(mPreinstalledFontFamilies);
            }
            List<String> names = new ArrayList<>(
                    mUpdatableFontDir.getCustomFontFamilyNames());
            for (String family : mPreinstalledFontFamilies) {
                if (!names.contains(family)) {
                    names.add(family);
                }
            }
            return names;
        }
    }

    private static final String CUSTOM_FONT_OVERLAY_PACKAGE = "android";
    private static final String CUSTOM_FONT_OVERLAY_NAME_PREFIX = "custom_font_family_u";
    private static final String[] CUSTOM_FONT_OVERLAY_RESOURCES = {
            // Legacy config_* entries — styles reference these directly via
            // @*android:string/config_... rather than passing a hardcoded name
            // to Typeface.create, so they bypass the alias resolver.
            "android:string/config_bodyFontFamily",
            "android:string/config_bodyFontFamilyMedium",
            "android:string/config_headlineFontFamily",
            "android:string/config_headlineFontFamilyMedium",
            "android:string/config_regularFontFamily",
            "android:string/config_lightFontFamily",
            "android:string/config_clockFontFamily",
            // Alias resources — cover hardcoded fontFamily names that flow
            // through Typeface.create(String, ...) and FontFamilyResolver.
            // Sans-serif variants
            "android:string/config_fontFamilyAlias_sans_serif",
            "android:string/config_fontFamilyAlias_sans_serif_medium",
            "android:string/config_fontFamilyAlias_sans_serif_light",
            "android:string/config_fontFamilyAlias_sans_serif_thin",
            "android:string/config_fontFamilyAlias_sans_serif_black",
            "android:string/config_fontFamilyAlias_sans_serif_regular",
            "android:string/config_fontFamilyAlias_sans_serif_condensed",
            "android:string/config_fontFamilyAlias_sans_serif_condensed_medium",
            "android:string/config_fontFamilyAlias_sans_serif_condensed_light",
            // GMS / Pixel / Roboto
            "android:string/config_fontFamilyAlias_google_sans",
            "android:string/config_fontFamilyAlias_google_sans_clock",
            "android:string/config_fontFamilyAlias_google_sans_flex",
            "android:string/config_fontFamilyAlias_google_sans_medium",
            "android:string/config_fontFamilyAlias_google_sans_text",
            "android:string/config_fontFamilyAlias_google_sans_text_medium",
            "android:string/config_fontFamilyAlias_roboto_regular",
            "android:string/config_fontFamilyAlias_font_family_flex_device_default",
            // Variable-axis: display
            "android:string/config_fontFamilyAlias_variable_display_large",
            "android:string/config_fontFamilyAlias_variable_display_large_emphasized",
            "android:string/config_fontFamilyAlias_variable_display_medium",
            "android:string/config_fontFamilyAlias_variable_display_medium_emphasized",
            "android:string/config_fontFamilyAlias_variable_display_small",
            "android:string/config_fontFamilyAlias_variable_display_small_emphasized",
            // Variable-axis: headline
            "android:string/config_fontFamilyAlias_variable_headline_large",
            "android:string/config_fontFamilyAlias_variable_headline_large_emphasized",
            "android:string/config_fontFamilyAlias_variable_headline_medium",
            "android:string/config_fontFamilyAlias_variable_headline_medium_emphasized",
            "android:string/config_fontFamilyAlias_variable_headline_small",
            "android:string/config_fontFamilyAlias_variable_headline_small_emphasized",
            // Variable-axis: title
            "android:string/config_fontFamilyAlias_variable_title_large",
            "android:string/config_fontFamilyAlias_variable_title_large_emphasized",
            "android:string/config_fontFamilyAlias_variable_title_medium",
            "android:string/config_fontFamilyAlias_variable_title_medium_emphasized",
            "android:string/config_fontFamilyAlias_variable_title_small",
            "android:string/config_fontFamilyAlias_variable_title_small_emphasized",
            // Variable-axis: label
            "android:string/config_fontFamilyAlias_variable_label_large",
            "android:string/config_fontFamilyAlias_variable_label_large_emphasized",
            "android:string/config_fontFamilyAlias_variable_label_medium",
            "android:string/config_fontFamilyAlias_variable_label_medium_emphasized",
            "android:string/config_fontFamilyAlias_variable_label_small",
            "android:string/config_fontFamilyAlias_variable_label_small_emphasized",
            // Variable-axis: body
            "android:string/config_fontFamilyAlias_variable_body_large",
            "android:string/config_fontFamilyAlias_variable_body_large_emphasized",
            "android:string/config_fontFamilyAlias_variable_body_medium",
            "android:string/config_fontFamilyAlias_variable_body_medium_emphasized",
            "android:string/config_fontFamilyAlias_variable_body_small",
            "android:string/config_fontFamilyAlias_variable_body_small_emphasized",
    };

    @android.annotation.EnforcePermission(android.Manifest.permission.INSTALL_CUSTOM_FONTS)
    @Override
    public int setActiveCustomFontFamily(@Nullable String familyName) {
        super.setActiveCustomFontFamily_enforcePermission();
        final int userId = UserHandle.getCallingUserId();
        synchronized (mUpdatableFontDirLock) {
            if (mUpdatableFontDir == null) {
                return FontManager.RESULT_ERROR_FONT_UPDATER_DISABLED;
            }
            if (familyName != null
                    && !mUpdatableFontDir.getCustomFontFamilyNames().contains(familyName)
                    && !mPreinstalledFontFamilies.contains(familyName)) {
                Slog.e(TAG, "Custom font family not installed: " + familyName);
                return FontManager.RESULT_ERROR_FONT_NOT_FOUND;
            }
        }
        try {
            applyCustomFontOverlayForUser(familyName, userId);
        } catch (RuntimeException e) {
            Slog.e(TAG, "Failed to apply custom font overlay", e);
            return FontManager.RESULT_ERROR_FAILED_UPDATE_CONFIG;
        }
        try {
            synchronized (mUpdatableFontDirLock) {
                mUpdatableFontDir.setActiveCustomFontFamily(userId, familyName);
            }
        } catch (SystemFontException e) {
            Slog.e(TAG, "Failed to persist active custom font family", e);
            return e.getErrorCode();
        }
        return FontManager.RESULT_SUCCESS;
    }

    @android.annotation.EnforcePermission(android.Manifest.permission.INSTALL_CUSTOM_FONTS)
    @Override
    public @Nullable String getActiveCustomFontFamily() {
        super.getActiveCustomFontFamily_enforcePermission();
        final int userId = UserHandle.getCallingUserId();
        synchronized (mUpdatableFontDirLock) {
            if (mUpdatableFontDir == null) {
                return null;
            }
            return mUpdatableFontDir.getActiveCustomFontFamily(userId);
        }
    }

    @android.annotation.EnforcePermission(android.Manifest.permission.INSTALL_CUSTOM_FONTS)
    @Override
    public @NonNull String installCustomFontFamilyFromFile(@NonNull ParcelFileDescriptor fd) {
        Slog.d(TAG, "installCustomFontFamilyFromFile: enforcing permission");
        super.installCustomFontFamilyFromFile_enforcePermission();
        Slog.d(TAG, "installCustomFontFamilyFromFile: permission OK, entering try block");
        try {
            synchronized (mUpdatableFontDirLock) {
                if (mUpdatableFontDir == null) {
                    throw new ServiceSpecificException(
                            FontManager.RESULT_ERROR_FONT_UPDATER_DISABLED,
                            "Font updater disabled");
                }
                Slog.d(TAG, "installCustomFontFamilyFromFile: calling installCustomFontFile");
                final String psName =
                        mUpdatableFontDir.installCustomFontFile(fd.getFileDescriptor());
                Slog.d(TAG, "installCustomFontFamilyFromFile: psName=" + psName);

                // Reject static fonts — only variable fonts with a wght axis are supported
                // so that a single file can serve all weights.
                final File fontFile = mUpdatableFontDir.getPostScriptMap().get(psName);
                if (fontFile == null) {
                    throw new SystemFontException(
                            FontManager.RESULT_ERROR_FONT_NOT_FOUND,
                            "Installed font file not found for: " + psName);
                }
                try (FileInputStream fis = new FileInputStream(fontFile);
                        FileChannel channel = fis.getChannel()) {
                    final ByteBuffer buffer =
                            channel.map(FileChannel.MapMode.READ_ONLY, 0, channel.size());
                    final Set<Integer> supportedAxes = FontFileUtil.getSupportedAxes(buffer, 0);
                    if (!supportedAxes.contains(WGHT_AXIS_TAG)) {
                        // Clean up the installed file before rejecting.
                        mUpdatableFontDir.removeCustomFontFileByPsName(psName);
                        throw new SystemFontException(
                                FontManager.RESULT_ERROR_INVALID_FONT_FILE,
                                "Only variable fonts with a weight (wght) axis are supported");
                    }
                } catch (IOException e) {
                    mUpdatableFontDir.removeCustomFontFileByPsName(psName);
                    throw new SystemFontException(
                            FontManager.RESULT_ERROR_INVALID_FONT_FILE,
                            "Failed to parse font axes", e);
                }

                final FontUpdateRequest.Font font = new FontUpdateRequest.Font(
                        psName,
                        new FontStyle(FontStyle.FONT_WEIGHT_NORMAL, FontStyle.FONT_SLANT_UPRIGHT),
                        0 /* index */,
                        "" /* fontVariationSettings */);
                final FontUpdateRequest.Family family = new FontUpdateRequest.Family(
                        psName, Collections.singletonList(font));
                Slog.d(TAG, "installCustomFontFamilyFromFile: registering family " + psName);
                mUpdatableFontDir.updateCustomFonts(
                        Collections.singletonList(new FontUpdateRequest(family)));
                updateSerializedFontMap();
                Slog.d(TAG, "installCustomFontFamilyFromFile: success, returning " + psName);
                return psName;
            }
        } catch (SystemFontException e) {
            Slog.e(TAG, "Failed to install custom font family from file", e);
            throw new ServiceSpecificException(e.getErrorCode(), e.getMessage());
        } finally {
            try {
                fd.close();
            } catch (IOException e) {
                Slog.w(TAG, "Failed to close fd", e);
            }
        }
    }

    private void applyCustomFontOverlayForUser(@Nullable String familyName, int userId) {
        final OverlayManagerInternal om =
                LocalServices.getService(OverlayManagerInternal.class);
        if (om == null) {
            Slog.w(TAG, "OverlayManagerInternal not available; cannot apply font overlay");
            return;
        }
        // One fabricated overlay per user — name embeds the user id so overlays do not collide.
        final String overlayName = CUSTOM_FONT_OVERLAY_NAME_PREFIX + userId;
        final OverlayIdentifier id = new OverlayIdentifier(
                CUSTOM_FONT_OVERLAY_PACKAGE, overlayName);

        // OverlayManagerInternal delegates back through the binder interface, which uses
        // Binder.getCallingUid() to enforce ownership of the overlay's target package. Callers
        // here typically carry an app UID (e.g. ThemePicker) that does not own "android", so the
        // commit would fail with SecurityException. Run as system for the duration of the call.
        final long token = Binder.clearCallingIdentity();
        try {
            if (familyName == null) {
                if (om.getOverlayInfo(id, UserHandle.of(userId)) == null) {
                    return;
                }
                final OverlayManagerTransaction.Builder disable =
                        new OverlayManagerTransaction.Builder()
                                .setEnabled(id, false, userId)
                                .unregisterFabricatedOverlay(id);
                om.commit(disable.build());
                return;
            }

            final FabricatedOverlay overlay = new FabricatedOverlay(
                    overlayName, CUSTOM_FONT_OVERLAY_PACKAGE);
            overlay.setOwningPackage(CUSTOM_FONT_OVERLAY_PACKAGE);
            for (String resName : CUSTOM_FONT_OVERLAY_RESOURCES) {
                overlay.setResourceValue(resName, TypedValue.TYPE_STRING, familyName,
                        null /* configuration */);
            }
            final OverlayManagerTransaction.Builder enable =
                    new OverlayManagerTransaction.Builder()
                            .registerFabricatedOverlay(overlay)
                            .setEnabled(id, true, userId);
            om.commit(enable.build());
        } finally {
            Binder.restoreCallingIdentity(token);
        }
    }

    private static void closeFileDescriptors(@Nullable List<FontUpdateRequest> requests) {
        // Make sure we close every passed FD, even if 'requests' is constructed incorrectly and
        // some fields are null.
        if (requests == null) return;
        for (FontUpdateRequest request : requests) {
            if (request == null) continue;
            ParcelFileDescriptor fd = request.getFd();
            if (fd == null) continue;
            try {
                fd.close();
            } catch (IOException e) {
                Slog.w(TAG, "Failed to close fd", e);
            }
        }
    }

    /* package */ static class SystemFontException extends AndroidException {
        private final int mErrorCode;

        SystemFontException(@FontManager.ResultCode int errorCode, String msg, Throwable cause) {
            super(msg, cause);
            mErrorCode = errorCode;
        }

        SystemFontException(int errorCode, String msg) {
            super(msg);
            mErrorCode = errorCode;
        }

        @FontManager.ResultCode
        int getErrorCode() {
            return mErrorCode;
        }
    }

    /** Class to manage FontManagerService's lifecycle. */
    public static final class Lifecycle extends SystemService {
        private final FontManagerService mService;
        private final CompletableFuture<Void> mServiceStarted = new CompletableFuture<>();

        public Lifecycle(@NonNull Context context, boolean safeMode) {
            super(context);
            mService = new FontManagerService(context, safeMode, mServiceStarted);
        }

        @Override
        public void onStart() {
            LocalServices.addService(FontManagerInternal.class,
                    new FontManagerInternal() {
                        @Override
                        @Nullable
                        public SharedMemory getSerializedSystemFontMap() {
                            if (!Typeface.ENABLE_LAZY_TYPEFACE_INITIALIZATION) {
                                return null;
                            }
                            mServiceStarted.join();
                            return mService.getCurrentFontMap();
                        }
                    });
            publishBinderService(Context.FONT_SERVICE, mService);
        }


        @Override
        public void onBootPhase(int phase) {
            final int latestFontLoadBootPhase =
                    (Flags.completeFontLoadInSystemServicesReady())
                            // Complete font load in the phase before PHASE_SYSTEM_SERVICES_READY
                            ? SystemService.PHASE_LOCK_SETTINGS_READY
                            : SystemService.PHASE_ACTIVITY_MANAGER_READY;
            if (phase == latestFontLoadBootPhase) {
                // Wait for FontManagerService to start since it will be needed after this point.
                mServiceStarted.join();
            }
        }
    }

    private static class FsverityUtilImpl implements UpdatableFontDir.FsverityUtil {

        private final String[] mDerCertPaths;

        FsverityUtilImpl(String[] derCertPaths) {
            mDerCertPaths = derCertPaths;
        }

        @Override
        public boolean isFromTrustedProvider(String fontPath, byte[] pkcs7Signature) {
            final byte[] digest = VerityUtils.getFsverityDigest(fontPath);
            if (digest == null) {
                Log.w(TAG, "Failed to get fs-verity digest for " + fontPath);
                return false;
            }
            for (String certPath : mDerCertPaths) {
                try (InputStream is = new FileInputStream(certPath)) {
                    if (VerityUtils.verifyPkcs7DetachedSignature(pkcs7Signature, digest, is)) {
                        return true;
                    }
                } catch (IOException e) {
                    Log.w(TAG, "Failed to read certificate file: " + certPath);
                }
            }
            return false;
        }

        @Override
        public void setUpFsverity(String filePath) throws IOException {
            VerityUtils.setUpFsverity(filePath);
        }

        @Override
        public boolean rename(File src, File dest) {
            // rename system call preserves fs-verity bit.
            return src.renameTo(dest);
        }
    }

    @NonNull
    private final Context mContext;

    private final boolean mIsSafeMode;

    private final Object mUpdatableFontDirLock = new Object();

    private String mDebugCertFilePath = null;

    @GuardedBy("mUpdatableFontDirLock")
    @Nullable
    private UpdatableFontDir mUpdatableFontDir;

    // mSerializedFontMapLock can be acquired while holding mUpdatableFontDirLock.
    // mUpdatableFontDirLock should not be newly acquired while holding mSerializedFontMapLock.
    private final Object mSerializedFontMapLock = new Object();

    @GuardedBy("mSerializedFontMapLock")
    @Nullable
    private SharedMemory mSerializedFontMap = null;

    private FontManagerService(
            Context context, boolean safeMode, CompletableFuture<Void> serviceStarted) {
        if (safeMode) {
            Slog.i(TAG, "Entering safe mode. Deleting all font updates.");
            UpdatableFontDir.deleteAllFiles(new File(FONT_FILES_DIR), new File(CONFIG_XML_FILE));
        }
        mContext = context;
        mIsSafeMode = safeMode;

        if (Flags.useOptimizedBoottimeFontLoading()) {
            Slog.i(TAG, "Using optimized boot-time font loading.");
            SystemServerInitThreadPool.submit(() -> {
                initialize();

                // Set system font map only if there is updatable font directory.
                // If there is no updatable font directory, `initialize` will have already loaded
                // the system font map, so there's no need to set the system font map again here.
                synchronized (mUpdatableFontDirLock) {
                    if  (mUpdatableFontDir != null) {
                        setSystemFontMap();
                    }
                }
                serviceStarted.complete(null);
            }, "FontManagerService_create");
        } else {
            Slog.i(TAG, "Not using optimized boot-time font loading.");
            initialize();
            setSystemFontMap();
            serviceStarted.complete(null);
        }
    }

    private void setSystemFontMap() {
        try {
            Typeface.setSystemFontMap(getCurrentFontMap());
        } catch (IOException | ErrnoException e) {
            Slog.w(TAG, "Failed to set system font map of system_server");
        }
    }

    @Nullable
    private UpdatableFontDir createUpdatableFontDir() {
        // Never read updatable font files in safe mode.
        if (mIsSafeMode) return null;
        // If apk verity is supported, fs-verity should be available.
        if (!VerityUtils.isFsVeritySupported()) return null;

        String[] certs = mContext.getResources().getStringArray(
                R.array.config_fontManagerServiceCerts);

        if (mDebugCertFilePath != null && Build.IS_DEBUGGABLE) {
            String[] tmp = new String[certs.length + 1];
            System.arraycopy(certs, 0, tmp, 0, certs.length);
            tmp[certs.length] = mDebugCertFilePath;
            certs = tmp;
        }

        return new UpdatableFontDir(new File(FONT_FILES_DIR), new OtfFontFileParser(),
                new FsverityUtilImpl(certs), new File(CONFIG_XML_FILE));
    }

    /**
     * Add debug certificate to the cert list. This must be called only on debuggable build.
     *
     * @param debugCertPath a debug certificate file path
     */
    public void addDebugCertificate(@Nullable String debugCertPath) {
        mDebugCertFilePath = debugCertPath;
    }

    private void initialize() {
        synchronized (mUpdatableFontDirLock) {
            mUpdatableFontDir = createUpdatableFontDir();
            if (mUpdatableFontDir == null) {
                if (Flags.useOptimizedBoottimeFontLoading()) {
                    // If fs-verity is not supported, load preinstalled system font map and use it
                    // for all apps.
                    Typeface.loadPreinstalledSystemFontMap();
                }
                setSerializedFontMap(serializeSystemServerFontMap());
                return;
            }
            mUpdatableFontDir.loadFontFileMap();
            loadPreinstalledFontFamilies();
            updateSerializedFontMap();
        }
        applyDefaultFontForAllUsers();
        registerUserRemovedReceiver();
        registerUserAddedReceiver();
    }

    /**
     * Discovers named font families shipped in the system image and populates
     * {@link #mPreinstalledFontFamilies} so they are surfaced through the custom
     * font API alongside user-installed fonts.
     */
    private void loadPreinstalledFontFamilies() {
        FontConfig config = SystemFonts.getSystemPreinstalledFontConfig();
        for (FontConfig.NamedFamilyList family : config.getNamedFamilyLists()) {
            mPreinstalledFontFamilies.add(family.getName());
        }
    }

    /**
     * For every existing user that has no active custom font, applies the
     * system default (read from {@code config_defaultCustomFontFamily}) through
     * the same fabricated-overlay path as user-initiated font changes.
     */
    private void applyDefaultFontForAllUsers() {
        String defaultFamily = mContext.getResources().getString(
                com.android.internal.R.string.config_defaultCustomFontFamily);
        if (defaultFamily == null || defaultFamily.isEmpty()) {
            return;
        }
        UserManager um = mContext.getSystemService(UserManager.class);
        if (um == null) {
            return;
        }
        for (UserHandle user : um.getUserHandles(true)) {
            int userId = user.getIdentifier();
            String active;
            synchronized (mUpdatableFontDirLock) {
                if (mUpdatableFontDir == null) {
                    continue;
                }
                active = mUpdatableFontDir.getActiveCustomFontFamily(userId);
            }
            if (active == null) {
                try {
                    applyCustomFontOverlayForUser(defaultFamily, userId);
                    synchronized (mUpdatableFontDirLock) {
                        if (mUpdatableFontDir != null) {
                            mUpdatableFontDir.setActiveCustomFontFamily(userId, defaultFamily);
                        }
                    }
                } catch (Exception e) {
                    Slog.w(TAG, "Failed to apply default font for user " + userId, e);
                }
            }
        }
    }

    private void registerUserRemovedReceiver() {
        final IntentFilter filter = new IntentFilter(Intent.ACTION_USER_REMOVED);
        mContext.registerReceiver(new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                if (!Intent.ACTION_USER_REMOVED.equals(intent.getAction())) {
                    return;
                }
                final int userId = intent.getIntExtra(Intent.EXTRA_USER_HANDLE, -1);
                if (userId < 0) {
                    return;
                }
                try {
                    synchronized (mUpdatableFontDirLock) {
                        if (mUpdatableFontDir != null
                                && mUpdatableFontDir.getActiveCustomFontFamily(userId) != null) {
                            mUpdatableFontDir.setActiveCustomFontFamily(userId, null);
                        }
                    }
                } catch (SystemFontException e) {
                    Slog.e(TAG, "Failed to purge font config for removed user " + userId, e);
                }
                try {
                    applyCustomFontOverlayForUser(null, userId);
                } catch (RuntimeException e) {
                    Slog.e(TAG, "Failed to remove font overlay for removed user " + userId, e);
                }
            }
        }, filter, null /* broadcastPermission */, null /* handler */);
    }

    private void registerUserAddedReceiver() {
        final IntentFilter filter = new IntentFilter(Intent.ACTION_USER_ADDED);
        mContext.registerReceiver(new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                if (!Intent.ACTION_USER_ADDED.equals(intent.getAction())) {
                    return;
                }
                final int userId = intent.getIntExtra(Intent.EXTRA_USER_HANDLE, -1);
                if (userId < 0) {
                    return;
                }
                String defaultFamily = mContext.getResources().getString(
                        com.android.internal.R.string.config_defaultCustomFontFamily);
                if (defaultFamily == null || defaultFamily.isEmpty()) {
                    return;
                }
                try {
                    applyCustomFontOverlayForUser(defaultFamily, userId);
                } catch (RuntimeException e) {
                    Slog.w(TAG, "Failed to apply default font overlay for new user " + userId, e);
                }
                try {
                    synchronized (mUpdatableFontDirLock) {
                        if (mUpdatableFontDir != null) {
                            mUpdatableFontDir.setActiveCustomFontFamily(userId, defaultFamily);
                        }
                    }
                } catch (SystemFontException e) {
                    Slog.e(TAG, "Failed to persist default font for new user " + userId, e);
                }
            }
        }, filter, null /* broadcastPermission */, null /* handler */);
    }

    @NonNull
    public Context getContext() {
        return mContext;
    }

    @Nullable /* package */ SharedMemory getCurrentFontMap() {
        synchronized (mSerializedFontMapLock) {
            return mSerializedFontMap;
        }
    }

    /* package */ void update(int baseVersion, List<FontUpdateRequest> requests)
            throws SystemFontException {
        synchronized (mUpdatableFontDirLock) {
            if (mUpdatableFontDir == null) {
                throw new SystemFontException(
                        FontManager.RESULT_ERROR_FONT_UPDATER_DISABLED,
                        "The font updater is disabled.");
            }
            // baseVersion == -1 only happens from shell command. This is filtered and treated as
            // error from SystemApi call.
            if (baseVersion != -1 && mUpdatableFontDir.getConfigVersion() != baseVersion) {
                throw new SystemFontException(
                        FontManager.RESULT_ERROR_VERSION_MISMATCH,
                        "The base config version is older than current.");
            }
            mUpdatableFontDir.update(requests);
            updateSerializedFontMap();
        }
    }

    /**
     * Clears all updates and restarts FontManagerService.
     *
     * <p>CAUTION: this method is not safe. Existing processes may crash due to missing font files.
     * This method is only for {@link FontManagerShellCommand}.
     */
    /* package */ void clearUpdates() {
        UpdatableFontDir.deleteAllFiles(new File(FONT_FILES_DIR), new File(CONFIG_XML_FILE));
        initialize();
    }

    /**
     * Restarts FontManagerService, removing not-the-latest font files.
     *
     * <p>CAUTION: this method is not safe. Existing processes may crash due to missing font files.
     * This method is only for {@link FontManagerShellCommand}.
     */
    /* package */ void restart() {
        initialize();
    }

    /* package */ Map<String, File> getFontFileMap() {
        synchronized (mUpdatableFontDirLock) {
            if (mUpdatableFontDir == null) {
                return Collections.emptyMap();
            }
            return mUpdatableFontDir.getPostScriptMap();
        }
    }

    @Override
    public void dump(@NonNull FileDescriptor fd, @NonNull PrintWriter writer,
            @Nullable String[] args) {
        if (!DumpUtils.checkDumpPermission(mContext, TAG, writer)) return;
        new FontManagerShellCommand(this).dumpAll(new IndentingPrintWriter(writer, "  "));
    }

    @Override
    public void onShellCommand(@Nullable FileDescriptor in,
            @Nullable FileDescriptor out,
            @Nullable FileDescriptor err,
            @NonNull String[] args,
            @Nullable ShellCallback callback,
            @NonNull ResultReceiver result) {
        new FontManagerShellCommand(this).exec(this, in, out, err, args, callback, result);
    }

    /**
     * Returns an active system font configuration.
     */
    public @NonNull FontConfig getSystemFontConfig() {
        synchronized (mUpdatableFontDirLock) {
            if (mUpdatableFontDir == null) {
                return SystemFonts.getSystemPreinstalledFontConfig();
            }
            return mUpdatableFontDir.getSystemFontConfig();
        }
    }

    /**
     * Makes new serialized font map data and updates mSerializedFontMap.
     */
    private void updateSerializedFontMap() {
        SharedMemory serializedFontMap = serializeFontMap(getSystemFontConfig());
        if (serializedFontMap == null) {
            // Fallback to the preloaded config.
            serializedFontMap = serializeSystemServerFontMap();
        }
        setSerializedFontMap(serializedFontMap);
    }

    @Nullable
    private static SharedMemory serializeFontMap(FontConfig fontConfig) {
        final ArrayMap<String, ByteBuffer> bufferCache = new ArrayMap<>();
        try {
            final Map<String, FontFamily[]> fallback =
                    SystemFonts.buildSystemFallback(fontConfig, bufferCache);
            final Map<String, Typeface> typefaceMap =
                    SystemFonts.buildSystemTypefaces(fontConfig, fallback);
            return Typeface.serializeFontMap(typefaceMap);
        } catch (IOException | ErrnoException e) {
            Slog.w(TAG, "Failed to serialize updatable font map. "
                    + "Retrying with system image fonts.", e);
            return null;
        } finally {
            // Unmap buffers promptly, as we map a lot of files and may hit mmap limit before
            // GC collects ByteBuffers and unmaps them.
            for (ByteBuffer buffer : bufferCache.values()) {
                if (buffer instanceof DirectByteBuffer) {
                    NioUtils.freeDirectBuffer(buffer);
                }
            }
        }
    }

    @Nullable
    private static SharedMemory serializeSystemServerFontMap() {
        try {
            return Typeface.serializeFontMap(Typeface.getSystemFontMap());
        } catch (IOException | ErrnoException e) {
            Slog.e(TAG, "Failed to serialize SystemServer system font map", e);
            return null;
        }
    }

    private void setSerializedFontMap(SharedMemory serializedFontMap) {
        SharedMemory oldFontMap = null;
        synchronized (mSerializedFontMapLock) {
            oldFontMap = mSerializedFontMap;
            mSerializedFontMap = serializedFontMap;
        }
        if (oldFontMap != null) {
            oldFontMap.close();
        }
    }
}
