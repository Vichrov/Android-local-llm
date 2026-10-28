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
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;

public final class MainActivity extends Activity {
    private int BG, PANEL, INK, MUTED, MINT, ACCENT, SOFT, CONTROL;
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
    private View subtitle, fullButton, newChat;
    private TextView chatHint;
    private LinearLayout root;
    private boolean busy, listening, speaking, settings, metered, loadingVoice;
    private volatile boolean destroyed;
    private volatile int turn;
    private volatile String loadedId = "";
    private HandsFreeVoice manualVoice;
    private final StringBuilder answer = new StringBuilder();
    private long started;
    private volatile long firstTokenAt;
    private boolean bargeInEnabled;
    private int bargeFrames;
    private long heardUntil;
    private boolean fullMode, autoReady, hearing, foreground, pendingFullPermission;
    private int fullSession;
    private long rearmAt;
    private HandsFreeVoice handsFree;
    private boolean autoEnabled, micRecording, pendingChatPermission, updatingAutoToggle;
    private Switch autoToggle;
    private TextView voiceCaption, asrHeading;
    private ProgressBar micLevel;
    private ScrollView pageScroll;
    private Dialog modelLoadingDialog;
    private int modelLoadingTicket = -1;


    @Override public void onCreate(Bundle saved) {
        super.onCreate(saved);
        loadPalette();
        getWindow().setDecorFitsSystemWindows(false);
        prefs = getSharedPreferences("settings", MODE_PRIVATE);
        bargeInEnabled=prefs.getBoolean("bargeIn",true);
        store = ModelStore.get(this);
        llm = new NativeLlm();
        selected = find(prefs.getString("selected", ""));
        if(selected != null && !store.installed(selected)) selected=null;
        voice = new VoiceOutput(this, new VoiceOutput.State() {
            public void speech(boolean value) {
                speaking=value;
                if(!value) rearmAt=SystemClock.uptimeMillis()+500;
                else if(autoEnabled && !fullMode && stateText!=null) {
                    boolean canInterrupt=bargeInEnabled && handsFree!=null && handsFree.echoControlled();
                    stateText.setText(canInterrupt?"Отвечаю · можно перебить":"Отвечаю…");
                }
                updateEyes();
            }
            public void unavailable(String why) { if(!destroyed) { prefs.edit().putString("ttsStatus",why).apply(); if(fullMode) exitFull(); if(info!=null) info.setText(why); } }
        });
        voice.enabled(prefs.getBoolean("voice", true));
        if(selected==null) showSettings(); else showChat();
        main.post(poll);
    }
    private ModelStore.Entry find(String id) { for(ModelStore.Entry e:ModelStore.CATALOG) if(e.id.equals(id)) return e; return null; }
    private ModelStore.Entry speechEntry() {
        ModelStore.Entry e=find(prefs.getString("asr","voskru"));
        return e!=null && e.asr?e:find("voskru");
    }
    private String speechName() { return speechEntry().id.equals("gigaam")?"GigaAM":"Vosk"; }
    private boolean speechMemoryAvailable() {
        if(!speechEntry().id.equals("gigaam")) return true;
        ActivityManager.MemoryInfo m=new ActivityManager.MemoryInfo(); ((ActivityManager)getSystemService(ACTIVITY_SERVICE)).getMemoryInfo(m);
        if(m.availMem>=650_000_000L) return true;
        toast("Для GigaAM нужно больше свободной памяти. Закройте приложения или выберите Vosk."); return false;
    }
    private int dp(float v) { return (int)(getResources().getDisplayMetrics().density*v+.5f); }
    private TextView text(String s, int size, int color) {
        TextView t=new TextView(this); t.setText(s); t.setTextSize(size); t.setTextColor(color); t.setPadding(0,dp(5),0,dp(5)); return t;
    }
    private void loadPalette() {
        BG=getColor(R.color.page); PANEL=getColor(R.color.surface); INK=getColor(R.color.ink);
        MUTED=getColor(R.color.muted); MINT=getColor(R.color.accent_text); ACCENT=getColor(R.color.accent);
        SOFT=getColor(R.color.soft); CONTROL=getColor(R.color.control);
        // PhoneWindow.getInsetsController() dereferences mDecor on Android 12.
        // onCreate calls this before setContentView: install the decor first.
        getWindow().getDecorView();
        getWindow().setStatusBarColor(fullMode?Color.BLACK:BG); getWindow().setNavigationBarColor(fullMode?Color.BLACK:BG);
        WindowInsetsController c=getWindow().getInsetsController();
        if(c!=null) { int flags=WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS | WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS;
            boolean light=(getResources().getConfiguration().uiMode & android.content.res.Configuration.UI_MODE_NIGHT_MASK)!=android.content.res.Configuration.UI_MODE_NIGHT_YES;
            c.setSystemBarsAppearance(light && !fullMode?flags:0,flags); }
    }
    private GradientDrawable rounded(int color,int radius) {
        GradientDrawable d=new GradientDrawable(); d.setColor(color); d.setCornerRadius(dp(radius)); return d;
    }
    private ColorStateList enabledColors(int color) {
        return new ColorStateList(new int[][]{new int[]{-android.R.attr.state_enabled},new int[]{}},new int[]{MUTED,color});
    }
    private Button button(String s, Runnable action) {
        Button b=new Button(this); b.setText(s); b.setAllCaps(false); b.setTextColor(enabledColors(INK)); b.setTextSize(14);
        b.setTypeface(Typeface.create("sans-serif-medium",Typeface.NORMAL)); b.setMinWidth(0); b.setMinimumWidth(0);
        b.setMinHeight(dp(48)); b.setPadding(dp(12),dp(10),dp(12),dp(10)); b.setStateListAnimator(null);
        b.setBackground(new RippleDrawable(ColorStateList.valueOf(0x20206cff),rounded(CONTROL,24),null));
        b.setOnClickListener(v -> action.run()); return b;
    }
    private Button primary(String s,Runnable action) {
        Button b=button(s,action); b.setTextColor(enabledColors(Color.WHITE));
        android.graphics.drawable.StateListDrawable backgrounds=new android.graphics.drawable.StateListDrawable();
        backgrounds.addState(new int[]{-android.R.attr.state_enabled},rounded(CONTROL,24));
        backgrounds.addState(new int[]{},rounded(0xff206cff,24));
        b.setBackground(new RippleDrawable(ColorStateList.valueOf(0x40ffffff),backgrounds,null)); return b;
    }
    private Switch toggle(String label) {
        Switch v=new Switch(this); v.setText(label); v.setTextColor(INK); v.setTextSize(16);
        v.setMinHeight(dp(56)); v.setPadding(dp(16),dp(8),dp(16),dp(8)); v.setSwitchPadding(dp(12));
        v.setBackground(rounded(PANEL,18));
        v.setThumbTintList(ColorStateList.valueOf(Color.WHITE));
        v.setTrackTintList(new ColorStateList(new int[][]{new int[]{android.R.attr.state_checked},new int[]{}},new int[]{ACCENT,CONTROL}));
        return v;
    }
    private TextView section(LinearLayout parent,String label) {
        TextView t=text(label,13,MUTED); t.setPadding(dp(16),dp(24),dp(16),dp(10)); parent.addView(t); return t;
    }
    private LinearLayout card(LinearLayout parent) {
        LinearLayout box=new LinearLayout(this); box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(16),dp(16),dp(16),dp(16)); box.setBackground(rounded(PANEL,18));
        LinearLayout.LayoutParams p=new LinearLayout.LayoutParams(-1,-2); p.setMargins(0,0,0,dp(10)); parent.addView(box,p); return box;
    }
    private void actionRow(LinearLayout parent,Button... buttons) {
        LinearLayout row=new LinearLayout(this); row.setGravity(Gravity.CENTER_VERTICAL);
        for(int n=0;n<buttons.length;n++) { LinearLayout.LayoutParams p=new LinearLayout.LayoutParams(0,-2,1); if(n>0)p.setMarginStart(dp(8)); row.addView(buttons[n],p); }
        LinearLayout.LayoutParams p=new LinearLayout.LayoutParams(-1,-2); p.setMargins(0,dp(10),0,0); parent.addView(row,p);
    }
    private void base() {
        root=new LinearLayout(this); root.setOrientation(LinearLayout.VERTICAL); root.setBackgroundColor(BG); root.setPadding(dp(20),dp(14),dp(20),dp(12));
        root.setOnApplyWindowInsetsListener((v,insets)-> { android.graphics.Insets i=insets.getInsets(WindowInsets.Type.systemBars() | WindowInsets.Type.ime()); root.setPadding(dp(20)+i.left,dp(12)+i.top,dp(20)+i.right,dp(12)+i.bottom); compactChat(getResources().getDisplayMetrics().heightPixels-i.top-i.bottom<dp(650)); return insets; });
        setContentView(root); root.requestApplyInsets(); info=null; asrHeading=null;
    }
    private void showChat() {
        settings=false; downloadLabels.clear(); modelButtons.clear(); base();
        LinearLayout header=new LinearLayout(this); header.setGravity(Gravity.CENTER_VERTICAL);
        TextView title=text("Local Eyes",32,INK); title.setTypeface(Typeface.create("sans-serif-medium",Typeface.NORMAL));
        header.addView(title,new LinearLayout.LayoutParams(0,-2,1));
        Button face=button("",this::enterFull); face.setContentDescription("Только глаза");
        face.setCompoundDrawablesWithIntrinsicBounds(R.drawable.ic_face_shortcut,0,0,0);
        face.setCompoundDrawableTintList(ColorStateList.valueOf(MINT));
        LinearLayout.LayoutParams fp=new LinearLayout.LayoutParams(dp(48),dp(48)); fp.setMarginEnd(dp(8)); header.addView(face,fp);
        title.setTextSize(getResources().getConfiguration().screenWidthDp<380?24:32);
        Button models=button("Модели",()-> { if(!busy && !listening && !loadingVoice) { voice.stop(); showSettings(); } else toast("Сначала остановите диалог"); }); header.addView(models); root.addView(header);
        subtitle=text("Всегда рядом. Всё на вашем телефоне.",13,MUTED); root.addView(subtitle);
        Button cinema=button("Только глаза  ›",this::enterFull); fullButton=cinema; cinema.setTextColor(MINT);
        cinema.setBackground(new RippleDrawable(ColorStateList.valueOf(0x20206cff),rounded(SOFT,24),null));
        LinearLayout.LayoutParams cinemaParams=new LinearLayout.LayoutParams(-1,dp(48)); cinemaParams.setMargins(0,dp(12),0,0); root.addView(cinema,cinemaParams);
        eyes=new EyesView(this); root.addView(eyes,new LinearLayout.LayoutParams(-1,dp(120)));
        stateText=text(busy?"Думаю…":speaking?"Отвечаю…":"Готов к разговору",15,MINT); stateText.setGravity(Gravity.CENTER); root.addView(stateText);
        info=text(selected==null ? "Выберите модель" : selected.name+" · "+speechName()+" · на устройстве",12,MUTED); info.setGravity(Gravity.CENTER); root.addView(info);
        autoToggle=toggle("Авто-голос"); autoToggle.setChecked(autoEnabled);
        autoToggle.setOnCheckedChangeListener((v,on)-> { if(updatingAutoToggle) return; if(on) startAutomatic(false); else stopAutomatic(); }); root.addView(autoToggle);
        micLevel=new ProgressBar(this,null,android.R.attr.progressBarStyleHorizontal); micLevel.setMax(100);
        micLevel.setProgressTintList(ColorStateList.valueOf(ACCENT)); micLevel.setProgressBackgroundTintList(ColorStateList.valueOf(CONTROL)); root.addView(micLevel,new LinearLayout.LayoutParams(-1,dp(3)));
        voiceCaption=text("",12,MUTED); voiceCaption.setMaxLines(2); root.addView(voiceCaption); syncAutoToggle();
        ScrollView scroll=new ScrollView(this); pageScroll=scroll; scroll.setFillViewport(true); scroll.setVerticalScrollBarEnabled(false);
        answerText=text(answer.length()==0 ? "Привет. Я рядом.\n\nНапишите сообщение или начните разговор голосом." : answer.toString(),18,INK);
        answerText.setTextIsSelectable(true); answerText.setLineSpacing(dp(3),1); answerText.setPadding(dp(18),dp(16),dp(18),dp(16)); answerText.setBackground(rounded(PANEL,18));
        scroll.addView(answerText); LinearLayout.LayoutParams sp=new LinearLayout.LayoutParams(-1,0,1); sp.setMargins(0,dp(12),0,dp(12)); root.addView(scroll,sp);
        input=new EditText(this); input.setTextColor(INK); input.setHintTextColor(MUTED); input.setHint("Сообщение…"); input.setContentDescription("Сообщение ассистенту"); input.setMaxLines(3); input.setTextSize(16); input.setMinHeight(dp(52));
        input.setInputType(android.text.InputType.TYPE_CLASS_TEXT | android.text.InputType.TYPE_TEXT_FLAG_MULTI_LINE | android.text.InputType.TYPE_TEXT_FLAG_CAP_SENTENCES);
        input.setPadding(dp(18),dp(12),dp(18),dp(12)); input.setBackground(rounded(PANEL,24)); root.addView(input);
        actionRow(root,button("Говорить",this::startListening),primary("Отправить",()->send(input.getText().toString())),button("Стоп",this::stop));
        chatHint=text("В режиме глаз удерживайте экран для выхода.",11,MUTED); chatHint.setGravity(Gravity.CENTER); root.addView(chatHint);
        newChat=button("Новый диалог",()-> { if(!busy && !loadingVoice) { stop(); history.clear(); answer.setLength(0); answerText.setText("Начнём сначала. О чём поговорим?"); } }); root.addView(newChat);
        updateAutoStatus(); updateEyes();
    }
    private void compactChat(boolean compact) {
        if(settings || fullMode || eyes==null) return;
        for(View v:new View[]{subtitle,fullButton,chatHint,newChat,info}) if(v!=null) v.setVisibility(compact?View.GONE:View.VISIBLE);
        android.view.ViewGroup.LayoutParams p=eyes.getLayoutParams();
        if(p!=null && p.height!=dp(compact?0:120)) { p.height=dp(compact?0:120); eyes.setLayoutParams(p); }
        if(input!=null) input.setMaxLines(compact?1:3);
    }
    private void showSettings() {
        stopAutomatic(); settings=true; eyes=null; stateText=null; answerText=null; base(); downloadLabels.clear(); modelButtons.clear();
        TextView title=text(selected==null?"Добро пожаловать":"Модели и голос",30,INK); title.setTypeface(Typeface.create("sans-serif-medium",Typeface.NORMAL)); root.addView(title);
        root.addView(text(selected==null?"Подготовим вашего личного ассистента":"Настройте общение под себя",14,MUTED));
        ScrollView scroll=new ScrollView(this); pageScroll=scroll; scroll.setVerticalScrollBarEnabled(false); LinearLayout list=new LinearLayout(this); list.setOrientation(LinearLayout.VERTICAL); scroll.addView(list); root.addView(scroll,new LinearLayout.LayoutParams(-1,0,1));
        section(list,"Оформление"); LinearLayout appearance=card(list);
        appearance.addView(text("Тема устройства",17,INK)); appearance.addView(text("Светлая и тёмная темы переключаются автоматически вместе с Android.",13,MUTED));
        section(list,"Подключение"); Switch network=toggle("Мобильный интернет"); network.setChecked(metered); network.setOnCheckedChangeListener((b,v)->metered=v); list.addView(network);
        list.addView(text("По умолчанию модели скачиваются только по Wi-Fi.",12,MUTED));
        section(list,"Языковые модели"); list.addView(text("Выберите одну модель. После загрузки интернет для общения не нужен.",14,MUTED));
        ActivityManager.MemoryInfo mem=new ActivityManager.MemoryInfo(); ((ActivityManager)getSystemService(ACTIVITY_SERVICE)).getMemoryInfo(mem);
        list.addView(text(String.format(Locale.ROOT,"Память устройства: %.1f ГБ · оценки RAM ниже ориентировочные",mem.totalMem/1e9),12,MUTED));
        for(ModelStore.Entry e:ModelStore.CATALOG) {
            if(e==ModelStore.SILERO) continue;
            if(e.id.equals("voskru")) {
                asrHeading=section(list,"Распознавание речи · "+speechName());
                list.addView(text("Выберите скачанный движок. Vosk показывает слова сразу; GigaAM распознаёт фразу после паузы. Выбор действует в чате и режиме глаз.",13,MUTED));
            }
            LinearLayout box=card(list);
            TextView name=text(e.name,18,INK); name.setTypeface(Typeface.create("sans-serif-medium",Typeface.NORMAL)); box.addView(name);
            box.addView(text(ModelStore.format(e.bytes)+(e.asr?"":" · RAM от "+e.ramGb+" ГБ")+"\n"+e.note,13,MUTED));
            TextView status=text(store.status(e),13,MINT); box.addView(status); downloadLabels.put(e.id,status);
            Button primary=primary("Скачать",()->modelAction(e)); Button remove=button("Удалить",()->removeModel(e));
            actionRow(box,primary,remove); modelButtons.put(e.id,new Button[]{primary,remove});
        }
        section(list,"Голос ассистента");
        Switch speech=toggle("Озвучивать ответы"); speech.setChecked(prefs.getBoolean("voice",true)); speech.setOnCheckedChangeListener((b,v)-> { prefs.edit().putBoolean("voice",v).apply(); voice.enabled(v); }); list.addView(speech);
        list.addView(text("Чтение начинается по мере ответа. Нужен установленный офлайн-голос на русском языке.",13,MUTED));
        Switch barge=toggle("Перебивать ассистента голосом"); barge.setChecked(bargeInEnabled); barge.setOnCheckedChangeListener((b,v)-> { bargeInEnabled=v; prefs.edit().putBoolean("bargeIn",v).apply(); }); list.addView(barge);
        list.addView(text("Во время озвучивания перебивание работает только когда Android даёт приложению системное эхоподавление. Без AEC Local Eyes автоматически не слушает собственный голос.",12,MUTED));
        list.addView(button("Настройки голоса Android  ›",()-> { try { startActivityForResult(new Intent("com.android.settings.TTS_SETTINGS"), 20); } catch(Exception e) { toast("Откройте в настройках Android раздел «Синтез речи»"); } }));
        section(list,"Local Eyes · v0.6-Fix");
        list.addView(text("Voice 2.0 · непрерывная локальная сессия, безопасное перебивание ответа, AEC/NoiseSuppressor при поддержке Android, ускоренный streaming TTS и повторное использование KV-префикса между репликами.\n\nCPU · KleidiAI auto · thermal fallback · контекст 2048 · batch 256 · до 512 токенов. Интернет для диалога не нужен.",12,MUTED));
        Button done=primary(selected==null?"Начать общение":"К ассистенту",()-> { if(selected!=null && store.installed(selected)) showChat(); else toast("Скачайте модель и нажмите «Выбрать»"); });
        LinearLayout.LayoutParams p=new LinearLayout.LayoutParams(-1,-2); p.setMargins(0,dp(12),0,0); root.addView(done,p); refreshModels();
    }
    private void modelAction(ModelStore.Entry e) {
        if(store.installed(e)) {
            if(e.asr) { prefs.edit().putString("asr",e.id).apply(); refreshModels(); toast("Распознавание: "+speechName()); }
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
                        main.post(()-> { if(e.asr && speechEntry()==e) prefs.edit().putString("asr","voskru").apply(); if(selected==e) { selected=null; prefs.edit().remove("selected").apply(); } });
                    } catch(Exception ex) { main.post(()->toast(ex.getMessage())); }
                    finally { main.post(()-> { busy=false; if(!destroyed) refreshModels(); }); }
                });
            }).show();
    }
    private final Runnable poll=new Runnable() { public void run() { if(destroyed) return; for(ModelStore.Entry e:ModelStore.CATALOG) store.status(e); if(settings) refreshModels(); main.postDelayed(this,1000); } };
    private void textIfChanged(TextView view,String value) {
        // Avoid repeated accessibility events and layout work on each poll.
        if(!value.contentEquals(view.getText())) view.setText(value);
    }
    private void refreshModels() {
        if(asrHeading!=null) textIfChanged(asrHeading,"Распознавание речи · "+speechName());
        for(ModelStore.Entry e:ModelStore.CATALOG) {
            TextView label=downloadLabels.get(e.id); if(label==null) continue;
            textIfChanged(label,store.status(e)); Button[] b=modelButtons.get(e.id);
            boolean installed=store.installed(e), downloading=store.busy(e);
            textIfChanged(b[0],installed?((e.asr?speechEntry()==e:selected==e)?"✓ Выбрана":"Выбрать"):downloading?"Загружается…":"Скачать");
            b[0].setEnabled(!busy && !downloading && !(installed && (e.asr?speechEntry()==e:selected==e)));
            textIfChanged(b[1],downloading?"Отменить":"Удалить");
            b[1].setEnabled(!busy && (store.removable(e) || downloading));
        }
    }
    // Native GGUF loading does not report byte progress: use an indeterminate spinner.
    private void showModelLoading(int ticket, String modelName) {
        dismissModelLoading();
        if(destroyed || ticket!=turn) return;
        modelLoadingTicket=ticket;
        if(eyes!=null) eyes.mode(EyesView.Mode.LOADING_MODEL);
        LinearLayout content=new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setGravity(Gravity.CENTER);
        content.setPadding(dp(28),dp(28),dp(28),dp(24));
        ProgressBar spinner=new ProgressBar(this);
        spinner.setIndeterminate(true);
        content.addView(spinner,new LinearLayout.LayoutParams(dp(56),dp(56)));
        TextView heading=text("Загрузка модели в память…",18,INK);
        heading.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams hp=new LinearLayout.LayoutParams(-1,-2);
        hp.topMargin=dp(18);
        content.addView(heading,hp);
        TextView detail=text(modelName+"\\nПервый запуск может занять некоторое время",13,MUTED);
        detail.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams detailParams=new LinearLayout.LayoutParams(-1,-2);
        detailParams.topMargin=dp(8);
        content.addView(detail,detailParams);
        Dialog dialog=new Dialog(this);
        dialog.setContentView(content);
        dialog.setCancelable(false);
        modelLoadingDialog=dialog;
        dialog.show();
        if(dialog.getWindow()!=null) {
            dialog.getWindow().setBackgroundDrawable(rounded(PANEL,24));
            dialog.getWindow().setLayout(Math.min(getResources().getDisplayMetrics().widthPixels-dp(40),dp(360)),WindowManager.LayoutParams.WRAP_CONTENT);
        }
    }
    private void dismissModelLoading() {
        modelLoadingTicket=-1;
        if(modelLoadingDialog!=null) {
            modelLoadingDialog.dismiss();
            modelLoadingDialog=null;
        }
    }
    private void dismissModelLoading(int ticket) {
        if(modelLoadingTicket==ticket) dismissModelLoading();
    }
    private void send(String message) {
        if(busy || listening || loadingVoice) { toast("Остановите текущую задачу"); return; }
        String question=message.trim(); if(question.isEmpty()) return;
        if(question.length()>1800) { toast("Сократите сообщение до 1800 символов"); return; }
        if(selected==null || !store.installed(selected)) { showSettings(); return; }
        final ModelStore.Entry model=selected;
        ActivityManager.MemoryInfo mem=new ActivityManager.MemoryInfo(); ((ActivityManager)getSystemService(ACTIVITY_SERVICE)).getMemoryInfo(mem);
        if(mem.availMem < model.bytes+450_000_000L && !loadedId.equals(model.id)) {
            if(fullMode) exitFull(); else stopAutomatic();
            new AlertDialog.Builder(this).setTitle("Мало свободной RAM").setMessage("Закройте другие приложения или выберите модель 0.8B Q4. Сейчас свободно "+ModelStore.format(mem.availMem)+".").setPositiveButton("Понятно",null).show(); return;
        }
        if(handsFree!=null) handsFree.arm(false);
        input.setText(""); answer.setLength(0); answerText.setText(""); busy=true; started=SystemClock.elapsedRealtime();
        int ticket=++turn; voice.begin(); firstTokenAt=0; updateEyes();
        boolean needsLoad=!loadedId.equals(model.id);
        stateText.setText(needsLoad?"Загрузка модели в RAM…":"Думаю…");
        if(needsLoad) showModelLoading(ticket,model.name);
        String prompt=prompt(question);
        worker.execute(()-> {
            try {
                if(ticket!=turn || destroyed) return;
                if(!LlmOperationGate.prepare(ticket,()->turn,llm::prepare)) return;
                if(!loadedId.equals(model.id)) {
                    loadedId="";
                    llm.load(store.file(model).getAbsolutePath(),PerformanceTuning.threads(MainActivity.this));
                    if(ticket!=turn || destroyed) return;
                    loadedId=model.id;
                }
                main.post(()-> { if(!destroyed) { dismissModelLoading(ticket); if(ticket==turn && stateText!=null) stateText.setText("Думаю…"); } });
                if(ticket!=turn || destroyed) return;
                int generated=llm.generate(prompt.getBytes(StandardCharsets.UTF_8), bytes-> {
                    if(ticket==turn && firstTokenAt==0) firstTokenAt=SystemClock.elapsedRealtime();
                    String token=new String(bytes,StandardCharsets.UTF_8);
                    main.post(()-> {
                        if(ticket!=turn || destroyed) return;
                        if(answer.length()==0 && info!=null) info.setText(String.format(Locale.ROOT,"Первый текст: %.1f с · %s",(SystemClock.elapsedRealtime()-started)/1000f,model.name));
                        answer.append(token); answerText.setText(answer.toString()); eyes.response(answer.toString()); voice.add(token);
                    });
                });
                int reused=llm.cacheReuse();
                long ended=SystemClock.elapsedRealtime();
                long first=firstTokenAt==0?ended:firstTokenAt;
                float ttft=(first-started)/1000f;
                float tps=generated<=0?0f:generated*1000f/Math.max(1,ended-first);
                main.post(()-> {
                    if(ticket!=turn || destroyed) return;
                    voice.finish(); history.addLast(new String[]{question,answer.toString()}); while(history.size()>4) history.removeFirst();
                    if(info!=null) info.setText(String.format(Locale.ROOT,"TTFT %.1f с · %.1f ток/с · KV +%d · %s",ttft,tps,reused,model.name));
                    stateText.setText(answer.length()==0?"Ответ пустой. Попробуйте ещё раз.":voice.hasWork()?"Отвечаю…":"Готов к разговору");
                });
            } catch(Exception ex) { main.post(()-> { if(ticket==turn && !destroyed) { voice.stop(); if(fullMode) exitFull(); else stopAutomatic(); stateText.setText("Не удалось ответить"); answerText.setText(ex.getMessage()); eyes.mode(EyesView.Mode.ERROR); } }); }
            finally { main.post(()-> { dismissModelLoading(ticket); if(ticket==turn) busy=false; if(!destroyed) updateEyes(); }); }
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
        loadPalette();
        WindowInsetsController controller=getWindow().getInsetsController();
        if(controller!=null) {
            controller.setSystemBarsBehavior(WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE);
            if(hide) { controller.hide(WindowInsets.Type.systemBars() | WindowInsets.Type.ime()); }
            else controller.show(WindowInsets.Type.systemBars());
        }
    }
    private void syncAutoToggle() {
        if(autoToggle!=null) { updatingAutoToggle=true; autoToggle.setChecked(autoEnabled); updatingAutoToggle=false; }
        if(voiceCaption!=null) voiceCaption.setVisibility(autoEnabled?View.VISIBLE:View.GONE);
        if(micLevel!=null) { micLevel.setVisibility(autoEnabled?View.VISIBLE:View.GONE); if(!autoEnabled) micLevel.setProgress(0); }
        if(root!=null && !fullMode) root.requestApplyInsets();
    }
    private void enterFull() { startAutomatic(true); }
    private void startAutomatic(boolean fullscreen) {
        if(busy || listening || loadingVoice) { toast("Сначала остановите текущую задачу"); syncAutoToggle(); return; }
        if(selected==null || !store.installed(selected) || !store.installed(speechEntry())) { toast("Выберите LLM и скачайте выбранный движок распознавания"); syncAutoToggle(); showSettings(); return; }
        if(fullscreen && !voice.available()) { toast("Для режима глаз включите офлайн-голос RU"); syncAutoToggle(); showSettings(); return; }
        if(checkSelfPermission(Manifest.permission.RECORD_AUDIO)!=PackageManager.PERMISSION_GRANTED) {
            pendingFullPermission=fullscreen; pendingChatPermission=!fullscreen;
            syncAutoToggle(); requestPermissions(new String[]{Manifest.permission.RECORD_AUDIO},10); return;
        }
        if(!speechMemoryAvailable()) { syncAutoToggle(); return; }
        voice.stop(); settings=false;
        if(fullscreen) {
            fullMode=true;
            root=new LinearLayout(this); root.setBackgroundColor(Color.BLACK);
            eyes=new EyesView(this); eyes.immersive(true);
            eyes.setContentDescription("Полноэкранные глаза. Удерживайте экран, чтобы выйти.");
            eyes.setOnLongClickListener(v->{ exitFull(); return true; });
            root.addView(eyes,new LinearLayout.LayoutParams(-1,-1)); setContentView(root); immersive(true);
        }
        if(!autoEnabled) {
            autoEnabled=true; autoReady=false; micRecording=false; hearing=false;
            rearmAt=SystemClock.uptimeMillis();
            if(voiceCaption!=null) voiceCaption.setText("Подключаю распознавание…");
            openHandsFree(++fullSession);
        }
        syncAutoToggle(); updateAutoStatus(); updateEyes();
    }
    private void openHandsFree(int session) {
        handsFree=new HandsFreeVoice(speechEntry().id,store.file(speechEntry()).getAbsolutePath(),store.file(ModelStore.SILERO).getAbsolutePath(),true,new HandsFreeVoice.Listener() {
            private boolean valid() { return !destroyed && autoEnabled && fullSession==session; }
            public void ready() { main.post(()-> { if(valid()) { autoReady=true; rearmAt=SystemClock.uptimeMillis(); main.removeCallbacks(autoLoop); main.post(autoLoop); } }); }
            public void recording(boolean active) { main.post(()-> { if(valid()) { micRecording=active; if(!active) hearing=false; updateAutoStatus(); } }); }
            public void activity(String partial,float rms,long epoch) { main.post(()-> {
                if(valid() && handsFree!=null && handsFree.current(epoch) && foreground) {
                    boolean interrupt=ConversationPolicy.interruptWindow(bargeInEnabled,busy,voice.hasWork(),speaking,handsFree.echoControlled());
                    if((!busy && !voice.hasWork()) || interrupt) {
                        hearing=!partial.isEmpty();
                        float level=(float)Math.max(0,Math.min(1,(20*Math.log10(Math.max(1,rms)/32768.0)+70)/70));
                        if(eyes!=null) eyes.level(level);
                        if(ConversationPolicy.shouldInterrupt(partial,rms,bargeInEnabled,busy,voice.hasWork(),speaking,handsFree.echoControlled())) {
                            if(++bargeFrames>=2) interruptForVoice();
                        } else if(partial.isEmpty()) bargeFrames=0;
                        if(!fullMode) {
                            if(micLevel!=null) micLevel.setProgress(Math.round(level*100));
                            if(voiceCaption!=null) voiceCaption.setText(partial.isEmpty()?"Микрофон · уровень "+Math.round(rms)+" · скажите фразу и сделайте паузу":partial.equals("…")?"Слышу речь · GigaAM распознает после паузы":"Слышу: "+partial);
                        }
                        updateEyes();
                    }
                }
            }); }
            public void result(String text,long epoch) { main.post(()-> {
                if(!valid() || handsFree==null) return;
                HandsFreeVoice source=handsFree;
                boolean interrupt=ConversationPolicy.interruptWindow(bargeInEnabled,busy,voice.hasWork(),speaking,source.echoControlled());
                if(source.current(epoch) && foreground && ((!busy && !voice.hasWork()) || interrupt)) {
                    if(interrupt) interruptForVoice();
                    source.arm(false); hearing=false; micRecording=false; bargeFrames=0; heardUntil=SystemClock.uptimeMillis()+300;
                    if(!fullMode && voiceCaption!=null) voiceCaption.setText("Вы: "+text);
                    send(text);
                }
                source.acknowledge(epoch);
            }); }
            public void notice(String message) { main.post(()-> { if(valid()) { if(fullMode) prefs.edit().putString("micNotice",message).apply(); else { if(info!=null) info.setText(message); toast(message); } } }); }
            public void error(String message) { main.post(()-> { if(valid()) { if(fullMode) exitFull(); else stopAutomatic(); if(stateText!=null) stateText.setText("Ошибка микрофона"); if(info!=null) info.setText(message); toast(message); } }); }
        });
    }
    private void updateAutoStatus() {
        if(!autoEnabled || fullMode || stateText==null) return;
        boolean echo=handsFree!=null && handsFree.echoControlled();
        boolean interrupt=ConversationPolicy.interruptWindow(bargeInEnabled,busy,voice.hasWork(),speaking,echo);
        if(busy || voice.hasWork()) {
            stateText.setText(speaking?(interrupt?"Отвечаю · можно перебить":"Отвечаю…"):(interrupt?"Думаю · можно перебить":"Думаю…"));
            return;
        }
        stateText.setText(!autoReady?"Авто: загружаю "+speechName()+"…":!foreground?"Авто: пауза":SystemClock.uptimeMillis()<rearmAt?"Авто: пауза после ответа":micRecording?"Авто: микрофон включён — говорите":"Авто: запускаю микрофон…");
    }
    private final Runnable autoLoop=new Runnable() {
        public void run() {
            if(!autoEnabled || destroyed) return;
            boolean echo=handsFree!=null && handsFree.echoControlled();
            boolean canListen=ConversationPolicy.canListen(autoReady,foreground,settings,listening,loadingVoice,busy,voice.hasWork(),speaking,bargeInEnabled,echo,SystemClock.uptimeMillis(),rearmAt);
            if(handsFree!=null) handsFree.arm(canListen);
            if(!canListen) { hearing=false; bargeFrames=0; if(eyes!=null) eyes.level(0); }
            updateAutoStatus(); updateEyes(); main.postDelayed(this,80);
        }
    };
    private void interruptForVoice() {
        if(!bargeInEnabled || (!busy && !voice.hasWork())) return;
        turn++; dismissModelLoading(); llm.cancel(); voice.stop(); rearmAt=SystemClock.uptimeMillis(); busy=false; bargeFrames=0;
        heardUntil=SystemClock.uptimeMillis()+300; hearing=true;
        if(!fullMode && stateText!=null) stateText.setText("Слушаю · перебивание");
        updateEyes();
    }
    private void stopAutomatic() {
        autoEnabled=false; fullSession++; autoReady=false; micRecording=false; hearing=false; bargeFrames=0;
        main.removeCallbacks(autoLoop);
        if(handsFree!=null) { handsFree.close(); handsFree=null; }
        syncAutoToggle();
        if(!fullMode && !busy && !settings && stateText!=null) stateText.setText("Готов к разговору");
        updateEyes();
    }
    private void exitFull() {
        if(!fullMode) return;
        fullMode=false; stopAutomatic(); stop(); immersive(false); showChat();
        String notice=prefs.getString("micNotice",""); if(!notice.isEmpty()) { info.setText(notice); prefs.edit().remove("micNotice").apply(); }
    }

    private void startListening() {
        if(autoEnabled) stopAutomatic();
        if(busy || listening || loadingVoice) { toast("Сначала нажмите «Стоп»"); return; }
        if(!store.installed(speechEntry())) { toast("Скачайте выбранный движок распознавания речи"); showSettings(); return; }
        if(checkSelfPermission(Manifest.permission.RECORD_AUDIO)!=PackageManager.PERMISSION_GRANTED) { requestPermissions(new String[]{Manifest.permission.RECORD_AUDIO},10); return; }
        if(!speechMemoryAvailable()) return;
        voice.stop(); int ticket=++turn; loadingVoice=true; stateText.setText("Загружаю "+speechName()+"…");
        manualVoice=new HandsFreeVoice(speechEntry().id,store.file(speechEntry()).getAbsolutePath(),store.file(ModelStore.SILERO).getAbsolutePath(),new HandsFreeVoice.Listener() {
            private boolean valid() { return !destroyed && ticket==turn && manualVoice!=null; }
            public void ready() { main.post(()-> {
                if(!valid()) return;
                loadingVoice=false; listening=true; manualVoice.arm(true); updateEyes();
                stateText.setText("Слушаю · "+speechName()+" · сделайте паузу");
                main.postDelayed(()-> { if(valid() && listening) { stopRecording(); stateText.setText("Время записи истекло. Нажмите «Говорить», чтобы повторить."); } },30000);
            }); }
            public void recording(boolean active) { }
            public void activity(String partial,float rms,long epoch) { main.post(()-> {
                if(valid() && manualVoice.current(epoch)) {
                    if(!partial.equals("…")) input.setText(partial);
                    if(eyes!=null) eyes.level(Math.min(1,rms/3000f));
                }
            }); }
            public void result(String text,long epoch) { main.post(()-> {
                if(!valid() || !manualVoice.current(epoch)) return;
                stopRecording(); send(text);
            }); }
            public void notice(String message) { main.post(()-> { if(valid()) { if(info!=null) info.setText(message); } }); }
            public void error(String message) { main.post(()-> { if(valid()) { stopRecording(); stateText.setText("Ошибка распознавания"); toast(message); } }); }
        });
    }
    private void stopRecording() {
        loadingVoice=false; listening=false;
        if(manualVoice!=null) { manualVoice.close(); manualVoice=null; }
        updateEyes();
    }
    private void stop() {
        if(autoEnabled) stopAutomatic();
        turn++; dismissModelLoading(); llm.cancel(); voice.stop(); stopRecording(); busy=false;
        if(stateText!=null) stateText.setText("Готов к разговору");
    }
    private void updateEyes() {
        if(fullMode || autoEnabled || busy || listening || speaking) getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON); else getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        if(eyes!=null) eyes.mode(SystemClock.uptimeMillis()<heardUntil?EyesView.Mode.HEARD:listening || hearing?EyesView.Mode.LISTENING:speaking?EyesView.Mode.SPEAKING:busy || (autoEnabled && !autoReady)?EyesView.Mode.THINKING:EyesView.Mode.IDLE);
    }
    private void toast(String s) { if(!destroyed) Toast.makeText(this,s,Toast.LENGTH_LONG).show(); }
    @Override public void onRequestPermissionsResult(int request,String[] permissions,int[] results) { super.onRequestPermissionsResult(request,permissions,results); if(request==10 && results.length>0 && results[0]==PackageManager.PERMISSION_GRANTED) { if(pendingFullPermission) { pendingFullPermission=false; enterFull(); } else if(pendingChatPermission) { pendingChatPermission=false; startAutomatic(false); } else startListening(); } else { pendingFullPermission=false; pendingChatPermission=false; syncAutoToggle(); toast("Микрофон отключён. Можно писать сообщения."); } }
    @Override protected void onActivityResult(int request, int result, Intent data) { super.onActivityResult(request,result,data); if(request==20) voice.refresh(); }
    @Override protected void onResume() { super.onResume(); foreground=true; }
    @Override protected void onPause() { foreground=false; if(handsFree!=null) handsFree.arm(false); super.onPause(); }
    @Override public void onWindowFocusChanged(boolean hasFocus) { super.onWindowFocusChanged(hasFocus); if(fullMode) { if(hasFocus) immersive(true);  } }
    @Override protected void onStop() { super.onStop(); if(fullMode) exitFull(); if(llm!=null) stop(); }
    @Override protected void onDestroy() {
        destroyed=true; dismissModelLoading(); fullMode=false; autoEnabled=false; fullSession++; main.removeCallbacks(autoLoop); if(handsFree!=null) handsFree.close(); main.removeCallbacks(poll); voice.close(); stopRecording(); llm.cancel(); worker.execute(llm::dispose); worker.shutdown(); super.onDestroy();
    }
    @Override public void onConfigurationChanged(android.content.res.Configuration config) {
        super.onConfigurationChanged(config);
        getTheme().applyStyle(R.style.AppTheme,true); loadPalette();
        if(fullMode) { eyes.invalidate(); immersive(true); return; }
        String draft=input==null?"":input.getText().toString();
        int selection=input==null?0:Math.max(0,input.getSelectionStart());
        boolean focused=input!=null && input.hasFocus();
        int scrollY=pageScroll==null?0:pageScroll.getScrollY();
        if(settings) showSettings(); else {
            showChat(); input.setText(draft); input.setSelection(Math.min(selection,draft.length()));
            if(focused) input.requestFocus();
        }
        ScrollView restored=pageScroll; restored.post(()->restored.scrollTo(0,scrollY));
    }
    @Override public void onBackPressed() { if(fullMode) exitFull(); else if(settings && selected!=null) showChat(); else super.onBackPressed(); }
}
