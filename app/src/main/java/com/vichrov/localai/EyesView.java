package com.vichrov.localai;

import android.content.Context;
import android.graphics.*;
import android.os.SystemClock;
import android.provider.Settings;
import android.view.Choreographer;
import android.view.View;
import java.util.Random;

/** Minimal living eyes: two rounded shapes, no pupils/face assets, vsync-driven animation. */
final class EyesView extends View implements Choreographer.FrameCallback {
    enum Mode { IDLE, LISTENING, HEARD, LOADING_MODEL, THINKING, SPEAKING, ERROR, SLEEP }

    private final Paint paint=new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF rect=new RectF();
    private final Random random=new Random();
    private final Choreographer choreographer=Choreographer.getInstance();

    private Mode mode=Mode.IDLE;
    private float mood, targetMood, inputLevel, level, height=1, spread=1, gazeX, gazeY;
    private float red=77, green=139, blue=255;
    private float targetX, targetY;
    private long previous=SystemClock.uptimeMillis();
    private long nextBlink=previous+2400, blinkStart=-1, nextGaze=previous+1500, nextMotionCheck;
    private boolean secondBlink;
    private boolean immersive, attached, frameScheduled, motion=true;

    EyesView(Context context) {
        super(context);
        setContentDescription("Глаза ассистента");
        setLayerType(View.LAYER_TYPE_HARDWARE,null);
        refreshMotion();
    }

    void immersive(boolean value) { immersive=value; ensureFrame(); invalidate(); }
    void mode(Mode value) {
        if(value==null) value=Mode.IDLE;
        if(mode!=value) { mode=value; ensureFrame(); invalidate(); }
    }
    void level(float value) { inputLevel=Math.max(0,Math.min(1,value)); ensureFrame(); }
    void response(String text) {
        String s=text.toLowerCase(java.util.Locale.ROOT);
        targetMood=s.contains("!") || s.contains("рад") || s.contains("отлично") || s.contains("готов") ? 1f :
            s.contains("жаль") || s.contains("ошиб") || s.contains("не удалось") ? -.55f : 0f;
        ensureFrame();
    }

