package org.remus.giteabot.review.inline;

import java.util.List;

/**
 * Prompt pieces for the inline (line-by-line) review.
 *
 * <p>The bot's own system prompt says <em>what</em> to look for (and may list a
 * project's rules); {@link #OUTPUT_CONTRACT} is appended to it and fixes
 * <em>how</em> to answer, so every configured prompt works with inline reviews.</p>
 */
public final class InlineReviewPrompt {

    private InlineReviewPrompt() {
    }

    public static final String OUTPUT_CONTRACT = """

            ## Answer format (inline review)
            Answer with ONE JSON object and nothing else (no prose before or after it):
            {"summary": "2-5 sentences: what the change does and your overall verdict",
             "findings": [
               {"path": "file path exactly as in the diff header (without a/ or b/)",
                "line": 123,
                "side": "RIGHT",
                "severity": "must_fix",
                "title": "one line naming the problem",
                "body": "why it is wrong and what to change (Markdown allowed; a ```suggestion block is fine)"}
             ],
             "resolved_prior": []}

            Rules:
            - Every diff line is prefixed with its number: [R12] is line 12 of the NEW file (use side "RIGHT"),
              [L40] is line 40 of the OLD file, a removed line (use side "LEFT"). Copy the number from the
              prefix into "line"; never count lines yourself. Prefer RIGHT lines that were added (+).
            - Only comment on lines that appear in the diff. Point at the line where the problem is.
            - severity "must_fix": a bug, a security hole, data loss or corruption, or a broken project rule
              from the instructions above. Everything else (style, naming, readability, a test worth adding,
              a small refactor) is "consider". When unsure, use "consider".
            - One finding per distinct problem; do not repeat a finding on several lines.
            - No problems: "findings": [].
            - "resolved_prior": only on a re-review; list the ids of PRIOR FINDINGS that the new code fixes.
              Never report a prior finding again as a new finding: one that is not fixed simply stays open.
            """;

    /** A prior bot finding still open on the PR, shown to the model on a re-review. */
    public record PriorFinding(String id, String path, Integer line, String title) {
    }

    /**
     * Builds the user message for one diff chunk: PR title/description, optional
     * enrichment, the open prior findings (re-review), and the line-numbered diff.
     */
    public static String buildUserMessage(String prTitle, String prBody, String annotatedDiff,
                                          int chunkNumber, int totalChunks, String additionalContext,
                                          List<PriorFinding> priorFindings, boolean incremental) {
        StringBuilder sb = new StringBuilder();
        sb.append(incremental
                ? "Re-review the parts of this pull request that new commits changed.\n\n"
                : "Review the following pull request.\n\n");
        sb.append("**Title:** ").append(prTitle == null ? "" : prTitle).append("\n");
        if (prBody != null && !prBody.isBlank()) {
            sb.append("**Description:** ").append(prBody).append("\n");
        }
        if (totalChunks > 1) {
            sb.append("**Diff chunk:** ").append(chunkNumber).append("/").append(totalChunks).append("\n");
        }
        if (additionalContext != null && !additionalContext.isBlank()) {
            sb.append("\n**Additional Context:**\n").append(additionalContext).append("\n");
        }
        if (priorFindings != null && !priorFindings.isEmpty()) {
            sb.append("\n**PRIOR FINDINGS still open** (put an id in resolved_prior only if the diff below fixes it):\n");
            for (PriorFinding p : priorFindings) {
                sb.append("- ").append(p.id()).append(": `").append(p.path()).append("`");
                if (p.line() != null) {
                    sb.append(" line ").append(p.line());
                }
                sb.append(" — ").append(p.title()).append("\n");
            }
        }
        sb.append("\n**Diff:**\n```diff\n").append(annotatedDiff).append("\n```");
        return sb.toString();
    }
}
