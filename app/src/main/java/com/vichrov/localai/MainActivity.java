package com.vichrov.localai;

import android.Manifest;
import android.app.*;
import android.content.*;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.content.res.ColorStateList;
import android.os.*;
import android.view.*;
import android.widget.*;
import org.json.JSONObject;
import org.vosk.Model;
import org.vosk.Recognizer;
import org.vosk.android.RecognitionListener;
import org.vosk.android.SpeechService;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;

public final class MainActivity extends Activity {
    private static final int BG=0xff080d16, PANEL=0xff131d2b, INK=0xffeef5f7, MUTED=0xff94a5b8, MINT=0xff73ead5;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private final ArrayDeque<String[]> history = new ArrayDeque<>();
    private final Map<String,TextView> downloadLabels = new HashMap<>();
    private final Map<String,Button[]> modelButtons = new HashMap<>();
    private ModelStore store;
    private NativeLlm llm;
    private VoiceOutput voice;
    private SharedPreferences prefs;
    private ModelStore.Entry selected;
    private EyesView eyes;
    private TextView stateText, answerText, info;
    private EditText input;
    private LinearLayout root;
    private boolean busy, listening, speaking, settings, metered, loadingVoice;
    private volatile boolean destroyed;
    private volatile int turn;
    private volatile String loadedId = "";
    private SpeechService speechService;
    private Recognizer recognizer;
    private Model speechModel;
    private final StringBuilder answer = new StringBuilder();
    private long started;
    private boolean fullMode, autoReady, hearing, foreground, focused=true, pendingFullPermission;
    private int fullSession;
    private long rearmAt;
    private HandsFreeVoice handsFree;


