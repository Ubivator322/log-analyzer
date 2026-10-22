package loganalyzer;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** Тесты без внешних библиотек. Запуск: java -cp build/classes:build/test-classes loganalyzer.Tests */
public final class Tests {

    private static Path dir;
    private static int passed;
    private static final List<String> failures = new ArrayList<>();

    public static void main(String[] args) throws Exception {
        dir = Files.createTempDirectory("loganalyzer-tests");
        parserTests();
        filterTests();
        statsTests();
        cliTests();
        reportTests();
        largeFileTest();

        System.out.println();
        System.out.println("Пройдено проверок: " + passed);
        System.out.println("Провалено проверок: " + failures.size());
        for (String failure : failures) {
            System.out.println("  FAIL: " + failure);
        }
        if (!failures.isEmpty()) {
            System.exit(1);
        }
    }

    // ---------- парсер ----------

    private static void parserTests() {
        Event event = LogParser.parse("2026-09-01T00:08:59Z | WARN | auth-api | AUTH_LOGIN_OK | User session created");
        same("время разобрано", Instant.parse("2026-09-01T00:08:59Z"), event.time());
        same("уровень разобран", "WARN", event.level());
        same("источник разобран", "auth-api", event.source());
        same("код разобран", "AUTH_LOGIN_OK", event.eventCode());
        same("сообщение разобрано", "User session created", event.message());

        Event trimmed = LogParser.parse("  2026-09-01T00:08:59Z  |  INFO  |  gateway  |  HTTP_502  |  Upstream returned  ");
        same("пробелы обрезаны", "INFO", trimmed.level());
        same("источник без пробелов", "gateway", trimmed.source());
        same("сообщение без пробелов", "Upstream returned", trimmed.message());

        Event comma = LogParser.parse("2026-09-01T00:08:59Z | INFO | gateway | HTTP_502 | текст, с запятой и \"кавычками\"");
        same("сообщение с запятой и кавычками", "текст, с запятой и \"кавычками\"", comma.message());

        badLine("нет даты", "не в формате ISO 8601 UTC",
                () -> LogParser.parse("not-a-timestamp | ERROR | orders-api | ORDER_REJECTED | x"));
        badLine("дата без T", "не в формате ISO 8601 UTC",
                () -> LogParser.parse("2026-09-04 09:00:00 | WARN | inventory-api | STOCK_LOW | x"));
        badLine("невозможная дата", "неверный timestamp",
                () -> LogParser.parse("2026-99-99T99:99:99Z | WARN | inventory-api | STOCK_LOW | x"));
        badLine("неизвестный уровень", "неизвестный уровень",
                () -> LogParser.parse("2026-09-02T08:12:00Z | NOTICE | gateway | HTTP_502 | x"));
        badLine("уровень в нижнем регистре", "неизвестный уровень",
                () -> LogParser.parse("2026-09-02T08:12:00Z | info | gateway | HTTP_502 | x"));
        badLine("пустой уровень", "неизвестный уровень",
                () -> LogParser.parse("2026-09-07T14:00:00Z |  | gateway | HTTP_502 | x"));
        badLine("пустой источник", "пустое поле source",
                () -> LogParser.parse("2026-09-01T00:00:00Z | INFO |  | HTTP_502 | x"));
        badLine("пустой код", "пустое поле eventCode",
                () -> LogParser.parse("2026-09-03T09:00:00Z | INFO | auth-api | | missing event code"));
        badLine("пустое сообщение", "пустое поле message",
                () -> LogParser.parse("2026-09-08T14:00:00Z | ERROR | gateway | HTTP_502 | "));
        badLine("четыре поля", "ожидалось 5 полей",
                () -> LogParser.parse("2026-09-05T11:10:00Z | ERROR | payment-worker | PAYMENT_TIMEOUT"));
        badLine("шесть полей", "ожидалось 5 полей", () -> LogParser.parse(
                "2026-09-06T11:10:00Z | ERROR | payment-worker | PAYMENT_TIMEOUT | message has | extra delimiter"));
        badLine("пустая строка", "пустая строка", () -> LogParser.parse("   "));
        badLine("null-строка", "пустая строка", () -> LogParser.parse(null));
    }

    // ---------- фильтр ----------

