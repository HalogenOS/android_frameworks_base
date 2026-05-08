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

package com.android.internal.graphics.fonts;

import android.os.ParcelFileDescriptor;
import android.graphics.fonts.FontUpdateRequest;
import android.text.FontConfig;

import java.util.List;
import java.util.Map;

/**
 * System private interface for talking with
 * {@link com.android.server.graphics.fonts.FontManagerService}.
 * @hide
 */
interface IFontManager {
    @EnforcePermission("UPDATE_FONTS")
    FontConfig getFontConfig();

    int updateFontFamily(in List<FontUpdateRequest> request, int baseVersion);

    @EnforcePermission("INSTALL_CUSTOM_FONTS")
    int installCustomFontFile(in ParcelFileDescriptor fd);

    @EnforcePermission("INSTALL_CUSTOM_FONTS")
    int installCustomFontFamily(in List<FontUpdateRequest> familyRequests);

    @EnforcePermission("INSTALL_CUSTOM_FONTS")
    int removeCustomFontFamily(String familyName);

    @EnforcePermission("INSTALL_CUSTOM_FONTS")
    List<String> getCustomFontFamilyNames();

    @EnforcePermission("INSTALL_CUSTOM_FONTS")
    Map getCustomFontFamilyDisplayNames();

    @EnforcePermission("INSTALL_CUSTOM_FONTS")
    int setActiveCustomFontFamily(@nullable String familyName);

    @EnforcePermission("INSTALL_CUSTOM_FONTS")
    @nullable String getActiveCustomFontFamily();

    @EnforcePermission("INSTALL_CUSTOM_FONTS")
    @nullable String getDefaultFontFamily();

    @EnforcePermission("INSTALL_CUSTOM_FONTS")
    String installCustomFontFamilyFromFile(in ParcelFileDescriptor fd);
}
