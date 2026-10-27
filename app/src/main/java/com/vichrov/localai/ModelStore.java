package com.vichrov.localai;

import android.app.DownloadManager;
import android.content.Context;
import android.content.SharedPreferences;
import android.database.Cursor;
import android.net.Uri;
import android.os.Environment;
import java.io.*;
import java.security.MessageDigest;
import java.util.*;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

final class ModelStore {
    static final class Entry {
        final String id, name, url, hash, note;
        final long bytes;
        final int ramGb;
        final boolean asr;
        Entry(String id, String name, String url, String hash, long bytes, int ram, boolean asr, String note) {
            this.id=id; this.name=name; this.url=url; this.hash=hash; this.bytes=bytes; ramGb=ram; this.asr=asr; this.note=note;
        }
    }
    static final String REV2 = "f6d5376be1edb4d416d56da11e5397a961aca8ae";
    static final String REV4 = "e87f176479d0855a907a41277aca2f8ee7a09523";
    static String url(String size, String rev, String quant) { return "https://huggingface.co/unsloth/Qwen3.5-"+size+"-GGUF/resolve/"+rev+"/Qwen3.5-"+size+"-"+quant+".gguf"; }
    static final Entry[] CATALOG = {
        new Entry("qwen2q4", "Qwen3.5 · 2B · Q4", url("2B", REV2, "Q4_K_M"), "aaf42c8b7c3cab2bf3d69c355048d4a0ee9973d48f16c731c0520ee914699223",1280835840L,6,false,"Лёгкий вариант для первого запуска. Apache 2.0."),
        new Entry("qwen2q8", "Qwen3.5 · 2B · Q8", url("2B", REV2, "Q8_0"), "1b04acba824817554f4ce23639bc8495ff70453b8fcb047900c731521021f2c1",2012012800L,8,false,"Выше точность весов; больше памяти и нагрузка. Apache 2.0."),
        new Entry("qwen4q6", "Qwen3.5 · 4B · Q6", url("4B", REV4, "Q6_K"), "fdedd781c9ce676ab66b018ca247ff78e8a33c98098a822c1e2d5075e7718f66",3525956768L,12,false,"Тяжёлый вариант. На Snapdragon 855 ответы могут быть медленными. Apache 2.0."),
        new Entry("voskru", "Русская речь · Vosk", "https://alphacephei.com/vosk/models/vosk-model-small-ru-0.22.zip", "961d5ff98a17f4aa6de69864d0aa71fa5bac682301d2b5d17a3f24c5c99a46d4",46236750L,0,true,"Офлайн-распознавание русского языка. Apache 2.0.")
    };
    private final Context context;
    private final DownloadManager dm;
    private final SharedPreferences prefs;
    private final File root;
    private final ExecutorService verifier = Executors.newSingleThreadExecutor();
    private final Set<String> verifying = Collections.synchronizedSet(new HashSet<>());
    private final Map<String,String> errors = Collections.synchronizedMap(new HashMap<>());
    private static ModelStore instance;
    static synchronized ModelStore get(Context c) { if(instance==null) instance=new ModelStore(c.getApplicationContext()); return instance; }
    private ModelStore(Context c) {
        context = c.getApplicationContext();
        dm = (DownloadManager)c.getSystemService(Context.DOWNLOAD_SERVICE);
        prefs = c.getSharedPreferences("downloads", Context.MODE_PRIVATE);
        root = new File(c.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS), "models");
        if (!root.exists() && !root.mkdirs()) throw new IllegalStateException("Хранилище моделей недоступно");
    }
    File file(Entry e) { return new File(root, e.id + (e.asr ? "" : ".gguf")); }
    private File part(Entry e) { return new File(root, e.id + ".part"); }
    boolean installed(Entry e) { return e.asr ? new File(file(e), "am/final.mdl").isFile() && new File(file(e), ".verified").isFile() : file(e).isFile() && file(e).length()==e.bytes; }
    boolean busy(Entry e) { return verifying.contains(e.id) || prefs.contains(e.id); }
    void download(Entry e, boolean metered) throws IOException {
        if (busy(e) || installed(e)) return;
        long required = e.asr ? 180_000_000L : e.bytes + 100_000_000L;
        if (root.getUsableSpace() < required) throw new IOException("Недостаточно свободного места: нужно " + format(required));
        errors.remove(e.id);
        part(e).delete();
        DownloadManager.Request request = new DownloadManager.Request(Uri.parse(e.url))
                .setTitle("Local Eyes · " + e.name).setDescription("Загрузка офлайн-модели")
                .setAllowedOverMetered(metered).setAllowedOverRoaming(false)
                .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
                .setDestinationInExternalFilesDir(context, Environment.DIRECTORY_DOWNLOADS, "models/"+e.id+".part");
        long id = dm.enqueue(request);
        prefs.edit().putLong(e.id, id).apply();
    }
    String status(Entry e) {
        if (installed(e)) {
            if (!verifying.contains(e.id) && prefs.contains(e.id)) {
                dm.remove(prefs.getLong(e.id,-1)); prefs.edit().remove(e.id).apply();
            }
            return "Скачана · SHA-256 проверен";
        }
        if (verifying.contains(e.id)) return "Проверка SHA-256 и установка…";
        long id = prefs.getLong(e.id, -1);
        if (id < 0) return errors.getOrDefault(e.id, "Не скачана");
        try (Cursor c = dm.query(new DownloadManager.Query().setFilterById(id))) {
            if (c == null || !c.moveToFirst()) { prefs.edit().remove(e.id).apply(); return "Загрузка удалена системой — повторите"; }
            int s = c.getInt(c.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS));
            long done = c.getLong(c.getColumnIndexOrThrow(DownloadManager.COLUMN_BYTES_DOWNLOADED_SO_FAR));
            if (s == DownloadManager.STATUS_SUCCESSFUL) {
                verifying.add(e.id);
                verifier.execute(() -> install(e, id));
                return "Проверка SHA-256…";
            }
            if (s == DownloadManager.STATUS_FAILED) {
                String error = "Ошибка загрузки ("+c.getInt(c.getColumnIndexOrThrow(DownloadManager.COLUMN_REASON))+"). Повторите.";
                errors.put(e.id,error); dm.remove(id); prefs.edit().remove(e.id).apply(); return error;
            }
            return (s == DownloadManager.STATUS_PAUSED ? "Пауза / ожидание сети · " : "Загрузка · ")+ format(done) + " / " + format(e.bytes);
        }
    }
    private void install(Entry e, long id) {
        File unpack = new File(root, e.id+".unpack");
        try {
            MessageDigest hash = MessageDigest.getInstance("SHA-256");
            try (InputStream in = new BufferedInputStream(new FileInputStream(part(e)))) {
                byte[] b = new byte[1024*1024]; int n;
                while ((n=in.read(b))!=-1) hash.update(b,0,n);
            }
            StringBuilder hex=new StringBuilder(); for(byte b:hash.digest()) hex.append(String.format(Locale.ROOT,"%02x", b & 255));
            if (!e.hash.equals(hex.toString())) throw new IOException("Контрольная сумма не совпала. Скачайте снова.");
            if (e.asr) {
                deleteTree(unpack); unpack.mkdirs();
                unzip(part(e), unpack);
                File extracted = new File(unpack,"vosk-model-small-ru-0.22");
                if (!new File(extracted,"am/final.mdl").isFile()) throw new IOException("Неполная модель речи");
                new File(extracted,".verified").createNewFile();
                deleteTree(file(e));
                if (!extracted.renameTo(file(e))) throw new IOException("Не удалось установить модель речи");
                deleteTree(unpack);
            } else if (!part(e).renameTo(file(e))) throw new IOException("Не удалось сохранить модель");
            dm.remove(id); // LLM has already moved to its final path.
        } catch (Exception ex) {
            errors.put(e.id, "Ошибка: " + ex.getMessage());
            dm.remove(id); deleteTree(unpack);
        } finally { prefs.edit().remove(e.id).apply(); verifying.remove(e.id); }
    }
    static void unzip(File zip, File dest) throws IOException {
        long total = 0; int entries = 0;
        try (ZipInputStream in = new ZipInputStream(new BufferedInputStream(new FileInputStream(zip)))) {
            ZipEntry entry; byte[] b = new byte[65536];
            while ((entry=in.getNextEntry())!=null) {
                if (++entries > 10000) throw new IOException("Слишком много файлов в архиве");
                File out = new File(dest,entry.getName());
                if (!out.getCanonicalPath().startsWith(dest.getCanonicalPath()+File.separator)) throw new IOException("Небезопасный путь архива");
                if (entry.isDirectory()) { out.mkdirs(); continue; }
                out.getParentFile().mkdirs();
                try (OutputStream os = new FileOutputStream(out)) {
                    int n; while((n=in.read(b))!=-1) {
                        total += n; if (total > 200_000_000L) throw new IOException("Архив превышает лимит"); os.write(b,0,n);
                    }
                }
            }
        }
    }
    void remove(Entry e) throws IOException {
        if (verifying.contains(e.id)) throw new IOException("Дождитесь завершения проверки");
        long id = prefs.getLong(e.id,-1); if(id>=0) dm.remove(id);
        prefs.edit().remove(e.id).apply(); errors.remove(e.id);
        deleteTree(file(e)); part(e).delete();
    }
    static void deleteTree(File f) throws IOException {
        if (f.isDirectory()) { File[] files=f.listFiles(); if(files!=null) for(File child:files) deleteTree(child); }
        if(f.exists() && !f.delete()) throw new IOException("Не удалось удалить " + f.getName());
    }
    static String format(long bytes) { return bytes >= 1_000_000_000L ? String.format(Locale.ROOT,"%.2f ГБ",bytes/1e9) : String.format(Locale.ROOT,"%.1f МБ",bytes/1e6); }

}
