package com.xavier.kesu;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.UUID;

/** Owns only the images explicitly published for external lockscreen themes. */
final class SharedWallpaper {
    static final int MAX_BYTES = 32 * 1024 * 1024;
    private final Path directory;

    SharedWallpaper(File directory) { this.directory = directory.toPath(); }

    private static boolean imageName(String name) {
        return name != null && name.matches("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}\\.jpg");
    }

    String current() throws IOException {
        Path pointer = directory.resolve("current");
        if (!Files.exists(pointer)) return null;
        String name = new String(Files.readAllBytes(pointer), StandardCharsets.US_ASCII);
        if (!imageName(name) || !Files.isRegularFile(directory.resolve(name))) {
            throw new IOException("共享壁纸记录损坏，请重新选择锁屏壁纸");
        }
        return name;
    }

    File image(String name) throws FileNotFoundException {
        if (!imageName(name) || !Files.isRegularFile(directory.resolve(name))) {
            throw new FileNotFoundException("壁纸不存在或已经过期");
        }
        return directory.resolve(name).toFile();
    }

    // The provider serializes calls: publish a complete image before switching its pointer.
    String publish(byte[] jpeg) throws IOException {
        if (jpeg == null || jpeg.length == 0 || jpeg.length > MAX_BYTES) throw new IOException("壁纸为空或超过 32 MiB");
        Files.createDirectories(directory);
        String previous;
        try { previous = current(); }
        catch (IOException damagedRecord) { previous = null; }
        String name = UUID.randomUUID() + ".jpg";
        Path image = directory.resolve(name);
        Path pending = directory.resolve("current.tmp");
        try {
            writeSynced(image, jpeg);
            writeSynced(pending, name.getBytes(StandardCharsets.US_ASCII));
            Files.move(pending, directory.resolve("current"), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException e) {
            try { Files.deleteIfExists(image); Files.deleteIfExists(pending); }
            catch (IOException cleanup) { e.addSuppressed(cleanup); }
            throw e;
        }
        // Retain one preceding image for a reader that queried just before a switch.
        File[] files = directory.toFile().listFiles();
        if (files != null) for (File file : files) {
            if (imageName(file.getName()) && !file.getName().equals(name) && !file.getName().equals(previous)) {
                file.delete();
            }
        }
        return name;
    }

    private static void writeSynced(Path file, byte[] bytes) throws IOException {
        try (FileOutputStream output = new FileOutputStream(file.toFile())) {
            output.write(bytes);
            output.getFD().sync();
        }
    }
}
