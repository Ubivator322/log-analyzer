package loganalyzer;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public final class Stats {

    public record InvalidLine(long number, String reason) {
    }

    private long total;
    private long valid;
    private long invalid;
    private long matched;
    private final Map<String, Integer> levels = new HashMap<>();
    private final Map<String, Integer> sources = new HashMap<>();
    private final Map<String, Integer> eventCodes = new HashMap<>();
    private final List<InvalidLine> invalidLines = new ArrayList<>();

    private Stats() {
    }


    public static Stats analyze(Path file, Filter filter, PrintStream err) throws IOException {
        Stats stats = new Stats();
        try (BufferedReader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            String line;
            while ((line = reader.readLine()) != null) {
                stats.total++;
                try {
                    Event event = LogParser.parse(line);
                    stats.valid++;
                    if (filter.test(event)) {
                        stats.add(event);
                    }
                } catch (IllegalArgumentException e) {
                    stats.invalid++;
                    stats.invalidLines.add(new InvalidLine(stats.total, e.getMessage()));
                    err.println("Строка " + stats.total + ": " + e.getMessage());
                }
            }
        }
        return stats;
    }

    private void add(Event event) {
        matched++;
        bump(levels, event.level());
        bump(sources, event.source());
        bump(eventCodes, event.eventCode());
    }

    private static void bump(Map<String, Integer> map, String key) {
        map.merge(key, 1, Integer::sum);
    }

    public static List<Map.Entry<String, Integer>> top(Map<String, Integer> map, int limit) {
        List<Map.Entry<String, Integer>> sorted = new ArrayList<>(map.entrySet());
        sorted.sort((a, b) -> a.getValue().equals(b.getValue())
                ? a.getKey().compareTo(b.getKey())
                : b.getValue() - a.getValue());
        return new ArrayList<>(sorted.subList(0, Math.min(limit, sorted.size())));
    }

    public long total() {
        return total;
    }

    public long valid() {
        return valid;
    }

    public long invalid() {
        return invalid;
    }

    public long matched() {
        return matched;
    }

    public Map<String, Integer> levels() {
        return levels;
    }

    public Map<String, Integer> sources() {
        return sources;
    }

    public Map<String, Integer> eventCodes() {
        return eventCodes;
    }

    public List<InvalidLine> invalidLines() {
        return invalidLines;
    }
}
