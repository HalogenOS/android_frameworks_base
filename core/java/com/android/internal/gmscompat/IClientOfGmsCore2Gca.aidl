package com.android.internal.gmscompat;

import android.os.BinderDef;

import com.android.internal.gmscompat.fileservice.IFileProxyService;

// calls from clients of GMS Core to GmsCompatApp
interface IClientOfGmsCore2Gca {
    @nullable BinderDef maybeGetBinderDef(String callerPkg, int processState, String ifaceName);

    IFileProxyService getGmsCoreFileProxyService();

    oneway void showMissingAppNotification(String pkgName);

    oneway void showPlayIntegrityNotification(String pkgName, boolean isBlocked);

    oneway void onGoogleIdCredentialOptionInit();
}
