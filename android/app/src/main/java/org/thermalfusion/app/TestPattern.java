package org.thermalfusion.app;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;

/** Graphic for testing PNG save only, with a permanent in-pixel disclosure. */
final class TestPattern {
    private TestPattern() {}
    static Bitmap create() {
        Bitmap image = Bitmap.createBitmap(960, 640, Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(image);
        Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        canvas.drawColor(Color.rgb(20, 28, 39));
        int[] colors = {0xff29378c, 0xff346da6, 0xff39a6ac, 0xffd7c35b, 0xffe8793e, 0xffc94a62};
        for (int y = 0; y < 6; y++) {
            for (int x = 0; x < 10; x++) {
                paint.setColor(colors[(x + y) % colors.length]);
                canvas.drawRect(24 + x * 92, 130 + y * 64, 108 + x * 92, 186 + y * 64, paint);
            }
        }
        paint.setColor(Color.WHITE);
        paint.setTextSize(43);
        paint.setFakeBoldText(true);
        canvas.drawText("SYNTHETIC TEST PATTERN", 26, 64, paint);
        paint.setFakeBoldText(false);
        paint.setTextSize(25);
        canvas.drawText("Not a thermal capture / No measured temperatures", 26, 104, paint);
        canvas.drawText("ThermalFusion - image saving test only", 26, 572, paint);
        canvas.drawText("No vendor SDK or hardware connected", 26, 610, paint);
        return image;
    }
}
