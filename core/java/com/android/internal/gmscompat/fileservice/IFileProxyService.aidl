package com.android.internal.gmscompat.fileservice;

interface IFileProxyService {
    ParcelFileDescriptor openFile(String path);
}
