package com.xavier.kesu;

import org.junit.Test;
import java.io.IOException;
import static org.junit.Assert.*;

public class ThemePathsTest {
    @Test public void standardRootLocatesOnlyThemeWallpapers() throws Exception {
        ThemePaths paths = new ThemePaths("primary:Android");
        assertEquals("primary:" + ThemePaths.CONTENT, paths.directoryId);
        assertEquals("primary:" + ThemePaths.META, paths.metadataId);
    }

    @Test public void referenceRootKeepsActualGrantedIdentifier() throws Exception {
        ThemePaths paths = new ThemePaths("primary:\u200bAndroid");
        assertEquals("primary:\u200b" + ThemePaths.CONTENT, paths.directoryId);
        assertEquals("primary:\u200b" + ThemePaths.META, paths.metadataId);
    }

    @Test public void directFolderGrantDoesNotAppendDirectoryTwice() throws Exception {
        assertEquals("primary:" + ThemePaths.CONTENT, new ThemePaths("primary:" + ThemePaths.CONTENT).directoryId);
        assertEquals("primary:\u200b" + ThemePaths.CONTENT,
                new ThemePaths("primary:\u200b" + ThemePaths.CONTENT).directoryId);
    }

    @Test public void providerMayReturnCanonicalChildOfReferenceRoot() throws Exception {
        ThemePaths paths = new ThemePaths("primary:\u200bAndroid");
        assertTrue(ThemePaths.isDirectChild("primary:" + ThemePaths.CONTENT + "/wallpaper", paths.directoryId));
        assertTrue(ThemePaths.isDirectChild("primary:\u200b" + ThemePaths.CONTENT + "/wallpaper", paths.directoryId));
    }

    @Test public void otherVolumeOrFolderCannotBeWritten() throws Exception {
        ThemePaths paths = new ThemePaths("primary:\u200bAndroid");
        assertFalse(ThemePaths.isDirectChild("other:" + ThemePaths.CONTENT + "/wallpaper", paths.directoryId));
        assertFalse(ThemePaths.isDirectChild("primary:Android/data/another.app/wallpaper", paths.directoryId));
        assertFalse(ThemePaths.isDirectChild("primary:" + ThemePaths.CONTENT + "/sub/wallpaper", paths.directoryId));
    }

    @Test public void unrelatedOrTraversingGrantIsRejected() {
        for (String id : new String[]{"primary:Download", "primary:Androidish", "primary:Android/../Download",
                "primary:\u200bAndroidish", "primary:Android/data/another.app", "invalid"}) {
            assertThrows(id, IOException.class, () -> new ThemePaths(id));
        }
    }
}
