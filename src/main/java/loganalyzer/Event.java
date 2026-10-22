package loganalyzer;

import java.time.Instant;

public record Event(Instant time, String level, String source, String eventCode, String message) {
}
