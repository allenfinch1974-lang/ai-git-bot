package org.remus.giteabot.review.inline;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Index of a unified diff ({@code git diff} / GitHub {@code application/vnd.github.v3.diff})
 * that knows which lines can carry an inline review comment.
 *
 * <p>GitHub only accepts a review comment on a line that is part of a diff hunk:
 * an added or context line on the {@code RIGHT} side, or a removed or context line
 * on the {@code LEFT} side. Anything else is rejected with HTTP 422 and the whole
 * review fails, so every model-produced anchor is checked here first.</p>
 *
 * <p>The index can also {@linkplain #annotate(String) annotate} a diff with explicit
 * line numbers (models are bad at counting from hunk headers) and
 * {@linkplain #retainHunksTouching(String, Map) cut} a diff down to the hunks that
 * overlap a set of lines (used to re-review only what new commits changed).</p>
 */
public final class DiffLineIndex {

    private static final Pattern HUNK = Pattern.compile("^@@ -(\\d+)(?:,(\\d+))? \\+(\\d+)(?:,(\\d+))? @@.*");

    /** Per-file information. */
    public static final class FileLines {
        private final String path;
        private final TreeSet<Integer> right = new TreeSet<>();
        private final TreeSet<Integer> left = new TreeSet<>();
        private final TreeSet<Integer> added = new TreeSet<>();
        private final TreeSet<Integer> deletionPoints = new TreeSet<>();   // new-file position where lines were removed
        private final List<int[]> rightHunks = new ArrayList<>();   // [start, endInclusive] on the new file
        private final List<int[]> leftHunks = new ArrayList<>();    // [start, endInclusive] on the old file

        FileLines(String path) {
            this.path = path;
        }

        public String path() {
            return path;
        }

        /** Lines (new file) that are added or context: commentable on RIGHT. */
        public Set<Integer> rightLines() {
            return Collections.unmodifiableSet(right);
        }

        /** Lines (old file) that are removed or context: commentable on LEFT. */
        public Set<Integer> leftLines() {
            return Collections.unmodifiableSet(left);
        }

        /** Added lines only (new file). */
        public Set<Integer> addedLines() {
            return Collections.unmodifiableSet(added);
        }

        TreeSet<Integer> lines(ReviewFinding.Side side) {
            return side == ReviewFinding.Side.LEFT ? left : right;
        }

        List<int[]> hunks(ReviewFinding.Side side) {
            return side == ReviewFinding.Side.LEFT ? leftHunks : rightHunks;
        }
    }

    private final Map<String, FileLines> files;

    private DiffLineIndex(Map<String, FileLines> files) {
        this.files = files;
    }

    /** Parses a unified diff. A {@code null} or blank diff yields an empty index. */
    public static DiffLineIndex parse(String diff) {
        Map<String, FileLines> files = new LinkedHashMap<>();
        if (diff == null || diff.isBlank()) {
            return new DiffLineIndex(files);
        }
        FileLines current = null;
        String oldPath = null;
        int oldLine = 0;
        int newLine = 0;
        int oldLeft = 0;
        int newLeft = 0;
        int[] curRight = null;
        int[] curLeft = null;
        for (String line : diff.split("\n", -1)) {
            boolean inHunk = oldLeft > 0 || newLeft > 0;
            if (!inHunk) {
                if (line.startsWith("diff --git ")) {
                    current = null;
                    oldPath = null;
                    continue;
                }
                if (line.startsWith("--- ")) {
                    oldPath = stripPrefix(line.substring(4));
                    continue;
                }
                if (line.startsWith("+++ ")) {
                    String newPath = stripPrefix(line.substring(4));
                    String path = newPath == null ? oldPath : newPath;
                    current = path == null ? null : files.computeIfAbsent(path, FileLines::new);
                    continue;
                }
                Matcher m = HUNK.matcher(line);
                if (m.matches() && current != null) {
                    oldLine = Integer.parseInt(m.group(1));
                    oldLeft = m.group(2) == null ? 1 : Integer.parseInt(m.group(2));
                    newLine = Integer.parseInt(m.group(3));
                    newLeft = m.group(4) == null ? 1 : Integer.parseInt(m.group(4));
                    curRight = new int[]{newLine, newLine - 1};
                    curLeft = new int[]{oldLine, oldLine - 1};
                    current.rightHunks.add(curRight);
                    current.leftHunks.add(curLeft);
                }
                continue;
            }
            if (line.startsWith("\\")) {
                continue;   // "\ No newline at end of file"
            }
            if (line.startsWith("+")) {
                current.right.add(newLine);
                current.added.add(newLine);
                curRight[1] = newLine;
                newLine++;
                newLeft--;
            } else if (line.startsWith("-")) {
                current.left.add(oldLine);
                current.deletionPoints.add(newLine);
                curLeft[1] = oldLine;
                oldLine++;
                oldLeft--;
            } else {
                // context line (" x", or an empty line from generators that strip the space)
                current.right.add(newLine);
                current.left.add(oldLine);
                curRight[1] = newLine;
                curLeft[1] = oldLine;
                newLine++;
                oldLine++;
                newLeft--;
                oldLeft--;
            }
            oldLeft = Math.max(oldLeft, 0);
            newLeft = Math.max(newLeft, 0);
        }
        return new DiffLineIndex(files);
    }

    private static String stripPrefix(String raw) {
        String p = raw.strip();
        int tab = p.indexOf('\t');
        if (tab >= 0) {
            p = p.substring(0, tab);
        }
        if (p.equals("/dev/null")) {
            return null;
        }
        if (p.startsWith("a/") || p.startsWith("b/")) {
            return p.substring(2);
        }
        return p;
    }

    public Set<String> paths() {
        return Collections.unmodifiableSet(files.keySet());
    }

    public FileLines file(String path) {
        return files.get(resolvePath(path));
    }

    public boolean isEmpty() {
        return files.isEmpty();
    }

    /**
     * Maps a path the model wrote to a path in the diff: exact match first, then a
     * stray {@code a/}/{@code b/} prefix, then a unique suffix match (the model
     * sometimes drops a leading directory). Returns {@code null} when unknown or ambiguous.
     */
    public String resolvePath(String path) {
        if (path == null || path.isBlank()) {
            return null;
        }
        String p = ReviewFinding.normalisePath(path);
        if (files.containsKey(p)) {
            return p;
        }
        if ((p.startsWith("a/") || p.startsWith("b/")) && files.containsKey(p.substring(2))) {
            return p.substring(2);
        }
        String match = null;
        for (String candidate : files.keySet()) {
            if (candidate.endsWith("/" + p)) {
                if (match != null) {
                    return null;
                }
                match = candidate;
            }
        }
        return match;
    }

    /** True when GitHub would accept a comment on this exact line/side. */
    public boolean isCommentable(String path, int line, ReviewFinding.Side side) {
        FileLines f = file(path);
        return f != null && f.lines(side).contains(line);
    }

    /**
     * Finds the commentable line closest to {@code line} within {@code maxDistance},
     * but only inside a hunk that contains or borders the requested line, so a finding
     * is never moved into unrelated code. Returns {@code null} when there is none.
     */
    public Integer nearestCommentable(String path, int line, ReviewFinding.Side side, int maxDistance) {
        FileLines f = file(path);
        if (f == null) {
            return null;
        }
        TreeSet<Integer> lines = f.lines(side);
        if (lines.contains(line)) {
            return line;
        }
        Integer best = null;
        for (int[] h : f.hunks(side)) {
            if (h[1] < h[0]) {
                continue;
            }
            if (line < h[0] - maxDistance || line > h[1] + maxDistance) {
                continue;
            }
            Integer lo = lines.floor(Math.min(line, h[1]));
            Integer hi = lines.ceiling(Math.max(line, h[0]));
            for (Integer c : new Integer[]{lo, hi}) {
                if (c == null || c < h[0] || c > h[1] || Math.abs(c - line) > maxDistance) {
                    continue;
                }
                if (best == null || Math.abs(c - line) < Math.abs(best - line)) {
                    best = c;
                }
            }
        }
        return best;
    }

    /**
     * Rewrites a diff so every hunk line carries its line number: {@code [R12] +added},
     * {@code [R13]  context}, {@code [L40] -removed}. File headers and hunk headers are
     * kept as they are. The model is told to copy these numbers into its findings.
     */
    public static String annotate(String diff) {
        if (diff == null || diff.isBlank()) {
            return diff;
        }
        StringBuilder out = new StringBuilder(diff.length() + diff.length() / 4);
        int oldLine = 0;
        int newLine = 0;
        int oldLeft = 0;
        int newLeft = 0;
        String[] lines = diff.split("\n", -1);
        for (int i = 0; i < lines.length; i++) {
            String line = lines[i];
            String rendered = line;
            boolean inHunk = oldLeft > 0 || newLeft > 0;
            if (!inHunk) {
                Matcher m = HUNK.matcher(line);
                if (m.matches()) {
                    oldLine = Integer.parseInt(m.group(1));
                    oldLeft = m.group(2) == null ? 1 : Integer.parseInt(m.group(2));
                    newLine = Integer.parseInt(m.group(3));
                    newLeft = m.group(4) == null ? 1 : Integer.parseInt(m.group(4));
                }
            } else if (line.startsWith("+")) {
                rendered = "[R" + newLine + "] " + line;
                newLine++;
                newLeft--;
            } else if (line.startsWith("-")) {
                rendered = "[L" + oldLine + "] " + line;
                oldLine++;
                oldLeft--;
            } else if (!line.startsWith("\\")) {
                rendered = "[R" + newLine + "] " + line;
                newLine++;
                oldLine++;
                newLeft--;
                oldLeft--;
            }
            oldLeft = Math.max(oldLeft, 0);
            newLeft = Math.max(newLeft, 0);
            out.append(rendered);
            if (i < lines.length - 1) {
                out.append('\n');
            }
        }
        return out.toString();
    }

    /**
     * Returns only the file sections and hunks of {@code diff} whose new-file range
     * overlaps one of {@code touched}'s lines for that file (path → new-file lines).
     * A file whose header is kept keeps its {@code diff --git}/{@code ---}/{@code +++}
     * lines. Returns an empty string when nothing overlaps.
     */
    public static String retainHunksTouching(String diff, Map<String, Set<Integer>> touched) {
        if (diff == null || diff.isBlank() || touched == null || touched.isEmpty()) {
            return "";
        }
        StringBuilder out = new StringBuilder();
        List<String> header = new ArrayList<>();
        List<String> hunk = new ArrayList<>();
        String path = null;
        String oldPath = null;
        boolean headerEmitted = false;
        int hunkStart = 0;
        int hunkEnd = -1;
        int newLine = 0;
        boolean inHunk = false;
        for (String line : diff.split("\n", -1)) {
            Matcher m = HUNK.matcher(line);
            boolean fileStart = line.startsWith("diff --git ");
            if (fileStart || m.matches()) {
                // close the pending hunk
                if (inHunk && keepHunk(touched.get(path), hunkStart, hunkEnd)) {
                    if (!headerEmitted) {
                        header.forEach(h -> out.append(h).append('\n'));
                        headerEmitted = true;
                    }
                    hunk.forEach(h -> out.append(h).append('\n'));
                }
                hunk.clear();
                inHunk = false;
            }
            if (fileStart) {
                header.clear();
                header.add(line);
                headerEmitted = false;
                path = null;
                oldPath = null;
                continue;
            }
            if (m.matches()) {
                inHunk = true;
                newLine = Integer.parseInt(m.group(3));
                hunkStart = newLine;
                hunkEnd = newLine - 1;
                hunk.add(line);
                continue;
            }
            if (!inHunk) {
                header.add(line);
                if (line.startsWith("--- ")) {
                    oldPath = stripPrefix(line.substring(4));
                } else if (line.startsWith("+++ ")) {
                    String np = stripPrefix(line.substring(4));
                    path = np == null ? oldPath : np;
                }
                continue;
            }
            hunk.add(line);
            if (line.startsWith("+") || line.startsWith(" ")) {
                hunkEnd = newLine;
                newLine++;
            } else if (line.startsWith("-")) {
                // a pure deletion still "touches" the position it was removed at
                hunkEnd = Math.max(hunkEnd, newLine);
            }
        }
        if (inHunk && keepHunk(touched.get(path), hunkStart, hunkEnd)) {
            if (!headerEmitted) {
                header.forEach(h -> out.append(h).append('\n'));
            }
            hunk.forEach(h -> out.append(h).append('\n'));
        }
        return out.toString();
    }

    private static boolean keepHunk(Set<Integer> lines, int start, int end) {
        if (lines == null || lines.isEmpty()) {
            return false;
        }
        int hi = Math.max(start, end);
        for (Integer l : lines) {
            if (l >= start && l <= hi) {
                return true;
            }
        }
        return false;
    }

    /**
     * New-file lines that a diff changed, per path: added lines, plus the position of
     * a pure deletion (so a hunk that only removed code still counts as changed).
     */
    public Map<String, Set<Integer>> changedRightLines() {
        Map<String, Set<Integer>> out = new LinkedHashMap<>();
        for (FileLines f : files.values()) {
            TreeSet<Integer> s = new TreeSet<>(f.added);
            for (Integer p : f.deletionPoints) {
                s.add(p);
                if (p > 1) {
                    s.add(p - 1);
                }
            }
            if (!s.isEmpty()) {
                out.put(f.path, s);
            }
        }
        return out;
    }
}
