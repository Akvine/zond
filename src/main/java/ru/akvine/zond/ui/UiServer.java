package ru.akvine.zond.ui;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import ru.akvine.zond.cli.ScanExecutor;
import ru.akvine.zond.cli.SessionSettings;
import ru.akvine.zond.config.ZondVersion;
import ru.akvine.zond.enums.Confidence;
import ru.akvine.zond.enums.ErrorLevel;
import ru.akvine.zond.enums.ErrorType;
import ru.akvine.zond.loaders.GitChangedFiles;
import ru.akvine.zond.models.PathExclusions;
import ru.akvine.zond.models.RuleInfo;
import ru.akvine.zond.models.ScanOptions;
import ru.akvine.zond.models.ScanResult;
import ru.akvine.zond.printers.HtmlPrinter;
import ru.akvine.zond.printers.MarkdownPrinter;
import ru.akvine.zond.printers.ReportFormatter;
import ru.akvine.zond.printers.SarifPrinter;
import ru.akvine.zond.printers.XlsxPrinter;
import ru.akvine.zond.services.Failures;
import ru.akvine.zond.services.RuleCatalog;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.BindException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Stream;

/**
 * Страница Zond в браузере вместо консольного меню: маленький веб-сервер на этом компьютере.
 * Сервер слушает только 127.0.0.1, а каждый запрос должен нести ключ сеанса. Ключ есть только в адресе,
 * который Zond печатает в консоли и открывает в браузере: ни посторонний сайт в том же браузере, ни другой
 * пользователь этого компьютера не смогут запустить сканирование, прочитать отчет или список папок
 */
@Component
@RequiredArgsConstructor
public class UiServer {
    public static final int DEFAULT_PORT = 8765;

    private static final int MAX_PORT = 65535;
    private static final String LOCAL_ADDRESS = "127.0.0.1";
    private static final String TOKEN_HEADER = "X-Zond-Token";
    private static final String TOKEN_PARAMETER = "token";
    private static final int TOKEN_BYTES = 24;
    private static final int HANDLER_THREADS = 4;
    private static final int MAX_FORM_BYTES = 64 * 1024;
    private static final int MAX_LISTED_DIRECTORIES = 500;
    private static final String RULES_SEPARATOR = "[,;\\s]+";
    private static final String RULES_DELIMITER = ", ";
    private static final String REPORT_NAME = "zond-report";
    private static final String HOME_PAGE = "/";
    // Отчет для страницы уходит в ответ сервера, файла у него нет
    private static final Path NO_FILE = null;
    private static final String INTERRUPTED = "Сканирование прервалось: подробности в консоли";
    private static final String HTML = "text/html; charset=utf-8";
    private static final String JSON = "application/json; charset=utf-8";
    private static final String TRUE = "true";
    private static final int OK = 200;
    private static final int BAD_REQUEST = 400;
    private static final int FORBIDDEN = 403;
    private static final int NOT_FOUND = 404;
    private static final int CONFLICT = 409;
    private static final int SERVER_ERROR = 500;

    // Форматы, в которых страница отдает отчет на скачивание
    private static final Map<String, String> EXPORT_TYPES = Map.of(
            "html", HTML,
            "md", "text/markdown; charset=utf-8",
            "txt", "text/plain; charset=utf-8",
            "sarif", "application/json; charset=utf-8",
            "xlsx", "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet");

    private final ScanExecutor scanExecutor;
    private final RuleCatalog ruleCatalog;
    private final ReportFormatter formatter;
    private final UiScanProgress progress;

    private final AtomicBoolean running = new AtomicBoolean();
    private final AtomicInteger scanNumber = new AtomicInteger();

    private volatile HttpServer server;
    private volatile ExecutorService handlers;
    private volatile ExecutorService scans;
    private volatile CountDownLatch stopSignal = new CountDownLatch(1);
    private volatile String token = "";
    private volatile SessionSettings session;
    private volatile Path path;
    private volatile ScanResult result;
    private volatile String error;