    private static void filterTests() {
        Event event = LogParser.parse("2026-09-02T00:00:00Z | ERROR | gateway | HTTP_502 | x");

        check("без фильтров проходит", new Filter(null, null, null, null).test(event));
        check("from включительно",
                new Filter(Instant.parse("2026-09-02T00:00:00Z"), null, null, null).test(event));
        check("from отсекает более раннее",
                !new Filter(Instant.parse("2026-09-02T00:00:01Z"), null, null, null).test(event));
        check("to исключительно",
                !new Filter(null, Instant.parse("2026-09-02T00:00:00Z"), null, null).test(event));
        check("to пропускает более раннее",
                new Filter(null, Instant.parse("2026-09-02T00:00:01Z"), null, null).test(event));
        check("уровень совпал", new Filter(null, null, "ERROR", null).test(event));
        check("уровень не совпал", !new Filter(null, null, "INFO", null).test(event));
        check("источник совпал", new Filter(null, null, null, "gateway").test(event));
        check("источник не совпал", !new Filter(null, null, null, "orders-api").test(event));
        check("все фильтры совпали", new Filter(Instant.parse("2026-09-01T00:00:00Z"),
                Instant.parse("2026-09-03T00:00:00Z"), "ERROR", "gateway").test(event));
        check("один фильтр не совпал", !new Filter(Instant.parse("2026-09-01T00:00:00Z"),
                Instant.parse("2026-09-03T00:00:00Z"), "INFO", "gateway").test(event));
    }

    // ---------- агрегация ----------

    private static void statsTests() throws IOException {
        Path file = write("small.log",
                "2026-09-01T00:00:00Z | INFO | a | A | m\n"
                        + "2026-09-01T01:00:00Z | ERROR | b | B | m\n"
                        + "2026-09-01T02:00:00Z | ERROR | a | A | m\n"
                        + "bad line\n"
                        + "\n");
        PrintStream sink = new PrintStream(OutputStream.nullOutputStream());

        Stats all = Stats.analyze(file, new Filter(null, null, null, null), sink);
        same("строк всего", 5L, all.total());
        same("корректных строк", 3L, all.valid());
        same("ошибочных строк", 2L, all.invalid());
        same("событий после фильтра", 3L, all.matched());
        same("сводка по уровням", Map.of("INFO", 1, "ERROR", 2), all.levels());
        same("сводка по источникам", Map.of("a", 2, "b", 1), all.sources());
        same("сводка по кодам", Map.of("A", 2, "B", 1), all.eventCodes());
        same("номера ошибочных строк", List.of(4L, 5L),
                all.invalidLines().stream().map(Stats.InvalidLine::number).toList());
        same("причина первой ошибки", "ожидалось 5 полей, найдено 1", all.invalidLines().get(0).reason());

        Stats byInterval = Stats.analyze(file,
                new Filter(Instant.parse("2026-09-01T01:00:00Z"), Instant.parse("2026-09-01T02:00:00Z"), null, null),
                sink);
        same("фильтр по интервалу", 1L, byInterval.matched());
        same("фильтр уровень+источник", 1L,
                Stats.analyze(file, new Filter(null, null, "ERROR", "b"), sink).matched());
        same("фильтр ничего не нашёл", 0L,
                Stats.analyze(file, new Filter(null, null, null, "нет-такого"), sink).matched());

        List<Map.Entry<String, Integer>> top = Stats.top(Map.of("A", 2, "B", 1, "C", 2), 2);
        same("top-N размер", 2, top.size());
        same("top-N первый по алфавиту при равенстве", "A", top.get(0).getKey());
        same("top-N второй при равенстве", "C", top.get(1).getKey());
        same("top-N больше размера", 1, Stats.top(Map.of("A", 1), 10).size());
        same("top-N ноль", 0, Stats.top(Map.of("A", 1), 0).size());
    }

    // ---------- CLI ----------

