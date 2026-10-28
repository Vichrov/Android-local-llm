package com.vichrov.localai;

import android.media.AudioFormat;
import java.util.*;

/** Secondary safety net on top of native AEC3: rejects obvious ASR copies of current TTS. */
final class PlaybackEchoGuard {
    private static final long TAIL_MS=900;
    private static final int MAX_TEXT=4096;
    private static final Set<String> BARGE_COMMANDS=new HashSet<>(Arrays.asList("стоп","хватит","подожди","погоди","тише","замолчи","отмена","прервись"));
    private static final class Utterance { final String text; int encoding=AudioFormat.ENCODING_PCM_16BIT,channels=1; boolean referenced; Utterance(String t){text=t;} }
    private final Map<String,Utterance> queued=new HashMap<>();
    private final Set<String> active=new HashSet<>();
    private final StringBuilder recent=new StringBuilder();
    private long tailUntil; private float referenceRms;
    synchronized void queued(String id,String text){queued.put(id,new Utterance(text==null?"":text));}
    synchronized void format(String id,int encoding,int channels){Utterance u=queued.get(id);if(u!=null){u.encoding=encoding;u.channels=Math.max(1,channels);}}
    synchronized void started(String id){Utterance u=queued.get(id);if(u!=null)referenceText(u);active.add(id);tailUntil=System.currentTimeMillis()+TAIL_MS;}
    synchronized void audio(String id,byte[] audio){Utterance u=queued.get(id);if(u!=null)referenceText(u);referenceRms=rms(audio,u==null?AudioFormat.ENCODING_PCM_16BIT:u.encoding);}
    synchronized void audioPcm(String id,short[] audio){Utterance u=queued.get(id);if(u!=null)referenceText(u);double sum=0;for(short v:audio)sum+=(double)v*v;referenceRms=audio.length==0?0:(float)Math.sqrt(sum/audio.length);}
    synchronized void done(String id){active.remove(id);queued.remove(id);tailUntil=System.currentTimeMillis()+TAIL_MS;}
    synchronized void stopped(){active.clear();queued.clear();tailUntil=System.currentTimeMillis()+TAIL_MS;}
    synchronized boolean protectedNow(){return !active.isEmpty()||System.currentTimeMillis()<tailUntil;}
    synchronized boolean looksLikeEcho(String hypothesis){
        if(!protectedNow())return false;String heard=normalize(hypothesis);if(heard.isEmpty())return false;
        String first=heard.indexOf(' ')<0?heard:heard.substring(0,heard.indexOf(' '));if(BARGE_COMMANDS.contains(first))return false;
        String ref=normalize(recent.toString());if(ref.isEmpty())return false;String[] h=heard.split(" ");
        if((heard.length()>=8||h.length>=2)&&ref.contains(heard))return true;
        Set<String> rw=new HashSet<>(Arrays.asList(ref.split(" ")));int meaningful=0,shared=0;
        for(String w:h){if(w.length()<2)continue;meaningful++;if(rw.contains(w))shared++;}
        if(meaningful>=2&&shared>=2&&shared/(float)meaningful>=0.72f)return true;
        return h.length==1&&heard.length()>=7&&referenceRms>350f&&rw.contains(heard);
    }
    private void referenceText(Utterance u){if(u.referenced)return;u.referenced=true;String n=normalize(u.text);if(n.isEmpty())return;if(recent.length()>0)recent.append(' ');recent.append(n);if(recent.length()>MAX_TEXT)recent.delete(0,recent.length()-MAX_TEXT);}
    static String normalize(String s){if(s==null)return "";StringBuilder out=new StringBuilder(s.length());boolean space=true;for(int i=0;i<s.length();i++){char c=Character.toLowerCase(s.charAt(i));if(c=='ё')c='е';if(Character.isLetterOrDigit(c)){out.append(c);space=false;}else if(!space){out.append(' ');space=true;}}while(out.length()>0&&out.charAt(out.length()-1)==' ')out.setLength(out.length()-1);return out.toString();}
    private static float rms(byte[] a,int enc){if(a==null||a.length==0)return 0;if(enc==AudioFormat.ENCODING_PCM_8BIT){double s=0;for(byte b:a){int v=(b&255)-128;s+=(double)v*v;}return(float)Math.sqrt(s/a.length)*256f;}int n=a.length/2;if(n==0)return 0;double s=0;for(int i=0;i+1<a.length;i+=2){short v=(short)((a[i]&255)|(a[i+1]<<8));s+=(double)v*v;}return(float)Math.sqrt(s/n);}
}
