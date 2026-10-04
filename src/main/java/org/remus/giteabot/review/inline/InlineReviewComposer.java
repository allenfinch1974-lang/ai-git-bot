package org.remus.giteabot.review.inline;

import org.remus.giteabot.repository.model.InlineReviewDraft;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Turns the model's findings into what is actually posted: line comments whose
 * anchors are proven against the diff, and one summary body that counts the
 * must-fix findings and carries every finding that could not be anchored.
 *
 * <p>Each posted line comment ends with a hidden marker
 * ({@value #FINDING_MARKER_PREFIX}…) so later runs — and tooling outside the bot,
 * such as a ship gate — can tell the bot's findings and their severity apart from
 * human comments without depending on the author's login.</p>
 */
public final class InlineReviewComposer {

    public static final String FINDING_MARKER_PREFIX = "<!-- ai-git-bot:finding";
    public static final String SUMMARY_MARKER_PREFIX = "<!-- ai-git-bot:summary";

    private static final Pattern SEVERITY_IN_MARKER = Pattern.compile(
            Pattern.quote(FINDING_MARKER_PREFIX) + "\\s+severity=([a-z_]+)");

    /** Default distance (in lines) a near-miss anchor may be moved inside the same hunk. */
    public static final int DEFAULT_SNAP_DISTANCE = 3;

    /** Default maximum of line comments per review; the rest are folded into the summary. */
    public static final int DEFAULT_MAX_COMMENTS = 25;

    /** What to post. */
    public record ComposedReview(String body, List<InlineReviewDraft> comments,
                                 int mustFix, int consider, List<ReviewFinding> folded) {
    }

    private final int snapDistance;
    private final int maxComments;

    public InlineReviewComposer() {
        this(DEFAULT_SNAP_DISTANCE, DEFAULT_MAX_COMMENTS);
    }

    public InlineReviewComposer(int snapDistance, int maxComments) {
        this.snapDistance = Math.max(0, snapDistance);
        this.maxComments = Math.max(0, maxComments);
    }

    /**
     * @param summary        the model's overall verdict (may be blank)
     * @param findings       all findings for this run
     * @param index          the diff the comments will be anchored to
     * @param headSha        commit the review is for (shown and stored in the summary marker)
     * @param resolvedNotes  lines to list under "Fixed since the last review" (may be empty)
     * @param notes          extra notes for the summary (chunk failures, truncation …)
     */
    public ComposedReview compose(String summary, List<ReviewFinding> findings, DiffLineIndex index,
                                  String headSha, List<String> resolvedNotes, List<String> notes) {
        List<ReviewFinding> ordered = new ArrayList<>(findings == null ? List.of() : findings);
        // must-fix first so they win the comment budget
        ordered.sort((a, b) -> Boolean.compare(b.isMustFix(), a.isMustFix()));

        List<InlineReviewDraft> comments = new ArrayList<>();
        List<ReviewFinding> folded = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        int mustFix = 0;
        int consider = 0;
        for (ReviewFinding f : ordered) {
            if (f.isMustFix()) {
                mustFix++;
            } else {
                consider++;
            }
            String path = index.resolvePath(f.path());
            Integer line = null;
            if (path != null && f.line() != null && f.line() > 0) {
                line = index.nearestCommentable(path, f.line(), f.side(), snapDistance);
            }
            String dedupeKey = path + "|" + line + "|" + f.side() + "|" + f.title().toLowerCase(Locale.ROOT);
            if (!seen.add(dedupeKey)) {
                if (f.isMustFix()) {
                    mustFix--;
                } else {
                    consider--;
                }
                continue;
            }
            if (line == null || comments.size() >= maxComments) {
                folded.add(path != null ? new ReviewFinding(path, f.line(), f.side(), f.severity(), f.title(), f.body()) : f);
                continue;
            }
            comments.add(new InlineReviewDraft(path, line, f.side().name(), renderComment(f)));
        }

        String body = renderSummary(summary, mustFix, consider, comments.size(), folded, headSha,
                resolvedNotes == null ? List.of() : resolvedNotes, notes == null ? List.of() : notes);
        return new ComposedReview(body, comments, mustFix, consider, folded);
    }

    /** Markdown for one line comment, ending with the hidden finding marker. */
    public static String renderComment(ReviewFinding f) {
        StringBuilder sb = new StringBuilder();
        sb.append(f.isMustFix() ? "🔴 **Must fix:** " : "🟡 **Consider:** ");
        sb.append(f.title().isBlank() ? "(untitled)" : f.title()).append("\n\n");
        if (!f.body().isBlank()) {
            sb.append(f.body()).append("\n\n");
        }
        if (f.isMustFix()) {
            sb.append("_Reply on this thread when it is fixed (\"fixed in <sha>\") or why not; "
                    + "the next review checks it._\n\n");
        }
        sb.append(FINDING_MARKER_PREFIX).append(" severity=").append(f.severity().wireName()).append(" -->");
        return sb.toString();
    }

    String renderSummary(String summary, int mustFix, int consider, int anchored,
                         List<ReviewFinding> folded, String headSha,
                         List<String> resolvedNotes, List<String> notes) {
        StringBuilder sb = new StringBuilder();
        sb.append("## 🤖 AI Code Review\n\n");
        sb.append("**Must fix: ").append(mustFix).append("** · Consider: ").append(consider);
        if (headSha != null && !headSha.isBlank()) {
            sb.append(" · at `").append(headSha, 0, Math.min(7, headSha.length())).append("`");
        }
        sb.append("\n\n");
        if (summary != null && !summary.isBlank()) {
            sb.append(summary.strip()).append("\n\n");
        }
        if (mustFix == 0 && consider == 0) {
            sb.append("No findings.\n\n");
        } else if (anchored > 0) {
            sb.append(anchored).append(anchored == 1 ? " finding is" : " findings are")
                    .append(" posted on the changed lines.\n\n");
        }
        if (!resolvedNotes.isEmpty()) {
            sb.append("### Fixed since the last review\n");
            resolvedNotes.forEach(n -> sb.append("- ").append(n).append("\n"));
            sb.append("\n");
        }
        if (!folded.isEmpty()) {
            sb.append("### Not on a changed line\n");
            for (ReviewFinding f : folded) {
                sb.append("- ").append(f.isMustFix() ? "🔴 **Must fix:** " : "🟡 **Consider:** ")
                        .append(f.title().isBlank() ? "(untitled)" : f.title());
                if (f.path() != null && !f.path().isBlank()) {
                    sb.append(" (`").append(f.path());
                    if (f.line() != null) {
                        sb.append(":").append(f.line());
                    }
                    sb.append("`)");
                }
                if (!f.body().isBlank()) {
                    sb.append(" — ").append(f.body().replace("\n", " "));
                }
                sb.append("\n");
            }
            sb.append("\n");
        }
        if (!notes.isEmpty()) {
            notes.forEach(n -> sb.append("**Note:** ").append(n).append("\n"));
            sb.append("\n");
        }
        sb.append("---\n*Automated review by AI Git Bot*\n");
        sb.append(SUMMARY_MARKER_PREFIX).append(" must_fix=").append(mustFix)
                .append(" consider=").append(consider);
        if (headSha != null && !headSha.isBlank()) {
            sb.append(" head=").append(headSha);
        }
        sb.append(" -->");
        return sb.toString();
    }

    /** Severity carried by a bot comment's hidden marker, or {@code null} if the body has none. */
    public static ReviewFinding.Severity severityOf(String commentBody) {
        if (commentBody == null) {
            return null;
        }
        Matcher m = SEVERITY_IN_MARKER.matcher(commentBody);
        return m.find() ? ReviewFinding.Severity.parse(m.group(1)) : null;
    }

    /** First line of a rendered finding without the severity badge — used as its title on re-review. */
    public static String titleOf(String commentBody) {
        if (commentBody == null) {
            return "";
        }
        String first = commentBody.strip().split("\n", 2)[0];
        return first.replaceFirst("^\\S+\\s+\\*\\*(Must fix|Consider):\\*\\*\\s*", "").strip();
    }
}
