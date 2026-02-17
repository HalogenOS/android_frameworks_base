package com.android.internal.gmscompat;

import android.annotation.Nullable;
import android.content.Context;
import android.content.Context.BindServiceFlags;
import android.content.Intent;
import android.content.ServiceConnection;
import android.os.BinderProxy;
import android.os.IInterface;
import android.os.Parcel;
import android.os.UserHandle;

import java.io.FileDescriptor;
import java.text.ParseException;
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

    /**
     * Parses a {@code com.google.android.gms.constellation.VerifyPhoneNumberRequest} and returns
     * its policy id.
     * <p>
     * This is not a replacement for {@link Parcel#readTypedObject}, so a
     * {@link Parcel#readInt} == 1 check should be done beforehand if {@code data} is from a
     * transaction. This is more of a replacement for direct {@code
     * VerifyPhoneNumberRequest.CREATOR} usage.
     * <p>
     * If any exceptions are thrown (exceptions from {@link Parcel} reading), the data position will
     * be in an undefined position.
     *
     * @param data The data where the {@code VerifyPhoneNumberRequest} is in. Initial data position
     *             should be at the header of the {@code VerifyPhoneNumberRequest}.
     */
    @Nullable
    String parseVerifyPhoneNumberRequestForPolicy(Parcel data) throws ParseException;
}
