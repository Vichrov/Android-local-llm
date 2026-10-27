package com.vichrov.localai;
import org.junit.Test;
import static org.junit.Assert.*;
public class SpeechChunksTest {
    @Test public void doesNotSpeakPartialWords() {
        SpeechChunks s=new SpeechChunks(); s.add("Привет как ваши де");
        assertEquals("Привет как ваши", s.take(false,true));
        s.add("ла сегодня"); assertEquals("",s.take(false,false));
        assertEquals("дела сегодня",s.take(true,false));
    }
    @Test public void sentenceCanStartBeforeCompletion() {
        SpeechChunks s=new SpeechChunks(); s.add("Здравствуйте! Как ваши дела?");
        assertEquals("Здравствуйте!",s.take(false,false));
        assertEquals("Как ваши дела?",s.take(true,false));
    }
    @Test public void stopDiscardsPreviousUtterance() {
        SpeechChunks s=new SpeechChunks(); s.add("старый ответ"); s.clear();
        s.add("новый ответ"); assertEquals("новый ответ",s.take(true,false));
    }
}
