package org.remus.giteabot.github;

import org.junit.jupiter.api.Test;
import org.remus.giteabot.repository.PostReviewAction;
import org.remus.giteabot.repository.RepositoryApiClient;
import org.remus.giteabot.repository.model.RepositoryCredentials;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.content;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.jsonPath;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

/**
 * Unit tests for {@link GitHubApiClient} verifying that it correctly implements
 * {@link RepositoryApiClient} and exposes the expected base URL, clone URL, and token.
 */
class GitHubApiClientTest {

    private static final RepositoryCredentials CREDS =
            RepositoryCredentials.of("https://api.github.com", "https://github.com", "ghp_token");

    @Test
    void implementsRepositoryApiClient() {
        GitHubApiClient client = new GitHubApiClient(null, CREDS);
        assertInstanceOf(RepositoryApiClient.class, client);
    }

    @Test
    void getBaseUrl_returnsConfiguredUrl() {
        GitHubApiClient client = new GitHubApiClient(null, CREDS);
        assertEquals("https://api.github.com", client.getBaseUrl());
    }

    @Test
    void getCloneUrl_returnsConfiguredUrl() {
        GitHubApiClient client = new GitHubApiClient(null, CREDS);
        assertEquals("https://github.com", client.getCloneUrl());
    }

    @Test
    void getToken_returnsConfiguredToken() {
        GitHubApiClient client = new GitHubApiClient(null, CREDS);
        assertEquals("ghp_token", client.getToken());
    }

    @Test
    void constructorWithEnterpriseUrl() {
        var enterpriseCreds = RepositoryCredentials.of(
                "https://github.example.com/api/v3", "https://github.example.com", "token123");
        GitHubApiClient client = new GitHubApiClient(null, enterpriseCreds);
        assertEquals("https://github.example.com/api/v3", client.getBaseUrl());
        assertEquals("https://github.example.com", client.getCloneUrl());
    }

    @Test
    void getIssueComments_fetchesIssueCommentsWithPageLimit() {
        RestClient.Builder builder = RestClient.builder().baseUrl("https://api.github.com");
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        GitHubApiClient client = new GitHubApiClient(builder.build(), CREDS);

        server.expect(requestTo("https://api.github.com/repos/owner/repo/issues/42/comments?per_page=50"))
                .andExpect(method(HttpMethod.GET))
                .andRespond(withSuccess("[{\"id\":101,\"body\":\"First comment\"}]", MediaType.APPLICATION_JSON));

        List<Map<String, Object>> comments = client.getIssueComments("owner", "repo", 42L);

        server.verify();
        assertEquals(1, comments.size());
        assertEquals(101, ((Number) comments.getFirst().get("id")).intValue());
        assertEquals("First comment", comments.getFirst().get("body"));
    }

