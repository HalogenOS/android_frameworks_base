/*
 * Copyright (C) 2024 The Android Open Source Project
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

package android.app.supervision;

import static android.Manifest.permission.INTERACT_ACROSS_USERS;
import static android.Manifest.permission.MANAGE_USERS;
import static android.Manifest.permission.QUERY_USERS;
import static android.permission.flags.Flags.FLAG_ENABLE_SYSTEM_SUPERVISION_ROLE_BEHAVIOR;

import android.annotation.FlaggedApi;
import android.annotation.NonNull;
import android.annotation.Nullable;
import android.annotation.RequiresPermission;
import android.annotation.SdkConstant;
import android.annotation.SdkConstant.SdkConstantType;
import android.annotation.SystemApi;
import android.annotation.SystemService;
import android.annotation.TestApi;
import android.annotation.UserHandleAware;
import android.annotation.UserIdInt;
import android.app.compat.gms.GmsCompat;
import android.app.supervision.flags.Flags;
import android.compat.annotation.UnsupportedAppUsage;
import android.content.Context;
import android.content.Intent;
import android.os.RemoteException;

/**
 * This class provides information about and manages supervision.
 *
 * @hide
 */
@SystemService(Context.SUPERVISION_SERVICE)
@SystemApi
@FlaggedApi(Flags.FLAG_SUPERVISION_MANAGER_APIS)
public class SupervisionManager {
    /**
     * Listener for supervision state changes.
     *
     * @hide
     */
    public abstract static class SupervisionListener {
        protected final ISupervisionListener mListener =
                new ISupervisionListener.Stub() {
                    @Override
                    public void onSetSupervisionEnabled(int userId, boolean enabled) {
                        if (enabled) {
                            onSupervisionEnabled(userId);
                        } else {
                            onSupervisionDisabled(userId);
                        }
                    }
                };

        /**
         * Called after supervision has been enabled for a given user.
         *
         * @param userId Int ID of the user for whom supervision was enabled.
         * @hide
         */
        public void onSupervisionEnabled(@UserIdInt int userId) {}

        /**
         * Called after supervision has been enabled for a given user.
         *
         * @param userId Int ID of the user for whom supervision was enabled.
         * @hide
         */
        public void onSupervisionDisabled(@UserIdInt int userId) {}
    }

    private final Context mContext;
    @Nullable private final ISupervisionManager mService;

    /**
     * Activity Action: Ask the user to confirm enabling supervision.
     *
     * <p>The intent must be invoked via {@link Activity#startActivityForResult} to receive the
     * result of whether or not the user approved the action. If approved, the result will be {@link
     * Activity#RESULT_OK}.
     *
     * <p>If supervision is already enabled, the operation will return a failure result.
     *
     * @hide
     */
    @SystemApi
    @FlaggedApi(Flags.FLAG_SUPERVISION_MANAGER_APIS)
    @SdkConstant(SdkConstantType.ACTIVITY_INTENT_ACTION)
    public static final String ACTION_ENABLE_SUPERVISION =
            "android.app.supervision.action.ENABLE_SUPERVISION";

    /**
     * Activity Action: Ask the user to confirm disabling supervision.
     *
     * <p>The intent must be invoked via {@link Activity#startActivityForResult} to receive the
     * result of whether or not the user approved the action. If approved, the result will be {@link
     * Activity#RESULT_OK}.
     *
     * <p>If supervision is not enabled, the operation will return a failure result.
     *
     * @hide
     */
    @SystemApi
    @FlaggedApi(Flags.FLAG_SUPERVISION_MANAGER_APIS)
    @SdkConstant(SdkConstantType.ACTIVITY_INTENT_ACTION)
    public static final String ACTION_DISABLE_SUPERVISION =
            "android.app.supervision.action.DISABLE_SUPERVISION";

    /**
     * SupervisionService's identifier for setting policies or restrictions in
     * {@link DevicePolicyManager}.
     *
     * @hide
     */
    public static final String SUPERVISION_SYSTEM_ENTITY = SupervisionManager.class.getName();

    /** @hide */
    @UnsupportedAppUsage
    public SupervisionManager(Context context, @Nullable ISupervisionManager service) {
        mContext = context;
        mService = service;
    }

    /**
     * Creates an {@link Intent} that can be used with {@link Context#startActivity(Intent)} to
     * launch the activity to verify supervision credentials.
     *
     * <p>A valid {@link Intent} is always returned if supervision is enabled at the time this API
     * is called, the launched activity still need to perform validity checks as the supervision
     * state can change when the activity is launched. A null intent is returned if supervision is
     * disabled at the time of this API call.
     *
     * <p>A result code of {@link android.app.Activity#RESULT_OK} indicates successful verification
     * of the supervision credentials.
     *
     * @hide
     */
    @SystemApi
    @FlaggedApi(Flags.FLAG_SUPERVISION_MANAGER_APIS)
    @RequiresPermission(anyOf = {MANAGE_USERS, QUERY_USERS})
    @Nullable
    public Intent createConfirmSupervisionCredentialsIntent() {
        if (mService != null) {
            try {
                Intent result =
                        mService.createConfirmSupervisionCredentialsIntent(mContext.getUserId());
                if (result != null) {
                    result.prepareToEnterProcess(
                            Intent.LOCAL_FLAG_FROM_SYSTEM, mContext.getAttributionSource());
                }
                return result;
            } catch (RemoteException e) {
                throw e.rethrowFromSystemServer();
            }
        }
        return null;
    }

