package com.xavier.kesu;

import android.content.ContentResolver;
import android.content.Context;
import android.content.Intent;
import android.content.UriPermission;
import android.database.Cursor;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.net.Uri;
import android.provider.DocumentsContract;
import android.util.Xml;

import org.xmlpull.v1.XmlPullParser;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

final class ThemeStore {
    static final String AUTHORITY = "com.android.externalstorage.documents";
    static final String PATH = "Android/data/com.android.thememanager/files/MIUI/theme/.data/content/lockscreen";
    private static final String META_PATH = "Android/data/com.android.thememanager/files/MIUI/theme/.data/meta/lockscreen";
    private static final String BACKUP_PREFIX = "原壁纸";
    private static final int MAX_BYTES = 32 * 1024 * 1024;
    private final Context context;
    private final ContentResolver resolver;
    private final Uri tree;
    private final String directoryId;
    private final String volume;

    static final class Entry {
        final String id;
        final String name;
        final String mime;
        final long flags;
        String title;
        String designer = "";
        Bitmap thumbnail;

        Entry(String id, String name, String mime, long flags) {
            this.id = id;
            this.name = name;
            this.mime = mime;
            this.flags = flags;
            title = name;
        }
    }

    ThemeStore(Context context, Uri tree) throws IOException {
        this.context = context;
        resolver = context.getContentResolver();
        this.tree = tree;
        if (!AUTHORITY.equals(tree.getAuthority()) || !DocumentsContract.isTreeUri(tree)) {
            throw new IOException("请选择系统文件选择器中的 Android 或主题 lockscreen 目录");
        }
        String granted = DocumentsContract.getTreeDocumentId(tree);
        int colon = granted.indexOf(':');
        if (colon < 0) throw new IOException("无法识别此目录");
        volume = granted.substring(0, colon + 1);
        String relative = granted.substring(colon + 1);
        if (!relative.isEmpty() && !relative.equals(PATH) && !PATH.startsWith(relative + "/")) {
            throw new IOException("目录不正确，请选择 Android 或小米主题 content/lockscreen 目录");
        }
        directoryId = volume + PATH;
        boolean allowed = false;
        for (UriPermission permission : resolver.getPersistedUriPermissions()) {
            if (permission.getUri().equals(tree) && permission.isReadPermission() && permission.isWritePermission()) {
                allowed = true;
                break;
            }
        }
        if (!allowed) throw new IOException("没有持久的读写权限，请重新授权并允许写入");
    }

