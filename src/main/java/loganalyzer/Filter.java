package loganalyzer;

import java.time.Instant;

public record Filter(Instant from, Instant to, String level, String source) {

    public boolean test(Event event) {
        if (from != null && event.time().isBefore(from)) {
            return false;
        }
        if (to != null && !event.time().isBefore(to)) {
            return false;
        }
        if (level != null && !level.equals(event.level())) {
            return false;
        }
        return source == null || source.equals(event.source());
    }
}
