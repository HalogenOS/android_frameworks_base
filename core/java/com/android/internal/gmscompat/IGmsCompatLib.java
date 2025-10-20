package com.android.internal.gmscompat;

import android.annotation.Nullable;
import android.content.Context;
import android.content.Context.BindServiceFlags;
import android.content.Intent;
import android.content.ServiceConnection;
import android.os.BinderProxy;
import android.os.IInterface;
import android.os.UserHandle;

import java.io.FileDescriptor;
import java.util.concurrent.Executor;

public interface IGmsCompatLib {
    void init(Context appContext, Context libContext, String processName);

    /** @see Context#bindService(Intent, BindServiceFlags, Executor, ServiceConnection) */
    @Nullable
    ServiceConnection maybeReplaceServiceConnection(Intent service, long flags, UserHandle user, ServiceConnection orig);

    /** @see BinderProxy#queryLocalInterface(String)  */
    @Nullable
    IInterface maybeProvideBinderProxyInterface(BinderProxy binderProxy, String ifaceDescriptor);

    /** @see BinderProxy#dump(FileDescriptor, String[])
     *  @see BinderProxy#dumpAsync(FileDescriptor, String[]) */
    boolean maybeInterceptBinderProxyDump(BinderProxy binderProxy, FileDescriptor fd, String[] args, boolean async);
}
