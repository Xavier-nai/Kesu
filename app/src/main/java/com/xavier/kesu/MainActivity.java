package com.xavier.kesu;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.WallpaperManager;
import android.content.ContentValues;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.ActivityInfo;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Bundle;
import android.provider.MediaStore;
import android.util.AtomicFile;
import android.view.Gravity;
import android.view.View;
import android.view.WindowInsets;
import android.widget.Button;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Consumer;

public final class MainActivity extends Activity {
    private static final int AUTHORIZE = 10, PHOTO = 11, CROP = 12;
    private static final int GREEN = 0xFF356B58, INK = 0xFF24362E, MUTED = 0xFF66756C;
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private SharedPreferences prefs;
    private LinearLayout body;
    private int page;
    private boolean busy;
    private String operation = "";
    private boolean pickerActive;
    private int pendingPage;
    private ThemeStore.Entry pendingEntry;
    private String selectedId = "";
    private final List<ThemeStore.Entry> entries = new ArrayList<>();
    private Bitmap desktopPreview;

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        prefs = getSharedPreferences("wallpaper", MODE_PRIVATE);
        getOnBackInvokedDispatcher().registerOnBackInvokedCallback(android.window.OnBackInvokedDispatcher.PRIORITY_DEFAULT, () -> {
            if (busy) { message("正在" + operation + "，请稍候"); return; }
            if (page == 1) { page = 0; render(); } else finish();
        });
        if (state != null) {
            page = state.getInt("page");
            selectedId = state.getString("selected", "");
            pickerActive = state.getBoolean("picker");
            pendingPage = state.getInt("pending_page");
            if (state.containsKey("pending_id")) {
                pendingEntry = new ThemeStore.Entry(state.getString("pending_id"), state.getString("pending_name"),
                        state.getString("pending_mime"), state.getLong("pending_flags"));
                pendingEntry.title = state.getString("pending_title");
            }
        }
        render();
        if (desktopFile().exists()) {
            worker.execute(() -> {
                try {
                    Bitmap preview = Images.decode(this, Uri.fromFile(desktopFile()), 800);
                    runOnUiThread(() -> { if (!isDestroyed()) { desktopPreview = preview; render(); } });
                } catch (IOException ignored) { /* A new picture can replace an unreadable local selection. */ }
            });
        }
        if (page == 1 && !pickerActive && !treeValue().isEmpty()) refresh();
    }

    private void render() {
        if (isDestroyed()) return;
        LinearLayout root = column();
        root.setBackgroundColor(0xFFF5F6F2);
        root.setOnApplyWindowInsetsListener((v, insets) -> {
            android.graphics.Insets bars = insets.getInsets(WindowInsets.Type.systemBars() | WindowInsets.Type.displayCutout());
            v.setPadding(bars.left, bars.top, bars.right, bars.bottom);
            return insets;
        });
        LinearLayout heading = column();
        heading.setPadding(dp(24), dp(24), dp(24), dp(12));
        heading.addView(text("Kesu", 30, INK, true));
        heading.addView(text("让喜欢的图片，留在每天亮屏的地方。", 14, MUTED, false));
        root.addView(heading);
        LinearLayout tabs = new LinearLayout(this);
        tabs.setPadding(dp(20), 0, dp(20), dp(8));
        for (int i = 0; i < 2; i++) {
            int target = i;
            Button tab = button(i == 0 ? "桌面壁纸" : "锁屏壁纸", () -> {
                page = target;
                render();
                if (page == 1 && entries.isEmpty() && !treeValue().isEmpty()) refresh();
            }, page == i);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, dp(50), 1);
            lp.setMargins(dp(4), 0, dp(4), 0);
            tabs.addView(tab, lp);
        }
        root.addView(tabs);
        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        body = column();
        body.setPadding(dp(24), dp(8), dp(24), dp(24));
        scroll.addView(body);
        root.addView(scroll, new LinearLayout.LayoutParams(-1, 0, 1));
        if (busy) body.addView(text(operation + "…", 15, GREEN, true));
        if (page == 0) desktopPage(); else lockPage();
        setContentView(root);
    }

    private void desktopPage() {
        LinearLayout card = card();
        card.addView(text("桌面壁纸", 21, INK, true));
        card.addView(text(desktopPreview == null ? "从相册选一张图片，裁剪后设为桌面壁纸。"
                : prefs.getBoolean("desktop_applied", false) ? "这张图片已通过 Kesu 应用到桌面。" : "已选图片，确认后应用到桌面。", 14, MUTED, false));
        if (desktopPreview != null) {
            ImageView image = new ImageView(this);
            image.setImageBitmap(desktopPreview);
            image.setScaleType(ImageView.ScaleType.FIT_CENTER);
            image.setContentDescription("已选桌面壁纸预览");
            LinearLayout.LayoutParams preview = new LinearLayout.LayoutParams(-1, dp(300));
            preview.setMargins(0, dp(16), 0, dp(12));
            card.addView(image, preview);
        } else {
            TextView empty = text("选择你的第一张壁纸", 18, MUTED, false);
            empty.setGravity(Gravity.CENTER);
            card.addView(empty, new LinearLayout.LayoutParams(-1, dp(240)));
        }
        addButton(card, desktopPreview == null ? "选择图片" : "更换图片", () -> choosePhoto(null), false);
        addButton(card, "读取当前桌面壁纸", this::readDesktop, false);
        if (desktopPreview != null) {
            addButton(card, "应用到桌面", this::applyDesktop, true);
            addButton(card, "保存图片到相册", () -> runTask("保存图片", () -> {
                export(Files.readAllBytes(desktopFile().toPath())); return true;
            }, ignored -> message("已保存到相册 Pictures/Kesu")), false);
        }
        body.addView(card);
    }

    private void lockPage() {
        LinearLayout explanation = card();
        explanation.addView(text("主题锁屏壁纸", 21, INK, true));
        explanation.addView(text("只替换主题背景图片。更换或恢复后，请到主题商店重新应用对应主题。", 14, MUTED, false));
        addButton(explanation, treeValue().isEmpty() ? "授权主题目录" : "重新授权目录", () -> authorize(false), false);
        addButton(explanation, "光锥兼容授权", () -> authorize(true), false);
        if (!treeValue().isEmpty()) addButton(explanation, "刷新壁纸列表", this::refresh, false);
        body.addView(explanation);

        ThemeStore.Entry selected = selected();
        if (selected != null) {
            LinearLayout actions = card();
            actions.addView(text(selected.title, 18, INK, true));
            actions.addView(text("首次替换前备份原图，每次写入后校验。", 13, MUTED, false));
            addButton(actions, "选择图片并替换", () -> choosePhoto(selected), true);
            addButton(actions, "恢复原图", () -> new AlertDialog.Builder(this)
                    .setTitle("恢复原图")
                    .setMessage("将「" + selected.title + "」恢复为首次替换前的图片，原图备份会保留。")
                    .setNegativeButton("取消", null).setPositiveButton("恢复", (d, w) -> mutate(selected, null)).show(), false);
            addButton(actions, "提取图片到相册", () -> {
                String tree = treeValue();
                runTask("提取图片", () -> {
                    export(new ThemeStore(getApplicationContext(), Uri.parse(tree)).extract(selected)); return true;
                }, ignored -> message("已保存到相册 Pictures/Kesu"));
            }, false);
            addButton(actions, "前往主题商店", this::openThemeManager, false);
            body.addView(actions);
        }
        TextView count = text(entries.isEmpty() ? "授权后，点击刷新读取主题壁纸。" : "主题壁纸 · " + entries.size(), 14, MUTED, false);
        count.setPadding(0, dp(8), 0, dp(12));
        body.addView(count);
        for (ThemeStore.Entry entry : entries) {
            LinearLayout row = new LinearLayout(this);
            row.setGravity(Gravity.CENTER_VERTICAL);
            row.setPadding(dp(12), dp(12), dp(12), dp(12));
            row.setBackground(shape(entry.id.equals(selectedId) ? 0xFFE4EDE3 : Color.WHITE, 16));
            row.setEnabled(!busy);
            ImageView image = new ImageView(this);
            image.setImageBitmap(entry.thumbnail);
            image.setScaleType(ImageView.ScaleType.CENTER_CROP);
            image.setContentDescription(entry.title + " 缩略图");
            row.addView(image, new LinearLayout.LayoutParams(dp(64), dp(88)));
            LinearLayout label = column();
            label.setPadding(dp(16), 0, 0, 0);
            label.addView(text(entry.title, 16, INK, true));
            if (!entry.designer.isEmpty()) label.addView(text(entry.designer, 12, MUTED, false));
            label.addView(text(entry.thumbnail == null ? "图片无法预览，可尝试恢复原图"
                    : entry.id.equals(selectedId) ? "已选择" : "点击选择此主题", 12, MUTED, false));
            row.addView(label, new LinearLayout.LayoutParams(0, -2, 1));
            row.setContentDescription(entry.title + (entry.id.equals(selectedId) ? "，已选择" : "，点击选择"));
            row.setOnClickListener(v -> { if (!busy) { selectedId = entry.id; render(); } });
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
            lp.setMargins(0, 0, 0, dp(10));
            body.addView(row, lp);
        }
    }

    private void authorize(boolean compatibility) {
        try { startActivityForResult(ThemeStore.authorizationIntent(compatibility), AUTHORIZE); }
        catch (Exception e) { error("无法打开系统目录选择器：" + e.getMessage()); }
    }

    private void refresh() {
        String value = treeValue();
        if (value.isEmpty()) return;
        runTask("读取主题壁纸", () -> new ThemeStore(getApplicationContext(), Uri.parse(value)).list(), this::setEntries);
    }

    private void setEntries(List<ThemeStore.Entry> next) {
        entries.clear();
        entries.addAll(next);
        render();
        if (entries.isEmpty()) message("没有找到可读取的主题壁纸。请先下载并应用主题，再检查目录授权。");
    }

    private void choosePhoto(ThemeStore.Entry target) {
        pickerActive = true;
        pendingPage = target == null ? 0 : 1;
        pendingEntry = target;
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT).setType("image/*")
                .addCategory(Intent.CATEGORY_OPENABLE).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        try { startActivityForResult(intent, PHOTO); }
        catch (Exception e) { pickerActive = false; error("无法打开图片选择器：" + e.getMessage()); }
    }

    @Override protected void onActivityResult(int request, int result, Intent data) {
        super.onActivityResult(request, result, data);
        if (request == AUTHORIZE && result == RESULT_OK && data != null && data.getData() != null) {
            Uri tree = data.getData();
            int flags = data.getFlags() & (Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
            try {
                if (flags != (Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION)) {
                    throw new IOException("只获得了读取权限，请重新授权并允许写入");
                }
                getContentResolver().takePersistableUriPermission(tree,
                        Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
                runTask("检查主题目录", () -> new ThemeStore(getApplicationContext(), tree).list(), list -> {
                    prefs.edit().putString("tree", tree.toString()).apply();
                    selectedId = "";
                    setEntries(list);
                });
            } catch (Exception e) { error(e.getMessage()); }
        } else if (request == PHOTO) {
            if (result == RESULT_OK && data != null && data.getData() != null) {
                Intent crop = new Intent(this, CropActivity.class).setData(data.getData())
                        .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
                startActivityForResult(crop, CROP);
            } else { pickerActive = false; reloadAfterPicker(); }
        } else if (request == CROP) {
            pickerActive = false;
            if (result != RESULT_OK) { reloadAfterPicker(); return; }
            File cropped = new File(getCacheDir(), "cropped.jpg");
            if (pendingPage == 0) {
                runTask("保存已选图片", () -> {
                    byte[] bytes = Files.readAllBytes(cropped.toPath());
                    AtomicFile file = new AtomicFile(desktopFile());
                    FileOutputStream output = file.startWrite();
                    try { output.write(bytes); file.finishWrite(output); }
                    catch (Exception e) { file.failWrite(output); throw e; }
                    prefs.edit().putBoolean("desktop_applied", false).apply();
                    cropped.delete();
                    return Images.decode(getApplicationContext(), Uri.fromFile(desktopFile()), 800);
                }, preview -> { desktopPreview = preview; page = 0; render(); });
            } else if (pendingEntry != null) mutate(pendingEntry, cropped);
        }
    }

    private void reloadAfterPicker() {
        if (page == 1 && entries.isEmpty() && !treeValue().isEmpty()) refresh();
    }

    private void mutate(ThemeStore.Entry entry, File replacement) {
        String tree = treeValue();
        runTask(replacement == null ? "恢复原图" : "备份并替换壁纸", () -> {
            ThemeStore store = new ThemeStore(getApplicationContext(), Uri.parse(tree));
            if (replacement == null) store.restore(entry);
            else {
                try { store.replace(entry, Files.readAllBytes(replacement.toPath())); }
                finally { replacement.delete(); }
            }
            return true;
        }, ignored -> {
            refresh();
            new AlertDialog.Builder(this).setTitle(replacement == null ? "原图已恢复" : "壁纸已替换")
                    .setMessage("请在主题商店中重新应用「" + entry.title + "」，然后锁屏检查效果。")
                    .setNegativeButton("稍后", null).setPositiveButton("前往主题商店", (d, w) -> openThemeManager()).show();
        });
    }

    private void applyDesktop() {
        runTask("设置桌面壁纸", () -> {
            WallpaperManager manager = WallpaperManager.getInstance(getApplicationContext());
            if (!manager.isWallpaperSupported() || !manager.isSetWallpaperAllowed()) throw new IOException("系统不允许此应用设置壁纸");
            try (java.io.InputStream input = Files.newInputStream(desktopFile().toPath())) {
                int id = manager.setStream(input, null, true, WallpaperManager.FLAG_SYSTEM);
                if (id == 0) throw new IOException("系统没有确认设置成功");
            }
            prefs.edit().putBoolean("desktop_applied", true).apply();
            return true;
        }, ignored -> { render(); message("已设置桌面壁纸"); });
    }

    private void readDesktop() {
        runTask("读取当前桌面图片", () -> {
            String[] paths = {
                    "/data/system/theme_magic/users/0/wallpaper/image/home_wallpaper.jpg",
                    "/data/system/theme_magic/users/0/wallpaper/maml/home_wallpaper.jpg",
                    "/data/system/theme_magic/users/0/wallpaper/video/home_wallpaper_thumbnail.jpg"
            };
            Bitmap current = null;
            for (String path : paths) {
                File source = new File(path);
                if (!source.canRead()) continue;
                try { current = Images.decode(getApplicationContext(), Uri.fromFile(source), 4096); break; }
                catch (IOException ignored) { /* Try the next image location used by the reference APK. */ }
            }
            if (current == null) throw new IOException("系统未开放当前桌面图片的读取权限，可以从相册选择图片；动态壁纸可能没有可读取的静态图");
            byte[] bytes;
            try { bytes = Images.jpeg(current); } finally { current.recycle(); }
            AtomicFile file = new AtomicFile(desktopFile());
            FileOutputStream output = file.startWrite();
            try { output.write(bytes); file.finishWrite(output); }
            catch (Exception e) { file.failWrite(output); throw e; }
            prefs.edit().putBoolean("desktop_applied", false).apply();
            return Images.decode(getApplicationContext(), Uri.fromFile(desktopFile()), 800);
        }, preview -> { desktopPreview = preview; render(); message("已读取当前桌面图片，可保存到相册"); });
    }

    private void openThemeManager() {
        try {
            Intent launch = getPackageManager().getLaunchIntentForPackage("com.android.thememanager");
            if (launch == null) throw new IOException("没有找到小米主题商店，请手动打开主题应用");
            startActivity(launch);
        } catch (Exception e) { error(e.getMessage()); }
    }

    private void export(byte[] bytes) throws IOException {
        BitmapFactory.Options options = new BitmapFactory.Options();
        options.inJustDecodeBounds = true;
        BitmapFactory.decodeByteArray(bytes, 0, bytes.length, options);
        String mime = options.outMimeType;
        if (mime == null) throw new IOException("无法识别图片格式");
        String ext = switch (mime) { case "image/png" -> ".png"; case "image/webp" -> ".webp"; case "image/gif" -> ".gif"; default -> ".jpg"; };
        ContentValues values = new ContentValues();
        values.put(MediaStore.Images.Media.DISPLAY_NAME, "Kesu-" + System.currentTimeMillis() + ext);
        values.put(MediaStore.Images.Media.MIME_TYPE, mime);
        values.put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/Kesu");
        values.put(MediaStore.Images.Media.IS_PENDING, 1);
        Uri created = getContentResolver().insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values);
        if (created == null) throw new IOException("无法创建相册图片");
        try {
            try (OutputStream output = getContentResolver().openOutputStream(created)) {
                if (output == null) throw new IOException("无法写入相册");
                output.write(bytes);
            }
            ContentValues ready = new ContentValues();
            ready.put(MediaStore.Images.Media.IS_PENDING, 0);
            if (getContentResolver().update(created, ready, null, null) != 1) throw new IOException("相册图片发布失败");
        } catch (Exception e) {
            getContentResolver().delete(created, null, null);
            if (e instanceof IOException) throw (IOException) e;
            throw new IOException("保存图片失败", e);
        }
    }

    private <T> void runTask(String label, Callable<T> task, Consumer<T> success) {
        if (busy) return;
        busy = true;
        operation = label;
        setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_LOCKED);
        render();
        worker.execute(() -> {
            T value = null;
            Exception failure = null;
            try { value = task.call(); } catch (Exception e) { failure = e; }
            T result = value;
            Exception error = failure;
            runOnUiThread(() -> {
                if (isDestroyed()) return;
                busy = false;
                setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED);
                render();
                if (error == null) success.accept(result);
                else error(label + "失败：" + (error.getMessage() == null ? error.getClass().getSimpleName() : error.getMessage()));
            });
        });
    }

    private ThemeStore.Entry selected() {
        for (ThemeStore.Entry entry : entries) if (entry.id.equals(selectedId)) return entry;
        return null;
    }
    private File desktopFile() { return new File(getFilesDir(), "desktop.jpg"); }
    private String treeValue() { return prefs.getString("tree", ""); }
    private void message(String text) { android.widget.Toast.makeText(this, text, android.widget.Toast.LENGTH_LONG).show(); }
    private void error(String text) { new AlertDialog.Builder(this).setTitle("操作未完成").setMessage(text).setPositiveButton("知道了", null).show(); }

    @Override protected void onSaveInstanceState(Bundle state) {
        super.onSaveInstanceState(state);
        state.putInt("page", page);
        state.putString("selected", selectedId);
        state.putBoolean("picker", pickerActive);
        state.putInt("pending_page", pendingPage);
        if (pendingEntry != null) {
            state.putString("pending_id", pendingEntry.id);
            state.putString("pending_name", pendingEntry.name);
            state.putString("pending_mime", pendingEntry.mime);
            state.putString("pending_title", pendingEntry.title);
            state.putLong("pending_flags", pendingEntry.flags);
        }
    }

    @Override protected void onDestroy() { worker.shutdown(); super.onDestroy(); }

    private LinearLayout column() { LinearLayout layout = new LinearLayout(this); layout.setOrientation(LinearLayout.VERTICAL); return layout; }
    private LinearLayout card() {
        LinearLayout layout = column();
        layout.setPadding(dp(18), dp(20), dp(18), dp(16));
        layout.setBackground(shape(Color.WHITE, 20));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
        lp.setMargins(0, 0, 0, dp(16));
        layout.setLayoutParams(lp);
        return layout;
    }
    private TextView text(String value, int size, int color, boolean bold) {
        TextView view = new TextView(this);
        view.setText(value);
        view.setTextSize(size);
        view.setTextColor(color);
        view.setPadding(0, 0, 0, dp(6));
        view.setLineSpacing(dp(3), 1);
        if (bold) view.setTypeface(null, Typeface.BOLD);
        return view;
    }
    private Button button(String label, Runnable action, boolean primary) {
        Button button = new Button(this);
        button.setText(label);
        button.setTextSize(15);
        button.setAllCaps(false);
        button.setTextColor(primary ? Color.WHITE : INK);
        button.setBackground(shape(primary ? GREEN : 0xFFEDF1E9, 14));
        button.setMinHeight(dp(48));
        button.setEnabled(!busy);
        button.setAlpha(busy ? 0.55f : 1f);
        button.setOnClickListener(v -> action.run());
        return button;
    }
    private void addButton(LinearLayout layout, String label, Runnable action, boolean primary) {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, dp(50));
        lp.setMargins(0, dp(10), 0, 0);
        layout.addView(button(label, action, primary), lp);
    }
    private GradientDrawable shape(int color, int radius) {
        GradientDrawable drawable = new GradientDrawable();
        drawable.setColor(color);
        drawable.setCornerRadius(dp(radius));
        return drawable;
    }
    private int dp(int value) { return Math.round(value * getResources().getDisplayMetrics().density); }
}
