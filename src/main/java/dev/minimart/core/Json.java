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
}