    /**
     * @param value порт как его ввели; пустая строка - порт по умолчанию
     * @return порт: 0 - любой свободный
     * @throws IllegalArgumentException если это не целое число от 0 до 65535
     */
    public static int parsePort(String value) {
        if (value == null || value.isBlank()) {
            return DEFAULT_PORT;
        }
        try {
            int port = Integer.parseInt(value.trim());
            if (port >= 0 && port <= MAX_PORT) {
                return port;
            }
        } catch (NumberFormatException exception) {
            // Сообщение ниже общее для "не число" и "число вне диапазона"
        }
        throw new IllegalArgumentException("Порт страницы должен быть целым числом от 0 до " + MAX_PORT
                + " (0 - любой свободный), а задано '" + value.trim() + "'");
    }

    /**
     * Открывает страницу и ждет, пока на ней не нажмут «Завершить» или приложение не остановят из консоли.
     *
     * @param initialPath что подставить в поле «Что проверять»; null - папка, из которой запущено приложение
     * @return код завершения приложения
     */
    public int serve(SessionSettings settings, Path initialPath, int port) {
        int actualPort;
        try {
            actualPort = start(settings, initialPath, port);
        } catch (BindException exception) {
            System.err.println("Ошибка: порт " + port + " занят. Задайте другой: --ui-port=<порт>, 0 - любой свободный");
            return ScanExecutor.EXIT_ERROR;
        } catch (IOException exception) {
            System.err.println("Ошибка: не удалось открыть страницу Zond: " + exception.getMessage());
            return ScanExecutor.EXIT_ERROR;
        }
        // Ключ сеанса - часть адреса: без него страница не откроется
        String url = "http://" + LOCAL_ADDRESS + ":" + actualPort + HOME_PAGE + "?" + TOKEN_PARAMETER + "=" + token;
        System.out.println("Страница Zond: " + url);
        System.out.println("Чтобы завершить работу, нажмите «Завершить» на странице или Ctrl+C здесь");
        openBrowser(url);
        awaitStop();
        stop();
        return ScanExecutor.EXIT_OK;
    }

    /**
     * Запускает сервер и сразу возвращает управление.
     *
     * @return порт, на котором сервер слушает
     */
    public int start(SessionSettings settings, Path initialPath, int port) throws IOException {
        // Порт занимается первым: если он занят, больше ничего создавать не нужно
        HttpServer created = HttpServer.create(new InetSocketAddress(InetAddress.getByName(LOCAL_ADDRESS), port), 0);
        session = settings;
        path = (initialPath == null ? Path.of("") : initialPath).toAbsolutePath().normalize();
        token = newToken();
        stopSignal = new CountDownLatch(1);
        scans = Executors.newSingleThreadExecutor(daemon("zond-ui-scan"));
        handlers = Executors.newFixedThreadPool(HANDLER_THREADS, daemon("zond-ui-http"));
        created.createContext(HOME_PAGE, this::handle);
        created.setExecutor(handlers);
        created.start();
        server = created;
        return created.getAddress().getPort();
    }

    /**
     * Ждет, пока на странице не нажмут «Завершить»
     */
    public void awaitStop() {
        try {
            stopSignal.await();
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
        }
    }

    public void stop() {
        HttpServer current = server;
        if (current != null) {
            current.stop(0);
            server = null;
        }
        if (handlers != null) {
            handlers.shutdownNow();
        }
        if (scans != null) {
            scans.shutdownNow();
        }
    }

    /**
     * @return ключ сеанса: он есть в адресе страницы, который печатается в консоли
     */
    public String token() {
        return token;
    }

    private void handle(HttpExchange exchange) throws IOException {
        boolean stopRequested = false;
        try (exchange) {
            try {
                stopRequested = route(exchange);
            } catch (IllegalArgumentException exception) {
                sendError(exchange, BAD_REQUEST, exception.getMessage());
            } catch (RuntimeException exception) {
                sendError(exchange, SERVER_ERROR, describe(exception));
            }
        }
        // Сервер останавливается после того, как ответ ушел странице: иначе она не узнает, что ее услышали
        if (stopRequested) {
            stopSignal.countDown();
        }
    }

