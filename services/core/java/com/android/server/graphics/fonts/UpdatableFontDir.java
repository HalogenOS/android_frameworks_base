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

package com.android.server.graphics.fonts;

import static com.android.server.graphics.fonts.FontManagerService.SystemFontException;

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.graphics.fonts.FontManager;
import android.graphics.fonts.FontUpdateRequest;
import android.graphics.fonts.SystemFonts;
import android.os.FileUtils;
import android.os.LocaleList;
import android.system.ErrnoException;
import android.system.Os;
import android.text.FontConfig;
import android.util.ArrayMap;
import android.util.ArraySet;
import android.util.AtomicFile;
import android.util.Base64;
import android.util.Slog;

import org.xmlpull.v1.XmlPullParserException;

import java.io.File;
import java.io.FileDescriptor;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Function;
import java.util.function.Supplier;

/**
 * Manages set of updatable font files.
 *
 * <p>This class is not thread safe.
 */
final class UpdatableFontDir {

    private static final String TAG = "UpdatableFontDir";
    private static final String RANDOM_DIR_PREFIX = "~~";

    private static final String FONT_SIGNATURE_FILE = "font.fsv_sig";
    private static final String CUSTOM_FONT_MARKER = "font.custom";

    /** Interface to mock font file access in tests. */
    interface FontFileParser {
        String getPostScriptName(File file) throws IOException;

        String buildFontFileName(File file) throws IOException;

        long getRevision(File file) throws IOException;

        void tryToCreateTypeface(File file) throws Throwable;
    }

    /** Interface to mock fs-verity in tests. */
    interface FsverityUtil {
        boolean isFromTrustedProvider(String path, byte[] pkcs7Signature);

        void setUpFsverity(String path) throws IOException;

        boolean rename(File src, File dest);
    }

    /** Data class to hold font file path and revision. */
    private static final class FontFileInfo {
        private final File mFile;
        private final String mPsName;
        private final long mRevision;

        FontFileInfo(File file, String psName, long revision) {
            mFile = file;
            mPsName = psName;
            mRevision = revision;
        }

        public File getFile() {
            return mFile;
        }

        public String getPostScriptName() {
            return mPsName;
        }

        /** Returns the unique randomized font dir containing this font file. */
        public File getRandomizedFontDir() {
            return mFile.getParentFile();
        }

        public long getRevision() {
            return mRevision;
        }

        @Override
        public String toString() {
            return "FontFileInfo{mFile=" + mFile
                    + ", psName=" + mPsName
                    + ", mRevision=" + mRevision + '}';
        }
    }

    /**
     * Root directory for storing updated font files. Each font file is stored in a unique
     * randomized dir. The font file path would be {@code mFilesDir/~~{randomStr}/{fontFileName}}.
     */
    private final File mFilesDir;
    private final FontFileParser mParser;
    private final FsverityUtil mFsverityUtil;
    private final AtomicFile mConfigFile;
    private final Supplier<Long> mCurrentTimeSupplier;
    private final Function<Map<String, File>, FontConfig> mConfigSupplier;

    private long mLastModifiedMillis;
    private int mConfigVersion;

    /**
     * A mutable map containing mapping from font file name (e.g. "NotoColorEmoji.ttf") to {@link
     * FontFileInfo}. All files in this map are validated, and have higher revision numbers than
     * corresponding font files returned by {@link #mConfigSupplier}.
     */
    private final ArrayMap<String, FontFileInfo> mFontFileInfoMap = new ArrayMap<>();

    UpdatableFontDir(File filesDir, FontFileParser parser, FsverityUtil fsverityUtil,
            File configFile) {
        this(filesDir, parser, fsverityUtil, configFile,
                System::currentTimeMillis,
                (map) -> SystemFonts.getSystemFontConfig(map, 0, 0)
        );
    }

    // For unit testing
    UpdatableFontDir(File filesDir, FontFileParser parser, FsverityUtil fsverityUtil,
            File configFile, Supplier<Long> currentTimeSupplier,
            Function<Map<String, File>, FontConfig> configSupplier) {
        mFilesDir = filesDir;
        mParser = parser;
        mFsverityUtil = fsverityUtil;
        mConfigFile = new AtomicFile(configFile);
        mCurrentTimeSupplier = currentTimeSupplier;
        mConfigSupplier = configSupplier;
    }