    private static void cliTests() throws IOException {
        Path log = write("cli.log",
                "2026-09-01T00:00:00Z | INFO | a | A | m\n"
                        + "2026-09-01T01:00:00Z | ERROR | b | B | m\n"
                        + "2026-09-01T02:00:00Z | ERROR | a | A | m\n"
                        + "bad line\n"
                        + "\n");

        Result help = run("--help");
        same("код help", 0, help.code());
        check("help содержит параметры", help.out().contains("--input") && help.out().contains("Примеры"));
        same("код -h", 0, run("-h").code());

        Result normal = run("--input", log.toString());
        same("код обычного запуска", 0, normal.code());
        check("сводка в stdout", normal.out().contains("Строк всего: 5")
                && normal.out().contains("Событий после фильтра: 3"));
        check("ошибочные строки с номерами в stderr",
                normal.err().contains("Строка 4:") && normal.err().contains("Строка 5:"));
        check("сводка по уровням в stdout", normal.out().contains("ERROR — 2"));

        Result noInput = run();
        same("код без --input", 2, noInput.code());
        check("сообщение без --input", noInput.err().contains("--input"));
        same("код неизвестного параметра", 2, run("--nope").code());
        same("код параметра без значения", 2, run("--input").code());
        check("сообщение параметра без значения", run("--input").err().contains("требует значения"));

        same("код отсутствующего файла", 2, run("--input", dir.resolve("nope.log").toString()).code());
        check("сообщение отсутствующего файла",
                run("--input", dir.resolve("nope.log").toString()).err().contains("файл не найден"));
        same("код каталога вместо файла", 2, run("--input", dir.toString()).code());
        check("сообщение каталога", run("--input", dir.toString()).err().contains("это не файл"));

        same("код неверного уровня", 2, run("--input", log.toString(), "--level", "NOTICE").code());
        check("сообщение неверного уровня",
                run("--input", log.toString(), "--level", "NOTICE").err().contains("недопустимый уровень"));
        same("код top=0", 2, run("--input", log.toString(), "--top", "0").code());
        same("код top не число", 2, run("--input", log.toString(), "--top", "abc").code());
        same("код неверной даты", 2, run("--input", log.toString(), "--from", "вчера").code());
        same("код from позже to",
                2, run("--input", log.toString(), "--from", "2026-09-02", "--to", "2026-09-01").code());
        check("сообщение from позже to", run("--input", log.toString(), "--from", "2026-09-02",
                "--to", "2026-09-01").err().contains("--from должен быть раньше --to"));
        same("код неверного формата", 2, run("--input", log.toString(), "--format", "xml").code());

        Result lowercaseLevel = run("--input", log.toString(), "--level", "error");
        same("уровень в нижнем регистре принят", 0, lowercaseLevel.code());
        check("фильтр по уровню", lowercaseLevel.out().contains("Событий после фильтра: 2"));

        Result interval = run("--input", log.toString(),
                "--from", "2026-09-01T01:00:00Z", "--to", "2026-09-01T02:00:00Z");
        check("границы интервала", interval.out().contains("Событий после фильтра: 1"));

        Result bySource = run("--input", log.toString(), "--source", "a");
        check("фильтр по источнику", bySource.out().contains("Событий после фильтра: 2"));

        Path json = dir.resolve("out.json");
        Result jsonRun = run("--input", log.toString(), "--level", "ERROR", "--top", "1",
                "--output", json.toString());
        same("код записи json", 0, jsonRun.code());
        check("сообщение о сохранении", jsonRun.out().contains("Отчёт сохранён"));
        String jsonText = Files.readString(json);
        check("json: фильтр и топ", jsonText.contains("\"level\": \"ERROR\"")
                && jsonText.contains("\"topEventCodes\": [{\"eventCode\": \"A\", \"count\": 1}]")
                && jsonText.contains("\"invalidLines\": ["));
        check("json: счётчики", jsonText.contains("\"valid\": 3") && jsonText.contains("\"invalid\": 2"));

        Path csv = dir.resolve("out.csv");
        same("код записи csv", 0,
                run("--input", log.toString(), "--format", "csv", "--top", "1", "--output", csv.toString()).code());
        String csvText = Files.readString(csv);
        check("csv: заголовок", csvText.startsWith("section,key,count,reason\n"));
        check("csv: сводка", csvText.contains("summary,total_lines,5,"));
        check("csv: ошибочная строка", csvText.contains("invalid_line,4,1,"));

        Path first = dir.resolve("first.json");
        Path second = dir.resolve("second.json");
        run("--input", log.toString(), "--output", first.toString());
        run("--input", log.toString(), "--output", second.toString());
        same("результат детерминирован", Files.readString(first), Files.readString(second));

        Path empty = write("empty.log", "");
        Result emptyRun = run("--input", empty.toString(), "--output", dir.resolve("empty.json").toString());
        same("код пустого файла", 0, emptyRun.code());
        check("пустой файл: нули и нет данных", emptyRun.out().contains("Строк всего: 0")
                && emptyRun.out().contains("Событий после фильтра: 0") && emptyRun.out().contains("нет данных"));
        check("пустой файл: json без ошибок", Files.readString(dir.resolve("empty.json"))
                .contains("\"invalidLines\": []"));

        Path broken = write("broken.log", "мусор\nещё мусор\n");
        Result brokenRun = run("--input", broken.toString());
        check("только повреждённые строки", brokenRun.out().contains("Корректных строк: 0")
                && brokenRun.out().contains("Ошибочных строк: 2"));

        same("код ошибки записи", 3, run("--input", log.toString(),
                "--output", dir.resolve("нет-каталога/out.json").toString()).code());
        check("сообщение ошибки записи", run("--input", log.toString(),
                "--output", dir.resolve("нет-каталога/out.json").toString()).err().contains("Ошибка ввода-вывода"));
    }

