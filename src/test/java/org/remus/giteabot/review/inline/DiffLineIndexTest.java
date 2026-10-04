package org.remus.giteabot.review.inline;

import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DiffLineIndexTest {

    static final String DIFF = String.join("\n",
            "diff --git a/app/pool.py b/app/pool.py",
            "index 111..222 100644",
            "--- a/app/pool.py",
            "+++ b/app/pool.py",
            "@@ -10,4 +10,5 @@ def setup():",
            " a = 1",
            "-b = 2",
            "+b = 3",
            "+c = 4",
            " d = 5",
            " e = 6",
            "@@ -40,2 +41,2 @@ def other():",
            " x = 1",
            "-y = 2",
            "+y = 3",
            "diff --git a/docs/new.md b/docs/new.md",
            "new file mode 100644",
            "--- /dev/null",
            "+++ b/docs/new.md",
            "@@ -0,0 +1,2 @@",
            "+# Title",
            "+text",
            "diff --git a/old.txt b/old.txt",
            "deleted file mode 100644",
            "--- a/old.txt",
            "+++ /dev/null",
            "@@ -1,2 +0,0 @@",
            "-gone",
            "-also gone",
            "");

    @Test
    void indexesRightAndLeftLinesPerFile() {
        DiffLineIndex idx = DiffLineIndex.parse(DIFF);

        assertEquals(Set.of("app/pool.py", "docs/new.md", "old.txt"), idx.paths());
        DiffLineIndex.FileLines pool = idx.file("app/pool.py");
        assertEquals(Set.of(10, 11, 12, 13, 14, 41, 42), pool.rightLines());
        assertEquals(Set.of(11, 12, 42), pool.addedLines());
        assertEquals(Set.of(10, 11, 12, 13, 40, 41), pool.leftLines());
        assertEquals(Set.of(1, 2), idx.file("docs/new.md").addedLines());
        assertEquals(Set.of(1, 2), idx.file("old.txt").leftLines());
        assertTrue(idx.file("old.txt").rightLines().isEmpty());
    }

    @Test
    void trailingNewlineDoesNotInventALine() {
        DiffLineIndex idx = DiffLineIndex.parse(DIFF);
        assertFalse(idx.isCommentable("docs/new.md", 3, ReviewFinding.Side.RIGHT));
        assertFalse(idx.isCommentable("app/pool.py", 43, ReviewFinding.Side.RIGHT));
    }

    @Test
    void commentableChecksSide() {
        DiffLineIndex idx = DiffLineIndex.parse(DIFF);
        assertTrue(idx.isCommentable("app/pool.py", 11, ReviewFinding.Side.RIGHT));
        assertTrue(idx.isCommentable("app/pool.py", 11, ReviewFinding.Side.LEFT));
        assertFalse(idx.isCommentable("app/pool.py", 20, ReviewFinding.Side.RIGHT));
        assertFalse(idx.isCommentable("old.txt", 1, ReviewFinding.Side.RIGHT));
        assertTrue(idx.isCommentable("old.txt", 1, ReviewFinding.Side.LEFT));
    }

    @Test
    void nearestCommentableSnapsOnlyInsideAHunk() {
        DiffLineIndex idx = DiffLineIndex.parse(DIFF);
        assertEquals(14, idx.nearestCommentable("app/pool.py", 16, ReviewFinding.Side.RIGHT, 3));
        assertEquals(41, idx.nearestCommentable("app/pool.py", 39, ReviewFinding.Side.RIGHT, 3));
        assertNull(idx.nearestCommentable("app/pool.py", 27, ReviewFinding.Side.RIGHT, 3));
        assertNull(idx.nearestCommentable("app/pool.py", 18, ReviewFinding.Side.RIGHT, 3));
        assertNull(idx.nearestCommentable("nope.py", 1, ReviewFinding.Side.RIGHT, 3));
    }

    @Test
    void resolvesStrayPrefixesAndUniqueSuffixes() {
        DiffLineIndex idx = DiffLineIndex.parse(DIFF);
        assertEquals("app/pool.py", idx.resolvePath("b/app/pool.py"));
        assertEquals("app/pool.py", idx.resolvePath("/app/pool.py"));
        assertEquals("app/pool.py", idx.resolvePath("pool.py"));
        assertNull(idx.resolvePath("other.py"));
        assertNull(idx.resolvePath(null));
    }

    @Test
    void ambiguousSuffixIsRefused() {
        String two = String.join("\n",
                "diff --git a/a/x.py b/a/x.py", "--- a/a/x.py", "+++ b/a/x.py", "@@ -1 +1 @@", "-1", "+2",
                "diff --git a/b/x.py b/b/x.py", "--- a/b/x.py", "+++ b/b/x.py", "@@ -1 +1 @@", "-1", "+2");
        DiffLineIndex idx = DiffLineIndex.parse(two);
        assertNull(idx.resolvePath("x.py"));
        assertEquals("a/x.py", idx.resolvePath("a/x.py"));
    }

    @Test
    void annotatePrefixesEveryHunkLineWithItsNumber() {
        String out = DiffLineIndex.annotate(DIFF);
        assertTrue(out.contains("[R10]  a = 1"));
        assertTrue(out.contains("[L11] -b = 2"));
        assertTrue(out.contains("[R11] +b = 3"));
        assertTrue(out.contains("[R12] +c = 4"));
        assertTrue(out.contains("[R42] +y = 3"));
        assertTrue(out.contains("[R1] +# Title"));
        assertTrue(out.contains("[L2] -also gone"));
        assertTrue(out.contains("+++ b/app/pool.py"));
        assertFalse(out.contains("[R] +++"));
    }

    @Test
    void retainHunksTouchingKeepsOnlyOverlappingHunksAndTheirHeader() {
        String out = DiffLineIndex.retainHunksTouching(DIFF, Map.of("app/pool.py", Set.of(42)));
        assertTrue(out.contains("+++ b/app/pool.py"));
        assertTrue(out.contains("@@ -40,2 +41,2 @@"));
        assertFalse(out.contains("@@ -10,4 +10,5 @@"));
        assertFalse(out.contains("docs/new.md"));
        assertEquals(Set.of(41, 42), DiffLineIndex.parse(out).file("app/pool.py").rightLines());
    }

    @Test
    void retainHunksTouchingReturnsEmptyWhenNothingOverlaps() {
        assertEquals("", DiffLineIndex.retainHunksTouching(DIFF, Map.of("app/pool.py", Set.of(30))));
        assertEquals("", DiffLineIndex.retainHunksTouching(DIFF, Map.of()));
    }

    @Test
    void changedRightLinesIncludeAddedLinesAndDeletionPoints() {
        String inc = String.join("\n",
                "diff --git a/app/pool.py b/app/pool.py", "--- a/app/pool.py", "+++ b/app/pool.py",
                "@@ -12,3 +12,2 @@", " c = 4", "-d = 5", " e = 6",
                "diff --git a/docs/new.md b/docs/new.md", "--- a/docs/new.md", "+++ b/docs/new.md",
                "@@ -2 +2 @@", "-text", "+better text", "");
        Map<String, Set<Integer>> changed = DiffLineIndex.parse(inc).changedRightLines();
        assertTrue(changed.get("app/pool.py").contains(13));
        assertEquals(Set.of(1, 2), changed.get("docs/new.md"));
    }

    @Test
    void emptyDiffGivesEmptyIndex() {
        assertTrue(DiffLineIndex.parse(null).isEmpty());
        assertTrue(DiffLineIndex.parse("  ").isEmpty());
    }
}