    /**
     * Loads fonts from file system, validate them, and delete obsolete font files.
     * Note that this method may be called by multiple times in integration tests via {@link
     * FontManagerService#restart()}.
     */
    /* package */ void loadFontFileMap() {
        mFontFileInfoMap.clear();
        mLastModifiedMillis = 0;
        mConfigVersion = 1;
        boolean success = false;
        try {
            PersistentSystemFontConfig.Config config = readPersistentConfig();
            mLastModifiedMillis = config.lastModifiedMillis;

            File[] dirs = mFilesDir.listFiles();
            if (dirs == null) {
                // mFilesDir should be created by init script.
                Slog.e(TAG, "Could not read: " + mFilesDir);
                return;
            }
            FontConfig fontConfig = null;
            for (File dir : dirs) {
                if (!dir.getName().startsWith(RANDOM_DIR_PREFIX)) {
                    Slog.e(TAG, "Unexpected dir found: " + dir);
                    return;
                }

                final boolean hasCustomMarker = new File(dir, CUSTOM_FONT_MARKER).exists();
                // Recover from stale configs that recorded a custom dir under updatedFontDirs:
                // the marker file is the authoritative source of truth for "this is a custom
                // (unsigned) font install".
                boolean isCustom =
                        config.customFontDirs.contains(dir.getName()) || hasCustomMarker;
                boolean isUpdated =
                        !isCustom && config.updatedFontDirs.contains(dir.getName());

                if (!isCustom && !isUpdated) {
                    Slog.i(TAG, "Deleting obsolete dir: " + dir);
                    FileUtils.deleteContentsAndDir(dir);
                    continue;
                }

                if (isCustom) {
                    // Custom font: no signature, just a marker file.
                    File markerFile = new File(dir, CUSTOM_FONT_MARKER);
                    if (!markerFile.exists()) {
                        Slog.e(TAG, "Custom font marker is missing: " + dir);
                        return;
                    }
                    File fontFile = findFontFileInDir(dir, markerFile);
                    if (fontFile == null) {
                        Slog.e(TAG, "Could not find font file in custom dir: " + dir);
                        return;
                    }
                    FontFileInfo fontFileInfo = validateCustomFontFile(fontFile);
                    putFontFileInfo(fontFileInfo);
                } else {
                    // Signed font update: requires signature + fs-verity.
                    File signatureFile = new File(dir, FONT_SIGNATURE_FILE);
                    if (!signatureFile.exists()) {
                        Slog.i(TAG, "The signature file is missing.");
                        return;
                    }
                    byte[] signature;
                    try {
                        signature =
                                Files.readAllBytes(Paths.get(signatureFile.getAbsolutePath()));
                    } catch (IOException e) {
                        Slog.e(TAG, "Failed to read signature file.");
                        return;
                    }

                    File fontFile = findFontFileInDir(dir, signatureFile);
                    if (fontFile == null) {
                        Slog.e(TAG, "Could not find font file in dir: " + dir);
                        return;
                    }

                    FontFileInfo fontFileInfo = validateFontFile(fontFile, signature);
                    if (fontConfig == null) {
                        fontConfig = mConfigSupplier.apply(Collections.emptyMap());
                    }
                    addFileToMapIfSameOrNewer(fontFileInfo, fontConfig,
                            true /* deleteOldFile */);
                }
            }

            // Treat as error if post script name of font family was not installed.
            List<FontUpdateRequest.Family> allFamilies = new ArrayList<>();
            allFamilies.addAll(config.fontFamilies);
            allFamilies.addAll(config.customFontFamilies);
            for (int i = 0; i < allFamilies.size(); ++i) {
                FontUpdateRequest.Family family = allFamilies.get(i);
                for (int j = 0; j < family.getFonts().size(); ++j) {
                    FontUpdateRequest.Font font = family.getFonts().get(j);
                    if (mFontFileInfoMap.containsKey(font.getPostScriptName())) {
                        continue;
                    }

                    if (fontConfig == null) {
                        fontConfig = mConfigSupplier.apply(Collections.emptyMap());
                    }

                    if (getFontByPostScriptName(font.getPostScriptName(), fontConfig) != null) {
                        continue;
                    }

                    Slog.e(TAG, "Unknown font that has PostScript name "
                            + font.getPostScriptName() + " is requested in FontFamily "
                            + family.getName());
                    return;
                }
            }

            success = true;
        } catch (Throwable t) {
            // If something happened during loading system fonts, clear all contents in finally
            // block. Here, just dumping errors.
            Slog.e(TAG, "Failed to load font mappings.", t);
        } finally {
            // Delete all files just in case if we find a problematic file.
            if (!success) {
                mFontFileInfoMap.clear();
                mLastModifiedMillis = 0;
                FileUtils.deleteContents(mFilesDir);
                mConfigFile.delete();
            }
        }
    }

