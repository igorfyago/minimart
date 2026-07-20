package dev.minimart.core;

/** A tiny JSON codec. Deliberately duplicated per service: a shared model
 *  object between a merchant and its processor would be a coupling neither
 *  should have. */
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

    /** Build a flat object from alternating key, value pairs. Every value is
     *  written as a string, including the numbers: an event payload is a
     *  CONTRACT, and a reader that must guess whether "amount" arrives as
     *  79 or 79.00 or "79.00" is a reader that will eventually guess wrong. */
    public static String obj(String... kv) {
        if (kv.length % 2 != 0) throw new IllegalArgumentException("obj() needs pairs");
        StringBuilder b = new StringBuilder("{");
        for (int i = 0; i < kv.length; i += 2) {
            if (i > 0) b.append(',');
            b.append('"').append(esc(kv[i])).append("\":\"").append(esc(kv[i + 1])).append('"');
        }
        return b.append('}').toString();
    }

    /**
     * Every value for a key, in document order.
     *
     * str() returns only the FIRST match in the whole document, which is right
     * for a flat object and useless for an array of them: a clearing batch of
     * two hundred lines would read back as one. Still a scanner rather than a
     * parser, so it cannot tell a nested key from a top-level one, and for the
     * flat arrays these messages carry that is sufficient. Stated here so the
     * limit is a known cost rather than a surprise later.
     */
    public static java.util.List<String> each(String json, String key) {
        java.util.List<String> out = new java.util.ArrayList<>();
        if (json == null) return out;
        String needle = "\"" + key + "\"";
        int from = 0;
        while (true) {
            int i = json.indexOf(needle, from);
            if (i < 0) return out;
            String v = str(json.substring(i), key);
            if (v != null) out.add(v);
            from = i + needle.length();
        }
    }

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

    // ------------------------------------------------------------ reading a row

    /**
     * The string a TOP-LEVEL key holds, unquoted, or null when the key is
     * absent or holds something that is not a string.
     *
     * Use this rather than str() for any document that MAY nest, which in
     * practice means anything another service composed. str() is right for the
     * flat messages this system mints itself and wrong the moment the shape is
     * somebody else's to change.
     */
    public static String text(String json, String key) {
        String v = top(json, key);
        if (v == null || v.length() < 2 || v.charAt(0) != '"') return null;
        return unescape(v.substring(1, v.length() - 1));
    }

    /**
     * The raw token a TOP-LEVEL key holds, or null.
     *
     * str() is a scanner: it answers with the FIRST occurrence of a key
     * anywhere in what it is given, inside nested objects and inside quoted
     * text alike. That is a known and acceptable cost when reading a flat
     * message. It stops being acceptable the moment the document can nest,
     * because the failure is not a crash and does not read as one. A payment
     * row that names the card it used before it names itself is a perfectly
     * correct row, and a scanner hands back the card's id as the payment's.
     * Depending on a peer to keep writing its keys in the order we happen to
     * expect is not a promise anybody made us.
     *
     * So this walks the object rather than searching it: string boundaries and
     * their escapes are tracked, a nested value is stepped over whole rather
     * than looked inside, and only members of the OUTERMOST object can answer.
     * Anything malformed answers null, which for every caller here means
     * skipping the row, and skipping a row an audit could not read is the one
     * outcome that is visible later.
     *
     * Deliberately the same walk as dev.minipay.Json.top, for the same reason
     * the whole codec is duplicated: the merchant and its processor agreeing
     * about bytes is not the same thing as them sharing a class.
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
                // of one row could attribute it to two different merchants.
                // Refusing is the only reading that cannot be gamed.
                if (found != null) return null;
                found = json.substring(i, valueEnd);
            }
            // A member is followed by another or by the end, and by nothing
            // else. Without this a missing comma reads as a well formed object,
            // which would make the promise above ("anything malformed answers
            // null") false in exactly the cases it was written for.
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
