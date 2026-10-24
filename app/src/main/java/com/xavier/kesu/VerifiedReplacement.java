package com.xavier.kesu;

import java.io.IOException;
import java.util.Arrays;

/** Reads back every write, and retains the first original across later replacements. */
final class VerifiedReplacement {
    interface Document {
        /** null means the backup does not yet exist; a target must always exist. */
        byte[] read() throws IOException;
        void write(byte[] bytes) throws IOException;
    }

    static void replace(Document target, Document backup, byte[] replacement) throws IOException {
        requireData(replacement, "新图片为空");
        byte[] before = target.read();
        requireData(before, "读取原壁纸失败，未修改主题");
        byte[] original = backup.read();
        if (original == null) {
            backup.write(before);
            verify(backup, before, "原图备份校验失败，未修改主题");
        } else {
            requireData(original, "已有备份为空，未修改主题。请检查目录中的原壁纸备份");
        }
        writeWithRollback(target, replacement, before);
    }

    static void restore(Document target, Document backup) throws IOException {
        byte[] original = backup.read();
        requireData(original, "没有可用的原图备份");
        byte[] before = target.read();
        if (before == null) throw new IOException("当前文件无法读取，已保留原图备份");
        writeWithRollback(target, original, before);
    }

    private static void writeWithRollback(Document target, byte[] next, byte[] before) throws IOException {
        try {
            target.write(next);
            verify(target, next, "写入后的文件内容不一致");
        } catch (IOException failure) {
            try {
                target.write(before);
                verify(target, before, "回滚校验失败");
            } catch (IOException rollback) {
                IOException error = new IOException("写入失败，自动恢复也未成功。原图备份已保留，请重新授权后恢复原图", failure);
                error.addSuppressed(rollback);
                throw error;
            }
            throw new IOException("写入失败，已恢复到操作前的壁纸。原图备份已保留", failure);
        }
    }

    private static void verify(Document document, byte[] expected, String message) throws IOException {
        if (!Arrays.equals(expected, document.read())) throw new IOException(message);
    }

    private static void requireData(byte[] bytes, String message) throws IOException {
        if (bytes == null || bytes.length == 0) throw new IOException(message);
    }

    private VerifiedReplacement() {}
}