    /**
     * Applies multiple {@link FontUpdateRequest}s in transaction.
     * If one of the request fails, the fonts and config are rolled back to the previous state
     * before this method is called.
     */
    public void update(List<FontUpdateRequest> requests) throws SystemFontException {
        for (FontUpdateRequest request : requests) {
            switch (request.getType()) {
                case FontUpdateRequest.TYPE_UPDATE_FONT_FILE:
                    Objects.requireNonNull(request.getFd());
                    Objects.requireNonNull(request.getSignature());
                    break;
                case FontUpdateRequest.TYPE_UPDATE_FONT_FAMILY:
                    Objects.requireNonNull(request.getFontFamily());
                    Objects.requireNonNull(request.getFontFamily().getName());
                    break;
            }
        }
        // Backup the mapping for rollback.
        ArrayMap<String, FontFileInfo> backupMap = new ArrayMap<>(mFontFileInfoMap);
        PersistentSystemFontConfig.Config curConfig = readPersistentConfig();
        Map<String, FontUpdateRequest.Family> familyMap = new HashMap<>();
        for (int i = 0; i < curConfig.fontFamilies.size(); ++i) {
            FontUpdateRequest.Family family = curConfig.fontFamilies.get(i);
            familyMap.put(family.getName(), family);
        }

        long backupLastModifiedDate = mLastModifiedMillis;
        boolean success = false;
        try {
            for (FontUpdateRequest request : requests) {
                switch (request.getType()) {
                    case FontUpdateRequest.TYPE_UPDATE_FONT_FILE:
                        installFontFile(
                                request.getFd().getFileDescriptor(), request.getSignature());
                        break;
                    case FontUpdateRequest.TYPE_UPDATE_FONT_FAMILY:
                        FontUpdateRequest.Family family = request.getFontFamily();
                        familyMap.put(family.getName(), family);
                        break;
                }
            }

            // Before processing font family update, check all family points the available fonts.
            for (FontUpdateRequest.Family family : familyMap.values()) {
                if (resolveFontFilesForNamedFamily(family) == null) {
                    throw new SystemFontException(
                            FontManager.RESULT_ERROR_FONT_NOT_FOUND,
                            "Required fonts are not available");
                }
            }

            // Write config file, preserving custom font state.
            mLastModifiedMillis = mCurrentTimeSupplier.get();

            PersistentSystemFontConfig.Config newConfig = new PersistentSystemFontConfig.Config();
            newConfig.lastModifiedMillis = mLastModifiedMillis;
            for (FontFileInfo info : mFontFileInfoMap.values()) {
                String dirName = info.getRandomizedFontDir().getName();
                if (curConfig.customFontDirs.contains(dirName)) {
                    newConfig.customFontDirs.add(dirName);
                } else {
                    newConfig.updatedFontDirs.add(dirName);
                }
            }
            newConfig.fontFamilies.addAll(familyMap.values());
            newConfig.customFontFamilies.addAll(curConfig.customFontFamilies);
            newConfig.activeCustomFontFamilyByUser.putAll(curConfig.activeCustomFontFamilyByUser);
            writePersistentConfig(newConfig);
            mConfigVersion++;
            success = true;
        } finally {
            if (!success) {
                mFontFileInfoMap.clear();
                mFontFileInfoMap.putAll(backupMap);
                mLastModifiedMillis = backupLastModifiedDate;
            }
        }
    }

    /**
     * Installs custom font files and registers font families without fs-verity verification.
     */
    /* package */ void updateCustomFonts(List<FontUpdateRequest> requests)
            throws SystemFontException {
        ArrayMap<String, FontFileInfo> backupMap = new ArrayMap<>(mFontFileInfoMap);
        PersistentSystemFontConfig.Config curConfig = readPersistentConfig();
        Map<String, FontUpdateRequest.Family> customFamilyMap = new HashMap<>();
        for (int i = 0; i < curConfig.customFontFamilies.size(); ++i) {
            FontUpdateRequest.Family family = curConfig.customFontFamilies.get(i);
            customFamilyMap.put(family.getName(), family);
        }

        long backupLastModifiedDate = mLastModifiedMillis;
        boolean success = false;
        try {
            for (FontUpdateRequest request : requests) {
                switch (request.getType()) {
                    case FontUpdateRequest.TYPE_UPDATE_FONT_FILE:
                        Objects.requireNonNull(request.getFd());
                        installCustomFontFile(request.getFd().getFileDescriptor());
                        break;
                    case FontUpdateRequest.TYPE_UPDATE_FONT_FAMILY:
                        FontUpdateRequest.Family family = request.getFontFamily();
                        Objects.requireNonNull(family);
                        Objects.requireNonNull(family.getName());
                        customFamilyMap.put(family.getName(), family);
                        break;
                }
            }

            for (FontUpdateRequest.Family family : customFamilyMap.values()) {
                if (resolveFontFilesForNamedFamily(family) == null) {
                    throw new SystemFontException(
                            FontManager.RESULT_ERROR_FONT_NOT_FOUND,
                            "Required fonts are not available for family: " + family.getName());
                }
            }

            mLastModifiedMillis = mCurrentTimeSupplier.get();
            writePersistentConfigPreservingAll(curConfig, customFamilyMap);
            mConfigVersion++;
            success = true;
        } finally {
            if (!success) {
                mFontFileInfoMap.clear();
                mFontFileInfoMap.putAll(backupMap);
                mLastModifiedMillis = backupLastModifiedDate;
            }
        }
    }

