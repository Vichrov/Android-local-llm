package com.vichrov.localai;

import android.content.*;
import android.os.Environment;
import android.view.*;
import android.widget.*;
import androidx.test.platform.app.InstrumentationRegistry;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import org.junit.Test;
import org.junit.runner.RunWith;
import java.io.*;
import java.nio.*;
import java.nio.file.Files;
import java.util.Arrays;
import static org.junit.Assert.*;

@RunWith(AndroidJUnit4.class)
public class GigaAndroidTest {
    private Context context() { return InstrumentationRegistry.getInstrumentation().getTargetContext(); }
    private File root() { return new File(context().getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS),"models"); }
    private GigaDecoder open() { return new GigaDecoder(new File(root(),"gigaam").getPath(),new File(root(),"silerovad.onnx").getPath()); }
    private short[] speech() throws Exception {
        byte[] bytes=Files.readAllBytes(new File(root(),"gigaam/example.wav").toPath());
        ByteBuffer b=ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN);
        assertEquals(16000,b.getInt(24)); assertEquals(1,b.getShort(22));
        short[] pcm=new short[(bytes.length-44)/2]; b.position(44); b.asShortBuffer().get(pcm); return pcm;
    }
    private String feed(StreamingSpeech processor,short[] pcm) {
        StringBuilder out=new StringBuilder();
        for(int i=0;i<pcm.length;i+=1280) {
            short[] frame=Arrays.copyOfRange(pcm,i,Math.min(i+1280,pcm.length));
            String text=processor.accept(frame,frame.length).text;
            if(!text.isEmpty()) out.append(text).append(' ');
        }
        return out.toString().trim();
    }
    @Test public void realRussianNormalAndQuietAndSilence() throws Exception {
        short[] normal=speech();
        try(GigaDecoder decoder=open()) {
            StreamingSpeech processor=new StreamingSpeech(decoder);
            String text=feed(processor,Arrays.copyOf(normal,normal.length+32000));
            assertTrue("Normal Russian: "+text,text.contains("похвал"));
            int peak=1; for(short x:normal) peak=Math.max(peak,Math.abs((int)x));
            short[] quiet=new short[normal.length+32000];
            for(int i=0;i<normal.length;i++) quiet[i]=(short)(normal[i]*180.0/peak);
            processor.reset(); text=feed(processor,quiet);
            assertTrue("Quiet Russian: "+text,text.contains("похвал"));
            processor.reset(); assertEquals("",feed(processor,new short[160000]));
            feed(processor,Arrays.copyOf(normal,16000));
            processor.reset(); assertEquals("Reset must discard an unfinished phrase","",feed(processor,new short[160000]));
        }
    }
    @Test public void gigaBundleRequiresVad() throws Exception {
        ModelStore store=ModelStore.get(context()); ModelStore.Entry giga=null;
        for(ModelStore.Entry e:ModelStore.CATALOG) if(e.id.equals("gigaam")) giga=e;
        assertNotNull(giga); assertTrue(store.installed(giga));
        File vad=store.file(ModelStore.SILERO), away=new File(vad.getPath()+".test");
        assertTrue(vad.renameTo(away));
        try { assertFalse(store.installed(giga)); }
        finally { assertTrue(away.renameTo(vad)); }
        assertTrue(store.installed(giga));
    }
    private TextView find(View v,String label) {
        if(v instanceof TextView && label.contentEquals(((TextView)v).getText())) return (TextView)v;
        if(v instanceof ViewGroup) { ViewGroup group=(ViewGroup)v; for(int i=0;i<group.getChildCount();i++) { TextView result=find(group.getChildAt(i),label); if(result!=null)return result; } }
        return null;
    }
    @Test public void selectGigaInSettingsAndKeepChoice() {
        var instrumentation=InstrumentationRegistry.getInstrumentation();
        SharedPreferences prefs=context().getSharedPreferences("settings",Context.MODE_PRIVATE);
        prefs.edit().putString("asr","voskru").commit();
        MainActivity activity=(MainActivity)instrumentation.startActivitySync(new Intent(context(),MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        try {
            instrumentation.runOnMainSync(()-> {
                TextView models=find(activity.getWindow().getDecorView(),"Модели"); assertNotNull(models); models.performClick();
                TextView title=find(activity.getWindow().getDecorView(),"Русская речь · GigaAM v3 INT8"); assertNotNull(title);
                TextView choose=find((View)title.getParent(),"Выбрать"); assertNotNull(choose); choose.performClick();
                assertEquals("gigaam",prefs.getString("asr",""));
                assertNotNull(find((View)title.getParent(),"✓ Выбрана"));
            });
        } finally { instrumentation.runOnMainSync(activity::finish); }
        instrumentation.waitForIdleSync();
        MainActivity next=(MainActivity)instrumentation.startActivitySync(new Intent(context(),MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        try {
            assertEquals("gigaam",prefs.getString("asr",""));
            instrumentation.runOnMainSync(()-> { find(next.getWindow().getDecorView(),"Модели").performClick(); assertNotNull(find(next.getWindow().getDecorView(),"Распознавание речи · GigaAM")); });
        } finally { instrumentation.runOnMainSync(next::finish); }
    }
}
