package org.remus.giteabot.review.inline;

import org.junit.jupiter.api.Test;
import org.remus.giteabot.repository.model.InlineReviewDraft;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
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

    @Test
    void countsMustFixMentionsInUnparsedText() {
        // the raw-answer shapes the model actually writes: tight JSON, spaced JSON, consider entries
        String raw = "{\"summary\": \"Two blocking problems remain\", \"findings\": [\n"
                + " {\"path\": \"a.py\", \"line\": 1, \"severity\": \"must_fix\", \"title\": \"One\"},\n"
                + " {\"path\": \"b.py\", \"line\": 2, \"severity\":\"must_fix\", \"title\": \"Two\"},\n"
                + " {\"path\": \"c.py\", \"line\": 3, \"severity\": \"consider\", \"title\": \"Meh\"}]}";
        assertEquals(2, InlineReviewComposer.countMustFixMentions(raw));
        assertEquals(0, InlineReviewComposer.countMustFixMentions(null));
        assertEquals(0, InlineReviewComposer.countMustFixMentions("  \n"));
        assertEquals(0, InlineReviewComposer.countMustFixMentions("{\"severity\": \"consider\"} alone"));
    }

    @Test
    void unprovenMustFixFailsClosedInTheMarker() {
        // the #595 shape: three must-fix severities named in an answer that did not parse, no anchored
        // findings. The marker must say unknown — never a clean zero a gate could read as "ship it".
        var out = composer.compose("{\"findings\": [{\"severity\": \"must_fix\"}, {\"severity\": \"must_fix\"}]}",
                List.of(), index, "abcdef1234567", List.of(),
                List.of("part of the review came back without line anchors, so it is in this summary."), 3);
        assertTrue(out.comments().isEmpty());
        assertTrue(out.body().contains("**Must fix: at least 3** · Consider: 0 · at `abcdef1`"));
        assertTrue(out.body().contains("<!-- ai-git-bot:summary must_fix=unknown consider=0 head=abcdef1234567 -->"));
        assertTrue(out.body().contains("part of the review came back without line anchors"));
        assertFalse(out.body().contains("No findings."), "unproven must-fix mentions are findings, not 'no findings'");
    }

    @Test
    void provenCountsWithUnprovenMustFixStillFailClosed() {
        // one anchored must-fix plus two unproven mentions: the header adds them, the marker stays unknown
        var out = composer.compose("Mixed.", List.of(must("app/pool.py", 11, "Bug")),
                index, "abcdef1234567", List.of(), List.of(), 2);
        assertEquals(1, out.mustFix());
        assertTrue(out.body().contains("**Must fix: at least 3**"));
        assertTrue(out.body().contains("must_fix=unknown consider=0 head=abcdef1234567"));
    }

    @Test
    void fullyParsedReviewsKeepTheirNumericMarker() {
        // zero unproven mentions: today's behavior unchanged, a parsed clean review still says 0
        var out = composer.compose("Fine.", List.of(), index, "abc", List.of(), List.of(), 0);
        assertTrue(out.body().contains("No findings."));
        assertTrue(out.body().contains("<!-- ai-git-bot:summary must_fix=0 consider=0 head=abc -->"));
    }
}