    /**
     * Removes a custom font family and its associated font files.
     */
    /* package */ void removeCustomFontFamily(String familyName) throws SystemFontException {
        PersistentSystemFontConfig.Config curConfig = readPersistentConfig();
        Map<String, FontUpdateRequest.Family> customFamilyMap = new HashMap<>();
        for (int i = 0; i < curConfig.customFontFamilies.size(); ++i) {
            FontUpdateRequest.Family family = curConfig.customFontFamilies.get(i);
            customFamilyMap.put(family.getName(), family);
        }

        FontUpdateRequest.Family removed = customFamilyMap.remove(familyName);
        if (removed == null) {
            throw new SystemFontException(
                    FontManager.RESULT_ERROR_FONT_NOT_FOUND,
                    "Custom font family not found: " + familyName);
        }

        // Collect PostScript names used by remaining custom families.
        Set<String> usedPsNames = new ArraySet<>();
        for (FontUpdateRequest.Family family : customFamilyMap.values()) {
            for (FontUpdateRequest.Font font : family.getFonts()) {
                usedPsNames.add(font.getPostScriptName());
            }
        }

        // Remove font files that are no longer referenced by any custom family.
        Set<String> customDirsToRemove = new ArraySet<>();
        for (FontUpdateRequest.Font font : removed.getFonts()) {
            String psName = font.getPostScriptName();
            if (!usedPsNames.contains(psName)) {
                FontFileInfo info = mFontFileInfoMap.get(psName);
                if (info != null && curConfig.customFontDirs.contains(
                        info.getRandomizedFontDir().getName())) {
                    customDirsToRemove.add(info.getRandomizedFontDir().getName());
                    FileUtils.deleteContentsAndDir(info.getRandomizedFontDir());
                    mFontFileInfoMap.remove(psName);
                }
            }
        }

        mLastModifiedMillis = mCurrentTimeSupplier.get();
        writePersistentConfigPreservingAll(curConfig, customFamilyMap);
        mConfigVersion++;
    }

    /**
     * Returns the names of all installed custom font families.
     */
    /* package */ List<String> getCustomFontFamilyNames() {
        PersistentSystemFontConfig.Config config = readPersistentConfig();
        List<String> names = new ArrayList<>();
        for (int i = 0; i < config.customFontFamilies.size(); ++i) {
            names.add(config.customFontFamilies.get(i).getName());
        }
        return names;
    }

    private void writePersistentConfigPreservingAll(
            PersistentSystemFontConfig.Config curConfig,
            Map<String, FontUpdateRequest.Family> customFamilyMap)
            throws SystemFontException {
        PersistentSystemFontConfig.Config newConfig = new PersistentSystemFontConfig.Config();
        newConfig.lastModifiedMillis = mLastModifiedMillis;
        for (FontFileInfo info : mFontFileInfoMap.values()) {
            final File dir = info.getRandomizedFontDir();
            final String dirName = dir.getName();
            // Classify via the on-disk marker written by installCustomFontFile(); the previous
            // config may not yet list a freshly installed custom dir, so checking curConfig alone
            // would misclassify new custom dirs as signed updates.
            final boolean isCustom =
                    curConfig.customFontDirs.contains(dirName)
                            || new File(dir, CUSTOM_FONT_MARKER).exists();
            if (isCustom) {
                newConfig.customFontDirs.add(dirName);
            } else {
                newConfig.updatedFontDirs.add(dirName);
            }
        }
        newConfig.fontFamilies.addAll(curConfig.fontFamilies);
        newConfig.customFontFamilies.addAll(customFamilyMap.values());
        newConfig.activeCustomFontFamilyByUser.putAll(curConfig.activeCustomFontFamilyByUser);
        writePersistentConfig(newConfig);
    }

