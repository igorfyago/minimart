package dev.b4rruf3t.sso.client;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * A small, strict JSON reader. No dependency, same doctrine as the rest of the
 * bank: no framework unless the framework is load-bearing.
 *
 * It exists because this client used to read claims with regular expressions,
 * and a regex does not parse JSON, it pattern-matches text that looks like it.
 * Two consequences, both security bugs:
 *
 *   A first-match regex takes the FIRST occurrence of a key. Python's
 *   json.loads takes the LAST. So a token whose payload carries "sub" twice
 *   authenticated as one person in Java and a different person in Python ·
 *   one token, one valid signature, two identities depending on which service
 *   you handed it to.
 *
 *   The value pattern "([^"]*)" stops at the first quote, so a subject
 *   containing a quote could close the string early and smuggle a second
 *   "sub" that the regex would then find first.
 *
 * The fix for both is to parse properly AND to REJECT duplicate keys outright
 * rather than pick a winner. Picking a winner is what put the two clients out
 * of step; refusing means every reader agrees, whatever language it is in.
 * A legitimate issuer never emits a duplicate claim.
 */
final class Json {

    /** Thrown for malformed JSON and for any duplicated object key. */
    static final class MalformedException extends RuntimeException {
        MalformedException(String message) { super(message); }
    }

    private final String src;
    private int pos;

    private Json(String src) { this.src = src; }

    /** Parse a complete JSON document. Trailing content is an error. */
    static Object parse(String text) {
        if (text == null) throw new MalformedException("no input");
        Json p = new Json(text);
        p.skipWhitespace();
        Object value = p.readValue(0);
        p.skipWhitespace();
        if (p.pos != p.src.length()) throw new MalformedException("trailing content");
        return value;
    }

    /** Parse and require a JSON object at the top level. */
    @SuppressWarnings("unchecked")
    static Map<String, Object> parseObject(String text) {
        Object v = parse(text);
        if (!(v instanceof Map)) throw new MalformedException("expected an object");
        return (Map<String, Object>) v;
    }

    /** A string claim, or null when absent. Wrong type is an error, not a null. */
    static String string(Map<String, Object> obj, String field) {
        Object v = obj.get(field);
        if (v == null) return null;
        if (!(v instanceof String)) throw new MalformedException(field + " is not a string");
        return (String) v;
    }

    /** A numeric claim as a long, or null when absent. */
    static Long number(Map<String, Object> obj, String field) {
        Object v = obj.get(field);
        if (v == null) return null;
        if (!(v instanceof Double)) throw new MalformedException(field + " is not a number");
        double d = (Double) v;
        return (long) d;
    }

    // Nesting is bounded so a hostile token cannot drive the parser into a
    // StackOverflowError with a few thousand opening brackets.
    private static final int MAX_DEPTH = 64;

    private Object readValue(int depth) {
        if (depth > MAX_DEPTH) throw new MalformedException("nested too deeply");
        if (pos >= src.length()) throw new MalformedException("unexpected end");
        char c = src.charAt(pos);
        switch (c) {
            case '{': return readObject(depth);
            case '[': return readArray(depth);
            case '"': return readString();
            case 't': expect("true"); return Boolean.TRUE;
            case 'f': expect("false"); return Boolean.FALSE;
            case 'n': expect("null"); return null;
            default: return readNumber();
        }
    }

    private Map<String, Object> readObject(int depth) {
        Map<String, Object> out = new LinkedHashMap<>();
        pos++;                                   // consume '{'
        skipWhitespace();
        if (peek() == '}') { pos++; return out; }
        while (true) {
            skipWhitespace();
            if (peek() != '"') throw new MalformedException("expected a key");
            String key = readString();
            skipWhitespace();
            if (peek() != ':') throw new MalformedException("expected ':'");
            pos++;
            skipWhitespace();
            Object value = readValue(depth + 1);
            // THE DUPLICATE RULE. Not a style preference: this is the check
            // that keeps every client on the same identity for a given token.
            if (out.containsKey(key)) throw new MalformedException("duplicate key: " + key);
            out.put(key, value);
            skipWhitespace();
            char c = peek();
            if (c == ',') { pos++; continue; }
            if (c == '}') { pos++; return out; }
            throw new MalformedException("expected ',' or '}'");
        }
    }

    private List<Object> readArray(int depth) {
        List<Object> out = new ArrayList<>();
        pos++;                                   // consume '['
        skipWhitespace();
        if (peek() == ']') { pos++; return out; }
        while (true) {
            skipWhitespace();
            out.add(readValue(depth + 1));
            skipWhitespace();
            char c = peek();
            if (c == ',') { pos++; continue; }
            if (c == ']') { pos++; return out; }
            throw new MalformedException("expected ',' or ']'");
        }
    }

    private String readString() {
        pos++;                                   // consume the opening quote
        StringBuilder sb = new StringBuilder();
        while (true) {
            if (pos >= src.length()) throw new MalformedException("unterminated string");
            char c = src.charAt(pos++);
            if (c == '"') return sb.toString();
            if (c != '\\') { sb.append(c); continue; }
            if (pos >= src.length()) throw new MalformedException("unterminated escape");
            char esc = src.charAt(pos++);
            switch (esc) {
                case '"':  sb.append('"');  break;
                case '\\': sb.append('\\'); break;
                case '/':  sb.append('/');  break;
                case 'b':  sb.append('\b'); break;
                case 'f':  sb.append('\f'); break;
                case 'n':  sb.append('\n'); break;
                case 'r':  sb.append('\r'); break;
                case 't':  sb.append('\t'); break;
                case 'u':
                    if (pos + 4 > src.length()) throw new MalformedException("bad \\u escape");
                    sb.append((char) Integer.parseInt(src.substring(pos, pos + 4), 16));
                    pos += 4;
                    break;
                default: throw new MalformedException("bad escape: \\" + esc);
            }
        }
    }

    private Double readNumber() {
        int start = pos;
        if (peek() == '-') pos++;
        while (pos < src.length() && isNumberChar(src.charAt(pos))) pos++;
        if (start == pos) throw new MalformedException("expected a value");
        try {
            return Double.valueOf(src.substring(start, pos));
        } catch (NumberFormatException e) {
            throw new MalformedException("bad number");
        }
    }

    private static boolean isNumberChar(char c) {
        return (c >= '0' && c <= '9') || c == '.' || c == 'e' || c == 'E' || c == '+' || c == '-';
    }

    private char peek() {
        if (pos >= src.length()) throw new MalformedException("unexpected end");
        return src.charAt(pos);
    }

    private void expect(String literal) {
        if (!src.startsWith(literal, pos)) throw new MalformedException("expected " + literal);
        pos += literal.length();
    }

    private void skipWhitespace() {
        while (pos < src.length()) {
            char c = src.charAt(pos);
            if (c == ' ' || c == '\t' || c == '\n' || c == '\r') pos++;
            else break;
        }
    }
}
