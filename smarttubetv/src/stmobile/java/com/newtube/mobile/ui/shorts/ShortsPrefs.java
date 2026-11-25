package com.newtube.mobile.ui.shorts;

import android.content.Context;
import android.content.SharedPreferences;

import com.liskovsoft.smartyoutubetv2.common.exoplayer.selector.FormatItem;
import com.liskovsoft.smartyoutubetv2.common.exoplayer.selector.FormatItem.VideoPreset;
import com.liskovsoft.smartyoutubetv2.common.misc.AppDataSourceManager;

import java.util.Locale;

/** Independent quality preference for the vertical Shorts player. */
public final class ShortsPrefs {
    public static final String KEY_DEFAULT_RESOLUTION = "mobile_shorts_default_resolution";
    public static final String RESOLUTION_AUTO = "auto";

    private ShortsPrefs() {
    }

    public static String getResolution(Context context) {
        String value = prefs(context).getString(KEY_DEFAULT_RESOLUTION, RESOLUTION_AUTO);
        return value == null ? RESOLUTION_AUTO : value;
    }

    public static void setResolution(Context context, String resolution) {
        prefs(context).edit().putString(KEY_DEFAULT_RESOLUTION, resolution).apply();
    }

    /** Auto returns null; the caller keeps Media3's automatic default height/short-side policy. */
    public static FormatItem getFormat(Context context) {
        String value = getResolution(context);
        if (RESOLUTION_AUTO.equals(value)) {
            return null;
        }
        try {
            int height = Integer.parseInt(value);
            for (VideoPreset preset : AppDataSourceManager.instance().getVideoPresets()) {
                if (preset.getHeight() == height
                        && !preset.name.toLowerCase(Locale.US).contains("hdr")
                        && preset.format != null) {
                    return preset.format;
                }
            }
        } catch (NumberFormatException ignored) {
        }
        return null;
    }

    private static SharedPreferences prefs(Context context) {
        return context.getApplicationContext().getSharedPreferences("mobile_shorts", Context.MODE_PRIVATE);
    }
}
