/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to you under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.jmeter.gui.action;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import com.fasterxml.jackson.databind.JsonNode;

final class AiRunOutput {
    private static final int MAX_FOLLOW_UP_LINES = 4;
    private static final int MAX_SUMMARY_LINES = 5;
    private Long inputTokens;
    private Long outputTokens;
    private Long totalTokens;
    private long piCachedInputTokens;
    private long piReasoningTokens;
    private int piUsageMessages;
    private boolean nextLineIsTotalTokens;
    private final List<String> finalResponseLines = new ArrayList<>();

    int piUsageMessages() {
        return piUsageMessages;
    }

    long piCachedInputTokens() {
        return piCachedInputTokens;
    }

    long piReasoningTokens() {
        return piReasoningTokens;
    }

    void capturePiUsage(JsonNode usage) {
        if (!usage.isObject()) {
            return;
        }
        long input = usage.path("input").asLong() + usage.path("cacheRead").asLong()
                + usage.path("cacheWrite").asLong();
        long completion = usage.path("output").asLong();
        piUsageMessages++;
        piCachedInputTokens += usage.path("cacheRead").asLong();
        piReasoningTokens += usage.path("reasoning").asLong();
        inputTokens = (inputTokens == null ? 0 : inputTokens) + input;
        outputTokens = (outputTokens == null ? 0 : outputTokens) + completion;
        totalTokens = inputTokens + outputTokens;
    }

    void captureTokenLine(String line) {
        String lower = line.toLowerCase(Locale.ROOT);
        if (lower.equals("tokens used")) {
            nextLineIsTotalTokens = true;
            return;
        }
        if (nextLineIsTotalTokens) {
            if (line.strip().matches("[0-9][0-9,.]*")) {
                parseTokenNumber(line).ifPresent(value -> totalTokens = value);
            }
            nextLineIsTotalTokens = false;
            return;
        }
        // Tool payloads and model prose also contain words such as "input" and "token".
        // Accept only explicit standalone usage labels, never arbitrary lines with numbers.
        java.util.regex.Matcher usage = java.util.regex.Pattern.compile(
                "^(input|output|completion|total)[ _]tokens?\\s*[:=]\\s*([0-9][0-9,.]*)$",
                java.util.regex.Pattern.CASE_INSENSITIVE).matcher(line.strip());
        if (usage.matches()) {
            parseTokenNumber(usage.group(2)).ifPresent(value -> {
                switch (usage.group(1).toLowerCase(Locale.ROOT)) {
                    case "input" -> inputTokens = value;
                    case "output", "completion" -> outputTokens = value;
                    case "total" -> totalTokens = value;
                    default -> { }
                }
            });
        }
    }

    private static java.util.Optional<Long> parseTokenNumber(String line) {
        java.util.regex.Matcher matcher = java.util.regex.Pattern.compile("([0-9][0-9.,]*)").matcher(line);
        Long found = null;
        while (matcher.find()) {
            String normalized = matcher.group(1).replace(".", "").replace(",", "");
            try {
                found = Long.parseLong(normalized);
            } catch (NumberFormatException ignored) {
                // Keep looking for another numeric token.
            }
        }
        return java.util.Optional.ofNullable(found);
    }

    void startFinalResponseBlock() {
        finalResponseLines.clear();
    }

    void captureFinalResponse(String line) {
        finalResponseLines.add(line);
    }

    void requireRepairCompletionStatus() {
        boolean hasStatus = finalResponseLines.stream().map(AiRunOutput::plainText)
                .map(line -> line.toLowerCase(Locale.ROOT))
                .anyMatch(line -> line.startsWith("status: completed") || line.startsWith("status: blocked"));
        if (!hasStatus) {
            finalResponseLines.add("Status: blocked - Agent ended without a repair completion status; validation is unconfirmed.");
        }
    }

    String inputTokensText() {
        return inputTokens == null ? "not reported" : String.valueOf(inputTokens);
    }

    String outputTokensText() {
        return outputTokens == null ? "not reported" : String.valueOf(outputTokens);
    }

    String totalTokensText() {
        return totalTokens == null ? "not reported" : String.valueOf(totalTokens);
    }

    List<String> summaryLines() {
        List<String> summary = new ArrayList<>();
        for (String line : finalResponseLines) {
            if (summary.size() >= MAX_SUMMARY_LINES) {
                break;
            }
            if (isMarkdownTableLine(line) || isMarkdownHeading(line)) {
                continue;
            }
            String plain = plainText(line);
            String lower = plain.toLowerCase(Locale.ROOT);
            if (plain.isBlank()) {
                continue;
            }
            if (lower.startsWith("repaired ")
                    || lower.startsWith("final validation")
                    || lower.contains("validates green")
                    || lower.contains("validation is green")
                    || lower.contains("green across")) {
                addDistinct(summary, plain);
            }
        }
        return summary;
    }