    /**
     * Installs a new font file, or updates an existing font file.
     *
     * <p>The new font will be immediately available for new Zygote-forked processes through
     * {@link #getPostScriptMap()}. Old font files will be kept until next system server reboot,
     * because existing Zygote-forked processes have paths to old font files.
     *
     * @param fd             A file descriptor to the font file.
     * @param pkcs7Signature A PKCS#7 detached signature to enable fs-verity for the font file.
     * @throws SystemFontException if error occurs.
     */
    private void installFontFile(FileDescriptor fd, byte[] pkcs7Signature)
            throws SystemFontException {
        File newDir = getRandomDir(mFilesDir);
        if (!newDir.mkdir()) {
            throw new SystemFontException(
                    FontManager.RESULT_ERROR_FAILED_TO_WRITE_FONT_FILE,
                    "Failed to create font directory.");
        }
        try {
            // Make newDir executable so that apps can access font file inside newDir.
            Os.chmod(newDir.getAbsolutePath(), 0711);
        } catch (ErrnoException e) {
            throw new SystemFontException(
                    FontManager.RESULT_ERROR_FAILED_TO_WRITE_FONT_FILE,
                    "Failed to change mode to 711", e);
        }
        boolean success = false;
        try {
            File tempNewFontFile = new File(newDir, "font.ttf");
            try (FileOutputStream out = new FileOutputStream(tempNewFontFile)) {
                FileUtils.copy(fd, out.getFD());
            } catch (IOException e) {
                throw new SystemFontException(
                        FontManager.RESULT_ERROR_FAILED_TO_WRITE_FONT_FILE,
                        "Failed to write font file to storage.", e);
            }
            try {
                // Do not parse font file before setting up fs-verity.
                // setUpFsverity throws IOException if failed.
                mFsverityUtil.setUpFsverity(tempNewFontFile.getAbsolutePath());
            } catch (IOException e) {
                throw new SystemFontException(
                        FontManager.RESULT_ERROR_VERIFICATION_FAILURE,
                        "Failed to setup fs-verity.", e);
            }
            String fontFileName;
            try {
                fontFileName = mParser.buildFontFileName(tempNewFontFile);
            } catch (IOException e) {
                throw new SystemFontException(
                        FontManager.RESULT_ERROR_INVALID_FONT_FILE,
                        "Failed to read PostScript name from font file", e);
            }
            if (fontFileName == null) {
                throw new SystemFontException(
                        FontManager.RESULT_ERROR_INVALID_FONT_NAME,
                        "Failed to read PostScript name from font file");
            }
            File newFontFile = new File(newDir, fontFileName);
            if (!mFsverityUtil.rename(tempNewFontFile, newFontFile)) {
                throw new SystemFontException(
                        FontManager.RESULT_ERROR_FAILED_TO_WRITE_FONT_FILE,
                        "Failed to move verified font file.");
            }
            try {
                // Make the font file readable by apps.
                Os.chmod(newFontFile.getAbsolutePath(), 0644);
            } catch (ErrnoException e) {
                throw new SystemFontException(
                        FontManager.RESULT_ERROR_FAILED_TO_WRITE_FONT_FILE,
                        "Failed to change font file mode to 644", e);
            }
            File signatureFile = new File(newDir, FONT_SIGNATURE_FILE);
            try (FileOutputStream out = new FileOutputStream(signatureFile)) {
                out.write(pkcs7Signature);
            } catch (IOException e) {
                // TODO: Do we need new error code for signature write failure?
                throw new SystemFontException(
                        FontManager.RESULT_ERROR_FAILED_TO_WRITE_FONT_FILE,
                        "Failed to write font signature file to storage.", e);
            }
            try {
                Os.chmod(signatureFile.getAbsolutePath(), 0600);
            } catch (ErrnoException e) {
                throw new SystemFontException(
                        FontManager.RESULT_ERROR_FAILED_TO_WRITE_FONT_FILE,
                        "Failed to change the signature file mode to 600", e);
            }
            FontFileInfo fontFileInfo = validateFontFile(newFontFile, pkcs7Signature);

            // Try to create Typeface and treat as failure something goes wrong.
            try {
                mParser.tryToCreateTypeface(fontFileInfo.getFile());
            } catch (Throwable t) {
                throw new SystemFontException(
                        FontManager.RESULT_ERROR_INVALID_FONT_FILE,
                        "Failed to create Typeface from file", t);
            }

            FontConfig fontConfig = getSystemFontConfig();
            if (!addFileToMapIfSameOrNewer(fontFileInfo, fontConfig, false)) {
                throw new SystemFontException(
                        FontManager.RESULT_ERROR_DOWNGRADING,
                        "Downgrading font file is forbidden.");
            }
            success = true;
        } finally {
            if (!success) {
                FileUtils.deleteContentsAndDir(newDir);
            }
        }
    }

    /**
     * Installs a custom font file without fs-verity signature verification.
     * The font file is still validated as a valid OpenType font.
     *
     * @param fd A file descriptor to the font file.
     * @return The PostScript name of the installed font.
     * @throws SystemFontException if error occurs.
     */
    /* package */ String installCustomFontFile(FileDescriptor fd) throws SystemFontException {
        File newDir = getRandomDir(mFilesDir);
        if (!newDir.mkdir()) {
            throw new SystemFontException(
                    FontManager.RESULT_ERROR_FAILED_TO_WRITE_FONT_FILE,
                    "Failed to create font directory.");
        }
        try {
            Os.chmod(newDir.getAbsolutePath(), 0711);
        } catch (ErrnoException e) {
            throw new SystemFontException(
                    FontManager.RESULT_ERROR_FAILED_TO_WRITE_FONT_FILE,
                    "Failed to change mode to 711", e);
        }
        boolean success = false;
        try {
            File tempNewFontFile = new File(newDir, "font.ttf");
            try (FileOutputStream out = new FileOutputStream(tempNewFontFile)) {
                FileUtils.copy(fd, out.getFD());
            } catch (IOException e) {
                throw new SystemFontException(
                        FontManager.RESULT_ERROR_FAILED_TO_WRITE_FONT_FILE,
                        "Failed to write font file to storage.", e);
            }

            String fontFileName;
            try {
                fontFileName = mParser.buildFontFileName(tempNewFontFile);
            } catch (IOException e) {
                throw new SystemFontException(
                        FontManager.RESULT_ERROR_INVALID_FONT_FILE,
                        "Failed to read PostScript name from font file", e);
            }
            if (fontFileName == null) {
                throw new SystemFontException(
                        FontManager.RESULT_ERROR_INVALID_FONT_NAME,
                        "Failed to read PostScript name from font file");
            }

            File newFontFile = new File(newDir, fontFileName);
            if (!tempNewFontFile.renameTo(newFontFile)) {
                throw new SystemFontException(
                        FontManager.RESULT_ERROR_FAILED_TO_WRITE_FONT_FILE,
                        "Failed to rename font file.");
            }
            try {
                Os.chmod(newFontFile.getAbsolutePath(), 0644);
            } catch (ErrnoException e) {
                throw new SystemFontException(
                        FontManager.RESULT_ERROR_FAILED_TO_WRITE_FONT_FILE,
                        "Failed to change font file mode to 644", e);
            }

            // Write marker file to identify this as a custom font dir.
            File markerFile = new File(newDir, CUSTOM_FONT_MARKER);
            try (FileOutputStream out = new FileOutputStream(markerFile)) {
                out.write(new byte[0]);
            } catch (IOException e) {
                throw new SystemFontException(
                        FontManager.RESULT_ERROR_FAILED_TO_WRITE_FONT_FILE,
                        "Failed to write custom font marker.", e);
            }

            // Validate that the font file is a valid OpenType font.
            String psName;
            try {
                psName = mParser.getPostScriptName(newFontFile);
            } catch (IOException e) {
                throw new SystemFontException(
                        FontManager.RESULT_ERROR_INVALID_FONT_NAME,
                        "Could not read PostScript name: " + newFontFile);
            }
            long revision = getFontRevision(newFontFile);
            if (revision == -1) {
                throw new SystemFontException(
                        FontManager.RESULT_ERROR_INVALID_FONT_FILE,
                        "Could not read font revision: " + newFontFile);
            }
            FontFileInfo fontFileInfo = new FontFileInfo(newFontFile, psName, revision);

            try {
                mParser.tryToCreateTypeface(fontFileInfo.getFile());
            } catch (Throwable t) {
                throw new SystemFontException(
                        FontManager.RESULT_ERROR_INVALID_FONT_FILE,
                        "Failed to create Typeface from file", t);
            }

            putFontFileInfo(fontFileInfo);
            success = true;
            return psName;
        } finally {
            if (!success) {
                FileUtils.deleteContentsAndDir(newDir);
            }
        }
    }

