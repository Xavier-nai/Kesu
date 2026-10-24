package com.xavier.kesu;

import android.app.Activity;
import android.app.AlertDialog;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.RectF;
import android.os.Bundle;
import android.util.AtomicFile;
import android.view.GestureDetector;
import android.view.MotionEvent;
import android.view.ScaleGestureDetector;
import android.view.View;
import android.view.WindowInsets;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.io.File;
import java.io.FileOutputStream;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public final class CropActivity extends Activity {
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private CropView crop;
    private Button confirm;
    private int outWidth;
    private int outHeight;
    private boolean saving;
    private TextView hint;

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        getOnBackInvokedDispatcher().registerOnBackInvokedCallback(android.window.OnBackInvokedDispatcher.PRIORITY_DEFAULT, () -> {
            if (!saving) finish();
        });
        Rect screen = getWindowManager().getMaximumWindowMetrics().getBounds();
        float factor = Math.min(1f, 4096f / Math.max(screen.width(), screen.height()));
        outWidth = Math.round(screen.width() * factor);
        outHeight = Math.round(screen.height() * factor);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(Color.rgb(20, 25, 23));
        root.setOnApplyWindowInsetsListener((v, insets) -> {
            android.graphics.Insets bars = insets.getInsets(WindowInsets.Type.systemBars() | WindowInsets.Type.displayCutout());
            v.setPadding(bars.left, bars.top, bars.right, bars.bottom);
            return insets;
        });
        TextView title = new TextView(this);
        title.setText("裁剪壁纸");
        title.setTextColor(Color.WHITE);
        title.setTextSize(22);
        title.setPadding(dp(20), dp(20), dp(20), dp(8));
        root.addView(title);
        hint = new TextView(this);
        hint.setText("拖动调整位置，双指缩放，双击复位");
        hint.setTextColor(0xFFCED9D2);
        hint.setPadding(dp(20), 0, dp(20), dp(12));
        root.addView(hint);
        crop = new CropView(state == null ? null : state.getFloatArray("transform"));
        root.addView(crop, new LinearLayout.LayoutParams(-1, 0, 1));
        LinearLayout actions = new LinearLayout(this);
        actions.setPadding(dp(16), dp(8), dp(16), dp(12));
        Button cancel = new Button(this);
        cancel.setText("取消");
        cancel.setOnClickListener(v -> { if (!saving) finish(); });
        confirm = new Button(this);
        confirm.setText("使用这张图片");
        confirm.setEnabled(false);
        confirm.setOnClickListener(v -> save());
        actions.addView(cancel, new LinearLayout.LayoutParams(0, -2, 1));
        actions.addView(confirm, new LinearLayout.LayoutParams(0, -2, 1));
        root.addView(actions);
        setContentView(root);
        worker.execute(() -> {
            try {
                if (getIntent().getData() == null) throw new java.io.IOException("没有收到图片");
                Bitmap bitmap = Images.decode(this, getIntent().getData(), 4096);
                runOnUiThread(() -> {
                    if (isDestroyed()) return;
                    crop.bitmap = bitmap;
                    crop.layoutImage();
                    confirm.setEnabled(true);
                });
            } catch (Exception e) {
                runOnUiThread(() -> {
                    if (!isDestroyed()) new AlertDialog.Builder(this).setMessage("图片读取失败：" + e.getMessage())
                            .setPositiveButton("返回", (d, w) -> finish()).setCancelable(false).show();
                });
            }
        });
    }

    private void save() {
        if (crop.bitmap == null || saving) return;
        saving = true;
        setRequestedOrientation(android.content.pm.ActivityInfo.SCREEN_ORIENTATION_LOCKED);
        crop.setEnabled(false);
        confirm.setEnabled(false);
        hint.setText("正在保存裁剪结果…");
        // Freeze the transform on the UI thread before encoding off-thread.
        Matrix matrix = new Matrix(crop.matrix);
        RectF frame = new RectF(crop.frame);
        Bitmap source = crop.bitmap;
        worker.execute(() -> {
            AtomicFile file = new AtomicFile(new File(getCacheDir(), "cropped.jpg"));
            FileOutputStream output = null;
            Bitmap result = null;
            try {
                result = Bitmap.createBitmap(outWidth, outHeight, Bitmap.Config.ARGB_8888);
                Canvas canvas = new Canvas(result);
                canvas.drawColor(Color.BLACK);
                canvas.scale(outWidth / frame.width(), outHeight / frame.height());
                canvas.translate(-frame.left, -frame.top);
                canvas.drawBitmap(source, matrix, new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG));
                output = file.startWrite();
                if (!result.compress(Bitmap.CompressFormat.JPEG, 95, output)) throw new java.io.IOException("图片编码失败");
                file.finishWrite(output);
                output = null;
                runOnUiThread(() -> { setResult(RESULT_OK); finish(); });
            } catch (Exception e) {
                if (output != null) file.failWrite(output);
                runOnUiThread(() -> {
                    if (isDestroyed()) return;
                    saving = false;
                    setRequestedOrientation(android.content.pm.ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED);
                    crop.setEnabled(true);
                    confirm.setEnabled(true);
                    hint.setText("拖动调整位置，双指缩放，双击复位");
                    new AlertDialog.Builder(this).setMessage("裁剪失败：" + e.getMessage()).setPositiveButton("知道了", null).show();
                });
            } finally {
                if (result != null) result.recycle();
            }
        });
    }

    @Override protected void onSaveInstanceState(Bundle state) {
        super.onSaveInstanceState(state);
        state.putFloatArray("transform", crop.transform());
    }

    @Override protected void onDestroy() {
        worker.shutdown();
        super.onDestroy();
    }

    private int dp(int value) { return Math.round(value * getResources().getDisplayMetrics().density); }

    private final class CropView extends View {
        Bitmap bitmap;
        final Matrix matrix = new Matrix();
        final RectF frame = new RectF();
        final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
        final ScaleGestureDetector pinch;
        final GestureDetector gestures;
        float scale;
        float minimum;
        float cx;
        float cy;
        float[] restored;
        boolean tapDetected;

        CropView(float[] restored) {
            super(CropActivity.this);
            this.restored = restored;
            setContentDescription("壁纸裁剪区域，可拖动和双指缩放，双击复位");
            pinch = new ScaleGestureDetector(getContext(), new ScaleGestureDetector.SimpleOnScaleGestureListener() {
                @Override public boolean onScale(ScaleGestureDetector detector) {
                    if (bitmap == null) return false;
                    float next = Math.max(minimum, Math.min(minimum * 6, scale * detector.getScaleFactor()));
                    float ratio = next / scale;
                    cx = detector.getFocusX() + (cx - detector.getFocusX()) * ratio;
                    cy = detector.getFocusY() + (cy - detector.getFocusY()) * ratio;
                    scale = next;
                    clamp();
                    return true;
                }
            });
            gestures = new GestureDetector(getContext(), new GestureDetector.SimpleOnGestureListener() {
                @Override public boolean onDown(MotionEvent e) { return true; }
                @Override public boolean onScroll(MotionEvent first, MotionEvent current, float dx, float dy) {
                    if (bitmap == null || pinch.isInProgress() || current.getPointerCount() != 1) return true;
                    cx -= dx;
                    cy -= dy;
                    clamp();
                    return true;
                }
                @Override public boolean onDoubleTap(MotionEvent e) { reset(); return true; }
                @Override public boolean onSingleTapUp(MotionEvent e) { tapDetected = true; return true; }
            });
        }

        @Override protected void onSizeChanged(int w, int h, int oldw, int oldh) { layoutImage(); }

        void layoutImage() {
            if (bitmap == null || getWidth() == 0 || getHeight() == 0) return;
            float fit = Math.min((getWidth() - dp(32)) / (float) outWidth, (getHeight() - dp(24)) / (float) outHeight);
            float width = outWidth * fit;
            float height = outHeight * fit;
            frame.set((getWidth() - width) / 2, (getHeight() - height) / 2,
                    (getWidth() + width) / 2, (getHeight() + height) / 2);
            minimum = Math.max(frame.width() / bitmap.getWidth(), frame.height() / bitmap.getHeight());
            reset();
            if (restored != null && restored.length == 3) {
                scale = Math.max(minimum, Math.min(minimum * 6, minimum * restored[0]));
                cx = frame.centerX() + restored[1] * frame.width();
                cy = frame.centerY() + restored[2] * frame.height();
                restored = null;
                clamp();
            }
        }

        void reset() { scale = minimum; cx = frame.centerX(); cy = frame.centerY(); clamp(); }

        void clamp() {
            if (bitmap == null || minimum == 0) return;
            float hw = bitmap.getWidth() * scale / 2;
            float hh = bitmap.getHeight() * scale / 2;
            cx = Math.max(frame.right - hw, Math.min(frame.left + hw, cx));
            cy = Math.max(frame.bottom - hh, Math.min(frame.top + hh, cy));
            matrix.setScale(scale, scale);
            matrix.postTranslate(cx - hw, cy - hh);
            invalidate();
        }

        float[] transform() {
            if (minimum == 0 || frame.isEmpty()) return restored;
            return new float[]{scale / minimum, (cx - frame.centerX()) / frame.width(), (cy - frame.centerY()) / frame.height()};
        }

        @Override protected void onDraw(Canvas canvas) {
            super.onDraw(canvas);
            if (bitmap == null) return;
            canvas.save();
            canvas.clipRect(frame);
            paint.setStyle(Paint.Style.FILL);
            canvas.drawBitmap(bitmap, matrix, paint);
            canvas.restore();
            paint.setColor(0xFFCED9D2);
            paint.setStyle(Paint.Style.STROKE);
            paint.setStrokeWidth(dp(1));
            canvas.drawRect(frame, paint);
        }

        @Override public boolean onTouchEvent(MotionEvent event) {
            if (!isEnabled()) return true;
            pinch.onTouchEvent(event);
            tapDetected = false;
            gestures.onTouchEvent(event);
            if (tapDetected) performClick();
            return true;
        }

        @Override public boolean performClick() { super.performClick(); return true; }
    }
}
