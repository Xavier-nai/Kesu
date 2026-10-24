package com.xavier.kesu;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.MatrixCursor;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.net.Uri;
import android.os.ParcelFileDescriptor;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.util.List;

/** Public, read-only access to the selected external-theme wallpaper, never arbitrary files. */
public final class WallpaperProvider extends ContentProvider {
    static final String AUTHORITY = "com.xavier.kesu.wallpaper";
    static final Uri CURRENT = Uri.parse("content://" + AUTHORITY + "/current");
    private static final Object LOCK = new Object();
    private static final String[] COLUMNS = {"image_uri", "version"};

    @Override public boolean onCreate() { return true; }

    private static SharedWallpaper store(Context context) {
        return new SharedWallpaper(new File(context.getFilesDir(), "shared-wallpaper"));
    }

    static void publish(Context context, byte[] jpeg) throws IOException {
        if (jpeg.length == 0 || jpeg.length > SharedWallpaper.MAX_BYTES) throw new IOException("壁纸为空或超过 32 MiB");
        BitmapFactory.Options bounds = new BitmapFactory.Options();
        bounds.inJustDecodeBounds = true;
        BitmapFactory.decodeByteArray(jpeg, 0, jpeg.length, bounds);
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) throw new IOException("壁纸图片无效");
        if (!"image/jpeg".equals(bounds.outMimeType) || bounds.outWidth > 4096 || bounds.outHeight > 4096) {
            Bitmap image = Images.decode(jpeg, 4096);
            try { jpeg = Images.jpeg(image); }
            finally { image.recycle(); }
        }
        synchronized (LOCK) { store(context).publish(jpeg); }
        context.getContentResolver().notifyChange(CURRENT, null, android.content.ContentResolver.NOTIFY_UPDATE);
    }

    private static boolean baseUri(Uri uri) {
        return "content".equals(uri.getScheme()) && AUTHORITY.equals(uri.getAuthority())
                && uri.getQuery() == null && uri.getFragment() == null;
    }

    private static String imageName(Uri uri) throws FileNotFoundException {
        List<String> segments = uri.getPathSegments();
        if (!baseUri(uri) || segments.size() != 2 || !"image".equals(segments.get(0))) {
            throw new FileNotFoundException("未知壁纸地址");
        }
        return segments.get(1);
    }

    @Override public Cursor query(Uri uri, String[] projection, String selection, String[] selectionArgs, String sortOrder) {
        if (!CURRENT.equals(uri)) throw new IllegalArgumentException("只能查询 /current");
        if ((selection != null && !selection.isEmpty()) || (selectionArgs != null && selectionArgs.length != 0)
                || (sortOrder != null && !sortOrder.isEmpty())) throw new IllegalArgumentException("不支持查询条件或排序");
        String[] columns = projection == null ? COLUMNS : projection;
        for (String column : columns) {
            if (!"image_uri".equals(column) && !"version".equals(column)) throw new IllegalArgumentException("未知列：" + column);
        }
        MatrixCursor result = new MatrixCursor(columns, 1);
        try {
            String name;
            synchronized (LOCK) { name = store(getContext()).current(); }
            MatrixCursor.RowBuilder row = result.newRow();
            for (String column : columns) row.add(name == null ? "" : "image_uri".equals(column)
                    ? new Uri.Builder().scheme("content").authority(AUTHORITY).appendPath("image").appendPath(name).build().toString()
                    : name.substring(0, name.length() - 4));
            result.setNotificationUri(getContext().getContentResolver(), CURRENT);
            return result;
        } catch (IOException e) {
            result.close();
            throw new IllegalStateException("无法读取共享壁纸", e);
        }
    }

    @Override public String getType(Uri uri) {
        if (CURRENT.equals(uri)) return "vnd.android.cursor.item/vnd.com.xavier.kesu.wallpaper";
        try {
            String name = imageName(uri);
            synchronized (LOCK) { store(getContext()).image(name); }
            return "image/jpeg";
        } catch (FileNotFoundException e) { throw new IllegalArgumentException("未知壁纸地址", e); }
    }

    @Override public ParcelFileDescriptor openFile(Uri uri, String mode) throws FileNotFoundException {
        if (!"r".equals(mode)) throw new FileNotFoundException("壁纸接口只允许读取");
        String name = imageName(uri);
        synchronized (LOCK) {
            return ParcelFileDescriptor.open(store(getContext()).image(name), ParcelFileDescriptor.MODE_READ_ONLY);
        }
    }

    @Override public Uri insert(Uri uri, ContentValues values) { throw new UnsupportedOperationException("只读接口"); }
    @Override public int update(Uri uri, ContentValues values, String selection, String[] args) { throw new UnsupportedOperationException("只读接口"); }
    @Override public int delete(Uri uri, String selection, String[] args) { throw new UnsupportedOperationException("只读接口"); }
}