    // Возвращает true, если страница попросила завершить работу
    private boolean route(HttpExchange exchange) throws IOException {
        // Запрос с чужим именем узла - признак подмены адреса (DNS rebinding): такой запрос не обслуживается
        if (!isLocal(exchange)) {
            sendError(exchange, FORBIDDEN, "Страница Zond доступна только по адресу этого компьютера");
            return false;
        }
        Map<String, String> query = parse(exchange.getRequestURI().getRawQuery());
        if (!hasToken(exchange, query)) {
            sendError(exchange, FORBIDDEN, "Нет ключа сеанса: откройте адрес страницы, который Zond напечатал"
                    + " в консоли");
            return false;
        }
        String address = exchange.getRequestMethod() + " " + exchange.getRequestURI().getPath();
        switch (address) {
            case "GET /" -> send(exchange, OK, HTML, UiPage.render(token).getBytes(StandardCharsets.UTF_8));
            case "GET /api/state" -> sendJson(exchange, OK, state());
            case "GET /api/dirs" -> sendJson(exchange, OK, directories(query.getOrDefault("path", "")));
            case "POST /api/scan" -> startScan(exchange);
            case "GET /api/status" -> sendJson(exchange, OK, status());
            case "GET /api/rules" -> sendJson(exchange, OK, rules());
            case "GET /report" -> sendReport(exchange);
            case "GET /api/export" -> sendExport(exchange, query.getOrDefault("format", ""));
            case "POST /api/stop" -> {
                sendJson(exchange, OK, Json.object("stopped", TRUE));
                return true;
            }
            default -> sendError(exchange, NOT_FOUND, "Нет такого адреса: " + exchange.getRequestURI().getPath());
        }
        return false;
    }

    private boolean isLocal(HttpExchange exchange) {
        String host = exchange.getRequestHeaders().getFirst("Host");
        int port = exchange.getLocalAddress().getPort();
        return (LOCAL_ADDRESS + ":" + port).equals(host) || ("localhost:" + port).equals(host);
    }

    // Ключ приходит заголовком, а там, где заголовок задать нельзя (сама страница, рамка с отчетом, ссылка
    // на скачивание), - в адресе. Сравнение не зависит от того, в каком символе ключи расходятся
    private boolean hasToken(HttpExchange exchange, Map<String, String> query) {
        String given = exchange.getRequestHeaders().getFirst(TOKEN_HEADER);
        if (given == null) {
            given = query.getOrDefault(TOKEN_PARAMETER, "");
        }
        return MessageDigest.isEqual(given.getBytes(StandardCharsets.UTF_8), token.getBytes(StandardCharsets.UTF_8));
    }

    // Настройки сеанса читают и меняют разные потоки сервера (вторая вкладка, повторный запрос): по очереди
    private synchronized String state() {
        List<String> levels = Arrays.stream(ErrorLevel.values())
                .map(level -> Json.object("value", Json.text(level.name()), "title", Json.text(level.name())))
                .toList();
        List<String> confidences = Arrays.stream(Confidence.values())
                .map(confidence -> Json.object(
                        "value", Json.text(confidence.name()), "title", Json.text(capitalize(confidence.getTitle()))))
                .toList();
        return Json.object(
                "version", Json.text(ZondVersion.current()),
                "path", Json.text(path.toString()),
                "levels", Json.array(levels),
                "confidences", Json.array(confidences),
                "settings", Json.object(
                        "minLevel", Json.text(session.getMinLevel().name()),
                        "minConfidence", Json.text(session.getMinConfidence().name()),
                        "threads", String.valueOf(session.getThreads()),
                        "skipTests", String.valueOf(session.isSkipTests()),
                        "changedOnly", String.valueOf(session.isChangedOnly()),
                        "onlyRules", Json.text(session.getOnlyRules()),
                        "disabledRules", Json.text(session.disabledRulesAsText()),
                        "exclude", Json.text(String.join(RULES_DELIMITER, session.getExclusions().patterns())),
                        "changedSince", Json.text(session.getChangedSince())));
    }