    @Test
    void addPullRequestReaction_postsEyesToIssueReactionEndpoint() {
        RestClient.Builder builder = RestClient.builder().baseUrl(CREDS.baseUrl());
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        GitHubApiClient client = new GitHubApiClient(builder.build(), CREDS);

        server.expect(requestTo("https://api.github.com/repos/owner/repo/issues/42/reactions"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(jsonPath("$.content").value("eyes"))
                .andRespond(withSuccess());

        client.addPullRequestReaction("owner", "repo", 42L, "eyes");

        server.verify();
    }

    @Test
    void addIssueReaction_postsEyesToIssueReactionEndpoint() {
        RestClient.Builder builder = RestClient.builder().baseUrl(CREDS.baseUrl());
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        GitHubApiClient client = new GitHubApiClient(builder.build(), CREDS);

        server.expect(requestTo("https://api.github.com/repos/owner/repo/issues/12/reactions"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(jsonPath("$.content").value("eyes"))
                .andRespond(withSuccess());

        client.addIssueReaction("owner", "repo", 12L, "eyes");

        server.verify();
    }

    @Test
    void postReview_requestChanges_submitsSingleReviewWithBodyAndEvent() {
        RestClient.Builder builder = RestClient.builder().baseUrl("https://api.github.com");
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        GitHubApiClient client = new GitHubApiClient(builder.build(), CREDS);

        server.expect(requestTo("https://api.github.com/repos/owner/repo/pulls/7/reviews"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(jsonPath("$.body").value("The findings"))
                .andExpect(jsonPath("$.event").value("REQUEST_CHANGES"))
                .andRespond(withSuccess());

        client.postReview("owner", "repo", 7L, "The findings", PostReviewAction.REQUEST_CHANGES);

        server.verify();
    }

    @Test
    void postReview_none_submitsSingleCommentReview() {
        RestClient.Builder builder = RestClient.builder().baseUrl("https://api.github.com");
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        GitHubApiClient client = new GitHubApiClient(builder.build(), CREDS);

        server.expect(requestTo("https://api.github.com/repos/owner/repo/pulls/7/reviews"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(jsonPath("$.body").value("Just a comment"))
                .andExpect(jsonPath("$.event").value("COMMENT"))
                .andRespond(withSuccess());

        client.postReview("owner", "repo", 7L, "Just a comment", PostReviewAction.NONE);

        server.verify();
    }

    @Test
    void assignIssue_postsAssignees() {
        RestClient.Builder builder = RestClient.builder().baseUrl("https://api.github.com");
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        GitHubApiClient client = new GitHubApiClient(builder.build(), CREDS);

        server.expect(requestTo("https://api.github.com/repos/owner/repo/issues/42/assignees"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.assignees[0]").value("alice"))
                .andRespond(withSuccess("{\"number\":42}", MediaType.APPLICATION_JSON));

        client.assignIssue("owner", "repo", 42L, "alice");

        server.verify();
    }

    // ---- inline reviews (upstream #121) ----

    private static final RepositoryCredentials GH = CREDS;

    @Test
    void submitInlineReview_postsOneReviewWithAnchoredComments() {
        RestClient.Builder builder = RestClient.builder().baseUrl("https://api.github.com");
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        GitHubApiClient client = new GitHubApiClient(builder.build(), GH);

        server.expect(requestTo("https://api.github.com/repos/owner/repo/pulls/7/reviews"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(jsonPath("$.commit_id").value("abc123"))
                .andExpect(jsonPath("$.event").value("COMMENT"))
                .andExpect(jsonPath("$.body").value("summary"))
                .andExpect(jsonPath("$.comments[0].path").value("app/pool.py"))
                .andExpect(jsonPath("$.comments[0].line").value(12))
                .andExpect(jsonPath("$.comments[0].side").value("RIGHT"))
                .andExpect(jsonPath("$.comments[0].body").value("bug"))
                .andRespond(withSuccess("{}", MediaType.APPLICATION_JSON));

        client.submitInlineReview("owner", "repo", 7L, "abc123", "summary",
                List.of(new org.remus.giteabot.repository.model.InlineReviewDraft("app/pool.py", 12, "RIGHT", "bug")));

        server.verify();
        assertTrue(client.supportsInlineReviews());
    }

    @Test
    void getCompareDiff_requestsDiffMediaType() {
        RestClient.Builder builder = RestClient.builder().baseUrl("https://api.github.com");
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        GitHubApiClient client = new GitHubApiClient(builder.build(), GH);

        server.expect(requestTo("https://api.github.com/repos/owner/repo/compare/aaa...bbb"))
                .andExpect(method(HttpMethod.GET))
                .andExpect(org.springframework.test.web.client.match.MockRestRequestMatchers
                        .header("Accept", "application/vnd.github.v3.diff"))
                .andRespond(withSuccess("diff text", MediaType.TEXT_PLAIN));

        assertEquals("diff text", client.getCompareDiff("owner", "repo", "aaa", "bbb"));
        server.verify();
    }

    @Test
    void getReviewThreads_readsGraphqlAndPaginates() {
        RestClient.Builder builder = RestClient.builder().baseUrl("https://api.github.com");
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        GitHubApiClient client = new GitHubApiClient(builder.build(), GH);

        server.expect(requestTo("https://api.github.com/graphql"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(jsonPath("$.variables.number").value(7))
                .andRespond(withSuccess("""
                        {"data":{"repository":{"pullRequest":{"reviewThreads":{
                          "pageInfo":{"hasNextPage":true,"endCursor":"C1"},
                          "nodes":[{"id":"T1","isResolved":false,"isOutdated":false,"path":"a.py","line":3,
                            "comments":{"nodes":[{"databaseId":55,"body":"b <!-- ai-git-bot:finding severity=must_fix -->",
                            "author":{"login":"bot"}}]}}]}}}}}""", MediaType.APPLICATION_JSON));
        server.expect(requestTo("https://api.github.com/graphql"))
                .andExpect(jsonPath("$.variables.cursor").value("C1"))
                .andRespond(withSuccess("""
                        {"data":{"repository":{"pullRequest":{"reviewThreads":{
                          "pageInfo":{"hasNextPage":false,"endCursor":null},
                          "nodes":[{"id":"T2","isResolved":true,"isOutdated":true,"path":"b.py","line":null,
                            "comments":{"nodes":[]}}]}}}}}""", MediaType.APPLICATION_JSON));

        var threads = client.getReviewThreads("owner", "repo", 7L);

        server.verify();
        assertEquals(2, threads.size());
        assertEquals("T1", threads.getFirst().id());
        assertEquals(55L, threads.getFirst().firstCommentId());
        assertEquals("bot", threads.getFirst().firstCommentAuthor());
        assertEquals(3, threads.getFirst().line());
        assertTrue(threads.get(1).resolved());
        assertTrue(threads.get(1).outdated());
        assertNull(threads.get(1).line());
    }

    @Test
    void resolveReviewThread_sendsMutationAndSurfacesGraphqlErrors() {
        RestClient.Builder builder = RestClient.builder().baseUrl("https://api.github.com");
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        GitHubApiClient client = new GitHubApiClient(builder.build(), GH);

        server.expect(requestTo("https://api.github.com/graphql"))
                .andExpect(jsonPath("$.variables.threadId").value("T1"))
                .andRespond(withSuccess("{\"data\":{\"resolveReviewThread\":{\"thread\":{\"id\":\"T1\"}}}}",
                        MediaType.APPLICATION_JSON));
        server.expect(requestTo("https://api.github.com/graphql"))
                .andRespond(withSuccess("{\"errors\":[{\"message\":\"Resource not accessible\"}]}",
                        MediaType.APPLICATION_JSON));

        client.resolveReviewThread("owner", "repo", "T1");
        IllegalStateException e = assertThrows(IllegalStateException.class,
                () -> client.resolveReviewThread("owner", "repo", "T2"));
        assertTrue(e.getMessage().contains("Resource not accessible"));
        server.verify();
    }

    @Test
    void replyToReviewComment_postsToRepliesEndpoint() {
        RestClient.Builder builder = RestClient.builder().baseUrl("https://api.github.com");
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        GitHubApiClient client = new GitHubApiClient(builder.build(), GH);

        server.expect(requestTo("https://api.github.com/repos/owner/repo/pulls/7/comments/55/replies"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(jsonPath("$.body").value("fixed"))
                .andRespond(withSuccess("{}", MediaType.APPLICATION_JSON));

        client.replyToReviewComment("owner", "repo", 7L, 55L, "fixed");
        server.verify();
    }

    @Test
    void graphqlUrl_followsRestBase() {
        assertEquals("https://api.github.com/graphql", GitHubApiClient.graphqlUrl("https://api.github.com/"));
        assertEquals("https://ghe.example.com/api/graphql",
                GitHubApiClient.graphqlUrl("https://ghe.example.com/api/v3"));
    }
}
