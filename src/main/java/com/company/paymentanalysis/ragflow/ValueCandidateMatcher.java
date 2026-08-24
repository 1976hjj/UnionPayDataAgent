package com.company.paymentanalysis.ragflow;

import java.text.Normalizer;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Strict surface matching for real dimension values. */
public final class ValueCandidateMatcher {

    private static final Pattern JSON_ALIASES = Pattern.compile(
            "[\\\"']?aliases[\\\"']?\\s*[:=]\\s*\\[([^]]*)]", Pattern.CASE_INSENSITIVE);
    private static final Pattern TEXT_ALIASES = Pattern.compile(
            "(?:值域别名|字段值别名|别名|aliases?)\\s*[:=：]\\s*([^;；|\\n]+)", Pattern.CASE_INSENSITIVE);
    private static final Pattern QUOTED_VALUE = Pattern.compile("[\\\"']([^\\\"']+)[\\\"']");

    private ValueCandidateMatcher() {
    }

    public static boolean matches(String queryTerm, String candidateValue) {
        return match(queryTerm, candidateValue, "") != MatchKind.NONE;
    }

    public static boolean matches(
            String queryTerm, String candidateValue, String candidateDescription) {
        return match(queryTerm, candidateValue, candidateDescription) != MatchKind.NONE;
    }

    public static MatchKind match(
            String queryTerm, String candidateValue, String candidateDescription) {
        String query = normalize(queryTerm);
        String value = normalize(candidateValue);
        if (query.isBlank() || value.isBlank()) {
            return MatchKind.NONE;
        }
        if (query.equals(value)) {
            return MatchKind.EXACT_VALUE;
        }
        // Composite metric phrases such as "POS交易笔数" may carry a complete
        // alphabetic business code. Pure numeric values require whole-term equality
        // so dates and identifiers cannot be mistaken for short dimension codes.
        if (isCompositeBusinessCode(value) && containsAsciiToken(query, value)) {
            return MatchKind.EXACT_VALUE;
        }
        for (String alias : aliases(candidateDescription)) {
            if (query.equals(normalize(alias))) {
                return MatchKind.EXACT_ALIAS;
            }
        }
        return MatchKind.NONE;
    }

    private static boolean containsAsciiToken(String query, String value) {
        int fromIndex = 0;
        while (fromIndex <= query.length() - value.length()) {
            int index = query.indexOf(value, fromIndex);
            if (index < 0) return false;
            int end = index + value.length();
            boolean leftBoundary = index == 0 || !isAsciiAlphaNumeric(query.charAt(index - 1));
            boolean rightBoundary = end == query.length() || !isAsciiAlphaNumeric(query.charAt(end));
            if (leftBoundary && rightBoundary) return true;
            fromIndex = index + 1;
        }
        return false;
    }

    private static boolean isCompositeBusinessCode(String value) {
        if (value.codePointCount(0, value.length()) < 2) return false;
        boolean containsLetter = false;
        for (int index = 0; index < value.length(); index++) {
            if (!isAsciiAlphaNumeric(value.charAt(index))) return false;
            containsLetter |= isAsciiLetter(value.charAt(index));
        }
        return containsLetter;
    }

    private static List<String> aliases(String description) {
        if (description == null || description.isBlank()) return List.of();
        List<String> result = new ArrayList<>();
        Matcher json = JSON_ALIASES.matcher(description);
        while (json.find()) {
            Matcher quoted = QUOTED_VALUE.matcher(json.group(1));
            while (quoted.find()) addAlias(result, quoted.group(1));
        }
        Matcher text = TEXT_ALIASES.matcher(description);
        while (text.find()) {
            String values = text.group(1).trim();
            if (values.startsWith("[")) continue;
            for (String alias : values.split("[,，、/]")) addAlias(result, alias);
        }
        return List.copyOf(result);
    }

    static String aliasEvidence(String description) {
        List<String> values = aliases(description);
        return values.isEmpty() ? "" : "aliases=[\"" + String.join("\",\"", values) + "\"]";
    }

    private static void addAlias(List<String> target, String alias) {
        String cleaned = alias == null ? "" : alias.replaceAll("^[\\s\\\"']+|[\\s\\\"']+$", "");
        if (!cleaned.isBlank() && !target.contains(cleaned)) target.add(cleaned);
    }

    private static String normalize(String value) {
        if (value == null) {
            return "";
        }
        return Normalizer.normalize(value, Normalizer.Form.NFKC)
                .toLowerCase(Locale.ROOT)
                .replaceAll("\\s+", " ")
                .trim();
    }

    private static boolean isAsciiAlphaNumeric(char value) {
        return isAsciiLetter(value)
                || value >= '0' && value <= '9';
    }

    private static boolean isAsciiLetter(char value) {
        return value >= 'a' && value <= 'z'
                || value >= 'A' && value <= 'Z';
    }

    public enum MatchKind { EXACT_VALUE, EXACT_ALIAS, NONE }
}