    // Вложенные папки для выбора проекта: браузер не отдает странице настоящие пути, поэтому диск читает сервер
    private String directories(String text) {
        // Путь в поле может быть файлом или еще не дописан: обзор открывается с ближайшей существующей папки
        Path directory = toPath(text.isBlank() ? path.toString() : text);
        while (!Files.isDirectory(directory) && directory.getParent() != null) {
            directory = directory.getParent();
        }
        if (!Files.isDirectory(directory)) {
            throw new IllegalArgumentException("Нет такой папки: " + directory);
        }
        List<String> children;
        try (Stream<Path> entries = Files.list(directory)) {
            children = entries
                    .filter(entry -> Files.isDirectory(entry) && !entry.getFileName().toString().startsWith("."))
                    .sorted((left, right) -> left.getFileName().toString()
                            .compareToIgnoreCase(right.getFileName().toString()))
                    .limit(MAX_LISTED_DIRECTORIES)
                    .map(entry -> Json.object(
                            "name", Json.text(entry.getFileName().toString()), "path", Json.text(entry.toString())))
                    .toList();
        } catch (IOException | UncheckedIOException exception) {
            throw new IllegalArgumentException("Папку не удалось прочитать: " + directory, exception);
        }
        // Другие диски показываются только там, где их несколько: в Windows
        List<String> roots = new ArrayList<>();
        FileSystems.getDefault().getRootDirectories().forEach(root -> roots.add(Json.text(root.toString())));
        return Json.object(
                "path", Json.text(directory.toString()),
                "parent", Json.text(directory.getParent() == null ? null : directory.getParent().toString()),
                "roots", Json.array(roots.size() > 1 ? roots : List.of()),
                "dirs", Json.array(children));
    }

    private void startScan(HttpExchange exchange) throws IOException {
        Map<String, String> form = parse(new String(
                exchange.getRequestBody().readNBytes(MAX_FORM_BYTES), StandardCharsets.UTF_8));
        String text = form.getOrDefault("path", "").trim();
        if (text.isEmpty()) {
            throw new IllegalArgumentException("Укажите папку проекта или .java-файл");
        }
        Path target = toPath(text);
        if (!Files.exists(target)) {
            throw new IllegalArgumentException("Нет такой папки или файла: " + target);
        }
        if (!running.compareAndSet(false, true)) {
            sendError(exchange, CONFLICT, "Сканирование уже идёт");
            return;
        }
        try {
            apply(form);
        } catch (IllegalArgumentException exception) {
            running.set(false);
            throw exception;
        }
        path = target;
        error = null;
        progress.reset();
        scans.execute(() -> scan(target));
        sendJson(exchange, OK, Json.object("started", TRUE));
    }

    private void scan(Path target) {
        // Останется, если проверку оборвало не исключение, а ошибка самой JVM вроде нехватки памяти
        String failure = INTERRUPTED;
        try {
            result = scanExecutor.run(target, session, false);
            scanNumber.incrementAndGet();
            failure = null;
        } catch (RuntimeException exception) {
            failure = describe(exception);
        } finally {
            error = failure;
            running.set(false);
        }
    }