    @Override public void onCreate(Bundle saved) {
        super.onCreate(saved);
        prefs = getSharedPreferences("settings", MODE_PRIVATE);
        store = ModelStore.get(this);
        llm = new NativeLlm();
        selected = find(prefs.getString("selected", ""));
        if(selected != null && !store.installed(selected)) selected=null;
        voice = new VoiceOutput(this, new VoiceOutput.State() {
            public void speech(boolean value) { speaking=value; if(!value) rearmAt=SystemClock.uptimeMillis()+700; updateEyes(); }
            public void unavailable(String why) { if(!destroyed) { prefs.edit().putString("ttsStatus",why).apply(); if(fullMode) exitFull(); if(info!=null) info.setText(why); } }
        });
        voice.enabled(prefs.getBoolean("voice", true));
        if(selected==null) showSettings(); else showChat();
        main.post(poll);
    }
    private ModelStore.Entry find(String id) { for(ModelStore.Entry e:ModelStore.CATALOG) if(e.id.equals(id)) return e; return null; }
    private int dp(float v) { return (int)(getResources().getDisplayMetrics().density*v+.5f); }
    private TextView text(String s, int size, int color) {
        TextView t=new TextView(this); t.setText(s); t.setTextSize(size); t.setTextColor(color); t.setPadding(0,dp(5),0,dp(5)); return t;
    }
    private Button button(String s, Runnable action) {
        Button b=new Button(this); b.setText(s); b.setAllCaps(false); b.setTextColor(INK); b.setTextSize(14);
        b.setTypeface(null,Typeface.BOLD); b.setMinHeight(dp(48)); b.setPadding(dp(10),dp(10),dp(10),dp(10));
        GradientDrawable surface=new GradientDrawable(); surface.setColor(PANEL); surface.setCornerRadius(dp(16)); surface.setStroke(dp(1),0xff263345);
        b.setBackground(new RippleDrawable(ColorStateList.valueOf(0x3373ead5),surface,null));
        b.setOnClickListener(v -> action.run()); return b;
    }
    private void base() {
        root=new LinearLayout(this); root.setOrientation(LinearLayout.VERTICAL); root.setBackgroundColor(BG); root.setPadding(dp(20),dp(14),dp(20),dp(12));
        root.setOnApplyWindowInsetsListener((v,insets)-> { android.graphics.Insets i=insets.getInsets(WindowInsets.Type.systemBars() | WindowInsets.Type.ime()); root.setPadding(dp(20)+i.left,dp(12)+i.top,dp(20)+i.right,dp(12)+i.bottom); return insets; });
        setContentView(root); root.requestApplyInsets(); info=null;
    }
    private void showChat() {
        settings=false; downloadLabels.clear(); modelButtons.clear(); base();
        LinearLayout header=new LinearLayout(this); header.setGravity(Gravity.CENTER_VERTICAL);
        TextView title=text("local eyes",23,INK); title.setTypeface(null,Typeface.BOLD);
        header.addView(title,new LinearLayout.LayoutParams(0,-2,1)); header.addView(button("Модели",()-> { if(!busy && !listening && !loadingVoice) { voice.stop(); showSettings(); } else toast("Сначала остановите диалог"); })); root.addView(header);
        root.addView(text("ВАШ ЛОКАЛЬНЫЙ СОБЕСЕДНИК",10,MUTED));
        Button cinema=button("◉   Только глаза",this::enterFull); cinema.setTextColor(MINT);
        LinearLayout.LayoutParams cinemaParams=new LinearLayout.LayoutParams(-1,dp(52)); cinemaParams.setMargins(0,dp(12),0,0); root.addView(cinema,cinemaParams);
        eyes=new EyesView(this); root.addView(eyes,new LinearLayout.LayoutParams(-1,dp(140)));
        stateText=text("Готов к разговору",16,MINT); stateText.setGravity(Gravity.CENTER); root.addView(stateText);
        info=text(selected==null ? "Выберите модель" : selected.name+" · CPU · полностью локально",12,MUTED); info.setGravity(Gravity.CENTER); root.addView(info);
        ScrollView scroll=new ScrollView(this);
        answerText=text(answer.length()==0 ? "Привет. Я рядом.\n\nРасскажите, что у вас на уме. Или включите «Только глаза» и начните говорить без кнопок." : answer.toString(),18,INK);
        answerText.setTextIsSelectable(true); answerText.setPadding(dp(14),dp(12),dp(14),dp(12));
        GradientDrawable card=new GradientDrawable(); card.setColor(PANEL); card.setCornerRadius(dp(18)); answerText.setBackground(card);
        scroll.addView(answerText); root.addView(scroll,new LinearLayout.LayoutParams(-1,0,1));
        input=new EditText(this); input.setTextColor(INK); input.setHintTextColor(MUTED); input.setHint("Ваше сообщение…"); input.setMaxLines(3); input.setTextSize(16); input.setInputType(android.text.InputType.TYPE_CLASS_TEXT | android.text.InputType.TYPE_TEXT_FLAG_MULTI_LINE | android.text.InputType.TYPE_TEXT_FLAG_CAP_SENTENCES); input.setPadding(dp(14),dp(12),dp(14),dp(12)); root.addView(input);
        LinearLayout actions=new LinearLayout(this);
        actions.addView(button("Говорить",this::startListening),new LinearLayout.LayoutParams(0,-2,1));
        actions.addView(button("Отправить",()->send(input.getText().toString())),new LinearLayout.LayoutParams(0,-2,1));
        actions.addView(button("Стоп",this::stop),new LinearLayout.LayoutParams(0,-2,1)); root.addView(actions);
        root.addView(text("Только глаза: речь запускается автоматически. Удерживайте экран или нажмите «Назад», чтобы выйти.",11,MUTED));
        root.addView(button("Новый диалог",()-> { if(!busy && !loadingVoice) { stop(); history.clear(); answer.setLength(0); answerText.setText("Начнём сначала. О чём поговорим?"); } }));
        updateEyes();
    }
    private void showSettings() {
        settings=true; eyes=null; stateText=null; answerText=null; base(); downloadLabels.clear(); modelButtons.clear();
        TextView title=text(selected==null?"Познакомимся?":"Модели и голос",28,INK); title.setTypeface(null,Typeface.BOLD); root.addView(title);
        root.addView(text("Скачайте одну языковую модель. Для голосового ввода добавьте Vosk RU. Все вычисления происходят на телефоне.",14,MUTED));
        ScrollView scroll=new ScrollView(this); LinearLayout list=new LinearLayout(this); list.setOrientation(LinearLayout.VERTICAL); scroll.addView(list); root.addView(scroll,new LinearLayout.LayoutParams(-1,0,1));
        ActivityManager.MemoryInfo mem=new ActivityManager.MemoryInfo(); ((ActivityManager)getSystemService(ACTIVITY_SERVICE)).getMemoryInfo(mem);
        list.addView(text(String.format(Locale.ROOT,"RAM устройства: %.1f ГБ · Android 12+ · ARM64\nCPU, 4 потока · контекст 2048 · до 512 токенов ответа\nОценки памяти ниже — ориентир, не гарантия скорости.",mem.totalMem/1e9),12,MUTED));
        Switch network=new Switch(this); network.setText("Разрешить мобильный интернет"); network.setTextColor(INK); network.setChecked(metered); network.setOnCheckedChangeListener((b,v)->metered=v); list.addView(network);
        for(ModelStore.Entry e:ModelStore.CATALOG) {
            LinearLayout card=new LinearLayout(this); card.setOrientation(LinearLayout.VERTICAL); card.setPadding(dp(12),dp(12),dp(12),dp(12));
            GradientDrawable bg=new GradientDrawable(); bg.setColor(PANEL); bg.setCornerRadius(dp(16)); card.setBackground(bg);
            LinearLayout.LayoutParams cp=new LinearLayout.LayoutParams(-1,-2); cp.setMargins(0,dp(10),0,0); list.addView(card,cp);
            card.addView(text(e.name,19,INK)); card.addView(text(ModelStore.format(e.bytes)+(e.asr?"":" · ориентир RAM: "+e.ramGb+" ГБ+")+"\n"+e.note,12,MUTED));
            TextView status=text(store.status(e),13,MINT); card.addView(status); downloadLabels.put(e.id,status);
            LinearLayout row=new LinearLayout(this);
            Button primary=button("Скачать",()->modelAction(e)); Button remove=button("Удалить / отменить",()->removeModel(e));
            row.addView(primary,new LinearLayout.LayoutParams(0,-2,1)); row.addView(remove,new LinearLayout.LayoutParams(0,-2,1)); card.addView(row); modelButtons.put(e.id,new Button[]{primary,remove});
        }
        Switch speech=new Switch(this); speech.setText("Озвучивать ответы по мере генерации"); speech.setTextColor(INK); speech.setChecked(prefs.getBoolean("voice",true)); speech.setOnCheckedChangeListener((b,v)-> { prefs.edit().putBoolean("voice",v).apply(); voice.enabled(v); }); list.addView(speech);
        list.addView(text("TTS использует только установленный офлайн-голос ru. Если его нет, ответы остаются текстовыми.",12,MUTED));
        list.addView(button("Настройки голоса Android",()-> { try { startActivityForResult(new Intent("com.android.settings.TTS_SETTINGS"), 20); } catch(Exception e) { toast("Откройте в настройках Android раздел «Синтез речи»"); } }));
        list.addView(text("v0.2 · экспериментальная версия\nНикакой телеметрии и облачного ИИ. Ответы могут быть ошибочными. История хранится только в оперативной памяти. При уходе с экрана запись и генерация останавливаются.",12,MUTED));
        root.addView(button("К ассистенту →",()-> { if(selected!=null && store.installed(selected)) showChat(); else toast("Скачайте модель и нажмите «Выбрать»"); }));
        refreshModels();
    }
    private void modelAction(ModelStore.Entry e) {
        if(store.installed(e)) {
            if(!e.asr) { if(selected!=e && !loadedId.isEmpty()) { busy=true; worker.execute(()-> { llm.close(); loadedId=""; main.post(()-> { busy=false; if(!destroyed) refreshModels(); }); }); } selected=e; prefs.edit().putString("selected",e.id).apply(); history.clear(); answer.setLength(0); refreshModels(); }
            return;
        }
        try { store.download(e,metered); refreshModels(); } catch(Exception ex) { toast(ex.getMessage()); }
    }
    private void removeModel(ModelStore.Entry e) {
        new AlertDialog.Builder(this).setTitle("Удалить «"+e.name+"»?").setMessage("Загрузку можно будет запустить заново.")
            .setNegativeButton("Оставить",null).setPositiveButton("Удалить",(d,w)-> {
                if(busy || loadingVoice || listening) { toast("Сначала остановите ассистента"); return; }
                if(store.busy(e) && store.status(e).startsWith("Проверка")) { toast("Дождитесь проверки модели"); return; }
                busy=true;
                worker.execute(()-> {
                    try {
                        if(e.id.equals(loadedId)) { llm.close(); loadedId=""; }
                        store.remove(e);
                        main.post(()-> { if(selected==e) { selected=null; prefs.edit().remove("selected").apply(); } });
                    } catch(Exception ex) { main.post(()->toast(ex.getMessage())); }
                    finally { main.post(()-> { busy=false; if(!destroyed) refreshModels(); }); }
                });
            }).show();
    }
    private final Runnable poll=new Runnable() { public void run() { if(destroyed) return; for(ModelStore.Entry e:ModelStore.CATALOG) store.status(e); if(settings) refreshModels(); main.postDelayed(this,1000); } };
    private void refreshModels() {
        for(ModelStore.Entry e:ModelStore.CATALOG) {
            TextView label=downloadLabels.get(e.id); if(label==null) continue;
            label.setText(store.status(e)); Button[] b=modelButtons.get(e.id);
            boolean installed=store.installed(e), downloading=store.busy(e);
            b[0].setText(installed?(e.asr?"Готово":selected==e?"✓ Выбрана":"Выбрать"):downloading?"Загружается…":"Скачать");
            b[0].setEnabled(!busy && !downloading && !(installed && e.asr));
            b[1].setEnabled(!busy && (installed || downloading));
        }
    }
    private void send(String message) {
        if(busy || listening || loadingVoice) { toast("Остановите текущую задачу"); return; }
        String question=message.trim(); if(question.isEmpty()) return;
        if(question.length()>1800) { toast("Сократите сообщение до 1800 символов"); return; }
        if(selected==null || !store.installed(selected)) { showSettings(); return; }
        final ModelStore.Entry model=selected;
        ActivityManager.MemoryInfo mem=new ActivityManager.MemoryInfo(); ((ActivityManager)getSystemService(ACTIVITY_SERVICE)).getMemoryInfo(mem);
        if(mem.availMem < model.bytes+450_000_000L && !loadedId.equals(model.id)) {
            new AlertDialog.Builder(this).setTitle("Мало свободной RAM").setMessage("Закройте другие приложения или выберите модель 0.8B Q4. Сейчас свободно "+ModelStore.format(mem.availMem)+".").setPositiveButton("Понятно",null).show(); return;
        }
        input.setText(""); answer.setLength(0); answerText.setText(""); busy=true; started=SystemClock.elapsedRealtime();
        int ticket=++turn; voice.begin(); llm.prepare(); updateEyes(); stateText.setText("Думаю…");
        String prompt=prompt(question);
        worker.execute(()-> {
            try {
                if(!loadedId.equals(model.id)) { loadedId=""; llm.load(store.file(model).getAbsolutePath(),Math.min(4,Runtime.getRuntime().availableProcessors())); loadedId=model.id; }
                if(ticket!=turn || destroyed) return;
                llm.generate(prompt.getBytes(StandardCharsets.UTF_8), bytes-> {
                    String token=new String(bytes,StandardCharsets.UTF_8);
                    main.post(()-> {
                        if(ticket!=turn || destroyed) return;
                        if(answer.length()==0 && info!=null) info.setText(String.format(Locale.ROOT,"Первый текст: %.1f с · %s",(SystemClock.elapsedRealtime()-started)/1000f,model.name));
                        answer.append(token); answerText.setText(answer.toString()); eyes.response(answer.toString()); voice.add(token);
                    });
                });
                main.post(()-> {
                    if(ticket!=turn || destroyed) return;
                    voice.finish(); history.addLast(new String[]{question,answer.toString()}); while(history.size()>2) history.removeFirst();
                    stateText.setText(answer.length()==0?"Ответ пустой. Попробуйте ещё раз.":"Готов к разговору");
                });
            } catch(Exception ex) { main.post(()-> { if(ticket==turn && !destroyed) { voice.stop(); if(fullMode) exitFull(); stateText.setText("Не удалось ответить"); answerText.setText(ex.getMessage()); eyes.mode(EyesView.Mode.ERROR); } }); }
            finally { main.post(()-> { busy=false; if(!destroyed) updateEyes(); }); }
        });
    }
    private String safe(String s) { return s.replace("<|", "〈|").replace("|>","|〉"); }
    private String prompt(String question) {
        StringBuilder p=new StringBuilder("<|im_start|>system\nТы — локальный голосовой помощник. Отвечай по-русски, кратко и понятно, без Markdown. Не придумывай доступ к интернету или действиям на телефоне.<|im_end|>\n");
        int used=question.length();
        for(String[] h:history) { if(used+h[0].length()+h[1].length()>2800) continue; used+=h[0].length()+h[1].length(); p.append("<|im_start|>user\n").append(safe(h[0])).append("<|im_end|>\n<|im_start|>assistant\n").append(safe(h[1])).append("<|im_end|>\n"); }
        return p.append("<|im_start|>user\n").append(safe(question)).append("<|im_end|>\n<|im_start|>assistant\n<think>\n\n</think>\n\n").toString();
    }

