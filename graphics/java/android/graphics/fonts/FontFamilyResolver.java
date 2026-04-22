/*
 * Copyright (C) 2026 The halogenOS Project
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

package android.graphics.fonts;

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.app.ActivityThread;
import android.app.AppGlobals;
import android.content.Context;
import android.content.res.AssetManager;
import android.content.res.Resources;

import java.util.Locale;
import java.util.WeakHashMap;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Resolves hardcoded font family names through per-user resource aliases.
 *
 * <p>For every hardcoded font family name used in framework styles and layouts,
 * there is a corresponding {@code config_fontFamilyAlias_<sanitized>} string resource
 * in {@code android} package. By default each alias maps to itself (identity).
 * A per-user Runtime Resource Overlay can override these aliases to redirect
 * hardcoded names to a user-selected custom font.</p>
 *
 * <p>This helper is called from {@link android.graphics.Typeface} before looking up
 * a family name in the system font map.</p>
 *
 * @hide
 */
public final class FontFamilyResolver {

    private static final String ALIAS_PREFIX = "config_fontFamilyAlias_";
    private static final String PACKAGE_ANDROID = "android";

    // Cache of alias resolutions keyed on the AssetManager they were resolved against.
    // On overlay change or configuration change, ResourcesManager creates a new ResourcesImpl
    // with a new AssetManager and swaps it onto the existing Resources via setImpl(), so the
    // old AssetManager becomes unreachable and its WeakHashMap entry is reclaimed. This means
    // the cache is naturally invalidated exactly when alias resolutions could have changed.
    private static final WeakHashMap<AssetManager, ConcurrentHashMap<String, String>> sCache =
            new WeakHashMap<>();

    private FontFamilyResolver() {}

    private static ConcurrentHashMap<String, String> getOrCreateCache(
            @NonNull AssetManager assets) {
        synchronized (sCache) {
            ConcurrentHashMap<String, String> entry = sCache.get(assets);
            if (entry == null) {
                entry = new ConcurrentHashMap<>();
                sCache.put(assets, entry);
            }
            return entry;
        }
    }

    /**
     * Resolves a font family name through the alias resource system.
     *
     * @param context Optional context. If null, the current application context is used.
     * @param name    The raw font family name (e.g. "variable-title-large").
     * @return The resolved name, or {@code name} unchanged if no alias is declared or
     *         the alias is the identity default.
     */
    public static @NonNull String resolve(@Nullable Context context, @NonNull String name) {
        if (name == null || name.isEmpty()) {
            return name;
        }

        Context ctx = context;
        if (ctx == null) {
            ctx = ActivityThread.currentApplication();
        }
        if (ctx == null) {
            ctx = AppGlobals.getInitialApplication();
        }
        if (ctx == null) {
            return name;
        }

        final Resources res = ctx.getResources();
        final ConcurrentHashMap<String, String> cache = getOrCreateCache(res.getAssets());
        final String cached = cache.get(name);
        if (cached != null) {
            return cached;
        }

        final String resName = ALIAS_PREFIX
                + name.replace('-', '_').toLowerCase(Locale.ROOT);
        final int id = res.getIdentifier(resName, "string", PACKAGE_ANDROID);
        final String resolved;
        if (id == 0) {
            resolved = name;
        } else {
            final String s = res.getString(id);
            resolved = (s == null || s.equals(name)) ? name : s;
        }
        cache.put(name, resolved);
        return resolved;
    }
}