    // Поля формы ложатся на те же настройки сеанса, что меняет консольное меню
    private synchronized void apply(Map<String, String> form) {
        String onlyRules = form.getOrDefault("onlyRules", "").trim();
        List<String> unknown = Arrays.stream(onlyRules.split(RULES_SEPARATOR))
                .filter(rule -> !rule.isBlank() && ruleCatalog.findCode(rule).isEmpty())
                .toList();
        if (!unknown.isEmpty()) {
            throw new IllegalArgumentException("В поле «Только эти правила» названы правила, которых нет: "
                    + String.join(", ", unknown));
        }
        ErrorLevel minLevel = ScanOptions.parse("", form.getOrDefault("minLevel", "")).minLevel();
        Confidence minConfidence = Confidence.parse(form.getOrDefault("minConfidence", ""));
        int threads = SessionSettings.parseThreads(form.getOrDefault("threads", ""));
        // Имя ветки уходит в командную строку git, поэтому проверяется до запуска
        String changedSince = GitChangedFiles.validate(form.getOrDefault("changedSince", ""));

        session.setMinLevel(minLevel);
        session.setMinConfidence(minConfidence);
        session.setThreads(threads);
        session.setSkipTests(TRUE.equals(form.get("skipTests")));
        session.setChangedOnly(TRUE.equals(form.get("changedOnly")));
        session.setChangedSince(changedSince);
        session.setExclusions(PathExclusions.parse(form.getOrDefault("exclude", "")));
        session.setOnlyRules(onlyRules);
        session.getDisabledRules().clear();
        Arrays.stream(form.getOrDefault("disabledRules", "").split(RULES_SEPARATOR))
                .filter(rule -> !rule.isBlank())
                .forEach(session.getDisabledRules()::add);
    }

    private String status() {
        UiScanProgress.Snapshot now = progress.snapshot();
        ScanResult last = result;
        return Json.object(
                "running", String.valueOf(running.get()),
                "finished", String.valueOf(now.finished()),
                "total", String.valueOf(now.total()),
                "rule", Json.text(now.rule()),
                "error", Json.text(error),
                "scan", String.valueOf(scanNumber.get()),
                "result", last == null ? "null" : Json.object(
                        "violations", String.valueOf(last.violations().size()),
                        "files", String.valueOf(last.filesCount()),
                        "rules", String.valueOf(last.rulesCount()),
                        "time", Json.text(session.getTimeUnit().format(last.checkNanos())),
                        "path", Json.text(last.root().toAbsolutePath().normalize().toString())));
    }

    private synchronized String rules() {
        List<String> rules = new ArrayList<>();
        for (RuleInfo rule : ruleCatalog.describe(session.toScanOptions())) {
            ErrorType type = rule.type();
            rules.add(Json.object(
                    "code", Json.text(rule.code()),
                    "name", Json.text(rule.name()),
                    "level", Json.text(rule.level().name()),
                    "type", Json.text(type.getDescription() == null ? type.name() : type.getDescription()),
                    "description", Json.text(rule.description()),
                    "active", String.valueOf(rule.active())));
        }
        return Json.array(rules);
    }

    private void sendReport(HttpExchange exchange) throws IOException {
        ScanResult last = result;
        if (last == null) {
            sendError(exchange, NOT_FOUND, "Сканирование ещё не запускалось");
            return;
        }
        send(exchange, OK, HTML, renderHtml(last).getBytes(StandardCharsets.UTF_8));
    }

    private void sendExport(HttpExchange exchange, String format) throws IOException {
        ScanResult last = result;
        String type = EXPORT_TYPES.get(format);
        if (type == null) {
            throw new IllegalArgumentException("Неизвестный формат отчёта: " + format);
        }
        if (last == null) {
            sendError(exchange, NOT_FOUND, "Сканирование ещё не запускалось");
            return;
        }
        byte[] content = export(last, format);
        exchange.getResponseHeaders().set(
                "Content-Disposition", "attachment; filename=\"" + REPORT_NAME + "." + format + "\"");
        send(exchange, OK, type, content);
    }

    private String renderHtml(ScanResult scanResult) {
        return new HtmlPrinter(NO_FILE, formatter.showsConfidence()).render(scanResult);
    }