    /**
     * Given {@code parent}, returns {@code parent/~~[randomStr]}.
     * Makes sure that {@code parent/~~[randomStr]} directory doesn't exist.
     * Notice that this method doesn't actually create any directory.
     */
    private static File getRandomDir(File parent) {
        SecureRandom random = new SecureRandom();
        byte[] bytes = new byte[16];
        File dir;
        do {
            random.nextBytes(bytes);
            String dirName = RANDOM_DIR_PREFIX
                    + Base64.encodeToString(bytes, Base64.URL_SAFE | Base64.NO_WRAP);
            dir = new File(parent, dirName);
        } while (dir.exists());
        return dir;
    }

    private FontFileInfo lookupFontFileInfo(String psName) {
        return mFontFileInfoMap.get(psName);
    }

    private void putFontFileInfo(FontFileInfo info) {
        mFontFileInfoMap.put(info.getPostScriptName(), info);
    }

    /**
     * Add the given {@link FontFileInfo} to {@link #mFontFileInfoMap} if its font revision is
     * equal to or higher than the revision of currently used font file (either in
     * {@link #mFontFileInfoMap} or {@code fontConfig}).
     */
    private boolean addFileToMapIfSameOrNewer(FontFileInfo fontFileInfo, FontConfig fontConfig,
            boolean deleteOldFile) {
        FontFileInfo existingInfo = lookupFontFileInfo(fontFileInfo.getPostScriptName());
        final boolean shouldAddToMap;
        if (existingInfo == null) {
            // We got a new updatable font. We need to check if it's newer than preinstalled fonts.
            // Note that getPreinstalledFontRevision() returns -1 if there is no preinstalled font
            // with 'name'.
            long preInstalledRev = getPreinstalledFontRevision(fontFileInfo, fontConfig);
            shouldAddToMap = preInstalledRev <= fontFileInfo.getRevision();
        } else {
            shouldAddToMap = existingInfo.getRevision() <= fontFileInfo.getRevision();
        }
        if (shouldAddToMap) {
            if (deleteOldFile && existingInfo != null) {
                FileUtils.deleteContentsAndDir(existingInfo.getRandomizedFontDir());
            }
            putFontFileInfo(fontFileInfo);
        } else {
            if (deleteOldFile) {
                FileUtils.deleteContentsAndDir(fontFileInfo.getRandomizedFontDir());
            }
        }
        return shouldAddToMap;
    }

    private FontConfig.Font getFontByPostScriptName(String psName, FontConfig fontConfig) {
        FontConfig.Font targetFont = null;
        for (int i = 0; i < fontConfig.getFontFamilies().size(); i++) {
            FontConfig.FontFamily family = fontConfig.getFontFamilies().get(i);
            for (int j = 0; j < family.getFontList().size(); ++j) {
                FontConfig.Font font = family.getFontList().get(j);
                if (font.getPostScriptName().equals(psName)) {
                    targetFont = font;
                    break;
                }
            }
        }
        for (int i = 0; i < fontConfig.getNamedFamilyLists().size(); ++i) {
            FontConfig.NamedFamilyList namedFamilyList = fontConfig.getNamedFamilyLists().get(i);
            for (int j = 0; j < namedFamilyList.getFamilies().size(); ++j) {
                FontConfig.FontFamily family = namedFamilyList.getFamilies().get(j);
                for (int k = 0; k < family.getFontList().size(); ++k) {
                    FontConfig.Font font = family.getFontList().get(k);
                    if (font.getPostScriptName().equals(psName)) {
                        targetFont = font;
                        break;
                    }
                }
            }
        }
        return targetFont;
    }

