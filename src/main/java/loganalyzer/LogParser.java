package loganalyzer;

import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.List;


public final class LogParser {

    public static final List<String> LEVELS = List.of("TRACE", "DEBUG", "INFO", "WARN", "ERROR");

    private LogParser() {
    }

    public static Event parse(String line) {
        if (line == null || line.isBlank()) {
            throw new IllegalArgumentException("пустая строка");
        }
        String[] parts = line.split("\\|", -1);
        if (parts.length != 5) {
            throw new IllegalArgumentException("ожидалось 5 полей, найдено " + parts.length);
        }

        String level = parts[1].trim();
        String source = parts[2].trim();
        String eventCode = parts[3].trim();
        String message = parts[4].trim();
        Instant time = parseTimestamp(parts[0].trim());

        if (!LEVELS.contains(level)) {
            throw new IllegalArgumentException("неизвестный уровень '" + level + "'");
        }
        if (source.isEmpty()) {
            throw new IllegalArgumentException("пустое поле source");
        }
        if (eventCode.isEmpty()) {
            throw new IllegalArgumentException("пустое поле eventCode");
        }
        if (message.isEmpty()) {
            throw new IllegalArgumentException("пустое поле message");
        }
        return new Event(time, level, source, eventCode, message);
    }

    static Instant parseTimestamp(String value) {
        if (!value.endsWith("Z")) {
            throw new IllegalArgumentException("timestamp не в формате ISO 8601 UTC: '" + value + "'");
        }
        try {
            return Instant.parse(value);
        } catch (DateTimeParseException e) {
            throw new IllegalArgumentException("неверный timestamp: '" + value + "'");
        }
    }
}