    private void refreshMotion() {
        motion=Settings.Global.getFloat(getContext().getContentResolver(),Settings.Global.ANIMATOR_DURATION_SCALE,1f)>0f;
        nextMotionCheck=SystemClock.uptimeMillis()+2000;
    }
    private boolean canAnimate() { return attached && isShown() && getWindowVisibility()==VISIBLE && motion; }
    private long frameDelay() {
        if(mode==Mode.IDLE) return 33;       // ~30 FPS: enough for blink and micro-saccades.
        if(mode==Mode.SLEEP) return 100;     // very slow breathing only.
        return 16;                           // active states target display vsync / ~60 FPS.
    }
    private void ensureFrame() {
        if(!frameScheduled && canAnimate()) {
            frameScheduled=true;
            choreographer.postFrameCallback(this);
        }
    }
    private void stopFrames() {
        if(frameScheduled) choreographer.removeFrameCallback(this);
        frameScheduled=false;
    }
    @Override public void doFrame(long frameTimeNanos) {
        frameScheduled=false;
        if(!canAnimate()) return;
        invalidate();
        frameScheduled=true;
        choreographer.postFrameCallbackDelayed(this,frameDelay());
    }
    @Override protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        attached=true; previous=SystemClock.uptimeMillis(); refreshMotion(); ensureFrame();
    }
    @Override protected void onDetachedFromWindow() {
        attached=false; stopFrames(); super.onDetachedFromWindow();
    }
    @Override protected void onWindowVisibilityChanged(int visibility) {
        super.onWindowVisibilityChanged(visibility);
        if(visibility==VISIBLE) { refreshMotion(); ensureFrame(); } else stopFrames();
    }

    private float toward(float from,float to,float blend) { return from+(to-from)*Math.max(0,Math.min(1,blend)); }

    @Override protected void onDraw(Canvas c) {
        super.onDraw(c);
        long now=SystemClock.uptimeMillis();
        if(now>=nextMotionCheck) refreshMotion();
        float dt=Math.min(.08f,Math.max(.001f,(now-previous)/1000f)); previous=now;
        float blend=motion?1-(float)Math.exp(-dt*9f):1f;
        float t=now/1000f;

        if(now>=nextGaze) {
            float rangeX=mode==Mode.LISTENING?.028f:.065f;
            float rangeY=mode==Mode.LISTENING?.012f:.024f;
            targetX=(random.nextFloat()-.5f)*rangeX;
            targetY=(random.nextFloat()-.5f)*rangeY;
            nextGaze=now+1500+random.nextInt(2700);
        }
        gazeX=toward(gazeX,targetX,blend*.28f);
        gazeY=toward(gazeY,targetY,blend*.28f);
        level=toward(level,inputLevel,blend);
        mood=toward(mood,targetMood,blend*.45f);

        float targetH;
        switch(mode) {
            case LISTENING: targetH=1.10f+level*.20f; break;
            case HEARD: targetH=1.18f; break;
            case LOADING_MODEL: targetH=.72f; break;
            case THINKING: targetH=.80f; break;
            case ERROR: targetH=.62f; break;
            case SLEEP: targetH=.18f; break;
            default: targetH=1f-mood*.10f;
        }
        height=toward(height,targetH,blend);
        spread=toward(spread,mode==Mode.LISTENING?1.055f:mode==Mode.HEARD?1.08f:1f,blend);

        int color;
        if(mode==Mode.ERROR) color=0xffff7f8b;
        else if(mode==Mode.THINKING || mode==Mode.LOADING_MODEL) color=getContext().getColor(R.color.eye_think);
        else if(mode==Mode.LISTENING || mode==Mode.HEARD) color=getContext().getColor(R.color.eye_listen);
        else color=getContext().getColor(R.color.eye_idle);
        red=toward(red,Color.red(color),blend); green=toward(green,Color.green(color),blend); blue=toward(blue,Color.blue(color),blend);

        float blink=1f;
        if(motion && mode!=Mode.SLEEP) {
            if(now>=nextBlink) {
                blinkStart=now;
                boolean makeDouble=random.nextInt(5)==0;
                secondBlink=makeDouble;
                nextBlink=now+(makeDouble?360:2600+random.nextInt(3600));
            }
            long age=now-blinkStart;
            if(age>=0 && age<170) blink=Math.max(.06f,Math.abs(age-85)/85f);
            else if(secondBlink && age>=220 && age<360) blink=Math.max(.08f,Math.abs((age-220)-70)/70f);
        }

        float face=Math.min(getWidth(),getHeight()*(immersive?1.55f:2.2f));
        float eyeW=face*.235f;
        float eyeH=face*.27f*height*blink;
        if(motion) {
            float breathe=mode==Mode.SLEEP?.012f:.020f;
            eyeH*=1+breathe*(float)Math.sin(t*1.7f);
            if(mode==Mode.SPEAKING) eyeH*=1+.055f*(float)Math.sin(t*9.5f)+.025f*(float)Math.sin(t*15.5f);
            if(mode==Mode.LOADING_MODEL) eyeH*=1+.065f*(float)Math.sin(t*3.2f);
        }
        float centerX=getWidth()/2f+(motion?gazeX*face:0);
        float centerY=getHeight()/2f+(motion?gazeY*face:0);
        paint.setColor(Color.rgb((int)red,(int)green,(int)blue));

        for(int i=0;i<2;i++) {
            float x=centerX+(i==0?-1:1)*face*.163f*spread;
            float offset=mode==Mode.THINKING && motion ? (i==0?-1:1)*face*.010f*(float)Math.sin(t*2.1f):0;
            float y=centerY+offset;
            float radius=Math.min(eyeW*.24f,Math.max(1,eyeH*.33f));
            // One subtle halo keeps Flyme-like softness without expensive blur effects.
            float halo=face*.010f;
            rect.set(x-eyeW/2-halo,y-eyeH/2-halo,x+eyeW/2+halo,y+eyeH/2+halo);
            paint.setAlpha(14); c.drawRoundRect(rect,radius+halo,radius+halo,paint);
            paint.setAlpha(255);
            rect.set(x-eyeW/2,y-eyeH/2,x+eyeW/2,y+eyeH/2);
            c.drawRoundRect(rect,radius,radius,paint);
        }
    }
}
