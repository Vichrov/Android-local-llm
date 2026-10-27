package com.vichrov.localai;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.os.SystemClock;
import android.view.View;

final class EyesView extends View {
    enum Mode { IDLE, LISTENING, THINKING, SPEAKING, ERROR }
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private Mode mode = Mode.IDLE;
    private float warmth;
    private final long start = SystemClock.uptimeMillis();
    EyesView(Context context) { super(context); setContentDescription("Анимированные глаза ассистента"); }
    void mode(Mode value) { mode = value; invalidate(); }
    void response(String text) {
        String s = text.toLowerCase(java.util.Locale.ROOT);
        warmth = s.contains("!") || s.contains("рад") || s.contains("отлично") ? 1 : s.contains("жаль") || s.contains("ошиб") ? -0.5f : 0;
    }
    protected void onDraw(Canvas c) {
        super.onDraw(c);
        float t = (SystemClock.uptimeMillis() - start) / 1000f;
        float w = getWidth(), h = getHeight();
        float blinkPhase = t % 4.9f;
        float blink = blinkPhase < .18f ? Math.max(.08f, Math.abs(blinkPhase - .09f) / .09f) : 1;
        float breathe = 1 + .045f * (float)Math.sin(t * 2);
        float eyeW = w * .21f, eyeH = Math.min(h * .36f, w * .18f) * breathe;
        float offset = mode == Mode.THINKING ? (float)Math.sin(t * 2) * 10 : (float)Math.sin(t * .6f) * 3;
        if (mode == Mode.LISTENING) eyeH *= 1.15f;
        if (mode == Mode.SPEAKING) eyeH *= 1 + .12f * (float)Math.sin(t * 11);
        eyeH *= blink * (1 - warmth * .13f);
        int color = mode == Mode.ERROR ? Color.rgb(255,142,139) : mode == Mode.THINKING ? Color.rgb(167,160,255) : Color.rgb(115,234,213);
        paint.setColor(color);
        for (int i=0; i<2; i++) {
            float x = w * (i == 0 ? .35f : .65f) + offset;
            float y = h * .5f + (mode == Mode.THINKING ? (i == 0 ? -5 : 5) : 0);
            paint.setAlpha(18);
            c.drawRoundRect(x-eyeW/2-8, y-eyeH/2-8, x+eyeW/2+8,y+eyeH/2+8,25,25,paint);
            paint.setAlpha(255);
            c.drawRoundRect(x-eyeW/2,y-eyeH/2,x+eyeW/2,y+eyeH/2,Math.min(22, eyeH/3),Math.min(22, eyeH/3),paint);
        }
        if (isShown()) postInvalidateDelayed(33);
    }
}
