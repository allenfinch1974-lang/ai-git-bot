package org.remus.giteabot.review.inline;

import java.util.List;

/**
 * The model's structured answer for one diff (or one diff chunk).
 *
 * @param summary       short overall verdict, shown in the review's summary body
 * @param findings      line-anchored findings (validated later against the diff)
 * @param resolvedPrior ids of earlier bot findings the new commits fix (re-review only)
 */
public record StructuredReview(String summary, List<ReviewFinding> findings, List<String> resolvedPrior) {

    public StructuredReview {
        summary = summary == null ? "" : summary.strip();
        findings = findings == null ? List.of() : List.copyOf(findings);
        resolvedPrior = resolvedPrior == null ? List.of() : List.copyOf(resolvedPrior);
    }
}
