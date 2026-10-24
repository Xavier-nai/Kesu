package com.xavier.kesu;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.ImageDecoder;
import android.net.Uri;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;

final class Images {
    static Bitmap decode(Context context, Uri uri, int maxSide) throws IOException {
        return decode(ImageDecoder.createSource(context.getContentResolver(), uri), maxSide);
    }

    static Bitmap decode(byte[] bytes, int maxSide) throws IOException {
        return decode(ImageDecoder.createSource(ByteBuffer.wrap(bytes)), maxSide);
    }

    private static Bitmap decode(ImageDecoder.Source source, int maxSide) throws IOException {
        return ImageDecoder.decodeBitmap(source,
                (decoder, info, imageSource) -> {
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
