package org.remus.giteabot.review.inline;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class StructuredReviewParserTest {

    @Test
    void parsesPlainJson() {
        String raw = """
                {"summary": "Adds a cache.",
                 "findings": [
                   {"path": "app/pool.py", "line": 12, "side": "RIGHT", "severity": "must_fix",
                    "title": "Cache keyed by id()", "body": "Ids are reused after close."},
                   {"path": "app/pool.py", "line": 41, "severity": "consider", "title": "Name", "body": "Rename y."}
                 ],
                 "resolved_prior": ["P2"]}""";
        StructuredReview r = StructuredReviewParser.parse(raw).orElseThrow();
        assertEquals("Adds a cache.", r.summary());
        assertEquals(2, r.findings().size());
        ReviewFinding f = r.findings().getFirst();
        assertEquals("app/pool.py", f.path());
        assertEquals(12, f.line());
        assertEquals(ReviewFinding.Side.RIGHT, f.side());
        assertTrue(f.isMustFix());
        assertEquals(ReviewFinding.Severity.CONSIDER, r.findings().get(1).severity());
        assertEquals(List.of("P2"), r.resolvedPrior());
    }

    @Test
    void parsesFencedJsonAfterProse() {
        String raw = "Here is my review:\n```json\n{\"summary\":\"ok\",\"findings\":[]}\n```\nThanks";
        StructuredReview r = StructuredReviewParser.parse(raw).orElseThrow();
        assertEquals("ok", r.summary());
        assertTrue(r.findings().isEmpty());
    }

    @Test
    void toleratesAlternativeKeysAndStringLines() {
        String raw = "{\"overview\":\"x\",\"comments\":[{\"file\":\"a.py\",\"line_number\":\"L17\","
                + "\"severity\":\"MUST-FIX\",\"message\":\"boom\"}]}";
        StructuredReview r = StructuredReviewParser.parse(raw).orElseThrow();
        assertEquals("x", r.summary());
        ReviewFinding f = r.findings().getFirst();
        assertEquals("a.py", f.path());
        assertEquals(17, f.line());
        assertTrue(f.isMustFix());
        assertEquals("boom", f.body());
    }

    @Test
    void bracesInsideStringsDoNotBreakBalancing() {
        String raw = "{\"summary\":\"use {x} and \\\"}\\\"\",\"findings\":[]} trailing }";
        StructuredReview r = StructuredReviewParser.parse(raw).orElseThrow();
        assertEquals("use {x} and \"}\"", r.summary());
    }

    @Test
    void findingsWithoutTitleOrBodyAreSkipped() {
        String raw = "{\"summary\":\"s\",\"findings\":[{\"path\":\"a.py\",\"line\":1}]}";
        assertTrue(StructuredReviewParser.parse(raw).orElseThrow().findings().isEmpty());
    }

    @Test
    void nonJsonGivesEmpty() {
        assertFalse(StructuredReviewParser.parse("Looks good to me!").isPresent());
        assertFalse(StructuredReviewParser.parse("").isPresent());
        assertFalse(StructuredReviewParser.parse(null).isPresent());
        assertFalse(StructuredReviewParser.parse("{not json").isPresent());
    }

    @Test
    void severityParsingIsLenient() {
        assertEquals(ReviewFinding.Severity.MUST_FIX, ReviewFinding.Severity.parse("must fix"));
        assertEquals(ReviewFinding.Severity.MUST_FIX, ReviewFinding.Severity.parse("Blocker"));
        assertEquals(ReviewFinding.Severity.CONSIDER, ReviewFinding.Severity.parse("nit"));
        assertEquals(ReviewFinding.Severity.CONSIDER, ReviewFinding.Severity.parse(null));
    }
}