    List<String> followUpLines() {
        List<String> followUpLines = new ArrayList<>();
        for (String line : finalResponseLines) {
            if (followUpLines.size() >= MAX_FOLLOW_UP_LINES) {
                break;
            }
            String tableIssue = remainingBlockerFromTable(line, true);
            if (tableIssue != null) {
                addDistinct(followUpLines, tableIssue);
                continue;
            }
            if (isMarkdownTableLine(line)) {
                continue;
            }
            String plain = plainText(line);
            String lower = stripNegatedBlockers(plain.toLowerCase(Locale.ROOT));
            if (plain.isBlank() || reportsNoFollowUp(lower) || reportsSuccess(lower)) {
                continue;
            }
            if (reportsRepairBlocker(lower)
                    || lower.contains("manual")
                    || lower.contains("could not")
                    || lower.contains("unresolved")) {
                addDistinct(followUpLines, plain);
            }
        }
        return followUpLines;
    }

    boolean hasRepairBlocker() {
        for (String line : finalResponseLines) {
            if (remainingBlockerFromTable(line, false) != null) {
                return true;
            }
            if (isMarkdownTableLine(line)) {
                continue;
            }
            String lower = stripNegatedBlockers(plainText(line).toLowerCase(Locale.ROOT));
            if (reportsNoFollowUp(lower) || reportsSuccess(lower)) {
                continue;
            }
            if (reportsRepairBlocker(lower)) {
                return true;
            }
        }
        return false;
    }

    private static String stripNegatedBlockers(String lower) {
        // Negation can govern a list: "without truncation, ignored failures, or remaining blockers".
        // Remove only that negative phrase, so a separate failure in the same line still counts.
        // Do not treat "without resolving remaining blockers" as a successful outcome.
        return lower.replaceAll("\\bwithout\\s+"
                + "(?:(?:truncation|(?:ignored\\s+)?(?:static\\s+)?failures|errors)\\s*(?:,\\s*|(?:and|or)\\s+))*"
                + "(?:(?:and|or)\\s+)?(?:any\\s+)?(?:remaining|unresolved)\\s+blockers?\\b", "");
    }

    private static boolean reportsRepairBlocker(String lower) {
        return lower.startsWith("status: blocked")
                || lower.startsWith("status: failed")
                || lower.contains("not fully green")
                || lower.contains("validation is not fully green")
                || lower.contains("validation remains blocked")
                || lower.contains("validation remains")
                || lower.contains("not validated past")
                || lower.contains("not reached due")
                || lower.contains("remaining blocker")
                || lower.contains("unresolved blocker")
                || lower.contains("gui bridge failure")
                || lower.contains("could not be restored")
                || lower.contains("could not be validated")
                || lower.contains("could not restore")
                || lower.contains("could not validate");
    }

    private static void addDistinct(List<String> lines, String line) {
        if (!lines.contains(line)) {
            lines.add(line);
        }
    }

    private static boolean isMarkdownTableLine(String line) {
        String trimmed = line.trim();
        return trimmed.startsWith("|") || trimmed.matches("\\|?\\s*[-:| ]{3,}\\s*\\|?");
    }

    private static boolean isMarkdownHeading(String line) {
        return line.trim().matches("#{1,6}\\s+.*");
    }

    private static String plainText(String line) {
        String plain = line.trim()
                .replace("`", "")
                .replace("**", "");
        while (plain.startsWith("- ") || plain.startsWith("* ")) {
            plain = plain.substring(2).trim();
        }
        return plain;
    }

    private static boolean reportsNoFollowUp(String lower) {
        return lower.contains("none reported")
                || lower.contains("no remaining blocker")
                || lower.contains("remaining blockers: none")
                || lower.contains("remaining blocker: none")
                || lower.equals("none");
    }

    private static boolean reportsSuccess(String lower) {
        return lower.contains("final validation is green")
                || lower.contains("validates green")
                || lower.contains("green across")
                || lower.contains("with no ignored static failures")
                || lower.contains("remaining blockers |");
    }

    private static String remainingBlockerFromTable(String line, boolean includeResidualNotes) {
        String trimmed = line.trim();
        if (!trimmed.startsWith("|")) {
            return null;
        }
        String[] rawCells = trimmed.split("\\|", -1);
        List<String> cells = new ArrayList<>();
        for (String rawCell : rawCells) {
            String cell = plainText(rawCell);
            if (!cell.isBlank()) {
                cells.add(cell);
            }
        }
        if (cells.size() < 5) {
            return null;
        }
        String transaction = cells.get(0);
        String blocker = cells.get(cells.size() - 1);
        String lower = blocker.toLowerCase(Locale.ROOT);
        if (transaction.equalsIgnoreCase("transaction")
                || lower.equals("remaining blockers")
                || blocker.matches("[-: ]+")) {
            return null;
        }
        if (lower.equals("none")) {
            return null;
        }
        if (lower.startsWith("none;")) {
            String residual = blocker.substring(blocker.indexOf(';') + 1).trim();
            return includeResidualNotes && !residual.isBlank()
                    ? transaction + ": " + residual
                    : null;
        }
        if (isNonBlockingResidual(lower)) {
            return includeResidualNotes ? transaction + ": " + blocker : null;
        }
        return transaction + ": " + blocker;
    }

    private static boolean isNonBlockingResidual(String lower) {
        return lower.contains("low-confidence")
                || lower.contains("noise")
                || lower.contains("left unchanged");
    }
}
