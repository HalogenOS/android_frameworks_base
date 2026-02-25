package android.ext;

import android.annotation.IntDef;
import android.annotation.SystemApi;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;

/** @hide */
@SystemApi
public interface AppInfoExtFlag {
    /* SysApi */ int HAS_GMSCORE_CLIENT_LIBRARY = 0;
    /** @hide */ int HAS_PLAY_STORE_SOURCE_STAMP_ON_APK_CERTS = 1;

    /** @hide */
    @IntDef(value = {
            HAS_GMSCORE_CLIENT_LIBRARY,
            HAS_PLAY_STORE_SOURCE_STAMP_ON_APK_CERTS,
    })
    @Retention(RetentionPolicy.SOURCE)
    @interface Enum {}
}
