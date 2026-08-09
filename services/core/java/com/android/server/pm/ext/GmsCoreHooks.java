package com.android.server.pm.ext;

import android.Manifest;
import android.app.compat.gms.GmsCorePackageFlag;
import android.content.pm.GosPackageState;
import android.content.pm.PackageManagerInternal;
import android.content.pm.ServiceInfo;
import android.ext.PackageId;
import android.os.SystemProperties;
import android.service.credentials.CredentialProviderService;

import com.android.internal.gmscompat.GmcMediaProjectionService;
import com.android.internal.gmscompat.GmsHooks;
import com.android.internal.pm.pkg.component.ParsedIntentInfo;
import com.android.internal.pm.pkg.component.ParsedPermission;
import com.android.internal.pm.pkg.component.ParsedService;
import com.android.internal.pm.pkg.component.ParsedServiceImpl;
import com.android.internal.pm.pkg.component.ParsedUsesPermissionImpl;
import com.android.internal.pm.pkg.parsing.ParsingPackage;
import com.android.server.LocalServices;

import java.util.Collections;
import java.util.List;

class GmsCoreHooks extends PackageHooks {

    @Override
    public int overridePermissionState(String permission, int userId) {
        if (android.os.Build.IS_ENG) {
            if (SystemProperties.getBoolean("sys.gmscore_grant." + permission, false)) {
                return PERMISSION_OVERRIDE_GRANT;
            }
            if (SystemProperties.getBoolean("sys.gmscore_revoke." + permission, false)) {
                return PERMISSION_OVERRIDE_REVOKE;
            }
        }

        int flag = 0;
        switch (permission) {
            case Manifest.permission.READ_PHONE_STATE:
            case Manifest.permission.READ_PHONE_NUMBERS:
                // Sandboxed GmsCore is unprivileged, but stock Play services
                // holds the phone permission group, and several of its
                // features self-check for it and disable themselves when it
                // is missing (wallet provisioning among them). Grant it so
                // those checks pass; data returned through the granted APIs
                // is sanitized at the TelephonyManager layer for GMS callers.
                return PERMISSION_OVERRIDE_GRANT;
            case Manifest.permission.READ_PRIVILEGED_PHONE_STATE:
                // Stock Play services is privileged and holds this; sandboxed
                // GmsCore does not. keystore2 gates device-ID attestation on it,
                // so without the grant every attested GMS keygen dies with
                // CANNOT_ATTEST_IDS before the attestation request can even be
                // formed. The attested values themselves stay synthetic — they
                // are sourced in the caller's process from the same sanitized
                // TelephonyManager/Build stubs as the phone-permission data.
                return PERMISSION_OVERRIDE_GRANT;
            case Manifest.permission.USE_ICC_AUTH_WITH_DEVICE_IDENTIFIER:
                flag = GmsCorePackageFlag.GRANT_PERMS_FOR_ICC_AUTHENTICATION;
                break;
            default:
                return NO_PERMISSION_OVERRIDE;
        }

        GosPackageState gosPs = LocalServices.getService(PackageManagerInternal.class)
                .getGosPackageState(PackageId.GMS_CORE_NAME, userId);
        if (gosPs.hasPackageFlag(flag)) {
            return PERMISSION_OVERRIDE_GRANT;
        }
        return PERMISSION_OVERRIDE_REVOKE;
    }

    static class ParsingHooks extends GmsCompatPkgParsingHooks {

        @Override
        public boolean shouldSkipPermissionDefinition(ParsedPermission p) {
            return shouldSkipPermissionDefinition(p.getName());
        }

        @Override
        public void amendParsedService(ParsedServiceImpl s) {
            super.amendParsedService(s);

            if (android.Manifest.permission.BIND_CREDENTIAL_PROVIDER_SERVICE.equals(s.getPermission())) {
                for (ParsedIntentInfo intentInfo : s.getIntents()) {
                    // SYSTEM_SERVICE_INTERFACE is allowed only for preinstalled providers
                    intentInfo.getIntentFilter().replaceAction(CredentialProviderService.SYSTEM_SERVICE_INTERFACE,
                            CredentialProviderService.SERVICE_INTERFACE);
                }
            }
        }

        static boolean shouldSkipPermissionDefinition(String name) {
            switch (name) {
                // These permissions are declared in GmsCompat app instead. They were moved there
                // because of an issue with permissions that have "normal" protectionLevel. If
                // the app that declares a "normal" permission is installed after an app that
                // requests that permission, the permission will not be granted. GmsCompat app
                // is a preinstalled app, it's always present.
                case "com.google.android.c2dm.permission.RECEIVE":
                case "com.google.android.providers.gsf.permission.READ_GSERVICES":
                // This permission is declared in GSF on regular Android. It was moved to GmsCompat
                // app to avoid the need to install GSF, which misbehaves on SDK 35+ due
                // to signature mismatch between itself and GmsCore (GSF and GmsCore use a sharedUid
                // on regular Android)
                case "com.google.android.c2dm.permission.SEND":
                    return true;
                default:
                    return false;
            }
        }

        @Override
        public List<ParsedUsesPermissionImpl> addUsesPermissions() {
            var res = super.addUsesPermissions();
            var l = createUsesPerms(
                    Manifest.permission.REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                    Manifest.permission.READ_PHONE_NUMBERS,
                    Manifest.permission.READ_PRIVILEGED_PHONE_STATE,
                    Manifest.permission.USE_ICC_AUTH_WITH_DEVICE_IDENTIFIER
            );
            res.addAll(l);
            return res;
        }

        @Override
        public List<ParsedService> addServices(ParsingPackage pkg) {
            ParsedServiceImpl s = createService(pkg, GmcMediaProjectionService.class.getName());
            s.setProcessName(GmsHooks.PERSISTENT_GmsCore_PROCESS);
            s.setForegroundServiceType(ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION);
            s.setExported(false);

            return Collections.singletonList(s);
        }
    }
}
