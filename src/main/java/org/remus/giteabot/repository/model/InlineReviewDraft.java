package org.remus.giteabot.repository.model;

/**
 * One line-anchored comment to submit as part of a pull-request review.
 *
 * @param path file path in the diff (new path for renamed files)
 * @param line line number on {@code side}: the new file for {@code RIGHT}, the old file for {@code LEFT}
 * @param side {@code RIGHT} or {@code LEFT}
 * @param body Markdown body of the comment
 */
public record InlineReviewDraft(String path, int line, String side, String body) {
}