    private void immersive(boolean hide) {
        WindowInsetsController controller=getWindow().getInsetsController();
        if(controller!=null) {
            controller.setSystemBarsBehavior(WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE);
            if(hide) { controller.hide(WindowInsets.Type.systemBars() | WindowInsets.Type.ime()); }
            else controller.show(WindowInsets.Type.systemBars());
        }
    }
    private void enterFull() {
        if(busy || listening || loadingVoice) { toast("Сначала остановите текущую задачу"); return; }
        if(selected==null || !store.installed(selected) || !store.installed(find("voskru"))) { toast("Для режима глаз выберите LLM и скачайте Vosk RU"); showSettings(); return; }
        if(!voice.available()) { toast("Для разговора без текста включите озвучивание и установите офлайн-голос RU"); showSettings(); return; }
        if(checkSelfPermission(Manifest.permission.RECORD_AUDIO)!=PackageManager.PERMISSION_GRANTED) { pendingFullPermission=true; requestPermissions(new String[]{Manifest.permission.RECORD_AUDIO},10); return; }
        voice.stop();
        fullMode=true; settings=false; autoReady=false; hearing=false;
        final int session=++fullSession;
        root=new LinearLayout(this); root.setBackgroundColor(Color.BLACK);
        eyes=new EyesView(this); eyes.immersive(true);
        eyes.setContentDescription("Полноэкранные глаза. Удерживайте экран, чтобы выйти.");
        eyes.setOnLongClickListener(v->{ exitFull(); return true; });
        root.addView(eyes,new LinearLayout.LayoutParams(-1,-1)); setContentView(root); immersive(true);
        busy=true; llm.prepare(); updateEyes();
        final ModelStore.Entry model=selected;
        worker.execute(()-> {
            try {
                if(!loadedId.equals(model.id)) {
                    llm.close(); loadedId="";
                    ActivityManager.MemoryInfo memory=new ActivityManager.MemoryInfo(); ((ActivityManager)getSystemService(ACTIVITY_SERVICE)).getMemoryInfo(memory);
                    if(memory.availMem<model.bytes+550_000_000L) throw new IllegalStateException("Мало RAM. Выберите модель 0.8B Q4 или закройте другие приложения.");
                    llm.load(store.file(model).getAbsolutePath(),Math.min(4,Runtime.getRuntime().availableProcessors())); loadedId=model.id;
                }
                main.post(()-> { busy=false; if(destroyed || !fullMode || session!=fullSession) { if(!destroyed) updateEyes(); return; } openHandsFree(session); updateEyes(); });
            } catch(Exception e) { main.post(()-> { busy=false; if(!destroyed && fullMode && session==fullSession) { exitFull(); toast(e.getMessage()); } if(!destroyed) updateEyes(); }); }
        });
    }
    private void openHandsFree(int session) {
        handsFree=new HandsFreeVoice(store.file(find("voskru")).getAbsolutePath(),new HandsFreeVoice.Listener() {
            private boolean valid() { return !destroyed && fullMode && fullSession==session; }
            public void ready() { main.post(()-> { if(valid()) { autoReady=true; rearmAt=SystemClock.uptimeMillis()+300; main.post(autoLoop); } }); }
            public void activity(boolean speech,float level) { main.post(()-> { if(valid()) { hearing=speech && !busy && !voice.hasWork(); eyes.level(hearing?level:0); updateEyes(); } }); }
            public void result(String text) { main.post(()-> { if(valid() && foreground && focused && !busy && !voice.hasWork()) { hearing=false; send(text); } }); }
            public void error(String message) { main.post(()-> { if(valid()) { exitFull(); toast(message); } }); }
        });
    }
    private final Runnable autoLoop=new Runnable() {
        public void run() {
            if(!fullMode || destroyed) return;
            boolean canListen=autoReady && foreground && focused && !busy && !voice.hasWork() && SystemClock.uptimeMillis()>=rearmAt;
            if(handsFree!=null) handsFree.arm(canListen);
            if(!canListen) { hearing=false; if(eyes!=null) eyes.level(0); }
            updateEyes(); main.postDelayed(this,100);
        }
    };
    private void exitFull() {
        if(!fullMode) return;
        fullMode=false; fullSession++; autoReady=false; hearing=false; main.removeCallbacks(autoLoop);
        if(handsFree!=null) { handsFree.close(); handsFree=null; }
        stop(); immersive(false); showChat();
    }

