package com.xavier.kesu;

import java.io.IOException;

/** Keep the picker/provider's real document IDs; normalize only for scope checks. */
final class ThemePaths {
    static final String CONTENT = "Android/data/com.android.thememanager/files/MIUI/theme/.data/content/lockscreen";
    static final String META = "Android/data/com.android.thememanager/files/MIUI/theme/.data/meta/lockscreen";
    static final String REFERENCE_ROOT = "\u200bAndroid";
    static final String INITIAL_ID = "primary:" + REFERENCE_ROOT;
    final String directoryId;
    final String metadataId;

    ThemePaths(String grantedId) throws IOException {
        int colon = grantedId.indexOf(':');
        if (colon <= 0) throw new IOException("无法识别授权目录");
        String volume = grantedId.substring(0, colon + 1);
        String relative = grantedId.substring(colon + 1);
        String normalized = normalizeRelative(relative);
        if (!normalized.isEmpty() && !normalized.equals(CONTENT) && !CONTENT.startsWith(normalized + "/")) {
            throw new IOException("目录不正确，请选择 Android 或小米主题 content/lockscreen 目录");
        }
        // Using a canonical Android path here would discard the actual granted root identifier.
        boolean referenceRoot = relative.equals(REFERENCE_ROOT) || relative.startsWith(REFERENCE_ROOT + "/");
        String root = referenceRoot ? REFERENCE_ROOT : "Android";
        directoryId = volume + root + CONTENT.substring("Android".length());
        metadataId = volume + root + META.substring("Android".length());
    }

    static boolean isDirectChild(String id, String directoryId) {
        int slash = id.lastIndexOf('/');
        return slash >= 0 && normalizeId(id.substring(0, slash)).equals(normalizeId(directoryId));
    }

    private static String normalizeId(String id) {
        int colon = id.indexOf(':');
        if (colon < 0) return id;
        return id.substring(0, colon + 1) + normalizeRelative(id.substring(colon + 1));
    }

    private static String normalizeRelative(String relative) {
        if (relative.equals(REFERENCE_ROOT) || relative.startsWith(REFERENCE_ROOT + "/")) {
            return "Android" + relative.substring(REFERENCE_ROOT.length());
        }
        return relative;
    }
}