    private long getPreinstalledFontRevision(FontFileInfo info, FontConfig fontConfig) {
        String psName = info.getPostScriptName();
        FontConfig.Font targetFont = getFontByPostScriptName(psName, fontConfig);

        if (targetFont == null) {
            return -1;
        }

        File preinstalledFontFile = targetFont.getOriginalFile() != null
                ? targetFont.getOriginalFile() : targetFont.getFile();
        if (!preinstalledFontFile.exists()) {
            return -1;
        }
        long revision = getFontRevision(preinstalledFontFile);
        if (revision == -1) {
            Slog.w(TAG, "Invalid preinstalled font file");
        }
        return revision;
    }

    /**
     * Finds the font file in a directory, given the non-font file to exclude.
     */
    @Nullable
    private static File findFontFileInDir(File dir, File excludeFile) {
        File[] files = dir.listFiles();
        if (files == null || files.length != 2) return null;
        return files[0].equals(excludeFile) ? files[1] : files[0];
    }

    /**
     * Validates a custom font file without fs-verity. Checks PostScript name and revision only.
     */
    @NonNull
    private FontFileInfo validateCustomFontFile(File file) throws SystemFontException {
        final String psName;
        try {
            psName = mParser.getPostScriptName(file);
        } catch (IOException e) {
            throw new SystemFontException(
                    FontManager.RESULT_ERROR_INVALID_FONT_NAME,
                    "Could not read PostScript name: " + file);
        }
        long revision = getFontRevision(file);
        if (revision == -1) {
            throw new SystemFontException(
                    FontManager.RESULT_ERROR_INVALID_FONT_FILE,
                    "Could not read font revision: " + file);
        }
        return new FontFileInfo(file, psName, revision);
    }

    /**
     * Checks the fs-verity protection status of the given font file, validates the file name, and
     * returns a {@link FontFileInfo} on success. This method does not check if the font revision
     * is higher than the currently used font.
     */
    @NonNull
    private FontFileInfo validateFontFile(File file, byte[] pkcs7Signature)
            throws SystemFontException {
        if (!mFsverityUtil.isFromTrustedProvider(file.getAbsolutePath(), pkcs7Signature)) {
            throw new SystemFontException(
                    FontManager.RESULT_ERROR_VERIFICATION_FAILURE,
                    "Font validation failed. Fs-verity is not enabled: " + file);
        }
        final String psName;
        try {
            psName = mParser.getPostScriptName(file);
        } catch (IOException e) {
            throw new SystemFontException(
                    FontManager.RESULT_ERROR_INVALID_FONT_NAME,
                    "Font validation failed. Could not read PostScript name name: " + file);
        }
        long revision = getFontRevision(file);
        if (revision == -1) {
            throw new SystemFontException(
                    FontManager.RESULT_ERROR_INVALID_FONT_FILE,
                    "Font validation failed. Could not read font revision: " + file);
        }
        return new FontFileInfo(file, psName, revision);
    }
    /** Returns the non-negative font revision of the given font file, or -1. */
    private long getFontRevision(File file) {
        try {
            return mParser.getRevision(file);
        } catch (IOException e) {
            Slog.e(TAG, "Failed to read font file", e);
            return -1;
        }
    }

    private FontConfig.NamedFamilyList resolveFontFilesForNamedFamily(
            FontUpdateRequest.Family fontFamily) {
        List<FontUpdateRequest.Font> fontList = fontFamily.getFonts();
        List<FontConfig.Font> resolvedFonts = new ArrayList<>(fontList.size());
        for (int i = 0; i < fontList.size(); i++) {
            FontUpdateRequest.Font font = fontList.get(i);
            FontFileInfo info = mFontFileInfoMap.get(font.getPostScriptName());
            if (info == null) {
                Slog.e(TAG, "Failed to lookup font file that has " + font.getPostScriptName());
                return null;
            }
            resolvedFonts.add(new FontConfig.Font(info.mFile, null, info.getPostScriptName(),
                    font.getFontStyle(), font.getIndex(), font.getFontVariationSettings(),
                    null /* family name */, FontConfig.Font.VAR_TYPE_AXES_NONE));
        }
        FontConfig.FontFamily family = new FontConfig.FontFamily(resolvedFonts,
                LocaleList.getEmptyLocaleList(), FontConfig.FontFamily.VARIANT_DEFAULT);
        return new FontConfig.NamedFamilyList(Collections.singletonList(family),
                fontFamily.getName(), null /* TODO: USF should support fallback */);
    }

    Map<String, File> getPostScriptMap() {
        Map<String, File> map = new ArrayMap<>();
        for (int i = 0; i < mFontFileInfoMap.size(); ++i) {
            FontFileInfo info = mFontFileInfoMap.valueAt(i);
            map.put(info.getPostScriptName(), info.getFile());
        }
        return map;
    }