    private void startListening() {
        if(busy || listening || loadingVoice) { toast("Сначала нажмите «Стоп»"); return; }
        if(!store.installed(find("voskru"))) { toast("Для голоса скачайте Vosk RU"); showSettings(); return; }
        if(checkSelfPermission(Manifest.permission.RECORD_AUDIO)!=PackageManager.PERMISSION_GRANTED) { requestPermissions(new String[]{Manifest.permission.RECORD_AUDIO},10); return; }
        voice.stop(); int ticket=++turn; loadingVoice=true; stateText.setText("Подключаю микрофон…");
        worker.execute(()-> {
            Model m=null; Recognizer r=null;
            try {
                m=new Model(store.file(find("voskru")).getAbsolutePath()); r=new Recognizer(m,16000f);
                Model finalM=m; Recognizer finalR=r;
                main.post(()-> {
                    loadingVoice=false;
                    if(ticket!=turn || destroyed) { finalR.close(); finalM.close(); return; }
                    speechModel=finalM; recognizer=finalR;
                    try {
                        speechService=new SpeechService(recognizer,16000f); listening=true; updateEyes(); stateText.setText("Слушаю… Сделайте паузу после вопроса");
                        speechService.startListening(new RecognitionListener() {
                            public void onPartialResult(String json) { main.post(()-> { if(ticket==turn && listening) input.setText(jsonText(json,"partial")); }); }
                            public void onResult(String json) { recognized(json,ticket); }
                            public void onFinalResult(String json) { recognized(json,ticket); }
                            public void onError(Exception e) { main.post(()-> { if(ticket==turn) { stopRecording(); stateText.setText("Ошибка микрофона: "+e.getMessage()); } }); }
                            public void onTimeout() { main.post(()-> { if(ticket==turn) { stopRecording(); stateText.setText("Время записи истекло"); } }); }
                        },30000);
                    } catch(Exception ex) { stopRecording(); toast(ex.getMessage()); }
                });
            } catch(Exception ex) {
                if(r!=null) r.close(); if(m!=null) m.close();
                main.post(()-> { loadingVoice=false; if(!destroyed) { stateText.setText("Не удалось запустить распознавание"); toast(ex.getMessage()); } });
            }
        });
    }
    private String jsonText(String json,String key) { try { return new JSONObject(json).optString(key,"").trim(); } catch(Exception e) { return ""; } }
    private void recognized(String json,int ticket) {
        String s=jsonText(json,"text");
        main.post(()-> { if(ticket!=turn || !listening || s.isEmpty()) return; stopRecording(); input.setText(s); send(s); });
    }
    private void stopRecording() {
        listening=false;
        if(speechService!=null) { speechService.stop(); speechService.shutdown(); speechService=null; }
        Recognizer r=recognizer; Model m=speechModel; recognizer=null; speechModel=null;
        if(r!=null || m!=null) worker.execute(()-> { if(r!=null) r.close(); if(m!=null) m.close(); });
        updateEyes();
    }
    private void stop() {
        turn++; llm.cancel(); voice.stop(); stopRecording();
        if(stateText!=null) stateText.setText(busy?"Останавливаю…":"Готов к разговору");
    }
    private void updateEyes() { if(fullMode || busy || listening || speaking) getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON); else getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON); if(eyes!=null) eyes.mode(listening || hearing?EyesView.Mode.LISTENING:speaking?EyesView.Mode.SPEAKING:busy?EyesView.Mode.THINKING:EyesView.Mode.IDLE); }
    private void toast(String s) { if(!destroyed) Toast.makeText(this,s,Toast.LENGTH_LONG).show(); }
    @Override public void onRequestPermissionsResult(int request,String[] permissions,int[] results) { super.onRequestPermissionsResult(request,permissions,results); if(request==10 && results.length>0 && results[0]==PackageManager.PERMISSION_GRANTED) { if(pendingFullPermission) { pendingFullPermission=false; enterFull(); } else startListening(); } else { pendingFullPermission=false; toast("Микрофон отключён. Можно писать сообщения."); } }
    @Override protected void onActivityResult(int request, int result, Intent data) { super.onActivityResult(request,result,data); if(request==20) voice.refresh(); }
    @Override protected void onResume() { super.onResume(); foreground=true; }
    @Override protected void onPause() { foreground=false; if(handsFree!=null) handsFree.arm(false); super.onPause(); }
    @Override public void onWindowFocusChanged(boolean hasFocus) { super.onWindowFocusChanged(hasFocus); focused=hasFocus; if(fullMode) { if(hasFocus) immersive(true); else if(handsFree!=null) handsFree.arm(false); } }
    @Override protected void onStop() { super.onStop(); if(fullMode) exitFull(); if(llm!=null) stop(); }
    @Override protected void onDestroy() {
        destroyed=true; fullMode=false; fullSession++; main.removeCallbacks(autoLoop); if(handsFree!=null) handsFree.close(); main.removeCallbacks(poll); voice.close(); stopRecording(); llm.cancel(); worker.execute(llm::dispose); worker.shutdown(); super.onDestroy();
    }
    @Override public void onBackPressed() { if(fullMode) exitFull(); else if(settings && selected!=null) showChat(); else super.onBackPressed(); }
}
