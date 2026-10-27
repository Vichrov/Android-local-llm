package com.vichrov.localai;

/** Only releases complete words during generation; never slices Cyrillic code points. */
final class SpeechChunks {
    private final StringBuilder pending = new StringBuilder();
    void add(String s) { pending.append(s); }
    void clear() { pending.setLength(0); }
    String take(boolean finish, boolean timer) {
        if (pending.length() == 0) return "";
        int cut = finish ? pending.length() : 0;
        if (!finish) {
            int words = 0;
            boolean inWord = false;
            for (int i = 0; i < pending.length(); i++) {
                char c = pending.charAt(i);
                if (Character.isWhitespace(c)) {
                    if (inWord) words++;
                    inWord = false;
                    if (words >= (timer ? 3 : 7)) { cut = i + 1; break; }
                } else inWord = true;
                if (".!?;:\n".indexOf(c) >= 0 && i >= 12) { cut = i + 1; break; }
            }
        }
        if (cut == 0) return "";
        String s = pending.substring(0, cut).replaceAll("[*#`_]", "").trim();
        pending.delete(0, cut);
        return s;
    }
}
