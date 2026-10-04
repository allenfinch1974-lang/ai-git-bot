# PR Review

> Workflow key: **`review`** · Enabled by default on every bot.

## The problem it solves

Every pull request should get a careful review, but human reviewers are busy and
small PRs often merge without a second pair of eyes. **PR Review** guarantees
that every pull request gets an AI review with concrete, actionable feedback the
moment it opens or updates.

This is the built-in default workflow — it runs on every bot with no setup
beyond connecting the bot to your Git host and AI provider.

## What it does

- On PR open or update, it reads the diff and posts a single Markdown review
  comment with a summary and findings.
- It answers follow-up questions when you mention the bot (`@bot …`) in a PR
  comment.
- It responds to bot mentions inside inline (line-level) diff comments.
- It processes review submissions that mention the bot.
- It cleans up its per-PR state when the PR closes.
- Optionally, it applies a formal review action (approve / request changes) —
  see below.

## Settings

Set these on **System settings → Workflow configurations → Workflows → PR
Review**. The defaults work well; tune them only if your model has an unusually
small or large context window.

| Setting | Default | What it controls |
|---|---|---|
| `maxDiffCharsPerChunk` | `120000` | How large a diff can be before it is split into chunks for review. |
| `maxDiffChunks` | `8` | Maximum number of chunks reviewed for one PR. Extra chunks are skipped. |
| `retryTruncatedChunkChars` | `60000` | If a chunk is too big for the model, it is truncated to this size and retried once. |
| `excludedFilePatterns` | *(blank)* | Files stripped from the diff before review. |
| `inlineFindings` | `false` | Post the review line by line (GitHub only) — see below. |

Large PRs are automatically split into chunks and reviewed piece by piece, so a
big diff never silently fails.

## Inline findings (GitHub)

With `inlineFindings` on, the review is posted line by line instead of as one
comment (issue #121):

- The model answers with structured findings — `path`, `line`, `side`,
  `severity` (`must_fix` or `consider`), `title`, `body`. The answer format is
  appended to the configured review prompt, so any prompt works; put your own
  "what counts as must fix" rules in the prompt.
- The diff sent to the model carries explicit line numbers (`[R12] +code`,
  `[L40] -code`), so the model copies anchors instead of counting.
- Every anchor is checked against the PR diff before posting. A near miss
  (up to 3 lines, inside the same hunk) is moved onto the diff; anything else is
  listed in the summary under **Not on a changed line** — nothing is dropped.
- Everything goes out as ONE GitHub review: a summary body ("**Must fix: N** ·
  Consider: M") plus one line comment per finding. If GitHub still rejects an
  anchor, the review is re-posted with every finding in the summary.
- On new commits (with **Run on PR update** on), only the PR hunks that the new
  commits touched are re-reviewed (`compare last-reviewed-sha...head`). The
  bot's still-open findings on those files are shown to the model; the ones it
  reports fixed get a "✅ Looks fixed in `sha`" reply and are resolved. The same
  head is never reviewed twice.
- Each line comment ends with a hidden marker
  (`<!-- ai-git-bot:finding severity=must_fix -->`) and the summary with
  `<!-- ai-git-bot:summary must_fix=N … head=sha -->`, so tooling (for example
  a merge gate that refuses while a must-fix thread is open) can read them.

Other providers keep the single summary comment.

## Post-review action

After posting the review comment, the bot can optionally submit a formal review
decision, configured on the bot's Git integration:

| Value | Effect |
|---|---|
| `NONE` (default) | Only the comment is posted. |
| `APPROVE` | The bot approves the PR. |
| `REQUEST_CHANGES` | The bot requests changes on the PR. |

## The review prompt

The review is driven by the operator-editable **Code-Review System-Prompt**
under **System settings → System prompts**. Edit it to change the review's tone,
focus, or policies; changes take effect on the next review.

## Enabling / disabling

PR Review is enabled by default via the seeded `Default` configuration. To turn
it off, untick **PR Review** in the workflow configuration assigned to the bot
(or clone the configuration and remove it there).

## See also

- [PR Workflows overview](PR_WORKFLOWS.md)
- [Agentic PR Review](PR_WORKFLOWS_AGENTIC_REVIEW.md) — a deeper review that
  reads the surrounding code first.
