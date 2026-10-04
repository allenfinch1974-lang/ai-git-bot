package org.remus.giteabot.review.inline;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Parses the model's JSON answer (see {@link InlineReviewPrompt#OUTPUT_CONTRACT}).
 *
 * <p>Lenient on purpose: the JSON may be wrapped in a Markdown fence or preceded
 * by prose, keys may use {@code snake_case} or {@code camelCase}, a line may be a
 * string, and a single finding object is accepted where a list was asked for.
 * Returns {@link Optional#empty()} when no JSON object can be found, so the caller
 * can fall back to the classic single-comment review.</p>
 */
@Slf4j
public final class StructuredReviewParser {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private StructuredReviewParser() {
    }

    public static Optional<StructuredReview> parse(String raw) {
        if (raw == null || raw.isBlank()) {
            return Optional.empty();
        }
        String json = extractJsonObject(raw);
        if (json == null) {
            return Optional.empty();
        }
        try {
            JsonNode root = MAPPER.readTree(json);
            if (root == null || !root.isObject()) {
                return Optional.empty();
            }
            String summary = text(root, "summary", "overview", "verdict");
            List<ReviewFinding> findings = new ArrayList<>();
            JsonNode list = first(root, "findings", "comments", "issues");
            if (list != null && list.isArray()) {
                for (JsonNode n : list) {
                    toFinding(n).ifPresent(findings::add);
                }
            } else if (list != null && list.isObject()) {
                toFinding(list).ifPresent(findings::add);
            }
            List<String> resolved = new ArrayList<>();
            JsonNode res = first(root, "resolved_prior", "resolvedPrior", "resolved");
            if (res != null && res.isArray()) {
                for (JsonNode n : res) {
                    String id = n.isTextual() ? n.asText() : n.toString();
                    if (id != null && !id.isBlank()) {
                        resolved.add(id.strip());
                    }
                }
            }
            return Optional.of(new StructuredReview(summary, findings, resolved));
        } catch (Exception e) {
            log.warn("Could not parse structured review JSON: {}", e.getMessage());
            return Optional.empty();
        }
    }

    private static Optional<ReviewFinding> toFinding(JsonNode n) {
        if (n == null || !n.isObject()) {
            return Optional.empty();
        }
        String path = text(n, "path", "file", "filename");
        Integer line = intValue(first(n, "line", "line_number", "lineNumber", "new_line"));
        String title = text(n, "title", "summary", "headline");
        String body = text(n, "body", "comment", "message", "description", "detail");
        if ((title == null || title.isBlank()) && (body == null || body.isBlank())) {
            return Optional.empty();
        }
        return Optional.of(new ReviewFinding(path, line,
                ReviewFinding.Side.parse(text(n, "side")),
                ReviewFinding.Severity.parse(text(n, "severity", "level", "priority")),
                title, body));
    }

    private static JsonNode first(JsonNode n, String... keys) {
        for (String k : keys) {
            JsonNode v = n.get(k);
            if (v != null && !v.isNull()) {
                return v;
            }
        }
        return null;
    }

    private static String text(JsonNode n, String... keys) {
        JsonNode v = first(n, keys);
        if (v == null) {
            return null;
        }
        return v.isValueNode() ? v.asText() : v.toString();
    }

    private static Integer intValue(JsonNode v) {
        if (v == null) {
            return null;
        }
        if (v.isNumber()) {
            return v.asInt();
        }
        String s = v.asText("").replaceAll("[^0-9]", " ").strip();
        if (s.isEmpty()) {
            return null;
        }
        try {
            return Integer.parseInt(s.split("\\s+")[0]);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /**
     * Finds the outermost balanced JSON object in {@code raw}, preferring the content
     * of a {@code ```json} fence. String literals are respected when balancing braces.
     */
    static String extractJsonObject(String raw) {
        String text = raw;
        int fence = text.indexOf("```");
        if (fence >= 0) {
            int bodyStart = text.indexOf('\n', fence);
            int fenceEnd = bodyStart < 0 ? -1 : text.indexOf("```", bodyStart);
            if (bodyStart >= 0 && fenceEnd > bodyStart) {
                String inner = text.substring(bodyStart + 1, fenceEnd);
                String found = balanced(inner);
                if (found != null) {
                    return found;
                }
            }
        }
        return balanced(text);
    }

    private static String balanced(String text) {
        int start = text.indexOf('{');
        while (start >= 0) {
            int depth = 0;
            boolean inString = false;
            boolean escaped = false;
            for (int i = start; i < text.length(); i++) {
                char c = text.charAt(i);
                if (inString) {
                    if (escaped) {
                        escaped = false;
                    } else if (c == '\\') {
                        escaped = true;
                    } else if (c == '"') {
                        inString = false;
                    }
                    continue;
                }
                if (c == '"') {
                    inString = true;
                } else if (c == '{') {
                    depth++;
                } else if (c == '}') {
                    depth--;
                    if (depth == 0) {
                        return text.substring(start, i + 1);
                    }
                }
            }
            start = text.indexOf('{', start + 1);
        }
        return null;
    }
}
