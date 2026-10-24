package com.xavier.kesu;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;

import static org.junit.Assert.*;

public class SharedWallpaperTest {
    @Rule public TemporaryFolder temp = new TemporaryFolder();

    private File directory() { return new File(temp.getRoot(), "shared"); }
    private SharedWallpaper store() { return new SharedWallpaper(directory()); }

    @Test public void unselectedWallpaperHasNoCurrentImage() throws Exception {
        assertNull(store().current());
        assertFalse(directory().exists());
    }

    @Test public void publishedImageAndRevisionSurviveReopening() throws Exception {
        byte[] bytes = {1, 2, 3};
        String name = store().publish(bytes);
        assertEquals(name, store().current());
        assertArrayEquals(bytes, Files.readAllBytes(store().image(name).toPath()));
    }

    @Test public void everySelectionChangesUriEvenForIdenticalBytes() throws Exception {
        String first = store().publish(new byte[]{1});
        String second = store().publish(new byte[]{1});
        assertNotEquals(first, second);
        assertEquals(second, store().current());
        assertArrayEquals(new byte[]{1}, Files.readAllBytes(store().image(first).toPath()));
    }

    @Test public void precedingImageRemainsReadableButOlderImagesAreRemoved() throws Exception {
        String first = store().publish(new byte[]{1});
        String second = store().publish(new byte[]{2});
        String third = store().publish(new byte[]{3});
        assertThrows(FileNotFoundException.class, () -> store().image(first));
        assertArrayEquals(new byte[]{2}, Files.readAllBytes(store().image(second).toPath()));
        assertEquals(third, store().current());
        assertEquals(3, directory().listFiles().length); // current record plus two images
    }

    @Test public void failedPointerWritePreservesPreviousSelectionAndBytes() throws Exception {
        String before = store().publish(new byte[]{1, 2});
        Files.createDirectory(directory().toPath().resolve("current.tmp"));
        assertThrows(IOException.class, () -> store().publish(new byte[]{3}));
        assertEquals(before, store().current());
        assertArrayEquals(new byte[]{1, 2}, Files.readAllBytes(store().image(before).toPath()));
    }

    @Test public void emptyAndOversizeSelectionsDoNotChangeCurrent() throws Exception {
        String before = store().publish(new byte[]{1});
        assertThrows(IOException.class, () -> store().publish(new byte[0]));
        assertThrows(IOException.class, () -> store().publish(new byte[SharedWallpaper.MAX_BYTES + 1]));
        assertEquals(before, store().current());
    }

    @Test public void traversalAndNonImageNamesCannotReadPrivateFiles() throws Exception {
        store().publish(new byte[]{1});
        Files.write(temp.getRoot().toPath().resolve("private.jpg"), new byte[]{7});
        for (String path : new String[]{"../private.jpg", "..\\private.jpg", "current", "/private.jpg", "file.jpg", ""}) {
            assertThrows(FileNotFoundException.class, () -> store().image(path));
        }
    }

    @Test public void abandonedPendingWriteDoesNotReplaceCurrentAndIsCleanedNextTime() throws Exception {
        String before = store().publish(new byte[]{1});
        String abandoned = UUID.randomUUID() + ".jpg";
        Files.write(directory().toPath().resolve(abandoned), new byte[]{8});
        Files.write(directory().toPath().resolve("current.tmp"), abandoned.getBytes(java.nio.charset.StandardCharsets.US_ASCII));
        assertEquals(before, store().current());
        String next = store().publish(new byte[]{2});
        assertEquals(next, store().current());
        assertThrows(FileNotFoundException.class, () -> store().image(abandoned));
    }

    @Test public void corruptRecordCannotEscapeDirectoryAndReselectionRepairsIt() throws Exception {
        store().publish(new byte[]{1});
        Path pointer = directory().toPath().resolve("current");
        Files.write(pointer, "../private.jpg".getBytes(java.nio.charset.StandardCharsets.US_ASCII));
        assertThrows(IOException.class, () -> store().current());
        String repaired = store().publish(new byte[]{2});
        assertEquals(repaired, store().current());
        assertArrayEquals(new byte[]{2}, Files.readAllBytes(store().image(repaired).toPath()));
    }
}
