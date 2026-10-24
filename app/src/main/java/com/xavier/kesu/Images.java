package com.xavier.kesu;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.ImageDecoder;
import android.net.Uri;

import java.io.ByteArrayOutputStream;
import java.io.IOException;

final class Images {
    static Bitmap decode(Context context, Uri uri, int maxSide) throws IOException {
        return ImageDecoder.decodeBitmap(ImageDecoder.createSource(context.getContentResolver(), uri),
                (decoder, info, source) -> {
                    decoder.setAllocator(ImageDecoder.ALLOCATOR_SOFTWARE);
                    int width = info.getSize().getWidth();
                    int height = info.getSize().getHeight();
                    float ratio = Math.min(1f, maxSide / (float) Math.max(width, height));
                    decoder.setTargetSize(Math.max(1, Math.round(width * ratio)), Math.max(1, Math.round(height * ratio)));
                });
    }

    static byte[] jpeg(Bitmap bitmap) throws IOException {
        try (ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            if (!bitmap.compress(Bitmap.CompressFormat.JPEG, 95, output)) throw new IOException("图片编码失败");
            return output.toByteArray();
        }
    }

    private Images() {}
}
