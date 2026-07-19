package dev.minipay;

/** A very small hand-rolled JSON reader/writer. A codec, not a framework. */
public final class Json {

    private Json() {}

    public static String esc(String s) {
        if (s == null) return "";
        StringBuilder b = new StringBuilder(s.length() + 8);
        for (char ch : s.toCharArray()) {
            switch (ch) {
                case '"' -> b.append("\\\"");
                case '\\' -> b.append("\\\\");
                case '\n' -> b.append("\\n");
                case '\r' -> b.append("\\r");
                case '\t' -> b.append("\\t");
                default -> { if (ch < 0x20) b.append(String.format("\\u%04x", (int) ch)); else b.append(ch); }
            }
        }
        return b.toString();
    }

    /** Pull a string value out of flat JSON. Tolerant of whitespace. */
    public static String str(String json, String key) {
        String needle = "\"" + key + "\"";
        int i = json.indexOf(needle);
        if (i < 0) return null;
        i = json.indexOf(':', i + needle.length());
        if (i < 0) return null;
        i++;
        while (i < json.length() && Character.isWhitespace(json.charAt(i))) i++;
        if (i >= json.length()) return null;
        if (json.charAt(i) == '"') {
            StringBuilder b = new StringBuilder();
            for (int j = i + 1; j < json.length(); j++) {
                char ch = json.charAt(j);
                if (ch == '\\' && j + 1 < json.length()) { b.append(json.charAt(++j)); continue; }
                if (ch == '"') break;
                b.append(ch);
            }
            return b.toString();
        }
        int end = i;
        while (end < json.length() && "-+.0123456789eE".indexOf(json.charAt(end)) >= 0) end++;
        return end > i ? json.substring(i, end) : null;
    }

    // ------------------------------------------------------- reading a decision

    /**
     * A TOP-LEVEL boolean, or null when the key is absent or holds something
     * else.
     *
     * Null rather than false, because "the issuer said no" and "the issuer did
     * not answer the question" are different facts and only the caller knows
     * which of them is safe to act on. Every caller here happens to treat both
     * as a decline, and it should have to say so.
     */
    public static Boolean bool(String json, String key) {
        String v = top(json, key);
        if ("true".equals(v)) return Boolean.TRUE;
        if ("false".equals(v)) return Boolean.FALSE;
        return null;
    }

    /** A TOP-LEVEL string value, unquoted, or null if the key is absent or is
     *  not a string. */
    public static String text(String json, String key) {
        String v = top(json, key);
        if (v == null || v.length() < 2 || v.charAt(0) != '"') return null;
        return unescape(v.substring(1, v.length() - 1));
    }

    /**
     * The raw token a TOP-LEVEL key holds, or null.
     *
     * str() is a scanner: it finds the first occurrence of a key ANYWHERE in the
     * document, inside nested objects and inside quoted text alike. That is a
     * known cost when reading a flat message and it is not a cost worth paying
     * when reading a DECISION. An issuer that declines and explains itself by
     * quoting the request back is sending a perfectly correct answer that a
     * scanner reads as an approval, because the bytes it hunts for are sitting
     * in the explanation.
     *
     * So this walks the object rather than searching it: string boundaries and
     * their escapes are tracked, a nested value is stepped over whole rather
     * than looked inside, and only members of the OUTERMOST object can answer.
     * Anything malformed answers null, which is the safe direction for the one
     * thing this is used for.
     */
    private static String top(String json, String key) {
        if (json == null) return null;
        int i = ws(json, 0);
        if (i >= json.length() || json.charAt(i) != '{') return null;
        i = ws(json, i + 1);
        if (i < json.length() && json.charAt(i) == '}') return null;      // an empty object says nothing
        String found = null;
        while (true) {
            if (i >= json.length() || json.charAt(i) != '"') return null;
            int nameEnd = endOfString(json, i);
            if (nameEnd < 0) return null;
            String name = unescape(json.substring(i + 1, nameEnd));
            i = ws(json, nameEnd + 1);
            if (i >= json.length() || json.charAt(i) != ':') return null;
            i = ws(json, i + 1);
            int valueEnd = endOfValue(json, i);
            if (valueEnd < 0) return null;
            if (name.equals(key)) {
                // A KEY GIVEN TWICE IS NOT AN ANSWER, IT IS A QUESTION.
                // Which one wins is genuinely reader-dependent, so two readers
                // of one body could reach opposite decisions about the same
                // money. Refusing is the only reading that cannot be gamed.
                if (found != null) return null;
                found = json.substring(i, valueEnd);
            }
            // A member is followed by another or by the end, and by nothing
            // else. Without this a missing comma reads as a well formed object,
            // which would make the promise above ("anything malformed answers
            // null") false in exactly the direction that approves a payment.
            i = ws(json, valueEnd);
            if (i >= json.length()) return null;              // ran out before the object closed
            char sep = json.charAt(i);
            if (sep == '}') return found;
            if (sep != ',') return null;
            i = ws(json, i + 1);
        }
    }

    private static int ws(String s, int i) {
        while (i < s.length() && Character.isWhitespace(s.charAt(i))) i++;
        return i;
    }

    /** The index of the quote that closes the string starting at i. */
    private static int endOfString(String s, int i) {
        for (int j = i + 1; j < s.length(); j++) {
            char ch = s.charAt(j);
            if (ch == '\\') { j++; continue; }                // an escaped quote is not the end
            if (ch == '"') return j;
        }
        return -1;
    }

    /** One past the end of the value starting at i, whatever kind it is. */
    private static int endOfValue(String s, int i) {
        if (i >= s.length()) return -1;
        char ch = s.charAt(i);
        if (ch == '"') { int e = endOfString(s, i); return e < 0 ? -1 : e + 1; }
        if (ch == '{' || ch == '[') {
            int depth = 0;
            for (int j = i; j < s.length(); j++) {
                char c = s.charAt(j);
                // a brace inside a string is text, not structure
                if (c == '"') { int e = endOfString(s, j); if (e < 0) return -1; j = e; continue; }
                if (c == '{' || c == '[') depth++;
                else if (c == '}' || c == ']') { if (--depth == 0) return j + 1; }
            }
            return -1;
        }
        int j = i;
        while (j < s.length() && ",}] \t\r\n".indexOf(s.charAt(j)) < 0) j++;
        return j == i ? -1 : j;
    }

    /** As str() does it: drop the backslash and keep what follows. */
    private static String unescape(String s) {
        if (s.indexOf('\\') < 0) return s;
        StringBuilder b = new StringBuilder(s.length());
        for (int i = 0; i < s.length(); i++) {
            char ch = s.charAt(i);
            if (ch == '\\' && i + 1 < s.length()) { b.append(s.charAt(++i)); continue; }
            b.append(ch);
        }
        return b.toString();
    }
}
