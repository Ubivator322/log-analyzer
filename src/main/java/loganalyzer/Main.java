package loganalyzer;

import java.io.IOException;
import java.io.PrintStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.format.DateTimeParseException;
import java.util.Locale;

public final class Main {

    private static final String USAGE = """
            Анализатор журналов событий (формат: timestamp | level | source | eventCode | message).

            Использование:
              java -cp build/classes loganalyzer.Main --input <файл> [параметры]

            Параметры:
              --input <файл>     входной журнал, обязательный
              --from <время>     начало интервала включительно (ISO 8601 UTC или ГГГГ-ММ-ДД)
              --to <время>       конец интервала исключительно (тот же формат)
              --level <уровень>  TRACE, DEBUG, INFO, WARN или ERROR
              --source <имя>     источник, например gateway
              --top <N>          сколько кодов событий показать (по умолчанию 5)
              --format <вид>     json или csv (по умолчанию json)
              --output <файл>    куда сохранить отчёт; без параметра файл не пишется
              --help             эта справка

            Примеры:
              java -cp build/classes loganalyzer.Main --input logs_events_sample.log --level ERROR --top 3
              java -cp build/classes loganalyzer.Main --input logs_events_sample.log --from 2026-09-02 --to 2026-09-03 --source gateway --output out.json
              java -cp build/classes loganalyzer.Main --input logs_events_sample.log --format csv --output out.csv
            """;

    private Main() {
    }

    public static void main(String[] args) {
        System.exit(run(args, System.out, System.err));
    }

    /** Возвращает код выхода: 0 — успех, 2 — неверные аргументы или файл, 3 — ошибка ввода-вывода. */
    public static int run(String[] args, PrintStream out, PrintStream err) {
        Options options;
        try {
            options = parse(args);
        } catch (IllegalArgumentException e) {
            err.println("Ошибка: " + e.getMessage());
            err.println("Подсказка: запустите с --help");
            return 2;
        }
        if (options.help()) {
            out.print(USAGE);
            return 0;
        }

        Path file = Path.of(options.input());
        if (!Files.exists(file)) {
            err.println("Ошибка: файл не найден: " + file);
            return 2;
        }
        if (!Files.isRegularFile(file)) {
            err.println("Ошибка: это не файл: " + file);
            return 2;
        }

        Filter filter = new Filter(options.from(), options.to(), options.level(), options.source());
        try {
            Stats stats = Stats.analyze(file, filter, err);
            Report.printSummary(out, options.input(), filter, stats, options.top());
            if (options.output() != null) {
                Report.write(options.output(), options.format(), options.input(), filter, stats, options.top());
                out.println("Отчёт сохранён: " + options.output());
            }
            return 0;
        } catch (IOException e) {
            err.println("Ошибка ввода-вывода: " + e.getMessage());
            return 3;
        }
    }

    /** Разобранные аргументы командной строки. */
    record Options(String input, Instant from, Instant to, String level, String source,
                   int top, String format, String output, boolean help) {
    }

    static Options parse(String[] args) {
        String input = null;
        Instant from = null;
        Instant to = null;
        String level = null;
        String source = null;
        int top = 5;
        String format = "json";
        String output = null;
        boolean help = false;

        for (int i = 0; i < args.length; i++) {
            String arg = args[i];
            switch (arg) {
                case "--help", "-h" -> help = true;
                case "--input" -> input = next(args, ++i, arg);
                case "--from" -> from = parseTime(arg, next(args, ++i, arg));
                case "--to" -> to = parseTime(arg, next(args, ++i, arg));
                case "--level" -> level = next(args, ++i, arg).toUpperCase(Locale.ROOT);
                case "--source" -> source = next(args, ++i, arg);
                case "--top" -> top = parseTop(next(args, ++i, arg));
                case "--format" -> format = next(args, ++i, arg).toLowerCase(Locale.ROOT);
                case "--output" -> output = next(args, ++i, arg);
                default -> throw new IllegalArgumentException("неизвестный параметр: " + arg);
            }
        }
        if (help) {
            return new Options(input, from, to, level, source, top, format, output, true);
        }
        if (input == null || input.isBlank()) {
            throw new IllegalArgumentException("не указан обязательный параметр --input");
        }
        if (level != null && !LogParser.LEVELS.contains(level)) {
            throw new IllegalArgumentException("недопустимый уровень '" + level + "', допустимые: " + LogParser.LEVELS);
        }
        if (!format.equals("json") && !format.equals("csv")) {
            throw new IllegalArgumentException("недопустимый формат '" + format + "', допустимые: json, csv");
        }
        if (from != null && to != null && !from.isBefore(to)) {
            throw new IllegalArgumentException("--from должен быть раньше --to");
        }
        return new Options(input, from, to, level, source, top, format, output, false);
    }

    private static String next(String[] args, int index, String name) {
        if (index >= args.length) {
            throw new IllegalArgumentException("параметр " + name + " требует значения");
        }
        return args[index];
    }

    private static int parseTop(String value) {
        try {
            int parsed = Integer.parseInt(value);
            if (parsed < 1) {
                throw new NumberFormatException();
            }
            return parsed;
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("--top должен быть целым числом больше нуля: '" + value + "'");
        }
    }

    private static Instant parseTime(String name, String value) {
        try {
            if (value.length() == 10) {
                return LocalDate.parse(value).atStartOfDay(ZoneOffset.UTC).toInstant();
            }
            return Instant.parse(value);
        } catch (DateTimeParseException e) {
            throw new IllegalArgumentException("неверное значение " + name + ": '" + value
                    + "', ожидается ISO 8601 UTC или ГГГГ-ММ-ДД");
        }
    }
}