    // Те же отчеты, что пишутся в файл, но сразу в ответ: файла на диске у выгрузки со страницы нет
    private byte[] export(ScanResult scanResult, String format) throws IOException {
        boolean confidence = formatter.showsConfidence();
        if ("xlsx".equals(format)) {
            ByteArrayOutputStream workbook = new ByteArrayOutputStream();
            new XlsxPrinter(NO_FILE, confidence).write(scanResult, workbook);
            return workbook.toByteArray();
        }
        String text = switch (format) {
            case "html" -> renderHtml(scanResult);
            case "md" -> new MarkdownPrinter(NO_FILE, confidence).render(scanResult);
            case "sarif" -> new SarifPrinter(NO_FILE, confidence).render(scanResult);
            default -> formatter.format(scanResult);
        };
        return text.getBytes(StandardCharsets.UTF_8);
    }

    private Path toPath(String text) {
        try {
            return Path.of(text.trim()).toAbsolutePath().normalize();
        } catch (InvalidPathException exception) {
            throw new IllegalArgumentException("Неверный путь: " + text.trim(), exception);
        }
    }

    // a=1&b=2 из адреса или из тела формы
    private Map<String, String> parse(String encoded) {
        Map<String, String> values = new HashMap<>();
        if (encoded == null || encoded.isEmpty()) {
            return values;
        }
        for (String pair : encoded.split("&")) {
            int equals = pair.indexOf('=');
            String name = equals < 0 ? pair : pair.substring(0, equals);
            String value = equals < 0 ? "" : pair.substring(equals + 1);
            values.put(URLDecoder.decode(name, StandardCharsets.UTF_8), URLDecoder.decode(value, StandardCharsets.UTF_8));
        }
        return values;
    }

    private String describe(RuntimeException exception) {
        String message = exception.getMessage();
        return message == null || message.isBlank() ? Failures.describe(exception) : message;
    }

    private String capitalize(String text) {
        return text.isEmpty() ? text : text.substring(0, 1).toUpperCase(Locale.ROOT) + text.substring(1);
    }

    private void sendJson(HttpExchange exchange, int status, String json) throws IOException {
        send(exchange, status, JSON, json.getBytes(StandardCharsets.UTF_8));
    }

    private void sendError(HttpExchange exchange, int status, String message) throws IOException {
        sendJson(exchange, status, Json.object("error", Json.text(message)));
    }

    private void send(HttpExchange exchange, int status, String contentType, byte[] body) throws IOException {
        exchange.getResponseHeaders().set("Content-Type", contentType);
        exchange.getResponseHeaders().set("Cache-Control", "no-store");
        exchange.getResponseHeaders().set("X-Content-Type-Options", "nosniff");
        // Страницу нельзя встроить в чужую, а ключ из адреса не уходит на другие сайты
        exchange.getResponseHeaders().set("X-Frame-Options", "SAMEORIGIN");
        exchange.getResponseHeaders().set("Referrer-Policy", "no-referrer");
        exchange.sendResponseHeaders(status, body.length);
        exchange.getResponseBody().write(body);
    }

    private String newToken() {
        byte[] bytes = new byte[TOKEN_BYTES];
        new SecureRandom().nextBytes(bytes);
        return HexFormat.of().formatHex(bytes);
    }

    private ThreadFactory daemon(String name) {
        return task -> {
            Thread thread = new Thread(task, name);
            thread.setDaemon(true);
            return thread;
        };
    }

    // Адрес печатается в консоли в любом случае: если браузер не открылся сам, его открывают вручную
    private void openBrowser(String url) {
        String system = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
        List<String> command;
        if (system.contains("win")) {
            command = List.of("rundll32", "url.dll,FileProtocolHandler", url);
        } else if (system.contains("mac")) {
            command = List.of("open", url);
        } else {
            command = List.of("xdg-open", url);
        }
        try {
            new ProcessBuilder(command).redirectErrorStream(true)
                    .redirectOutput(ProcessBuilder.Redirect.DISCARD)
                    .start();
        } catch (IOException exception) {
            System.out.println("Браузер не открылся сам: откройте адрес вручную");
        }
    }
}
