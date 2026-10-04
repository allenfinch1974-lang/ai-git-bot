package org.remus.giteabot.review.inline;

import org.junit.jupiter.api.Test;
import org.remus.giteabot.repository.model.InlineReviewDraft;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class InlineReviewComposerTest {

    private final DiffLineIndex index = DiffLineIndex.parse(DiffLineIndexTest.DIFF);
    private final InlineReviewComposer composer = new InlineReviewComposer();

    private static ReviewFinding must(String path, Integer line, String title) {
        return new ReviewFinding(path, line, ReviewFinding.Side.RIGHT, ReviewFinding.Severity.MUST_FIX, title, "why");
    }

    private static ReviewFinding consider(String path, Integer line, String title) {
        return new ReviewFinding(path, line, ReviewFinding.Side.RIGHT, ReviewFinding.Severity.CONSIDER, title, "maybe");
    }

    @Test
    void anchorsValidFindingsAndCountsMustFix() {
        var out = composer.compose("Summary.", List.of(must("app/pool.py", 11, "Bug"),
                consider("app/pool.py", 42, "Nit")), index, "abcdef1234567", List.of(), List.of());

        assertEquals(2, out.comments().size());
        assertEquals(1, out.mustFix());
        assertEquals(1, out.consider());
        InlineReviewDraft c = out.comments().getFirst();
        assertEquals("app/pool.py", c.path());
        assertEquals(11, c.line());
        assertEquals("RIGHT", c.side());
        assertTrue(c.body().startsWith("🔴 **Must fix:** Bug"));
        assertTrue(c.body().endsWith("<!-- ai-git-bot:finding severity=must_fix -->"));
        assertTrue(out.body().contains("**Must fix: 1** · Consider: 1 · at `abcdef1`"));
        assertTrue(out.body().contains("<!-- ai-git-bot:summary must_fix=1 consider=1 head=abcdef1234567 -->"));
    }

    @Test
    void snapsNearMissesAndFoldsUnanchorableFindingsIntoTheSummary() {
        var out = composer.compose("", List.of(must("app/pool.py", 16, "Off by two"),
                must("app/pool.py", 27, "Far away"), consider("unknown.py", 3, "Elsewhere"),
                consider("app/pool.py", null, "No line")), index, null, List.of(), List.of());

        assertEquals(1, out.comments().size());
        assertEquals(14, out.comments().getFirst().line());
        assertEquals(3, out.folded().size());
        assertTrue(out.body().contains("### Not on a changed line"));
        assertTrue(out.body().contains("Far away (`app/pool.py:27`)"));
        assertTrue(out.body().contains("Elsewhere (`unknown.py:3`)"));
        assertEquals(2, out.mustFix());
    }

    @Test
    void mustFixWinsTheCommentBudget() {
        InlineReviewComposer small = new InlineReviewComposer(0, 1);
        var out = small.compose("", List.of(consider("app/pool.py", 12, "Nit"), must("app/pool.py", 11, "Bug")),
                index, null, List.of(), List.of());
        assertEquals(1, out.comments().size());
        assertTrue(out.comments().getFirst().body().contains("Bug"));
        assertEquals("Nit", out.folded().getFirst().title());
    }

    @Test
    void duplicatesAreDropped() {
        var out = composer.compose("", List.of(must("app/pool.py", 11, "Bug"), must("app/pool.py", 11, "bug")),
                index, null, List.of(), List.of());
        assertEquals(1, out.comments().size());
        assertEquals(1, out.mustFix());
    }

    @Test
    void leftSideFindingAnchorsOnRemovedLine() {
        var f = new ReviewFinding("old.txt", 2, ReviewFinding.Side.LEFT, ReviewFinding.Severity.MUST_FIX, "Lost", "x");
        var out = composer.compose("", List.of(f), index, null, List.of(), List.of());
        assertEquals("LEFT", out.comments().getFirst().side());
        assertEquals(2, out.comments().getFirst().line());
    }

    @Test
    void noFindingsSaysSoAndListsResolvedAndNotes() {
        var out = composer.compose("Fine.", List.of(), index, "abc", List.of("Cache keyed by id() (`app/pool.py`)"),
                List.of("re-review of the hunks changed since `123`."));
        assertTrue(out.comments().isEmpty());
        assertTrue(out.body().contains("No findings."));
        assertTrue(out.body().contains("### Fixed since the last review"));
        assertTrue(out.body().contains("**Note:** re-review"));
    }

    @Test
    void markerHelpersReadSeverityAndTitleBack() {
        String body = InlineReviewComposer.renderComment(must("a.py", 1, "Cache keyed by id()"));
        assertEquals(ReviewFinding.Severity.MUST_FIX, InlineReviewComposer.severityOf(body));
        assertEquals("Cache keyed by id()", InlineReviewComposer.titleOf(body));
        String c = InlineReviewComposer.renderComment(consider("a.py", 1, "Rename"));
        assertEquals(ReviewFinding.Severity.CONSIDER, InlineReviewComposer.severityOf(c));
        assertNull(InlineReviewComposer.severityOf("a human comment"));
    }

    @Test
    void emptyIndexFoldsEverything() {
        List<ReviewFinding> fs = new ArrayList<>(List.of(must("app/pool.py", 11, "Bug")));
        var out = composer.compose("", fs, DiffLineIndex.parse(""), null, List.of(), List.of());
        assertTrue(out.comments().isEmpty());
        assertEquals(1, out.folded().size());
    }
}
