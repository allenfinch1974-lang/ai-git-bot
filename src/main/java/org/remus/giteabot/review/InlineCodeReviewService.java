package org.remus.giteabot.review;

import lombok.extern.slf4j.Slf4j;
import org.remus.giteabot.ai.AiClient;
import org.remus.giteabot.gitea.model.WebhookPayload;
import org.remus.giteabot.repository.RepositoryApiClient;
import org.remus.giteabot.repository.model.Review;
import org.remus.giteabot.repository.model.ReviewThread;
import org.remus.giteabot.review.inline.DiffLineIndex;
import org.remus.giteabot.review.inline.InlineReviewComposer;
import org.remus.giteabot.review.inline.InlineReviewPrompt;
import org.remus.giteabot.review.inline.ReviewFinding;
import org.remus.giteabot.review.inline.StructuredReview;
import org.remus.giteabot.review.inline.StructuredReviewParser;
import org.remus.giteabot.session.ReviewSession;
import org.remus.giteabot.session.SessionService;
import org.springframework.web.client.HttpClientErrorException;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Line-by-line pull-request review (upstream issue #121).
 *
 * <p>The model answers in JSON ({@link InlineReviewPrompt#OUTPUT_CONTRACT}); every
 * finding's anchor is proven against the PR diff ({@link DiffLineIndex}) and posted
 * as ONE provider review: a summary body that counts the must-fix findings, plus
 * one line comment per anchored finding. Findings that cannot be anchored are
 * listed in the summary instead of being dropped.</p>
 *
 * <p>On new commits only the PR hunks the new commits touched are re-reviewed
 * (compare {@code lastReviewedSha...head}). Open must-fix/consider threads the bot
 * started on those files are shown to the model; the ones it reports fixed get a
 * reply and are resolved.</p>
 *
 * <p>Created by {@link CodeReviewService}; uses its diff filtering, chunking and
 * context enrichment so both review styles share one configuration.</p>
 */
@Slf4j
public class InlineCodeReviewService {

    static final String EMPTY_REVIEW_DIFF = "(no changed hunks in the new commits)";

    /** Lines within which a new finding counts as a repeat of a still-open thread on the same file. */
    static final int REPEAT_DISTANCE = 2;

    private final CodeReviewService base;
    private final RepositoryApiClient repositoryClient;
    private final AiClient aiClient;
    private final SessionService sessionService;
    private final String sessionPromptKey;
    private final String systemPrompt;
    private final InlineReviewComposer composer;

    public InlineCodeReviewService(CodeReviewService base, RepositoryApiClient repositoryClient, AiClient aiClient,
                                   SessionService sessionService, String sessionPromptKey, String reviewSystemPrompt,
                                   InlineReviewComposer composer) {
        this.base = base;
        this.repositoryClient = repositoryClient;
        this.aiClient = aiClient;
        this.sessionService = sessionService;
        this.sessionPromptKey = sessionPromptKey;
        this.systemPrompt = reviewSystemPrompt + InlineReviewPrompt.OUTPUT_CONTRACT;
        this.composer = composer;
    }

    /**
     * Reviews the PR and posts an inline review.
     *
     * @return {@code true} when a review was posted
     */
    public boolean review(WebhookPayload payload) {
        String owner = payload.getRepository().getOwner().getLogin();
        String repo = payload.getRepository().getName();
        Long prNumber = payload.getPullRequest().getNumber();
        String prTitle = payload.getPullRequest().getTitle();
        String prBody = payload.getPullRequest().getBody();
        log.info("Starting inline code review for PR #{} '{}' in {}/{}", prNumber, prTitle, owner, repo);

        try {
            String fullDiff = base.fetchFilteredDiff(owner, repo, prNumber);
            if (fullDiff == null || fullDiff.isBlank()) {
                log.warn("No diff found for PR #{} in {}/{}", prNumber, owner, repo);
                return false;
            }
            String headSha = resolveHeadSha(payload, owner, repo, prNumber);
            String lastSha = lastReviewedSha(owner, repo, prNumber);
            if (headSha != null && headSha.equals(lastSha)) {
                log.info("PR #{} in {}/{} already reviewed at {}; skipping", prNumber, owner, repo, headSha);
                return false;
            }

            List<ReviewThread> openThreads = openBotThreads(owner, repo, prNumber);

            String reviewDiff = fullDiff;
            boolean incremental = false;
            if (lastSha != null && headSha != null) {
                try {
                    String inc = repositoryClient.getCompareDiff(owner, repo, lastSha, headSha);
                    Map<String, Set<Integer>> changed = DiffLineIndex.parse(inc).changedRightLines();
                    reviewDiff = DiffLineIndex.retainHunksTouching(fullDiff, changed);
                    incremental = true;
                    log.info("Re-review of PR #{}: {} of {} chars of the PR diff touched by {}...{}",
                            prNumber, reviewDiff.length(), fullDiff.length(), lastSha, headSha);
                } catch (Exception e) {
                    log.warn("Could not compare {}...{} for PR #{} ({}); reviewing the whole diff",
                            lastSha, headSha, prNumber, e.getMessage());
                }
            }

            DiffLineIndex fullIndex = DiffLineIndex.parse(fullDiff);
            Map<String, ReviewThread> priorById = new LinkedHashMap<>();
            List<InlineReviewPrompt.PriorFinding> priors = new ArrayList<>();
            if (incremental) {
                Set<String> reviewedPaths = DiffLineIndex.parse(reviewDiff).paths();
                int n = 0;
                for (ReviewThread t : openThreads) {
                    if (t.outdated() || reviewedPaths.contains(t.path()) || !fullIndex.paths().contains(t.path())) {
                        String id = "P" + (++n);
                        priorById.put(id, t);
                        priors.add(new InlineReviewPrompt.PriorFinding(id, t.path(), t.line(),
                                InlineReviewComposer.titleOf(t.firstCommentBody())));
                    }
                }
                if (reviewDiff.isBlank() && priors.isEmpty()) {
                    // No model call, but still a (summary-only) review AT this head, so a merge gate that
                    // waits for "reviewed at the current head" is not left waiting forever.
                    log.info("New commits on PR #{} touch none of its reviewed hunks; posting a no-change note", prNumber);
                    long openMust = openThreads.stream()
                            .filter(t -> InlineReviewComposer.severityOf(t.firstCommentBody())
                                    == ReviewFinding.Severity.MUST_FIX)
                            .count();
                    List<String> notes = new ArrayList<>();
                    notes.add("the new commits since `" + shortSha(lastSha) + "` change no code that is reviewed here; "
                            + "nothing to re-review.");
                    if (openMust > 0) {
                        notes.add(openMust + " must-fix finding(s) from earlier reviews are still open.");
                    }
                    InlineReviewComposer.ComposedReview noChange =
                            composer.compose("", List.of(), fullIndex, headSha, List.of(), notes);
                    repositoryClient.submitInlineReview(owner, repo, prNumber, headSha, noChange.body(), List.of());
                    return true;
                }
            }

            String headRef = base.resolveHeadRef(payload);
            String additionalContext = reviewDiff.isBlank() ? ""
                    : base.gatherAdditionalContext(owner, repo, prNumber, reviewDiff, headRef, prBody);

            CodeReviewService.ChunkingResult chunking = reviewDiff.isBlank()
                    ? new CodeReviewService.ChunkingResult(List.of(EMPTY_REVIEW_DIFF), false)
                    : base.splitDiffIntoChunks(reviewDiff);

            List<String> summaries = new ArrayList<>();
            List<ReviewFinding> findings = new ArrayList<>();
            Set<String> resolvedIds = new LinkedHashSet<>();
            List<String> notes = new ArrayList<>();
            int failed = 0;
            int total = chunking.chunks().size();
            Exception lastError = null;
            for (int i = 0; i < total; i++) {
                String chunk = chunking.chunks().get(i);
                Set<String> chunkPaths = DiffLineIndex.parse(chunk).paths();
                final boolean firstChunk = i == 0;
                List<InlineReviewPrompt.PriorFinding> chunkPriors = priors.stream()
                        .filter(p -> chunkPaths.contains(p.path())
                                || (firstChunk && !fullIndex.paths().contains(p.path()))
                                || (firstChunk && priorById.get(p.id()).outdated()))
                        .toList();
                String userMessage = InlineReviewPrompt.buildUserMessage(prTitle, prBody,
                        DiffLineIndex.annotate(chunk), i + 1, total, additionalContext, chunkPriors, incremental);
                try {
                    String raw = aiClient.submitReviewPrompt(systemPrompt, null, userMessage);
                    var parsed = StructuredReviewParser.parse(raw);
                    if (parsed.isPresent()) {
                        StructuredReview r = parsed.get();
                        if (!r.summary().isBlank()) {
                            summaries.add(total > 1 ? "(" + (i + 1) + "/" + total + ") " + r.summary() : r.summary());
                        }
                        findings.addAll(r.findings());
                        Set<String> allowed = new LinkedHashSet<>();
                        chunkPriors.forEach(p -> allowed.add(p.id()));
                        r.resolvedPrior().stream().filter(allowed::contains).forEach(resolvedIds::add);
                    } else if (raw != null && !raw.isBlank()) {
                        // The model ignored the JSON contract: keep its words as summary text.
                        summaries.add(raw.strip());
                        notes.add("part of the review came back without line anchors, so it is in this summary.");
                    }
                } catch (Exception e) {
                    failed++;
                    lastError = e;
                    aiClient.reportError(e);
                    log.warn("Inline review failed for chunk {}/{}: {}", i + 1, total, e.getMessage());
                }
            }
            if (failed == total) {
                throw new IllegalStateException("All " + failed + " chunk(s) failed during inline review", lastError);
            }
            if (failed > 0) {
                notes.add(failed + " of " + total + " diff chunk(s) could not be reviewed due to API errors.");
            }
            if (chunking.wasTruncated()) {
                notes.add("review is incomplete because the diff was truncated.");
            }

            List<String> resolvedNotes = acknowledgeResolved(owner, repo, prNumber, headSha, resolvedIds, priorById);
            List<ReviewThread> stillOpen = openThreads.stream()
                    .filter(t -> !isResolvedNow(t, resolvedIds, priorById))
                    .toList();
            int before = findings.size();
            findings.removeIf(f -> repeatsOpenThread(f, stillOpen));
            if (findings.size() < before) {
                notes.add((before - findings.size()) + " finding(s) repeated a thread that is still open and were not "
                        + "posted again.");
            }
            long stillOpenMustFix = openThreads.stream()
                    .filter(t -> InlineReviewComposer.severityOf(t.firstCommentBody()) == ReviewFinding.Severity.MUST_FIX)
                    .filter(t -> !isResolvedNow(t, resolvedIds, priorById))
                    .count();
            if (stillOpenMustFix > 0) {
                notes.add(stillOpenMustFix + " must-fix finding(s) from earlier reviews are still open.");
            }
            if (incremental) {
                notes.add("re-review of the hunks changed since `" + shortSha(lastSha) + "`.");
            }

            String summary = String.join("\n\n", summaries);
            InlineReviewComposer.ComposedReview composed =
                    composer.compose(summary, findings, fullIndex, headSha, resolvedNotes, notes);
            post(owner, repo, prNumber, headSha, composed, summary, findings, resolvedNotes, notes);

            ReviewSession session = sessionService.getOrCreateSession(owner, repo, prNumber, sessionPromptKey);
            sessionService.addMessage(session, "user", incremental
                    ? "The pull request '" + prTitle + "' was updated; please re-review the changed hunks."
                    : "I opened a pull request titled '" + prTitle + "'. Please review it.");
            sessionService.addMessage(session, "assistant", composed.body() + "\n\n" + findingsAsText(findings));
            sessionService.compactContextWindow(session);

            log.info("Inline code review completed for PR #{} in {}/{}: {} must-fix, {} consider, {} line comment(s)",
                    prNumber, owner, repo, composed.mustFix(), composed.consider(), composed.comments().size());
            return true;
        } catch (Exception e) {
            log.error("Inline code review failed for PR #{} in {}/{}: {}", prNumber, owner, repo, e.getMessage(), e);
            return false;
        }
    }

    private void post(String owner, String repo, Long prNumber, String headSha,
                      InlineReviewComposer.ComposedReview composed, String summary, List<ReviewFinding> findings,
                      List<String> resolvedNotes, List<String> notes) {
        try {
            repositoryClient.submitInlineReview(owner, repo, prNumber, headSha, composed.body(), composed.comments());
        } catch (HttpClientErrorException e) {
            if (composed.comments().isEmpty() || e.getStatusCode().value() != 422) {
                throw e;
            }
            // The provider refused an anchor after all (e.g. the PR moved on meanwhile):
            // post the same findings with every one of them in the summary.
            log.warn("Provider rejected the line comments ({}); posting all findings in the summary", e.getMessage());
            InlineReviewComposer.ComposedReview foldedAll = composer.compose(summary, findings,
                    DiffLineIndex.parse(""), headSha, resolvedNotes, notes);
            repositoryClient.submitInlineReview(owner, repo, prNumber, headSha, foldedAll.body(), List.of());
        }
    }

    private List<String> acknowledgeResolved(String owner, String repo, Long prNumber, String headSha,
                                             Set<String> resolvedIds, Map<String, ReviewThread> priorById) {
        List<String> resolvedNotes = new ArrayList<>();
        for (String id : resolvedIds) {
            ReviewThread t = priorById.get(id);
            if (t == null) {
                continue;
            }
            String title = InlineReviewComposer.titleOf(t.firstCommentBody());
            try {
                if (t.firstCommentId() != null) {
                    repositoryClient.replyToReviewComment(owner, repo, prNumber, t.firstCommentId(),
                            "✅ Looks fixed" + (headSha != null ? " in `" + shortSha(headSha) + "`" : "")
                                    + ". Resolving this thread.");
                }
                if (t.id() != null) {
                    repositoryClient.resolveReviewThread(owner, repo, t.id());
                }
                resolvedNotes.add(title + " (`" + t.path() + "`)");
            } catch (Exception e) {
                log.warn("Could not acknowledge fixed thread {} on PR #{}: {}", t.id(), prNumber, e.getMessage());
            }
        }
        return resolvedNotes;
    }

    /** A new finding on (nearly) the same line of the same file as a still-open bot thread is a repeat. */
    static boolean repeatsOpenThread(ReviewFinding f, List<ReviewThread> stillOpen) {
        if (f.line() == null || f.side() != ReviewFinding.Side.RIGHT) {
            return false;
        }
        for (ReviewThread t : stillOpen) {
            if (t.line() != null && t.path() != null && t.path().equals(f.path())
                    && Math.abs(t.line() - f.line()) <= REPEAT_DISTANCE) {
                return true;
            }
        }
        return false;
    }

    private static boolean isResolvedNow(ReviewThread t, Set<String> resolvedIds, Map<String, ReviewThread> priorById) {
        for (String id : resolvedIds) {
            if (priorById.get(id) == t) {
                return true;
            }
        }
        return false;
    }

    /** Unresolved threads that the bot started (identified by its hidden finding marker). */
    List<ReviewThread> openBotThreads(String owner, String repo, Long prNumber) {
        try {
            return repositoryClient.getReviewThreads(owner, repo, prNumber).stream()
                    .filter(t -> !t.resolved())
                    .filter(t -> InlineReviewComposer.severityOf(t.firstCommentBody()) != null)
                    .toList();
        } catch (Exception e) {
            log.warn("Could not list review threads for PR #{} in {}/{}: {}", prNumber, owner, repo, e.getMessage());
            return List.of();
        }
    }

    /** Commit of the bot's latest inline review (its summary carries the marker), or {@code null}. */
    String lastReviewedSha(String owner, String repo, Long prNumber) {
        try {
            String sha = null;
            long bestId = Long.MIN_VALUE;
            for (Review r : repositoryClient.getReviews(owner, repo, prNumber)) {
                if (r.getBody() != null && r.getBody().contains(InlineReviewComposer.SUMMARY_MARKER_PREFIX)
                        && r.getCommitId() != null && r.getId() != null && r.getId() > bestId) {
                    bestId = r.getId();
                    sha = r.getCommitId();
                }
            }
            return sha;
        } catch (Exception e) {
            log.warn("Could not list reviews for PR #{} in {}/{}: {}", prNumber, owner, repo, e.getMessage());
            return null;
        }
    }

    @SuppressWarnings("unchecked")
    private String resolveHeadSha(WebhookPayload payload, String owner, String repo, Long prNumber) {
        if (payload.getPullRequest().getHead() != null && payload.getPullRequest().getHead().getSha() != null) {
            return payload.getPullRequest().getHead().getSha();
        }
        try {
            Map<String, Object> details = repositoryClient.getPullRequestDetails(owner, repo, prNumber);
            Object head = details == null ? null : details.get("head");
            if (head instanceof Map<?, ?> h && h.get("sha") != null) {
                return h.get("sha").toString();
            }
        } catch (Exception e) {
            log.debug("Could not resolve head sha for PR #{}: {}", prNumber, e.getMessage());
        }
        return null;
    }

    private static String shortSha(String sha) {
        return sha == null ? "" : sha.substring(0, Math.min(7, sha.length()));
    }

    private static String findingsAsText(List<ReviewFinding> findings) {
        StringBuilder sb = new StringBuilder();
        for (ReviewFinding f : findings) {
            sb.append("- [").append(f.severity().wireName()).append("] ").append(f.path());
            if (f.line() != null) {
                sb.append(":").append(f.line());
            }
            sb.append(" ").append(f.title()).append("\n");
        }
        return sb.toString();
    }
}
