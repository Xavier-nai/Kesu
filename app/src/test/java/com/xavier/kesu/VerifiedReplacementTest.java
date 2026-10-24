package com.xavier.kesu;

import org.junit.Test;
import java.io.IOException;
import static org.junit.Assert.*;

public class VerifiedReplacementTest {
    private static final byte[] ORIGINAL = {1, 2, 3, 4};
    private static final byte[] NEXT = {8, 9};

    private static final class MemoryDocument implements VerifiedReplacement.Document {
        byte[] value;
        int writes;
        boolean failFirst;
        boolean corruptFirst;
        boolean failAll;
        MemoryDocument(byte[] value) { this.value = value; }
        @Override public byte[] read() { return value == null ? null : value.clone(); }
        @Override public void write(byte[] bytes) throws IOException {
            writes++;
            value = new byte[]{0}; // Simulate truncation before a provider fails.
            if (failAll || (failFirst && writes == 1)) throw new IOException("provider refused write");
            value = corruptFirst && writes == 1 ? new byte[]{0} : bytes.clone();
        }
    }

    @Test public void firstOriginalSurvivesRepeatedReplacements() throws Exception {
        MemoryDocument target = new MemoryDocument(ORIGINAL);
        MemoryDocument backup = new MemoryDocument(null);
        VerifiedReplacement.replace(target, backup, NEXT);
        VerifiedReplacement.replace(target, backup, new byte[]{7});
        assertArrayEquals(ORIGINAL, backup.value);
        assertEquals(1, backup.writes);
        assertArrayEquals(new byte[]{7}, target.value);
    }

    @Test public void failedBackupNeverWritesTarget() {
        MemoryDocument target = new MemoryDocument(ORIGINAL);
        MemoryDocument backup = new MemoryDocument(null);
        backup.failFirst = true;
        assertThrows(IOException.class, () -> VerifiedReplacement.replace(target, backup, NEXT));
        assertEquals(0, target.writes);
        assertArrayEquals(ORIGINAL, target.value);
    }

    @Test public void failedTargetWriteRestoresPreOperationImage() {
        MemoryDocument target = new MemoryDocument(ORIGINAL);
        MemoryDocument backup = new MemoryDocument(null);
        target.failFirst = true;
        IOException error = assertThrows(IOException.class, () -> VerifiedReplacement.replace(target, backup, NEXT));
        assertTrue(error.getMessage().contains("已恢复到操作前"));
        assertArrayEquals(ORIGINAL, target.value);
        assertArrayEquals(ORIGINAL, backup.value);
    }

    @Test public void corruptReadbackTriggersRollback() {
        MemoryDocument target = new MemoryDocument(ORIGINAL);
        MemoryDocument backup = new MemoryDocument(null);
        target.corruptFirst = true;
        assertThrows(IOException.class, () -> VerifiedReplacement.replace(target, backup, NEXT));
        assertEquals(2, target.writes);
        assertArrayEquals(ORIGINAL, target.value);
    }

    @Test public void failedRollbackReportsFailureAndKeepsOriginalBackup() {
        MemoryDocument target = new MemoryDocument(ORIGINAL);
        MemoryDocument backup = new MemoryDocument(null);
        target.failAll = true;
        IOException error = assertThrows(IOException.class, () -> VerifiedReplacement.replace(target, backup, NEXT));
        assertTrue(error.getMessage().contains("自动恢复也未成功"));
        assertEquals(1, error.getSuppressed().length);
        assertArrayEquals(ORIGINAL, backup.value);
    }

    @Test public void emptyExistingBackupStopsReplacement() {
        MemoryDocument target = new MemoryDocument(ORIGINAL);
        MemoryDocument backup = new MemoryDocument(new byte[0]);
        assertThrows(IOException.class, () -> VerifiedReplacement.replace(target, backup, NEXT));
        assertEquals(0, target.writes);
    }

    @Test public void restoreRepairsAnEmptyTargetAndRetainsBackup() throws Exception {
        MemoryDocument target = new MemoryDocument(new byte[0]);
        MemoryDocument backup = new MemoryDocument(ORIGINAL);
        VerifiedReplacement.restore(target, backup);
        assertArrayEquals(ORIGINAL, target.value);
        assertArrayEquals(ORIGINAL, backup.value);
        assertEquals(0, backup.writes);
    }

    @Test public void emptyReplacementCannotTouchTargetOrBackup() {
        MemoryDocument target = new MemoryDocument(ORIGINAL);
        MemoryDocument backup = new MemoryDocument(null);
        assertThrows(IOException.class, () -> VerifiedReplacement.replace(target, backup, new byte[0]));
        assertEquals(0, target.writes);
        assertEquals(0, backup.writes);
    }

    @Test public void corruptBackupReadbackNeverTouchesTarget() {
        MemoryDocument target = new MemoryDocument(ORIGINAL);
        MemoryDocument backup = new MemoryDocument(null);
        backup.corruptFirst = true;
        assertThrows(IOException.class, () -> VerifiedReplacement.replace(target, backup, NEXT));
        assertEquals(0, target.writes);
        assertArrayEquals(ORIGINAL, target.value);
    }

    @Test public void rollbackReturnsToLatestImageRatherThanFirstOriginal() {
        MemoryDocument target = new MemoryDocument(NEXT);
        MemoryDocument backup = new MemoryDocument(ORIGINAL);
        target.failFirst = true;
        assertThrows(IOException.class, () -> VerifiedReplacement.replace(target, backup, new byte[]{5}));
        assertArrayEquals(NEXT, target.value);
        assertArrayEquals(ORIGINAL, backup.value);
    }
}
