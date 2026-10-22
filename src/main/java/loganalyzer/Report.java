package loganalyzer;

import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

public final class Report {

    private Report() {
    }

    public static void printSummary(PrintStream out, String input, Filter filter, Stats stats, int top) {
        out.println("Файл: " + input);
        out.println("Фильтр: from=" + orDash(filter.from()) + ", to=" + orDash(filter.to())
                + ", level=" + orDash(filter.level()) + ", source=" + orDash(filter.source()));
        out.println("Строк всего: " + stats.total());
        out.println("Корректных строк: " + stats.valid());
        out.println("Ошибочных строк: " + stats.invalid());
        out.println("Событий после фильтра: " + stats.matched());
        printCounts(out, "По уровням", stats.levels());
        printCounts(out, "По источникам", stats.sources());

        out.println();
        out.println("Топ-" + top + " кодов событий:");
        List<Map.Entry<String, Integer>> codes = Stats.top(stats.eventCodes(), top);
        if (codes.isEmpty()) {
            out.println("  нет данных");
            return;
        }
        int place = 1;
        for (Map.Entry<String, Integer> entry : codes) {
            out.println("  " + place++ + ". " + entry.getKey() + " — " + entry.getValue());
        }
    }

    private static void printCounts(PrintStream out, String title, Map<String, Integer> counts) {
        out.println();
        out.println(title + ":");
        List<Map.Entry<String, Integer>> entries = Stats.top(counts, Integer.MAX_VALUE);
        if (entries.isEmpty()) {
            out.println("  нет данных");
            return;
        }
        for (Map.Entry<String, Integer> entry : entries) {
            out.println("  " + entry.getKey() + " — " + entry.getValue());
        }
    }

    private static String orDash(Object value) {
        return value == null ? "-" : value.toString();
    }

    /** Отчёт в JSON. */
    public static String toJson(String input, Filter filter, Stats stats, int top) {
        StringBuilder b = new StringBuilder(2048);
        b.append("{\n");
        b.append("  \"input\": ").append(text(input)).append(",\n");
        b.append("  \"filters\": {\"from\": ").append(text(orNull(filter.from())))
                .append(", \"to\": ").append(text(orNull(filter.to())))
                .append(", \"level\": ").append(text(filter.level()))
                .append(", \"source\": ").append(text(filter.source())).append("},\n");
        b.append("  \"lines\": {\"total\": ").append(stats.total())
                .append(", \"valid\": ").append(stats.valid())
                .append(", \"invalid\": ").append(stats.invalid()).append("},\n");
        b.append("  \"matched\": ").append(stats.matched()).append(",\n");
        b.append("  \"byLevel\": ").append(countsJson(stats.levels(), Integer.MAX_VALUE)).append(",\n");
        b.append("  \"bySource\": ").append(countsJson(stats.sources(), Integer.MAX_VALUE)).append(",\n");
        b.append("  \"topEventCodes\": ").append(topJson(stats.eventCodes(), top)).append(",\n");
        b.append("  \"invalidLines\": ");
        List<Stats.InvalidLine> invalid = stats.invalidLines();
        if (invalid.isEmpty()) {
            b.append("[]\n");
        } else {
            b.append("[\n");
            for (int i = 0; i < invalid.size(); i++) {
                b.append("    {\"line\": ").append(invalid.get(i).number())
                        .append(", \"reason\": ").append(text(invalid.get(i).reason()))
                        .append(i + 1 < invalid.size() ? "},\n" : "}\n");
            }
            b.append("  ]\n");
        }
        return b.append("}\n").toString();
    }

    private static String orNull(Object value) {
        return value == null ? null : value.toString();
    }

    private static String countsJson(Map<String, Integer> counts, int limit) {
        StringBuilder b = new StringBuilder("{");
        List<Map.Entry<String, Integer>> entries = Stats.top(counts, limit);
        for (int i = 0; i < entries.size(); i++) {
            if (i > 0) {
                b.append(", ");
            }
            b.append(text(entries.get(i).getKey())).append(": ").append(entries.get(i).getValue());
        }
        return b.append('}').toString();
    }

    private static String topJson(Map<String, Integer> counts, int limit) {
        StringBuilder b = new StringBuilder("[");
        List<Map.Entry<String, Integer>> entries = Stats.top(counts, limit);
        for (int i = 0; i < entries.size(); i++) {
            if (i > 0) {
                b.append(", ");
            }
            b.append("{\"eventCode\": ").append(text(entries.get(i).getKey()))
                    .append(", \"count\": ").append(entries.get(i).getValue()).append('}');
        }
        return b.append(']').toString();
    }

    private static String text(String value) {
        if (value == null) {
            return "null";
        }
        StringBuilder b = new StringBuilder(value.length() + 2).append('"');
        for (char c : value.toCharArray()) {
            switch (c) {
                case '"' -> b.append("\\\"");
                case '\\' -> b.append("\\\\");
                case '\n' -> b.append("\\n");
                case '\r' -> b.append("\\r");
                case '\t' -> b.append("\\t");
                default -> {
                    if (c < 0x20) {
                        b.append(String.format("\\u%04x", (int) c));
                    } else {
                        b.append(c);
                    }
                }
            }
        }
        return b.append('"').toString();
    }

    public static String toCsv(Stats stats, int top) {
        StringBuilder b = new StringBuilder(1024);
        b.append("section,key,count,reason\n");
        csvRow(b, "summary", "total_lines", stats.total(), "");
        csvRow(b, "summary", "valid_lines", stats.valid(), "");
        csvRow(b, "summary", "invalid_lines", stats.invalid(), "");
        csvRow(b, "summary", "matched_events", stats.matched(), "");
        for (Map.Entry<String, Integer> entry : Stats.top(stats.levels(), Integer.MAX_VALUE)) {
            csvRow(b, "level", entry.getKey(), entry.getValue(), "");
        }
        for (Map.Entry<String, Integer> entry : Stats.top(stats.sources(), Integer.MAX_VALUE)) {
            csvRow(b, "source", entry.getKey(), entry.getValue(), "");
        }
        for (Map.Entry<String, Integer> entry : Stats.top(stats.eventCodes(), top)) {
            csvRow(b, "event_code", entry.getKey(), entry.getValue(), "");
        }
        for (Stats.InvalidLine line : stats.invalidLines()) {
            csvRow(b, "invalid_line", String.valueOf(line.number()), 1, line.reason());
        }
        return b.toString();
    }

    private static void csvRow(StringBuilder b, String section, String key, long count, String reason) {
        b.append(csv(section)).append(',').append(csv(key)).append(',').append(count)
                .append(',').append(csv(reason)).append('\n');
    }

    private static String csv(String value) {
        if (value.indexOf(',') < 0 && value.indexOf('"') < 0 && value.indexOf('\n') < 0) {
            return value;
        }
        return '"' + value.replace("\"", "\"\"") + '"';
    }

    public static void write(String path, String format, String input, Filter filter, Stats stats, int top)
            throws IOException {
        String content = "csv".equals(format)
                ? toCsv(stats, top)
                : toJson(input, filter, stats, top);
        Files.writeString(Path.of(path), content, StandardCharsets.UTF_8);
    }
}