    /**
     * Returns whether supervision is enabled for the current user.
     *
     * @hide
     */
    @SystemApi
    @FlaggedApi(Flags.FLAG_SUPERVISION_MANAGER_APIS)
    @RequiresPermission(anyOf = {MANAGE_USERS, QUERY_USERS})
    @UserHandleAware(requiresPermissionIfNotCaller = INTERACT_ACROSS_USERS)
    public boolean isSupervisionEnabled() {
        return isSupervisionEnabledForUser(mContext.getUserId());
    }

    /**
     * Returns whether supervision is enabled for the given user.
     *
     * @hide
     */
    @RequiresPermission(anyOf = {MANAGE_USERS, QUERY_USERS})
    @UserHandleAware(requiresPermissionIfNotCaller = INTERACT_ACROSS_USERS)
    public boolean isSupervisionEnabledForUser(@UserIdInt int userId) {
        if (mService != null) {
            try {
                return mService.isSupervisionEnabledForUser(userId);
            } catch (RemoteException e) {
                throw e.rethrowFromSystemServer();
            } catch (SecurityException e) {
                GmsCompat.catchOrRethrow(e);
                return false;
            }
        }
        return false;
    }

    /**
     * Sets whether the supervision is enabled for the current user.
     *
     * @hide
     */
    @TestApi
    @UserHandleAware(requiresPermissionIfNotCaller = INTERACT_ACROSS_USERS)
    public void setSupervisionEnabled(boolean enabled) {
        setSupervisionEnabledForUser(mContext.getUserId(), enabled);
    }

    /**
     * Sets whether the supervision is enabled for the given user.
     *
     * @hide
     */
    @UserHandleAware(requiresPermissionIfNotCaller = INTERACT_ACROSS_USERS)
    public void setSupervisionEnabledForUser(@UserIdInt int userId, boolean enabled) {
        if (mService != null) {
            try {
                mService.setSupervisionEnabledForUser(userId, enabled);
            } catch (RemoteException e) {
                throw e.rethrowFromSystemServer();
            }
        }
    }

    /**
     * Returns the package name of the app that is acting as the active supervision app or null if
     * supervision is disabled.
     *
     * @hide
     */
    @UserHandleAware
    @Nullable
    public String getActiveSupervisionAppPackage() {
        if (mService != null) {
            try {
                return mService.getActiveSupervisionAppPackage(mContext.getUserId());
            } catch (RemoteException e) {
                throw e.rethrowFromSystemServer();
            }
        }
        return null;
    }

    /**
     * @return {@code true} if bypassing the qualification is allowed for the specified role based
     *     on the current state of the device.
     * @hide
     */
    @SystemApi
    @FlaggedApi(FLAG_ENABLE_SYSTEM_SUPERVISION_ROLE_BEHAVIOR)
    @RequiresPermission(android.Manifest.permission.MANAGE_ROLE_HOLDERS)
    public boolean shouldAllowBypassingSupervisionRoleQualification() {
        if (mService != null) {
            try {
                return mService.shouldAllowBypassingSupervisionRoleQualification();
            } catch (RemoteException e) {
                throw e.rethrowFromSystemServer();
            }
        }
        return false;
    }

    /**
     * Sets the supervision recovery information.
     *
     * @hide
     */
    public void setSupervisionRecoveryInfo(SupervisionRecoveryInfo recoveryInfo) {
        if (mService != null) {
            try {
                mService.setSupervisionRecoveryInfo(recoveryInfo);
            } catch (RemoteException e) {
                throw e.rethrowFromSystemServer();
            }
        }
    }

    /**
     * Returns the supervision recovery information or null if recovery is not setup.
     *
     * @hide
     */
    @Nullable
    public SupervisionRecoveryInfo getSupervisionRecoveryInfo() {
        if (mService != null) {
            try {
                return mService.getSupervisionRecoveryInfo();
            } catch (RemoteException e) {
                throw e.rethrowFromSystemServer();
            }
        }
        return null;
    }

    /**
     * Returns whether supervision credentials are set up.
     *
     * @hide
     */
    @RequiresPermission(anyOf = {MANAGE_USERS, QUERY_USERS})
    public boolean hasSupervisionCredentials() {
        if (mService != null) {
            try {
                return mService.hasSupervisionCredentials();
            } catch (RemoteException e) {
                throw e.rethrowFromSystemServer();
            }
        }
        return false;
    }

    /**
     * Registers a listener to be notified on supervision state changes.
     *
     * @param listener Listener to be registered. Can't be null.
     * @hide
     */
    public void registerSupervisionListener(@NonNull SupervisionListener listener) {
        if (mService != null) {
            try {
                mService.registerSupervisionListener(mContext.getUserId(), listener.mListener);
            } catch (RemoteException e) {
                throw e.rethrowFromSystemServer();
            }
        }
    }

    /**
     * Unregisters a listener that was previously registered.
     *
     * @param listener Listener to be unregistered. Can't be null.
     * @hide
     */
    public void unregisterSupervisionListener(@NonNull SupervisionListener listener) {
        if (mService != null) {
            try {
                mService.unregisterSupervisionListener(listener.mListener);
            } catch (RemoteException e) {
                throw e.rethrowFromSystemServer();
            }
        }
    }
}
