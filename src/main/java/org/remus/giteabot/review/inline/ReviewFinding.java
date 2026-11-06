package org.remus.giteabot.review.inline;

import java.util.Locale;

/**
 * One structured finding returned by the model for an inline review.
 *
 * <p>{@code line} is a line number in the new file ({@link Side#RIGHT}) or the old
 * file ({@link Side#LEFT}); it is validated against the PR diff by
 * {@link DiffLineIndex} before it is posted as an inline comment.</p>
 */
public record ReviewFinding(String path, Integer line, Side side, Severity severity, String title, String body) {

    /** How serious a finding is. {@link #MUST_FIX} blocks the merge; {@link #CONSIDER} is advice. */
    public enum Severity {
        MUST_FIX, CONSIDER;

        /** Lenient parse: {@code must_fix}, {@code must-fix}, {@code MUST FIX}, {@code blocker} … Unknown → CONSIDER. */
        public static Severity parse(String raw) {
            if (raw == null) {
                return CONSIDER;
            }
            String s = raw.trim().toLowerCase(Locale.ROOT).replace('-', '_').replace(' ', '_');
            return switch (s) {
                case "must_fix", "mustfix", "blocker", "critical", "high", "error" -> MUST_FIX;
                default -> CONSIDER;
            };
        }

        /** Wire name used in prompts and in the hidden comment marker. */
        public String wireName() {
            return name().toLowerCase(Locale.ROOT);
        }
    }

    /** Which side of the diff the line number refers to (GitHub's {@code side}). */
    public enum Side {
        RIGHT, LEFT;

        public static Side parse(String raw) {
            if (raw != null && raw.trim().equalsIgnoreCase("LEFT")) {
                return LEFT;
            }
            return RIGHT;
        }
    }

    public ReviewFinding {
        side = side == null ? Side.RIGHT : side;
        severity = severity == null ? Severity.CONSIDER : severity;
        title = title == null ? "" : title.strip();
        body = body == null ? "" : body.strip();
        path = normalisePath(path);
    }

    public boolean isMustFix() {
        return severity == Severity.MUST_FIX;
    }

    /** Returns a copy anchored at another line (used when a near-miss is snapped onto the diff). */
    public ReviewFinding withLine(int newLine) {
        return new ReviewFinding(path, newLine, side, severity, title, body);
    }

    static String normalisePath(String path) {
        if (path == null) {
            return "";
        }
        String p = path.strip();
        while (p.startsWith("/")) {
            p = p.substring(1);
        }
        return p;
    }
}
