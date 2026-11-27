package org.telegram.messenger.timegram.plugins;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * A small strict JSON parser, used instead of org.json on purpose:
 * no comments, no single quotes, no trailing commas, no duplicate keys, limited nesting depth.
 * Objects become LinkedHashMap, arrays ArrayList, numbers Long or Double, plus String, Boolean and null.
 */
final class PluginJson {

    static final int MAX_DEPTH = 6;

    private final String s;
    private int pos;

    private PluginJson(String s) {
        this.s = s;
    }

    static Object parse(String text) throws PluginException {
        PluginJson p = new PluginJson(text);
        p.skipWhitespace();
        Object value = p.readValue(0);
        p.skipWhitespace();
        if (p.pos != p.s.length()) {
            throw p.error("unexpected data after the end of JSON");
        }
        return value;
    }

    private PluginException error(String message) {
        return new PluginException("Invalid JSON at position " + pos + ": " + message);
    }

    private void skipWhitespace() {
        while (pos < s.length()) {
            char c = s.charAt(pos);
            if (c == ' ' || c == '\t' || c == '\n' || c == '\r') {
                pos++;
            } else {
                break;
            }
        }
    }

    private Object readValue(int depth) throws PluginException {
        if (depth > MAX_DEPTH) {
            throw error("nesting is too deep");
        }
        if (pos >= s.length()) {
            throw error("unexpected end");
        }
        char c = s.charAt(pos);
        switch (c) {
            case '{':
                return readObject(depth);
            case '[':
                return readArray(depth);
            case '"':
                return readString();
            case 't':
                expectWord("true");
                return Boolean.TRUE;
            case 'f':
                expectWord("false");
                return Boolean.FALSE;
            case 'n':
                expectWord("null");
                return null;
            default:
                if (c == '-' || (c >= '0' && c <= '9')) {
                    return readNumber();
                }
                throw error("unexpected character '" + c + "'");
        }
    }

    private void expectWord(String word) throws PluginException {
        if (!s.startsWith(word, pos)) {
            throw error("expected " + word);
        }
        pos += word.length();
    }

    private Map<String, Object> readObject(int depth) throws PluginException {
        pos++; // {
        Map<String, Object> map = new LinkedHashMap<>();
        skipWhitespace();
        if (pos < s.length() && s.charAt(pos) == '}') {
            pos++;
            return map;
        }
        while (true) {
            skipWhitespace();
            if (pos >= s.length() || s.charAt(pos) != '"') {
                throw error("expected a string key");
            }
            String key = readString();
            if (map.containsKey(key)) {
                throw error("duplicate key \"" + key + "\"");
            }
            skipWhitespace();
            if (pos >= s.length() || s.charAt(pos) != ':') {
                throw error("expected ':'");
            }
            pos++;
            skipWhitespace();
            map.put(key, readValue(depth + 1));
            skipWhitespace();
            if (pos >= s.length()) {
                throw error("unexpected end");
            }
            char c = s.charAt(pos++);
            if (c == '}') {
                return map;
            }
            if (c != ',') {
                throw error("expected ',' or '}'");
            }
        }
    }

    private List<Object> readArray(int depth) throws PluginException {
        pos++; // [
        List<Object> list = new ArrayList<>();
        skipWhitespace();
        if (pos < s.length() && s.charAt(pos) == ']') {
            pos++;
            return list;
        }
        while (true) {
            skipWhitespace();
            list.add(readValue(depth + 1));
            skipWhitespace();
            if (pos >= s.length()) {
                throw error("unexpected end");
            }
            char c = s.charAt(pos++);
            if (c == ']') {
                return list;
            }
            if (c != ',') {
                throw error("expected ',' or ']'");
            }
        }
    }

    private String readString() throws PluginException {
        pos++; // opening quote
        StringBuilder sb = new StringBuilder();
        while (true) {
            if (pos >= s.length()) {
                throw error("unterminated string");
            }
            char c = s.charAt(pos++);
            if (c == '"') {
                return sb.toString();
            }
            if (c < 0x20) {
                throw error("control character in string");
            }
            if (c != '\\') {
                sb.append(c);
                continue;
            }
            if (pos >= s.length()) {
                throw error("unterminated escape");
            }
            char e = s.charAt(pos++);
            switch (e) {
                case '"': sb.append('"'); break;
                case '\\': sb.append('\\'); break;
                case '/': sb.append('/'); break;
                case 'b': sb.append('\b'); break;
                case 'f': sb.append('\f'); break;
                case 'n': sb.append('\n'); break;
                case 'r': sb.append('\r'); break;
                case 't': sb.append('\t'); break;
                case 'u':
                    if (pos + 4 > s.length()) {
                        throw error("bad \\u escape");
                    }
                    int code = 0;
                    for (int i = 0; i < 4; i++) {
                        int d = Character.digit(s.charAt(pos + i), 16);
                        if (d < 0) {
                            throw error("bad \\u escape");
                        }
                        code = code * 16 + d;
                    }
                    pos += 4;
                    sb.append((char) code);
                    break;
                default:
                    throw error("bad escape \\" + e);
            }
        }
    }

    private Object readNumber() throws PluginException {
        int start = pos;
        if (s.charAt(pos) == '-') {
            pos++;
        }
        int digitsStart = pos;
        while (pos < s.length() && Character.isDigit(s.charAt(pos))) {
            pos++;
        }
        if (pos == digitsStart) {
            throw error("bad number");
        }
        if (s.charAt(digitsStart) == '0' && pos - digitsStart > 1) {
            throw error("leading zeros are not allowed");
        }
        boolean fraction = false;
        if (pos < s.length() && s.charAt(pos) == '.') {
            fraction = true;
            pos++;
            int f = pos;
            while (pos < s.length() && Character.isDigit(s.charAt(pos))) {
                pos++;
            }
            if (pos == f) {
                throw error("bad number");
            }
        }
        if (pos < s.length() && (s.charAt(pos) == 'e' || s.charAt(pos) == 'E')) {
            throw error("exponents are not allowed");
        }
        String number = s.substring(start, pos);
        if (number.length() > 18) {
            throw error("number is too long");
        }
        try {
            return fraction ? (Object) Double.valueOf(number) : (Object) Long.valueOf(number);
        } catch (NumberFormatException e) {
            throw error("bad number");
        }
    }
}
