package com.aismarttube.app;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Turns subtitle files (WebVTT, SRT, TTML/XML, YouTube json3) into plain readable text
 * that can be handed to an AI. Pure Java, no Android classes, so it is easy to test.
 */
public class TextClean {

    private static final Pattern TIMING = Pattern.compile(
            "^\\s*(\\d{1,2}:)?\\d{1,2}:\\d{2}[.,]\\d{1,3}\\s*-->.*$");
    private static final Pattern UTF8 = Pattern.compile("\"utf8\"\\s*:\\s*\"((?:[^\"\\\\]|\\\\.)*)\"");

    /** @param raw subtitle file content; @param maxChars cut-off (0 = no limit) */
    public static String fromSubtitle(String raw, int maxChars) {
        if (raw == null) return "";
        String s = raw.trim();
        if (s.isEmpty()) return "";

        String text;
        if (s.startsWith("{")) {
            text = fromJson3(s);
        } else {
            text = fromMarkup(s);
        }
        text = dedupeLines(text);
        if (maxChars > 0 && text.length() > maxChars) {
            text = text.substring(0, maxChars);
            int cut = text.lastIndexOf('\n');            // prefer ending on a whole line
            if (cut < maxChars / 2) cut = text.lastIndexOf(' ');
            if (cut > maxChars / 2) text = text.substring(0, cut);
        }
        return text.trim();
    }

    private static String fromJson3(String s) {
        StringBuilder sb = new StringBuilder();
        Matcher m = UTF8.matcher(s);
        while (m.find()) {
            String piece = unescapeJson(m.group(1));
            if (piece.equals("\n")) sb.append('\n');
            else sb.append(piece);
        }
        return sb.toString();
    }

    private static String fromMarkup(String s) {
        // line breaks inside XML/TTML
        s = s.replaceAll("(?i)<br\\s*/?>", "\n").replaceAll("(?i)</p>", "\n");
        // drop all remaining tags (<c>, <v>, <span>, <00:00:01.000> ...)
        s = s.replaceAll("<[^>]*>", "");
        s = unescapeHtml(s);
        StringBuilder sb = new StringBuilder();
        for (String line : s.split("\\r?\\n")) {
            String t = line.trim();
            if (t.isEmpty()) continue;
            if (t.startsWith("WEBVTT") || t.startsWith("NOTE") || t.startsWith("Kind:")
                    || t.startsWith("Language:") || t.startsWith("STYLE") || t.startsWith("REGION")) continue;
            if (TIMING.matcher(t).matches()) continue;
            if (t.matches("^\\d+$")) continue; // SRT cue numbers
            sb.append(t).append('\n');
        }
        return sb.toString();
    }

    /** Auto-generated captions repeat the same line in consecutive cues — keep one. */
    private static String dedupeLines(String text) {
        StringBuilder sb = new StringBuilder();
        String prev = null;
        for (String line : text.split("\\r?\\n")) {
            String t = line.trim().replaceAll("\\s+", " ");
            if (t.isEmpty()) continue;
            if (t.equals(prev)) continue;
            sb.append(t).append('\n');
            prev = t;
        }
        return sb.toString();
    }

    private static String unescapeHtml(String s) {
        return s.replace("&amp;", "&").replace("&lt;", "<").replace("&gt;", ">")
                .replace("&quot;", "\"").replace("&#39;", "'").replace("&apos;", "'")
                .replace("&nbsp;", " ");
    }

    private static String unescapeJson(String s) {
        StringBuilder sb = new StringBuilder(s.length());
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c != '\\' || i + 1 >= s.length()) { sb.append(c); continue; }
            char n = s.charAt(++i);
            switch (n) {
                case 'n': sb.append('\n'); break;
                case 't': sb.append(' '); break;
                case 'r': break;
                case '"': sb.append('"'); break;
                case '\\': sb.append('\\'); break;
                case '/': sb.append('/'); break;
                case 'u':
                    if (i + 4 < s.length()) {
                        try {
                            sb.append((char) Integer.parseInt(s.substring(i + 1, i + 5), 16));
                            i += 4;
                        } catch (Exception e) { sb.append('u'); }
                    } else sb.append('u');
                    break;
                default: sb.append(n);
            }
        }
        return sb.toString();
    }
}
