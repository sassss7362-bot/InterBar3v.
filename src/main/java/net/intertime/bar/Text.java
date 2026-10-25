package net.intertime.bar;

import org.bukkit.ChatColor;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Кольори без залежностей від нових API (працює на 1.16.5 і новіших):
 * &a - звичайні кольори, &#RRGGBB - hex, &lt;g:#від:#до[:b]&gt;текст&lt;/g&gt; - градієнт (:b = жирний).
 */
final class Text {

    private static final char S = '\u00a7';
    private static final Pattern GRAD = Pattern.compile("<g:(#[0-9a-fA-F]{6}):(#[0-9a-fA-F]{6})(:b)?>(.*?)</g>");
    private static final Pattern HEX = Pattern.compile("&#([0-9a-fA-F]{6})");

    private Text() {
    }

    /**
     * Перетворює текст у рядок з кольорами. Якщо результат довший за maxLen
     * (ліміт скорборду), градієнт стає "грубішим" - кілька літер одним кольором.
     */
    static String colorize(String s, int maxLen) {
        String r = s;
        for (int chunk = 1; chunk <= 16; chunk++) {
            r = render(s, chunk);
            if (r.length() <= maxLen) break;
        }
        if (r.length() > maxLen) r = r.substring(0, safeSplit(r, maxLen));
        return r;
    }

    private static String render(String s, int chunk) {
        Matcher m = GRAD.matcher(s);
        StringBuffer sb = new StringBuffer();
        while (m.find()) {
            m.appendReplacement(sb, Matcher.quoteReplacement(
                    gradient(m.group(4), m.group(1), m.group(2), m.group(3) != null, chunk)));
        }
        m.appendTail(sb);

        Matcher h = HEX.matcher(sb.toString());
        StringBuffer out = new StringBuffer();
        while (h.find()) {
            h.appendReplacement(out, Matcher.quoteReplacement(hex(h.group(1))));
        }
        h.appendTail(out);
        return ChatColor.translateAlternateColorCodes('&', out.toString());
    }

    static String hex(String six) {
        StringBuilder b = new StringBuilder().append(S).append('x');
        for (char c : six.toCharArray()) b.append(S).append(Character.toLowerCase(c));
        return b.toString();
    }

    private static String gradient(String text, String from, String to, boolean bold, int chunk) {
        int n = text.length();
        if (n == 0) return "";
        int groups = (n + chunk - 1) / chunk;
        int r1 = Integer.parseInt(from.substring(1, 3), 16), g1 = Integer.parseInt(from.substring(3, 5), 16),
                b1 = Integer.parseInt(from.substring(5, 7), 16);
        int r2 = Integer.parseInt(to.substring(1, 3), 16), g2 = Integer.parseInt(to.substring(3, 5), 16),
                b2 = Integer.parseInt(to.substring(5, 7), 16);
        StringBuilder sb = new StringBuilder();
        for (int g = 0; g < groups; g++) {
            double t = groups == 1 ? 0 : g / (double) (groups - 1);
            int r = (int) Math.round(r1 + (r2 - r1) * t);
            int gg = (int) Math.round(g1 + (g2 - g1) * t);
            int b = (int) Math.round(b1 + (b2 - b1) * t);
            sb.append(hex(String.format("%02x%02x%02x", r, gg, b)));
            if (bold) sb.append(S).append('l');
            sb.append(text, g * chunk, Math.min(n, (g + 1) * chunk));
        }
        return sb.toString();
    }

    /** Останній активний колір і форматування (з підтримкою hex) - для переносу в suffix. */
    static String lastColors(String s) {
        String color = "";
        StringBuilder fmt = new StringBuilder();
        for (int i = 0; i < s.length() - 1; i++) {
            if (s.charAt(i) != S) continue;
            char c = Character.toLowerCase(s.charAt(i + 1));
            if (c == 'x' && i + 14 <= s.length()) {
                color = s.substring(i, i + 14);
                fmt.setLength(0);
                i += 13;
            } else if ("0123456789abcdef".indexOf(c) >= 0) {
                color = "" + S + c;
                fmt.setLength(0);
                i++;
            } else if ("klmno".indexOf(c) >= 0) {
                fmt.append(S).append(c);
                i++;
            } else if (c == 'r') {
                color = "";
                fmt.setLength(0);
                i++;
            }
        }
        return color + fmt;
    }

    /** Місце розрізу рядка, яке не ламає кольорові коди. */
    static int safeSplit(String s, int limit) {
        int i = Math.min(limit, s.length());
        for (int k = Math.max(0, i - 14); k < i; k++) {
            if (s.charAt(k) == S && k + 1 < s.length() && s.charAt(k + 1) == 'x' && k + 14 > i) {
                i = k;
                break;
            }
        }
        if (i > 0 && s.charAt(i - 1) == S) i--;
        return i;
    }
}
