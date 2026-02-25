package com.android.internal.gmscompat.fileservice;

import android.app.compat.gms.GmsCompat;
import android.content.Context;
import android.os.Binder;
import android.os.ParcelFileDescriptor;
import android.system.ErrnoException;
import android.system.Os;
import android.system.OsConstants;
import android.util.Log;

import java.io.File;
import java.io.FileDescriptor;
import java.io.IOException;

public final class FileProxyService extends IFileProxyService.Stub {
    public static final String TAG = "GmsCompat/FileProxyService";

    private final String deDataPrefix; // device-encrypted data dir
    private final String ceDataPrefix; // credential-encrypted data dir

    public FileProxyService(Context context) {
        try {
            deDataPrefix = context.createDeviceProtectedStorageContext().getDataDir().getCanonicalPath() + "/";
            ceDataPrefix = context.getDataDir().getCanonicalPath() + "/";
        } catch (IOException e) {
            throw new IllegalArgumentException(e);
        }
        Log.d(TAG, "allowed locations: " + deDataPrefix + " and " + ceDataPrefix);
    }

    @Override
    public ParcelFileDescriptor openFile(String rawPath) {
        try {
            String path = sanitizeFilePath(rawPath);
            if (path != null) {
                FileDescriptor fd = Os.open(path, OsConstants.O_RDONLY | OsConstants.O_CLOEXEC, 0);
                Log.d(TAG, "Opened " + rawPath + " for caller UID " + Binder.getCallingUid());
                return new ParcelFileDescriptor(fd);
            }
        } catch (IOException | ErrnoException e) {
            Log.d(TAG, "openFile failed for " + rawPath, e);
        } catch (Throwable t) {
            GmsCompat.appContext().getMainThreadHandler().post(() -> {
                throw new IllegalStateException(t);
            });
        }
        // don't forward exceptions to the untrusted caller to minimize the information leaks
        return null;
    }

    private String sanitizeFilePath(String rawPath) throws IOException, ErrnoException {
        // Normalize path for security checks
        String path = new File(rawPath).getCanonicalPath();

        String allowedPrefix = null;
        if (path.startsWith(deDataPrefix)) {
            allowedPrefix = deDataPrefix;
        } else if (path.startsWith(ceDataPrefix)) {
            allowedPrefix = ceDataPrefix;
        }

        if (allowedPrefix == null) {
            Log.d(TAG, "Path " + rawPath + " is not in an allowed location, realpath is " + path);
            return null;
        }

        // Make sure that all path components below chimeraRoot are world-accessible
        {
            // Check full path first to simplify checks of its parents
            int mode = Os.stat(path).st_mode;

            boolean valid = OsConstants.S_ISREG(mode) && (mode & OsConstants.S_IROTH) != 0;
            if (!valid) {
                Log.d(TAG, "Path " + path + " is not a world-readable regular file");
                return null;
            }
        }
        for (int i = allowedPrefix.length(), m = path.length(); i < m; ++i) {
            if (path.charAt(i) != '/') {
                continue;
            }
            String dirPath = path.substring(0, i);
            int mode = Os.stat(dirPath).st_mode;

            boolean valid = OsConstants.S_ISDIR(mode) && (mode & OsConstants.S_IXOTH) != 0;
            if (!valid) {
                Log.d(TAG, "Node " + dirPath + " in path " + path + " is not a world-readable directory");
                return null;
            }
        }
        return path;
    }
}
