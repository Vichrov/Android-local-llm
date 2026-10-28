package com.vichrov.localai;

import android.media.AudioFormat;
import java.util.*;

/**
 * Lightweight software echo guard for local TTS/ASR duplex operation.
 *
 * Android AEC is still enabled when available, but is not trusted as the only
 * protection. We also keep the exact TTS text plus PCM energy reported by
 * UtteranceProgressListener.onAudioAvailable(). ASR hypotheses that strongly
 * match the assistant's currently playing/recent speech are rejected instead
 * of being sent back to the LLM. User barge-in with different words remains
 * available while TTS is speaking.
 */
final class PlaybackEchoGuard {
    private static final long TAIL_MS = 900;
    private static final int MAX_TEXT = 4096;
    private static final Set<String> BARGE_COMMANDS = new HashSet<>(Arrays.asList(
            "стоп", "хватит", "подожди", "погоди", "тише", "замолчи", "отмена", "прервись"));

    private static final class Utterance {
        final String text;
        int encoding = AudioFormat.ENCODING_PCM_16BIT;
        int channels = 1;
        boolean referenced;
        Utterance(String text) { this.text = text; }
    }

    private final Map<String,Utterance> queued = new HashMap<>();
    private final Set<String> active = new HashSet<>();
    private final StringBuilder recent = new StringBuilder();
    private long tailUntil;
    private float referenceRms;

    synchronized void queued(String id,String text) {
        queued.put(id,new Utterance(text==null?"":text));
    }

    synchronized void format(String id,int encoding,int channels) {
        Utterance u=queued.get(id);
        if(u!=null) { u.encoding=encoding; u.channels=Math.max(1,channels); }
    }

    synchronized void started(String id) {
        Utterance u=queued.get(id);
        if(u!=null) referenceText(u);
        active.add(id);
        tailUntil=System.currentTimeMillis()+TAIL_MS;
    }

    synchronized void audio(String id,byte[] audio) {
        Utterance u=queued.get(id);
        if(u!=null) referenceText(u);
        active.add(id);
        referenceRms=rms(audio,u==null?AudioFormat.ENCODING_PCM_16BIT:u.encoding);
        tailUntil=System.currentTimeMillis()+TAIL_MS;
    }

    synchronized void done(String id) {
        active.remove(id);
        queued.remove(id);
        tailUntil=System.currentTimeMillis()+TAIL_MS;
    }

    synchronized void stopped() {
        active.clear();
        queued.clear();
        tailUntil=System.currentTimeMillis()+TAIL_MS;
    }

    synchronized boolean protectedNow() {
        return !active.isEmpty() || System.currentTimeMillis()<tailUntil;
    }

    synchronized float referenceRms() { return referenceRms; }

    synchronized boolean looksLikeEcho(String hypothesis) {
        if(!protectedNow()) return false;
        String heard=normalize(hypothesis);
        if(heard.isEmpty()) return false;
        String first=heard.indexOf(' ')<0?heard:heard.substring(0,heard.indexOf(' '));
        if(BARGE_COMMANDS.contains(first)) return false;
        String ref=normalize(recent.toString());
        if(ref.isEmpty()) return false;

        String[] h=heard.split(" ");
        if((heard.length()>=8 || h.length>=2) && ref.contains(heard)) return true;

        Set<String> refWords=new HashSet<>(Arrays.asList(ref.split(" ")));
        int meaningful=0,shared=0;
        for(String w:h) {
            if(w.length()<2) continue;
            meaningful++;
            if(refWords.contains(w)) shared++;
        }
        if(meaningful>=2 && shared>=2 && shared/(float)meaningful>=0.72f) return true;

        // A single long word is only considered echo while PCM reference energy
        // is actually present; this avoids suppressing normal one-word commands.
        return h.length==1 && heard.length()>=7 && referenceRms>350f && refWords.contains(heard);
    }

    private void referenceText(Utterance u) {
        if(u.referenced) return;
        u.referenced=true;
        String n=normalize(u.text);
        if(n.isEmpty()) return;
        if(recent.length()>0) recent.append(' ');
        recent.append(n);
        if(recent.length()>MAX_TEXT) recent.delete(0,recent.length()-MAX_TEXT);
    }

    static String normalize(String s) {
        if(s==null) return "";
        StringBuilder out=new StringBuilder(s.length());
        boolean space=true;
        for(int i=0;i<s.length();i++) {
            char c=Character.toLowerCase(s.charAt(i));
            if(c=='ё') c='е';
            if(Character.isLetterOrDigit(c)) { out.append(c); space=false; }
            else if(!space) { out.append(' '); space=true; }
        }
        int n=out.length();
        while(n>0 && out.charAt(n-1)==' ') { out.setLength(--n); }
        return out.toString();
    }

    private static float rms(byte[] audio,int encoding) {
        if(audio==null || audio.length==0) return 0;
        if(encoding==AudioFormat.ENCODING_PCM_8BIT) {
            double sum=0;
            for(byte b:audio) { int v=(b&0xff)-128; sum+=(double)v*v; }
            return (float)Math.sqrt(sum/audio.length)*256f;
        }
        // Android TTS engines normally report PCM 16-bit here. Unknown formats
        // are intentionally treated as 16-bit reference energy rather than
        // disabling the guard.
        int samples=audio.length/2;
        if(samples==0) return 0;
        double sum=0;
        for(int i=0;i+1<audio.length;i+=2) {
            short v=(short)((audio[i]&0xff)|(audio[i+1]<<8));
            sum+=(double)v*v;
        }
        return (float)Math.sqrt(sum/samples);
    }
}