    static Intent authorizationIntent(boolean compatibility) {
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT_TREE);
        intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                | Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION | Intent.FLAG_GRANT_PREFIX_URI_PERMISSION);
        // This is only the picker starting location used by the reference APK. It grants no access.
        String hint = compatibility ? "primary:\u200bAndroid" : "primary:Android";
        intent.putExtra(DocumentsContract.EXTRA_INITIAL_URI, DocumentsContract.buildDocumentUri(AUTHORITY, hint));
        intent.putExtra("android.content.extra.SHOW_ADVANCED", true);
        return intent;
    }

    List<Entry> list() throws IOException {
        List<Entry> result = new ArrayList<>();
        List<Entry> files = children(directoryId);
        java.util.Set<String> backedUp = new java.util.HashSet<>();
        for (Entry entry : files) {
            if (entry.name.startsWith(BACKUP_PREFIX)) backedUp.add(entry.name.substring(BACKUP_PREFIX.length()));
        }
        for (Entry entry : files) {
            if (entry.name.startsWith(BACKUP_PREFIX)) continue;
            try (InputStream input = open(entry.id)) {
                BitmapFactory.Options options = new BitmapFactory.Options();
                options.inJustDecodeBounds = true;
                BitmapFactory.decodeStream(input, null, options);
                if ((options.outWidth > 0 && options.outHeight > 0) || backedUp.contains(entry.name)) result.add(entry);
            } catch (SecurityException e) {
                throw new IOException("读取被拒绝，请重新授权主题目录", e);
            }
        }
        Map<String, Entry> metadata = new HashMap<>();
        try {
            for (Entry item : children(volume + META_PATH)) metadata.put(stem(item.name), item);
        } catch (IOException | SecurityException ignored) {
            // A directly granted content/lockscreen folder does not grant its sibling metadata.
        }
        for (Entry entry : result) {
            Entry meta = metadata.get(stem(entry.name));
            if (meta != null) {
                try {
                    String xml = new String(read(meta.id, 256 * 1024), StandardCharsets.UTF_8);
                    String title = metadataValue(xml, "titles");
                    if (!title.isEmpty()) entry.title = title;
                    entry.designer = metadataValue(xml, "designers");
                } catch (Exception ignored) {
                    // Unreadable optional names must not prevent wallpaper management.
                }
            }
            try {
                entry.thumbnail = Images.decode(context, uri(entry.id), 240);
            } catch (IOException ignored) {
                // A partial target must remain in the list so its intact backup can be restored.
            }
        }
        result.sort(Comparator.comparing(e -> e.title));
        return result;
    }

    void replace(Entry entry, byte[] jpeg) throws IOException {
        validateEntry(entry);
        requireImage(jpeg);
        requireImage(read(entry.id, MAX_BYTES));
        Document backup = new Document(entry);
        byte[] oldBackup = backup.read();
        if (oldBackup != null) requireImage(oldBackup);
        VerifiedReplacement.replace(new Document(entry.id), backup, jpeg);
    }

    void restore(Entry entry) throws IOException {
        validateEntry(entry);
        Document backup = new Document(entry);
        byte[] original = backup.read();
        if (original == null) throw new IOException("此主题没有原图备份");
        requireImage(original);
        VerifiedReplacement.restore(new Document(entry.id), backup);
    }

    byte[] extract(Entry entry) throws IOException {
        validateEntry(entry);
        byte[] data = read(entry.id, MAX_BYTES);
        requireImage(data);
        return data;
    }

    private void validateEntry(Entry entry) throws IOException {
        String parent = entry.id.substring(0, Math.max(0, entry.id.lastIndexOf('/')));
        if (!parent.equals(directoryId) || entry.name.startsWith(BACKUP_PREFIX)) {
            throw new IOException("目标不在授权的主题壁纸目录内");
        }
        Entry actual = null;
        for (Entry candidate : children(directoryId)) if (candidate.id.equals(entry.id)) actual = candidate;
        if (actual == null || !actual.name.equals(entry.name)) throw new IOException("主题文件已变化，请刷新后重试");
        if ((actual.flags & DocumentsContract.Document.FLAG_SUPPORTS_WRITE) == 0) {
            throw new IOException("文件提供器未允许写入此壁纸，请重新授权");
        }
    }

    private List<Entry> children(String parentId) throws IOException {
        Uri children = managed(DocumentsContract.buildChildDocumentsUriUsingTree(tree, parentId));
        String[] projection = {DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                DocumentsContract.Document.COLUMN_DISPLAY_NAME, DocumentsContract.Document.COLUMN_MIME_TYPE,
                DocumentsContract.Document.COLUMN_FLAGS};
        List<Entry> result = new ArrayList<>();
        try (Cursor cursor = resolver.query(children, projection, null, null, null)) {
            if (cursor == null) throw new IOException("无法读取主题目录，请检查目录授权");
            while (cursor.moveToNext()) {
                String mime = cursor.getString(2);
                if (!DocumentsContract.Document.MIME_TYPE_DIR.equals(mime)) {
                    result.add(new Entry(cursor.getString(0), cursor.getString(1), mime, cursor.getLong(3)));
                }
            }
        } catch (RuntimeException e) {
            throw new IOException("主题目录不可访问。系统或文件选择器拒绝了访问，请重新授权", e);
        }
        return result;
    }

    private InputStream open(String id) throws IOException {
        InputStream input = resolver.openInputStream(uri(id));
        if (input == null) throw new IOException("无法读取主题图片");
        return input;
    }

    private byte[] read(String id, int limit) throws IOException {
        try (InputStream input = open(id); ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[16 * 1024];
            int n;
            while ((n = input.read(buffer)) != -1) {
                if (output.size() + n > limit) throw new IOException("文件过大，未执行操作");
                output.write(buffer, 0, n);
            }
            return output.toByteArray();
        } catch (SecurityException e) {
            throw new IOException("读取权限已失效，请重新授权", e);
        }
    }

    private Uri uri(String id) {
        return managed(DocumentsContract.buildDocumentUriUsingTree(tree, id));
    }

    private static Uri managed(Uri uri) {
        return uri.buildUpon().appendQueryParameter("manage", "1").build();
    }

    private Entry findBackup(Entry entry) throws IOException {
        for (Entry candidate : children(directoryId)) {
            if (candidate.name.equals(BACKUP_PREFIX + entry.name)) return candidate;
        }
        return null;
    }

    private final class Document implements VerifiedReplacement.Document {
        private String id;
        private final Entry backupOf;

        Document(String id) { this.id = id; backupOf = null; }
        Document(Entry entry) { backupOf = entry; }

        @Override public byte[] read() throws IOException {
            if (id == null && backupOf != null) {
                Entry found = findBackup(backupOf);
                if (found == null) return null;
                id = found.id;
            }
            return ThemeStore.this.read(id, MAX_BYTES);
        }

        @Override public void write(byte[] bytes) throws IOException {
            try {
                if (id == null && backupOf != null) {
                    Entry existing = findBackup(backupOf);
                    if (existing != null) {
                        // Never overwrite a backup that appeared since it was checked.
                        throw new IOException("备份文件已变化，请刷新后重试");
                    }
                    Uri created = DocumentsContract.createDocument(resolver, uri(directoryId),
                            "application/octet-stream", BACKUP_PREFIX + backupOf.name);
                    if (created == null) throw new IOException("创建原图备份失败，未修改主题");
                    id = DocumentsContract.getDocumentId(created);
                }
                // rwt requests truncation: a smaller replacement must not retain an old trailing payload.
                try (OutputStream output = resolver.openOutputStream(uri(id), "rwt")) {
                    if (output == null) throw new IOException("无法打开目标文件进行写入");
                    output.write(bytes);
                    output.flush();
                }
            } catch (SecurityException | IllegalArgumentException e) {
                throw new IOException("写入被拒绝，请检查目录读写权限", e);
            }
        }
    }

    private static void requireImage(byte[] data) throws IOException {
        BitmapFactory.Options options = new BitmapFactory.Options();
        options.inJustDecodeBounds = true;
        BitmapFactory.decodeByteArray(data, 0, data.length, options);
        if (options.outWidth <= 0 || options.outHeight <= 0) throw new IOException("图片或原图备份无效，未执行写入");
    }

    private static String stem(String name) {
        int dot = name.lastIndexOf('.');
        return dot > 0 ? name.substring(0, dot) : name;
    }

    private static String metadataValue(String text, String group) throws Exception {
        XmlPullParser parser = Xml.newPullParser();
        parser.setInput(new StringReader(text));
        int groupDepth = -1;
        String first = "";
        for (int event = parser.next(); event != XmlPullParser.END_DOCUMENT; event = parser.next()) {
            if (event == XmlPullParser.START_TAG && parser.getName().equals(group)) groupDepth = parser.getDepth();
            else if (groupDepth > 0 && event == XmlPullParser.START_TAG && parser.getDepth() == groupDepth + 1) {
                String language = parser.getAttributeValue(null, "locale");
                if (language == null) language = parser.getAttributeValue(null, "language");
                String value = parser.nextText().trim();
                if (first.isEmpty()) first = value;
                if (language != null && language.startsWith("zh")) return value;
            } else if (groupDepth > 0 && event == XmlPullParser.END_TAG && parser.getDepth() == groupDepth) break;
        }
        return first;
    }
}