    /* package */ FontConfig getSystemFontConfig() {
        FontConfig config = mConfigSupplier.apply(getPostScriptMap());
        PersistentSystemFontConfig.Config persistentConfig = readPersistentConfig();
        List<FontUpdateRequest.Family> families = persistentConfig.fontFamilies;
        List<FontUpdateRequest.Family> customFamilies = persistentConfig.customFontFamilies;

        List<FontConfig.NamedFamilyList> mergedFamilies =
                new ArrayList<>(config.getNamedFamilyLists().size()
                        + families.size() + customFamilies.size());
        // We should keep the first font family (config.getFontFamilies().get(0)) because it's used
        // as a fallback font. See SystemFonts.java.
        mergedFamilies.addAll(config.getNamedFamilyLists());
        // When building Typeface, a latter font family definition will override the previous font
        // family definition with the same name. An exception is config.getFontFamilies.get(0),
        // which will be used as a fallback font without being overridden.
        for (int i = 0; i < families.size(); ++i) {
            FontConfig.NamedFamilyList family = resolveFontFilesForNamedFamily(families.get(i));
            if (family != null) {
                mergedFamilies.add(family);
            }
        }
        for (int i = 0; i < customFamilies.size(); ++i) {
            FontConfig.NamedFamilyList named =
                    resolveFontFilesForNamedFamily(customFamilies.get(i));
            if (named != null) {
                mergedFamilies.add(named);
            }
        }

        return new FontConfig(
                config.getFontFamilies(), config.getAliases(), mergedFamilies,
                config.getLocaleFallbackCustomizations(), mLastModifiedMillis, mConfigVersion);
    }

    private PersistentSystemFontConfig.Config readPersistentConfig() {
        PersistentSystemFontConfig.Config config = new PersistentSystemFontConfig.Config();
        try (FileInputStream fis = mConfigFile.openRead()) {
            PersistentSystemFontConfig.loadFromXml(fis, config);
        } catch (IOException | XmlPullParserException e) {
            // The font config file is missing on the first boot. Just do nothing.
        }
        return config;
    }

    private void writePersistentConfig(PersistentSystemFontConfig.Config config)
            throws SystemFontException {
        FileOutputStream fos = null;
        try {
            fos = mConfigFile.startWrite();
            PersistentSystemFontConfig.writeToXml(fos, config);
            mConfigFile.finishWrite(fos);
        } catch (IOException e) {
            if (fos != null) {
                mConfigFile.failWrite(fos);
            }
            throw new SystemFontException(
                    FontManager.RESULT_ERROR_FAILED_UPDATE_CONFIG,
                    "Failed to write config XML.", e);
        }
    }

    /**
     * Removes an installed font file by PostScript name without touching persistent family config.
     * Used to clean up after a rejected install (e.g. non-variable font).
     */
    /* package */ void removeCustomFontFileByPsName(@NonNull String psName) {
        FontFileInfo info = mFontFileInfoMap.remove(psName);
        if (info != null) {
            FileUtils.deleteContentsAndDir(info.getRandomizedFontDir());
        }
    }

    /* package */ int getConfigVersion() {
        return mConfigVersion;
    }

    /* package */ @Nullable String getActiveCustomFontFamily(int userId) {
        return readPersistentConfig().activeCustomFontFamilyByUser.get(userId);
    }

    /* package */ Map<Integer, String> getActiveCustomFontFamiliesByUser() {
        return new HashMap<>(readPersistentConfig().activeCustomFontFamilyByUser);
    }

    /* package */ void setActiveCustomFontFamily(int userId, @Nullable String familyName)
            throws SystemFontException {
        PersistentSystemFontConfig.Config config = readPersistentConfig();
        if (familyName == null) {
            config.activeCustomFontFamilyByUser.remove(userId);
        } else {
            config.activeCustomFontFamilyByUser.put(userId, familyName);
        }
        writePersistentConfig(config);
        mConfigVersion++;
    }

    public Map<String, FontConfig.NamedFamilyList> getFontFamilyMap() {
        PersistentSystemFontConfig.Config curConfig = readPersistentConfig();
        Map<String, FontConfig.NamedFamilyList> familyMap = new HashMap<>();
        for (int i = 0; i < curConfig.fontFamilies.size(); ++i) {
            FontUpdateRequest.Family family = curConfig.fontFamilies.get(i);
            FontConfig.NamedFamilyList resolvedFamily = resolveFontFilesForNamedFamily(family);
            if (resolvedFamily != null) {
                familyMap.put(family.getName(), resolvedFamily);
            }
        }
        return familyMap;
    }

    /* package */ static void deleteAllFiles(File filesDir, File configFile) {
        // As this method is called in safe mode, try to delete all files even though an exception
        // is thrown.
        try {
            new AtomicFile(configFile).delete();
        } catch (Throwable t) {
            Slog.w(TAG, "Failed to delete " + configFile);
        }
        try {
            FileUtils.deleteContents(filesDir);
        } catch (Throwable t) {
            Slog.w(TAG, "Failed to delete " + filesDir);
        }
    }
}
