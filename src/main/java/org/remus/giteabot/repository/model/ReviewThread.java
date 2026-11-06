package org.remus.giteabot.repository.model;

/**
 * A pull-request review thread (a line comment and its replies), as needed to
 * follow up on earlier inline findings: whether it is resolved or outdated,
 * where it is anchored, and its first comment.
 *
 * @param id                provider thread id (GitHub GraphQL node id), used to resolve it
 * @param resolved          whether the thread is already resolved
 * @param outdated          whether the anchored code has since changed
 * @param path              file path the thread is anchored to
 * @param line              current line on the PR head (may be {@code null} when outdated)
 * @param firstCommentId    numeric id of the first comment (to reply to the thread)
 * @param firstCommentAuthor login of the first comment's author
 * @param firstCommentBody  body of the first comment
 */
public record ReviewThread(String id, boolean resolved, boolean outdated, String path, Integer line,
                           Long firstCommentId, String firstCommentAuthor, String firstCommentBody) {
}
