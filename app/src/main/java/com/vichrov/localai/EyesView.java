package com.vichrov.localai;

import android.content.Context;
import android.graphics.*;
import android.os.SystemClock;
import android.provider.Settings;
import android.view.View;
import java.util.Random;

/** Two rounded rectangles only: expressions come from shape, placement and light. */
final class EyesView extends View {
    enum Mode { IDLE, LISTENING, THINKING, SPEAKING, ERROR }
    private final Paint paint=new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF rect=new RectF();
    private final Random random=new Random();
    private Mode mode=Mode.IDLE;
    private float mood, targetMood, inputLevel, level, height=1, spread=1, gazeX, gazeY;
    private float red=115, green=234, blue=213;
    private long previous=SystemClock.uptimeMillis(), nextBlink=previous+2600, blinkStart=-1, nextGaze=previous+1700;
    private float targetX, targetY;
    private boolean immersive;
    EyesView(Context context) { super(context); setContentDescription("Глаза ассистента"); }
    void immersive(boolean value) { immersive=value; invalidate(); }
    void mode(Mode value) { mode=value; invalidate(); }
    void level(float value) { inputLevel=value; }
    void response(String text) {
        String s=text.toLowerCase(java.util.Locale.ROOT);
        targetMood=s.contains("!") || s.contains("рад") || s.contains("отлично") ? 1 : s.contains("жаль") || s.contains("ошиб") ? -.5f : 0;
    }
    private float toward(float from,float to,float blend) { return from+(to-from)*blend; }
    @Override protected void onDraw(Canvas c) {
        super.onDraw(c);
        long now=SystemClock.uptimeMillis();
        float dt=Math.min(.08f,(now-previous)/1000f); previous=now;
        boolean motion=Settings.Global.getFloat(getContext().getContentResolver(),Settings.Global.ANIMATOR_DURATION_SCALE,1)>0;
        float blend=motion?1-(float)Math.exp(-dt*9):1;
        float t=now/1000f;
        if(now>=nextGaze) { targetX=(random.nextFloat()-.5f)*.07f; targetY=(random.nextFloat()-.5f)*.025f; nextGaze=now+1800+random.nextInt(2600); }
        gazeX=toward(gazeX,mode==Mode.LISTENING?0:targetX,blend*.3f);
        gazeY=toward(gazeY,targetY,blend*.3f);
        level=toward(level,inputLevel,blend); mood=toward(mood,targetMood,blend*.5f);
        float targetH=mode==Mode.LISTENING?1.13f+level*.17f:mode==Mode.THINKING?.8f:mode==Mode.ERROR?.66f:1-mood*.12f;
        height=toward(height,targetH,blend); spread=toward(spread,mode==Mode.LISTENING?1.06f:1,blend);
        int color=mode==Mode.ERROR?0xffff999f:mode==Mode.THINKING?0xffb4a0ff:mode==Mode.LISTENING?0xff85eaff:0xff73ead5;
        red=toward(red,Color.red(color),blend); green=toward(green,Color.green(color),blend); blue=toward(blue,Color.blue(color),blend);
        float blink=1;
        if(motion) {
            if(now>=nextBlink) { blinkStart=now; nextBlink=now+2700+random.nextInt(3600); }
            if(blinkStart>=0 && now-blinkStart<180) blink=Math.max(.07f,Math.abs((now-blinkStart)-90)/90f);
        }
        float face=Math.min(getWidth(),getHeight()*(immersive?1.55f:2.2f));
        float eyeW=face*.235f, eyeH=face*.27f*height*blink;
        if(motion) {
            eyeH*=1+.025f*(float)Math.sin(t*1.8);
            if(mode==Mode.SPEAKING) eyeH*=1+.065f*(float)Math.sin(t*10)+.035f*(float)Math.sin(t*17);
        }
        float centerX=getWidth()/2f+(motion?gazeX*face:0), centerY=getHeight()/2f+(motion?gazeY*face:0);
        paint.setColor(Color.rgb((int)red,(int)green,(int)blue));
        for(int i=0;i<2;i++) {
            float x=centerX+(i==0?-1:1)*face*.163f*spread;
            float offset=mode==Mode.THINKING && motion ? (i==0?-1:1)*face*.012f*(float)Math.sin(t*2):0;
            float y=centerY+offset, radius=Math.min(eyeW*.24f,eyeH*.33f);
            for(int layer=3;layer>0;layer--) {
                float halo=face*.008f*layer;
                rect.set(x-eyeW/2-halo,y-eyeH/2-halo,x+eyeW/2+halo,y+eyeH/2+halo);
                paint.setAlpha(5+layer*2); c.drawRoundRect(rect,radius+halo,radius+halo,paint);
            }
            paint.setAlpha(255); rect.set(x-eyeW/2,y-eyeH/2,x+eyeW/2,y+eyeH/2); c.drawRoundRect(rect,radius,radius,paint);
        }
        if(isShown() && motion) postInvalidateDelayed(mode==Mode.IDLE?33:16);
    }
}
