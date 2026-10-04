package org.remus.giteabot.review;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.remus.giteabot.ai.AiClient;
import org.remus.giteabot.config.ReviewConfigProperties;
import org.remus.giteabot.gitea.model.WebhookPayload;
import org.remus.giteabot.github.model.GitHubReview;
import org.remus.giteabot.repository.RepositoryApiClient;
import org.remus.giteabot.repository.model.InlineReviewDraft;
import org.remus.giteabot.repository.model.ReviewThread;
import org.remus.giteabot.review.inline.InlineReviewComposer;
import org.remus.giteabot.review.inline.ReviewFinding;
import org.remus.giteabot.session.ReviewSession;
import org.remus.giteabot.session.SessionService;
import org.springframework.http.HttpStatus;
import org.springframework.web.client.HttpClientErrorException;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class InlineCodeReviewServiceTest {

    private static final String PROMPT = "Review carefully.";
    private static final String HEAD = "bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb";
    private static final String OLD = "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa";

    private static final String PR_DIFF = String.join("\n",
            "diff --git a/app/pool.py b/app/pool.py",
            "--- a/app/pool.py",
            "+++ b/app/pool.py",
            "@@ -10,3 +10,4 @@",
            " a = 1",
            "+_SCOPES = {}",
            "+key = id(conn)",
            " b = 2",
            "@@ -50,2 +51,3 @@",
            " x = 1",
            "+y = 2",
            " z = 3",
            "");

    @Mock private RepositoryApiClient repo;
    @Mock private AiClient ai;
    @Mock private SessionService sessions;

    private CodeReviewService service;

    @BeforeEach
    void setUp() {
        service = new CodeReviewService(repo, ai, sessions, "ai_bot", new ReviewConfigProperties(),
                "system-prompt:1", PROMPT, 120000, 8, 60000, "");
        lenient().when(repo.supportsInlineReviews()).thenReturn(true);
        ReviewSession session = new ReviewSession("acme", "web", 7L, null);
        lenient().when(sessions.getOrCreateSession(any(), any(), any(), any())).thenReturn(session);
        lenient().when(sessions.addMessage(any(), anyString(), anyString())).thenReturn(session);
        lenient().when(repo.getPullRequestDiff("acme", "web", 7L)).thenReturn(PR_DIFF);
    }

    private static WebhookPayload payload() {
        WebhookPayload p = new WebhookPayload();
        WebhookPayload.Repository r = new WebhookPayload.Repository();
        WebhookPayload.Owner o = new WebhookPayload.Owner();
        o.setLogin("acme");
        r.setOwner(o);
        r.setName("web");
        p.setRepository(r);
        WebhookPayload.PullRequest pr = new WebhookPayload.PullRequest();
        pr.setNumber(7L);
        pr.setTitle("Pool cache");
        pr.setBody("Adds a scope cache");
        WebhookPayload.Head head = new WebhookPayload.Head();
        head.setRef("feature");
        head.setSha(HEAD);
        pr.setHead(head);
        p.setPullRequest(pr);
        return p;
    }

    @SuppressWarnings("unchecked")
    private List<InlineReviewDraft> captureComments(ArgumentCaptor<String> body) {
        ArgumentCaptor<List<InlineReviewDraft>> comments = ArgumentCaptor.forClass(List.class);
        verify(repo).submitInlineReview(eq("acme"), eq("web"), eq(7L), eq(HEAD), body.capture(), comments.capture());
        return comments.getValue();
    }

    @Test
    void firstReviewPostsAnchoredFindingsAndSummary() {
        when(repo.getReviews("acme", "web", 7L)).thenReturn(List.of());
        ArgumentCaptor<String> userMessage = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<String> systemPrompt = ArgumentCaptor.forClass(String.class);
        when(ai.submitReviewPrompt(systemPrompt.capture(), isNull(), userMessage.capture())).thenReturn("""
                ```json
                {"summary":"Adds a connection scope cache.",
                 "findings":[
                  {"path":"app/pool.py","line":12,"side":"RIGHT","severity":"must_fix",
                   "title":"Scope cache keyed by id() alone","body":"A closed connection's id is reused."},
                  {"path":"app/pool.py","line":99,"severity":"consider","title":"Far line","body":"x"}]}
                ```""");

        assertTrue(service.reviewPullRequestInline(payload()));

        assertTrue(systemPrompt.getValue().startsWith(PROMPT));
        assertTrue(systemPrompt.getValue().contains("Answer format (inline review)"));
        assertTrue(userMessage.getValue().contains("[R12] +key = id(conn)"));
        ArgumentCaptor<String> body = ArgumentCaptor.forClass(String.class);
        List<InlineReviewDraft> comments = captureComments(body);
        assertEquals(1, comments.size());
        assertEquals(12, comments.getFirst().line());
        assertTrue(comments.getFirst().body().contains("Must fix:"));
        assertTrue(body.getValue().contains("**Must fix: 1** · Consider: 1"));
        assertTrue(body.getValue().contains("Far line (`app/pool.py:99`)"));
        verify(repo, never()).postReviewComment(any(), any(), any(), any());
    }

    @Test
    void sameHeadAsLastReviewIsSkipped() {
        GitHubReview prev = new GitHubReview();
        prev.setId(5L);
        prev.setBody("## 🤖 AI Code Review\n" + InlineReviewComposer.SUMMARY_MARKER_PREFIX + " must_fix=0 -->");
        prev.setCommitId(HEAD);
        when(repo.getReviews("acme", "web", 7L)).thenReturn(List.of(prev));

        assertFalse(service.reviewPullRequestInline(payload()));
        verify(ai, never()).submitReviewPrompt(any(), any(), any());
    }

    @Test
    void reReviewSendsOnlyTouchedHunksAndResolvesFixedThreads() {
        GitHubReview prev = new GitHubReview();
        prev.setId(5L);
        prev.setBody(InlineReviewComposer.SUMMARY_MARKER_PREFIX + " must_fix=1 -->");
        prev.setCommitId(OLD);
        GitHubReview human = new GitHubReview();
        human.setId(9L);
        human.setBody("LGTM");
        human.setCommitId(HEAD);
        when(repo.getReviews("acme", "web", 7L)).thenReturn(List.of(prev, human));
        when(repo.getCompareDiff("acme", "web", OLD, HEAD)).thenReturn(String.join("\n",
                "diff --git a/app/pool.py b/app/pool.py", "--- a/app/pool.py", "+++ b/app/pool.py",
                "@@ -11 +11 @@", "-_SCOPES = []", "+_SCOPES = {}", ""));
        String mustBody = InlineReviewComposer.renderComment(new ReviewFinding("app/pool.py", 11,
                ReviewFinding.Side.RIGHT, ReviewFinding.Severity.MUST_FIX, "Wrong container", "x"));
        String otherBody = InlineReviewComposer.renderComment(new ReviewFinding("app/pool.py", 52,
                ReviewFinding.Side.RIGHT, ReviewFinding.Severity.MUST_FIX, "Unrelated", "x"));
        when(repo.getReviewThreads("acme", "web", 7L)).thenReturn(List.of(
                new ReviewThread("T1", false, false, "app/pool.py", 11, 100L, "ai_bot", mustBody),
                new ReviewThread("T2", true, false, "app/pool.py", 52, 101L, "ai_bot", otherBody),
                new ReviewThread("T3", false, false, "app/pool.py", 12, 102L, "human", "why?")));
        ArgumentCaptor<String> userMessage = ArgumentCaptor.forClass(String.class);
        when(ai.submitReviewPrompt(anyString(), isNull(), userMessage.capture()))
                .thenReturn("{\"summary\":\"Fixed.\",\"findings\":[],\"resolved_prior\":[\"P1\",\"P9\"]}");

        assertTrue(service.reviewPullRequestInline(payload()));

        String sent = userMessage.getValue();
        assertTrue(sent.startsWith("Re-review"));
        assertTrue(sent.contains("@@ -10,3 +10,4 @@"));
        assertFalse(sent.contains("@@ -50,2 +51,3 @@"));
        assertTrue(sent.contains("P1: `app/pool.py` line 11 — Wrong container"));
        assertFalse(sent.contains("Unrelated"));
        verify(repo).replyToReviewComment(eq("acme"), eq("web"), eq(7L), eq(100L), contains("Looks fixed in `bbbbbbb`"));
        verify(repo).resolveReviewThread("acme", "web", "T1");
        verify(repo, times(1)).resolveReviewThread(any(), any(), any());
        ArgumentCaptor<String> body = ArgumentCaptor.forClass(String.class);
        captureComments(body);
        assertTrue(body.getValue().contains("### Fixed since the last review"));
        assertTrue(body.getValue().contains("Wrong container (`app/pool.py`)"));
    }

    @Test
    void reReviewWithNothingTouchedPostsANoChangeNoteWithoutAModelCall() {
        GitHubReview prev = new GitHubReview();
        prev.setId(5L);
        prev.setBody(InlineReviewComposer.SUMMARY_MARKER_PREFIX + " -->");
        prev.setCommitId(OLD);
        when(repo.getReviews("acme", "web", 7L)).thenReturn(List.of(prev));
        when(repo.getCompareDiff("acme", "web", OLD, HEAD)).thenReturn(String.join("\n",
                "diff --git a/README.md b/README.md", "--- a/README.md", "+++ b/README.md",
                "@@ -1 +1 @@", "-a", "+b", ""));
        String mustBody = InlineReviewComposer.renderComment(new ReviewFinding("app/other.py", 3,
                ReviewFinding.Side.RIGHT, ReviewFinding.Severity.MUST_FIX, "Still broken", "x"));
        when(repo.getReviewThreads("acme", "web", 7L)).thenReturn(List.of(
                new ReviewThread("T9", false, false, "app/pool.py", 51, 109L, "ai_bot", mustBody)));

        assertTrue(service.reviewPullRequestInline(payload()));

        verify(ai, never()).submitReviewPrompt(any(), any(), any());
        ArgumentCaptor<String> body = ArgumentCaptor.forClass(String.class);
        List<InlineReviewDraft> comments = captureComments(body);
        assertTrue(comments.isEmpty());
        assertTrue(body.getValue().contains("nothing to re-review"));
        assertTrue(body.getValue().contains("1 must-fix finding(s) from earlier reviews are still open"));
        assertTrue(body.getValue().contains("head=" + HEAD));
    }

    @Test
    void reReviewDoesNotRepostAFindingThatIsStillOpen() {
        GitHubReview prev = new GitHubReview();
        prev.setId(5L);
        prev.setBody(InlineReviewComposer.SUMMARY_MARKER_PREFIX + " must_fix=1 -->");
        prev.setCommitId(OLD);
        when(repo.getReviews("acme", "web", 7L)).thenReturn(List.of(prev));
        when(repo.getCompareDiff("acme", "web", OLD, HEAD)).thenReturn(String.join("\n",
                "diff --git a/app/pool.py b/app/pool.py", "--- a/app/pool.py", "+++ b/app/pool.py",
                "@@ -11 +11 @@", "-_SCOPES = []", "+_SCOPES = {}", ""));
        String mustBody = InlineReviewComposer.renderComment(new ReviewFinding("app/pool.py", 12,
                ReviewFinding.Side.RIGHT, ReviewFinding.Severity.MUST_FIX, "Keyed by id()", "x"));
        when(repo.getReviewThreads("acme", "web", 7L)).thenReturn(List.of(
                new ReviewThread("T1", false, false, "app/pool.py", 12, 100L, "ai_bot", mustBody)));
        when(ai.submitReviewPrompt(anyString(), isNull(), anyString())).thenReturn(
                "{\"summary\":\"s\",\"findings\":[{\"path\":\"app/pool.py\",\"line\":12,"
                        + "\"severity\":\"must_fix\",\"title\":\"Still keyed by id()\",\"body\":\"b\"}],"
                        + "\"resolved_prior\":[]}");

        assertTrue(service.reviewPullRequestInline(payload()));

        ArgumentCaptor<String> body = ArgumentCaptor.forClass(String.class);
        List<InlineReviewDraft> comments = captureComments(body);
        assertTrue(comments.isEmpty());
        assertTrue(body.getValue().contains("1 finding(s) repeated a thread that is still open"));
        assertTrue(body.getValue().contains("1 must-fix finding(s) from earlier reviews are still open"));
        verify(repo, never()).resolveReviewThread(any(), any(), any());
    }

    @Test
    void rejectedAnchorsAreRetriedWithEverythingInTheSummary() {
        when(repo.getReviews("acme", "web", 7L)).thenReturn(List.of());
        when(ai.submitReviewPrompt(anyString(), isNull(), anyString())).thenReturn(
                "{\"summary\":\"s\",\"findings\":[{\"path\":\"app/pool.py\",\"line\":11,"
                        + "\"severity\":\"must_fix\",\"title\":\"Bug\",\"body\":\"b\"}]}");
        doThrow(new HttpClientErrorException(HttpStatus.valueOf(422)))
                .doNothing()
                .when(repo).submitInlineReview(eq("acme"), eq("web"), eq(7L), eq(HEAD), anyString(), anyList());

        assertTrue(service.reviewPullRequestInline(payload()));

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<InlineReviewDraft>> comments = ArgumentCaptor.forClass(List.class);
        ArgumentCaptor<String> body = ArgumentCaptor.forClass(String.class);
        verify(repo, times(2)).submitInlineReview(eq("acme"), eq("web"), eq(7L), eq(HEAD), body.capture(),
                comments.capture());
        assertEquals(1, comments.getAllValues().get(0).size());
        assertTrue(comments.getAllValues().get(1).isEmpty());
        assertTrue(body.getAllValues().get(1).contains("### Not on a changed line"));
    }

    @Test
    void unstructuredAnswerBecomesTheSummary() {
        when(repo.getReviews("acme", "web", 7L)).thenReturn(List.of());
        when(ai.submitReviewPrompt(anyString(), isNull(), anyString())).thenReturn("Looks fine overall.");

        assertTrue(service.reviewPullRequestInline(payload()));

        ArgumentCaptor<String> body = ArgumentCaptor.forClass(String.class);
        List<InlineReviewDraft> comments = captureComments(body);
        assertTrue(comments.isEmpty());
        assertTrue(body.getValue().contains("Looks fine overall."));
        assertTrue(body.getValue().contains("without line anchors"));
    }

    @Test
    void providerWithoutInlineSupportFallsBackToSummaryReview() {
        when(repo.supportsInlineReviews()).thenReturn(false);
        when(ai.submitReviewPrompt(eq(PROMPT), isNull(), anyString())).thenReturn("Summary only");

        assertTrue(service.reviewPullRequestInline(payload()));

        verify(repo).postReviewComment(eq("acme"), eq("web"), eq(7L), contains("Summary only"));
        verify(repo, never()).submitInlineReview(any(), any(), any(), any(), any(), anyList());
    }

    @Test
    void allChunksFailingReturnsFalse() {
        when(repo.getReviews("acme", "web", 7L)).thenReturn(List.of());
        when(ai.submitReviewPrompt(anyString(), isNull(), anyString())).thenThrow(new RuntimeException("down"));

        assertFalse(service.reviewPullRequestInline(payload()));
        verify(repo, never()).submitInlineReview(any(), any(), any(), any(), any(), anyList());
    }
}
