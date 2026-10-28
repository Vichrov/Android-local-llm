package com.vichrov.localai;

import android.app.DownloadManager;
import android.content.*;
import android.database.Cursor;
import android.net.Uri;
import android.os.Environment;
import java.io.*;
import java.security.MessageDigest;
import java.util.Locale;
import java.util.concurrent.*;
import org.apache.commons.compress.archivers.tar.*;
import org.apache.commons.compress.compressors.bzip2.BZip2CompressorInputStream;

/** Download/install state for the optional quantized Piper Russian voice. */
final class PiperVoiceStore {
    static final String URL="https://github.com/k2-fsa/sherpa-onnx/releases/download/tts-models/vits-piper-ru_RU-dmitri-medium-int8.tar.bz2";
    static final String SHA256="7636793307f634ce54c6e65528a91a61683114f1a6635a08caf64ba6c54e6a63";
    static final long ARCHIVE_BYTES=21_129_441L;
    private static final String KEY="piper_dmitri_medium_int8";
    private final Context context;
    private final DownloadManager dm;
    private final SharedPreferences prefs;
    private final File root, archive, model;
    private final ExecutorService installer=Executors.newSingleThreadExecutor();
    private volatile boolean installing;
    private volatile String error="";

    PiperVoiceStore(Context c) {
        context=c.getApplicationContext(); dm=(DownloadManager)c.getSystemService(Context.DOWNLOAD_SERVICE);
        prefs=c.getSharedPreferences("piper_voice",Context.MODE_PRIVATE);
        root=new File(c.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS),"tts");
        archive=new File(root,"piper-dmitri.part"); model=new File(root,"piper-dmitri-int8");
        if(!root.exists()) root.mkdirs();
    }
    boolean installed() { return new File(model,".verified").isFile() && modelFile()!=null && tokensFile()!=null; }
    boolean busy() { return installing || prefs.contains(KEY); }
    File modelFile() { return find(model,".onnx"); }
    File tokensFile() { return findNamed(model,"tokens.txt"); }
    void download(boolean metered) throws IOException {
        if(installed() || busy()) return;
        if(root.getUsableSpace()<100_000_000L) throw new IOException("Для Piper нужно около 100 МБ свободного места на установку.");
        error=""; archive.delete();
        DownloadManager.Request r=new DownloadManager.Request(Uri.parse(URL))
            .setTitle("Local Eyes · Piper · Дмитрий")
            .setDescription("Русский локальный голос · INT8")
            .setAllowedOverMetered(metered).setAllowedOverRoaming(false)
            .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
            .setDestinationInExternalFilesDir(context,Environment.DIRECTORY_DOWNLOADS,"tts/piper-dmitri.part");
        prefs.edit().putLong(KEY,dm.enqueue(r)).apply();
    }
    String status() {
        if(installed()) { clearDownload(); return "Скачан · Piper Дмитрий INT8 · SHA-256 проверен"; }
        if(installing) return "Проверка SHA-256 и установка Piper…";
        long id=prefs.getLong(KEY,-1); if(id<0) return error.isEmpty()?"Не скачан · архив 21 МБ":error;
        try(Cursor c=dm.query(new DownloadManager.Query().setFilterById(id))) {
            if(c==null || !c.moveToFirst()) { prefs.edit().remove(KEY).apply(); return "Загрузка Piper удалена системой — повторите"; }
            int s=c.getInt(c.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS));
            long done=c.getLong(c.getColumnIndexOrThrow(DownloadManager.COLUMN_BYTES_DOWNLOADED_SO_FAR));
            if(s==DownloadManager.STATUS_SUCCESSFUL) {
                installing=true; installer.execute(()->install(id)); return "Проверка SHA-256…";
            }
            if(s==DownloadManager.STATUS_FAILED) {
                error="Ошибка загрузки Piper ("+c.getInt(c.getColumnIndexOrThrow(DownloadManager.COLUMN_REASON))+")";
                dm.remove(id); prefs.edit().remove(KEY).apply(); return error;
            }
            return (s==DownloadManager.STATUS_PAUSED?"Piper: ожидание сети · ":"Piper: загрузка · ")+format(done)+" / 21 МБ";
        } catch(Exception e) { return "Piper: "+e.getMessage(); }
    }
    void remove() throws IOException {
        long id=prefs.getLong(KEY,-1); if(id>=0) dm.remove(id); prefs.edit().remove(KEY).apply();
        deleteTree(model); archive.delete(); error="";
    }
    private void clearDownload() { long id=prefs.getLong(KEY,-1); if(id>=0) dm.remove(id); prefs.edit().remove(KEY).apply(); }
    private void install(long id) {
        File temp=new File(root,"piper-dmitri.unpack");
        try {
            if(!SHA256.equals(hash(archive))) throw new IOException("Контрольная сумма Piper не совпала. Скачайте голос снова.");
            deleteTree(temp); if(!temp.mkdirs()) throw new IOException("Не удалось создать папку Piper");
            unpack(archive,temp);
            if(find(temp,".onnx")==null || findNamed(temp,"tokens.txt")==null) throw new IOException("В архиве Piper нет model/tokens");
            new File(temp,".verified").createNewFile(); deleteTree(model);
            if(!temp.renameTo(model)) throw new IOException("Не удалось установить Piper");
            archive.delete(); error=""; clearDownload();
        } catch(Exception e) {
            error="Piper: "+e.getMessage(); clearDownload(); try { deleteTree(temp); } catch(Exception ignored) {}
        } finally { installing=false; }
    }
    private static void unpack(File src,File dest) throws IOException {
        long total=0; int entries=0; byte[] buf=new byte[65536]; String base=dest.getCanonicalPath()+File.separator;
        try(InputStream raw=new BufferedInputStream(new FileInputStream(src)); InputStream bz=new BZip2CompressorInputStream(raw); TarArchiveInputStream in=new TarArchiveInputStream(bz)) {
            TarArchiveEntry e;
            while((e=in.getNextTarEntry())!=null) {
                if(++entries>2000 || e.isSymbolicLink() || e.isLink() || (!e.isFile()&&!e.isDirectory())) throw new IOException("Недопустимый элемент архива Piper");
                File out=new File(dest,e.getName()); String path=out.getCanonicalPath();
                if(!path.startsWith(base)) throw new IOException("Небезопасный путь архива Piper");
                if(e.isDirectory()) { if(!out.exists()&&!out.mkdirs()) throw new IOException("Не удалось создать папку"); continue; }
                if(e.getSize()<0 || total+e.getSize()>120_000_000L) throw new IOException("Архив Piper превышает лимит");
                if(!out.getParentFile().exists()&&!out.getParentFile().mkdirs()) throw new IOException("Не удалось создать папку");
                try(OutputStream os=new BufferedOutputStream(new FileOutputStream(out))) { int n; while((n=in.read(buf))!=-1) { total+=n; if(total>120_000_000L) throw new IOException("Архив Piper превышает лимит"); os.write(buf,0,n); } }
            }
        }
    }
    private static String hash(File f) throws Exception { MessageDigest d=MessageDigest.getInstance("SHA-256"); try(InputStream in=new BufferedInputStream(new FileInputStream(f))) { byte[] b=new byte[1<<20]; int n; while((n=in.read(b))!=-1)d.update(b,0,n); } StringBuilder s=new StringBuilder(); for(byte b:d.digest())s.append(String.format(Locale.ROOT,"%02x",b&255)); return s.toString(); }
    private static File find(File d,String suffix) { if(d==null||!d.exists())return null; File[] a=d.listFiles(); if(a==null)return null; for(File f:a) { if(f.isFile()&&f.getName().endsWith(suffix))return f; if(f.isDirectory()){File x=find(f,suffix);if(x!=null)return x;} } return null; }
    private static File findNamed(File d,String name) { if(d==null||!d.exists())return null; File[] a=d.listFiles(); if(a==null)return null; for(File f:a) { if(f.isFile()&&f.getName().equals(name))return f; if(f.isDirectory()){File x=findNamed(f,name);if(x!=null)return x;} } return null; }
    private static void deleteTree(File f) throws IOException { if(!f.exists())return; if(f.isDirectory()){File[] a=f.listFiles(); if(a!=null) for(File x:a) deleteTree(x);} if(!f.delete()) throw new IOException("Не удалось удалить "+f.getName()); }
    private static String format(long n) { return String.format(Locale.ROOT,"%.1f МБ",n/1048576.0); }
}