    // ---------- отчёты ----------

    private static void reportTests() throws IOException {
        PrintStream sink = new PrintStream(OutputStream.nullOutputStream());
        Path empty = write("report-empty.log", "");
        Stats stats = Stats.analyze(empty, new Filter(null, null, null, null), sink);

        String escaped = Report.toJson("in\"put\n", new Filter(null, null, "a\"b\\c\n\t\u0001", "src"),
                stats, 3);
        check("json: экранирование",
                escaped.contains("\\\"") && escaped.contains("\\\\") && escaped.contains("\\n")
                        && escaped.contains("\\t") && escaped.contains("\\u0001"));
        check("json: пустые сводки", escaped.contains("\"byLevel\": {}")
                && escaped.contains("\"bySource\": {}") && escaped.contains("\"topEventCodes\": []"));
        check("json: null-фильтры", escaped.contains("\"from\": null, \"to\": null"));

        Path withComma = write("comma.log", "2026-09-01T00:00:00Z | NO,TICE | a | A | m\n");
        Stats commaStats = Stats.analyze(withComma, new Filter(null, null, null, null), sink);
        String csv = Report.toCsv(commaStats, 5);
        check("csv: причина с запятой экранирована", csv.contains("\"неизвестный уровень 'NO,TICE'\""));

        String withFilter = Report.toJson("input.log",
                new Filter(Instant.parse("2026-09-01T00:00:00Z"), null, "INFO", "a"), stats, 1);
        check("json: значения фильтра", withFilter.contains("\"from\": \"2026-09-01T00:00:00Z\""));

        String csvEmpty = Report.toCsv(stats, 5);
        check("csv: пустой отчёт", csvEmpty.contains("summary,valid_lines,0,")
                && csvEmpty.contains("summary,matched_events,0,"));
    }

    // ---------- большой файл ----------

    private static void largeFileTest() throws IOException {
        StringBuilder text = new StringBuilder();
        for (int i = 0; i < 20000; i++) {
            text.append("2026-09-01T00:00:00Z | ERROR | gateway | HTTP_502 | m\n");
        }
        Path big = write("big.log", text.toString());
        PrintStream sink = new PrintStream(OutputStream.nullOutputStream());
        Stats stats = Stats.analyze(big, new Filter(null, null, null, null), sink);
        same("большой файл: строк", 20000L, stats.total());
        same("большой файл: корректных", 20000L, stats.valid());
        same("большой файл: после фильтра", 20000L, stats.matched());
        same("большой файл: счётчик кода", 20000, stats.eventCodes().get("HTTP_502"));
    }

    // ---------- вспомогательные методы ----------

    private static Path write(String name, String content) throws IOException {
        Path file = dir.resolve(name);
        Files.writeString(file, content, StandardCharsets.UTF_8);
        return file;
    }

    private record Result(int code, String out, String err) {
    }

    private static Result run(String... args) {
        ByteArrayOutputStream outBytes = new ByteArrayOutputStream();
        ByteArrayOutputStream errBytes = new ByteArrayOutputStream();
        PrintStream out = new PrintStream(outBytes, true, StandardCharsets.UTF_8);
        PrintStream err = new PrintStream(errBytes, true, StandardCharsets.UTF_8);
        int code = Main.run(args, out, err);
        return new Result(code, outBytes.toString(StandardCharsets.UTF_8), errBytes.toString(StandardCharsets.UTF_8));
    }

    private static void check(String name, boolean condition) {
        if (condition) {
            passed++;
        } else {
            failures.add(name);
        }
    }

    private static void same(String name, Object expected, Object actual) {
        check(name + " (ожидалось: " + expected + ", получено: " + actual + ")", Objects.equals(expected, actual));
    }

    private static void badLine(String name, String fragment, Runnable action) {
        try {
            action.run();
            failures.add(name + ": ожидалась ошибка разбора");
        } catch (IllegalArgumentException e) {
            check(name + ": " + e.getMessage(), e.getMessage().contains(fragment));
        }
    }
}
