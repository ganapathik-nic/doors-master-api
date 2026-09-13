package org.gepnic.doors.masterapi.service;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Converts retrieved PDF text into readable Markdown without generative AI. */
final class AiraExtractiveFormatter {
    private static final Pattern SOURCE = Pattern.compile(
            "^\\[SOURCE:\\s*([^|\\]]+?)(?:\\s*\\|\\s*PAGE:\\s*(\\d+))?(?:\\s*\\|[^\\]]*)?]\\s*",
            Pattern.CASE_INSENSITIVE);
    private static final List<String> ROLES = List.of(
            "Security Administrator", "Data Manager", "Developer", "Data Viewer", "External User", "API User");
    private static final Set<String> BOILERPLATE = Set.of(
            "doors", "document control", "field value", "publication rule", "doors governance");

    private AiraExtractiveFormatter() {}

    static String format(List<String> snippets) {
        List<Passage> passages = snippets.stream()
                .map(AiraExtractiveFormatter::parse)
                .filter(p -> !p.body().isBlank())
                .sorted((a, b) -> Integer.compare(score(b), score(a)))
                .filter(p -> !isFlyer(p.source()) || snippets.size() == 1)
                .limit(2)
                .toList();
        if (passages.isEmpty()) passages = snippets.stream().map(AiraExtractiveFormatter::parse).limit(1).toList();
        passages = passages.stream().sorted(AiraExtractiveFormatter::compareDocumentOrder).toList();

        StringBuilder answer = new StringBuilder("According to the retrieved DOORS documentation:\n\n");
        Set<String> seenLines = new LinkedHashSet<>();
        Set<String> citations = new LinkedHashSet<>();
        int appended = 0;
        for (Passage passage : passages) {
            String cleaned = cleanBody(passage.body(), seenLines);
            if (cleaned.isBlank()) continue;
            if (appended++ > 0) answer.append("\n\n");
            answer.append(cleaned);
            citations.add(citation(passage));
        }
        answer.append("\n\nSources:\n");
        citations.forEach(source -> answer.append("- ").append(source).append('\n'));
        return answer.toString().trim();
    }

    static String formatSources(List<String> snippets) {
        Set<String> citations = new LinkedHashSet<>();
        snippets.stream().map(AiraExtractiveFormatter::parse)
                .forEach(passage -> citations.add(citation(passage)));
        StringBuilder sources = new StringBuilder("Sources:\n");
        citations.forEach(source -> sources.append("- ").append(source).append('\n'));
        return sources.toString().trim();
    }

    private static Passage parse(String snippet) {
        String value = snippet == null ? "" : snippet.trim();
        Matcher matcher = SOURCE.matcher(value);
        if (matcher.find()) {
            return new Passage(matcher.group(1).trim(), matcher.group(2), value.substring(matcher.end()).trim());
        }
        if (value.startsWith("[DOORS PLATFORM OPERATIONAL GUIDE")) {
            int end = value.indexOf('\n');
            return new Passage("DOORS Platform Operational Guide", null, end < 0 ? value : value.substring(end + 1));
        }
        return new Passage("DOORS knowledge base", null, value);
    }

    private static String cleanBody(String body, Set<String> seenLines) {
        List<String> output = new ArrayList<>();
        StringBuilder paragraph = new StringBuilder();
        for (String raw : body.replace('\uFFFD', ' ').split("\\R")) {
            String line = unescapePdfText(raw).replaceAll("\\s+", " ").trim();
            if (line.isEmpty()) { flushParagraph(output, paragraph, seenLines); continue; }
            String lower = line.toLowerCase(Locale.ROOT);
            if (BOILERPLATE.contains(lower) || lower.startsWith("controlled copy")
                    || lower.startsWith("doors | controlled technical reference")
                    || lower.startsWith("nic eprocurement project | version")
                    || lower.startsWith("document id version audience classification")
                    || lower.matches("^(title|owner|version / status|effective date|review cycle|audience|authoritative source)\\s+.*")) {
                flushParagraph(output, paragraph, seenLines);
                continue;
            }
            String role = ROLES.stream().filter(r -> line.startsWith(r + " ")).findFirst().orElse(null);
            if (role != null) {
                flushParagraph(output, paragraph, seenLines);
                addUnique(output, seenLines, "- " + role + ": " + line.substring(role.length()).trim());
            } else if (line.startsWith("•") || line.startsWith("- ")) {
                flushParagraph(output, paragraph, seenLines);
                addUnique(output, seenLines, "- " + line.replaceFirst("^[•-]\\s*", ""));
            } else if (isHeading(line)) {
                flushParagraph(output, paragraph, seenLines);
                addUnique(output, seenLines, titleCaseHeading(line) + "\n");
            } else {
                if (paragraph.length() > 0) paragraph.append(' ');
                paragraph.append(line);
            }
        }
        flushParagraph(output, paragraph, seenLines);
        return String.join("\n", output);
    }

    private static boolean isHeading(String line) {
        if (line.matches("^\\d+\\.\\s+[^.]+$")) return true;
        if (line.length() > 55 || line.contains("|") || line.matches(".*\\d.*")) return false;
        String lower = line.toLowerCase(Locale.ROOT);
        return lower.equals("role matrix") || lower.equals("segregation rules") || lower.equals("access review")
                || lower.equals("security at every layer") || lower.equals("five roles. clear responsibilities.")
                || lower.equals("scope") || lower.equals("reference sequence");
    }

    private static String titleCaseHeading(String line) {
        if (!line.equals(line.toUpperCase(Locale.ROOT))) return line;
        String lower = line.toLowerCase(Locale.ROOT);
        return Character.toUpperCase(lower.charAt(0)) + lower.substring(1);
    }

    private static void flushParagraph(List<String> output, StringBuilder paragraph, Set<String> seenLines) {
        if (!paragraph.isEmpty()) {
            addUnique(output, seenLines, paragraph.toString());
            paragraph.setLength(0);
        }
    }

    private static void addUnique(List<String> output, Set<String> seenLines, String line) {
        String key = line.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]+", " ").trim();
        if (!key.isBlank() && seenLines.add(key)) output.add(line);
    }

    private static int score(Passage passage) {
        int score = Math.min(passage.body().length(), 2_000);
        if (isFlyer(passage.source())) score -= 1_200;
        String body = passage.body().toLowerCase(Locale.ROOT);
        if (body.contains("role matrix")) score += 1_000;
        if (body.contains("segregation rules")) score += 700;
        return score;
    }

    private static boolean isFlyer(String source) { return source.toLowerCase(Locale.ROOT).contains("flyer"); }
    private static String unescapePdfText(String value) {
        return value.replaceAll("\\\\([._<>-])", "$1").replaceAll("\\\\(?=\\d*\\.)", "");
    }
    private static int compareDocumentOrder(Passage left, Passage right) {
        int source = left.source().compareToIgnoreCase(right.source());
        if (source != 0) return source;
        return Integer.compare(pageNumber(left.page()), pageNumber(right.page()));
    }
    private static int pageNumber(String page) {
        try { return page == null ? Integer.MAX_VALUE : Integer.parseInt(page); }
        catch (NumberFormatException ignored) { return Integer.MAX_VALUE; }
    }
    private static String citation(Passage passage) {
        String source = passage.source().replaceFirst("(?i)^secure-docs/", "");
        return passage.page() == null ? source : source + ", page " + passage.page();
    }
    private record Passage(String source, String page, String body) {}
}
