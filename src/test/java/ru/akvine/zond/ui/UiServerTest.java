package ru.akvine.zond.ui;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import ru.akvine.zond.cli.ScanExecutor;
import ru.akvine.zond.cli.SessionSettings;
import ru.akvine.zond.config.RuleSettings;
import ru.akvine.zond.enums.Confidence;
import ru.akvine.zond.enums.ErrorLevel;
import ru.akvine.zond.loaders.FileSystemConfigLoader;
import ru.akvine.zond.loaders.FileSystemSourceLoader;
import ru.akvine.zond.loaders.FileSystemTextFileLoader;
import ru.akvine.zond.loaders.GitChangedFiles;
import ru.akvine.zond.loaders.HashChangedFiles;
import ru.akvine.zond.loaders.MavenClasspathResolver;
import ru.akvine.zond.printers.PrinterFactory;
import ru.akvine.zond.printers.ReportFormatter;
import ru.akvine.zond.rules.Rule;
import ru.akvine.zond.rules.logical.TransactionOnPrivateMethodRule;
import ru.akvine.zond.services.RuleCatalog;
import ru.akvine.zond.services.Scanner;

import java.io.IOException;
import java.net.Socket;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class UiServerTest {
    private static final String KEY_HEADER = "X-Zond-Token";
    private static final int OK = 200;
    private static final int BAD_REQUEST = 400;
    private static final int FORBIDDEN = 403;
    private static final int NOT_FOUND = 404;
    private static final int SOCKET_TIMEOUT_MS = 5_000;
    private static final long SCAN_TIMEOUT_MS = 60_000;
    private static final long POLL_MS = 50;

    @TempDir
    Path dir;

    @TempDir
    Path configDir;

    private final HttpClient client = HttpClient.newHttpClient();
    private final Rule rule = new TransactionOnPrivateMethodRule();

    private SessionSettings session;
    private UiServer server;
    private int port;

    @BeforeEach
    void setUp() throws IOException {
        Files.writeString(dir.resolve("Bad.java"), """
                class Bad {
                    @Transactional
                    private void save() {}
                }
                """);
        List<Rule> rules = List.of(rule);
        RuleSettings ruleSettings = RuleSettings.empty();
        UiScanProgress progress = new UiScanProgress();
        ReportFormatter formatter = new ReportFormatter();
        ScanExecutor executor = new ScanExecutor(
                new Scanner(
                        new FileSystemSourceLoader(),
                        new FileSystemConfigLoader(),
                        new FileSystemTextFileLoader(),
                        rules,
                        progress,
                        ruleSettings),
                new PrinterFactory(formatter),
                new MavenClasspathResolver(configDir.resolve("repository")),
                new GitChangedFiles(),
                new HashChangedFiles(configDir.resolve("snapshots")));
        server = new UiServer(executor, new RuleCatalog(rules, ruleSettings), formatter, progress);
        session = SessionSettings.of(null, "", "", false);
        port = server.start(session, dir, 0);
    }

    @AfterEach
    void tearDown() {
        server.stop();
    }

    @Test
    void pageOpensOnlyWithSessionKey() throws Exception {
        HttpResponse<String> page = getWithoutKey("/?token=" + server.token());

        assertThat(page.statusCode()).isEqualTo(OK);
        assertThat(page.body()).contains("<title>Zond</title>", server.token());
        assertThat(page.headers().firstValue("X-Frame-Options").orElse("")).isEqualTo("SAMEORIGIN");
        assertThat(getWithoutKey("/").statusCode()).isEqualTo(FORBIDDEN);
        assertThat(getWithoutKey("/?token=" + "0".repeat(server.token().length())).statusCode()).isEqualTo(FORBIDDEN);
        assertThat(getWithoutKey("/api/state").statusCode()).isEqualTo(FORBIDDEN);
        assertThat(getWithoutKey("/api/dirs?path=").statusCode()).isEqualTo(FORBIDDEN);
    }

    // Чужое имя узла при верном ключе - признак подмены адреса: по такому запросу страница не отдается
    @Test
    void requestWithForeignHostIsRefused() throws IOException {
        String request = "GET /?token=" + server.token() + " HTTP/1.1\r\n"
                + "Host: zond.example\r\nConnection: close\r\n\r\n";

        try (Socket socket = new Socket("127.0.0.1", port)) {
            socket.setSoTimeout(SOCKET_TIMEOUT_MS);
            socket.getOutputStream().write(request.getBytes(StandardCharsets.US_ASCII));
            socket.getOutputStream().flush();
            String reply = new String(socket.getInputStream().readAllBytes(), StandardCharsets.UTF_8);

            assertThat(reply).startsWith("HTTP/1.1 403");
            assertThat(reply).doesNotContain("<title>Zond</title>");
        }
    }

    @Test
    void stateCarriesPathAndSettingsOfTheSession() throws Exception {
        session.setMinLevel(ErrorLevel.MAJOR);
        session.setThreads(4);
        session.getDisabledRules().add("jr:40");

        String state = get("/api/state").body();

        assertThat(state).contains(
                "\"path\":" + Json.text(dir.toAbsolutePath().normalize().toString()),
                "\"minLevel\":\"MAJOR\"",
                "\"minConfidence\":\"SUSPICION\"",
                "\"threads\":4",
                "\"disabledRules\":\"jr:40\"",
                "\"title\":\"Подтверждено\"");
    }

    @Test
    void foldersAreListedForBrowsing() throws Exception {
        Files.createDirectory(dir.resolve("service"));
        Files.createDirectory(dir.resolve(".git"));

        String listed = get("/api/dirs?path=" + encode(dir.toString())).body();
        // Путь, которого еще нет, открывается с ближайшей существующей папки
        String nearest = get("/api/dirs?path=" + encode(dir.resolve("missing").resolve("deeper").toString())).body();

        assertThat(listed).contains(
                "\"name\":\"service\"",
                "\"parent\":" + Json.text(dir.toAbsolutePath().normalize().getParent().toString()));
        assertThat(listed).doesNotContain(".git", "Bad.java");
        assertThat(nearest).contains("\"name\":\"service\"");
    }

    @Test
    void scanIsShownAsReportAndDownloadedInEveryFormat() throws Exception {
        assertThat(post("/api/scan", Map.of("path", dir.toString())).statusCode()).isEqualTo(OK);
        String status = waitForScan();

        assertThat(status).contains("\"violations\":1", "\"files\":1", "\"error\":null", "\"scan\":1");
        String report = getWithoutKey("/report?token=" + server.token() + "&scan=1").body();
        assertThat(report).contains(rule.code(), "Bad.java");

        HttpResponse<String> markdown = getWithoutKey("/api/export?format=md&token=" + server.token());
        assertThat(markdown.body()).startsWith("# Zond").contains(rule.code());
        assertThat(markdown.headers().firstValue("Content-Disposition").orElse("")).contains("zond-report.md");
        assertThat(get("/api/export?format=html").body()).contains("<html", rule.code());
        assertThat(get("/api/export?format=sarif").body()).contains("\"version\"", rule.code());
        assertThat(get("/api/export?format=txt").body()).contains("Найдено проблем: 1");
        byte[] excel = client.send(request("/api/export?format=xlsx").build(), HttpResponse.BodyHandlers.ofByteArray())
                .body();
        // Книга Excel - это zip-архив
        assertThat(new String(excel, 0, 2, StandardCharsets.ISO_8859_1)).isEqualTo("PK");
        assertThat(get("/api/export?format=exe").statusCode()).isEqualTo(BAD_REQUEST);
    }

    @Test
    void reportIsAbsentBeforeTheFirstScan() throws Exception {
        assertThat(get("/report").statusCode()).isEqualTo(NOT_FOUND);
        assertThat(get("/api/export?format=md").statusCode()).isEqualTo(NOT_FOUND);
        assertThat(get("/api/status").body()).contains("\"running\":false", "\"result\":null");
    }

    @Test
    void formChangesSettingsOfTheSession() throws Exception {
        HttpResponse<String> reply = post("/api/scan", Map.of(
                "path", dir.toString(),
                "minLevel", "MAJOR",
                "minConfidence", "PROBABLE",
                "threads", "2",
                "skipTests", "true",
                "changedOnly", "false",
                "onlyRules", rule.code(),
                "disabledRules", "jr:40, TodoCommentRule",
                "exclude", "generated; **/dto/**",
                "changedSince", " origin/main "));
        waitForScan();

        assertThat(reply.statusCode()).isEqualTo(OK);
        assertThat(session.getMinLevel()).isEqualTo(ErrorLevel.MAJOR);
        assertThat(session.getMinConfidence()).isEqualTo(Confidence.PROBABLE);
        assertThat(session.getThreads()).isEqualTo(2);
        assertThat(session.isSkipTests()).isTrue();
        assertThat(session.getOnlyRules()).isEqualTo(rule.code());
        assertThat(session.getDisabledRules()).containsExactly("jr:40", "TodoCommentRule");
        assertThat(session.getExclusions().patterns()).containsExactly("generated", "**/dto/**");
        assertThat(session.getChangedSince()).isEqualTo("origin/main");
    }

    @Test
    void excludedPathsAreNotScanned() throws Exception {
        Files.createDirectory(dir.resolve("generated"));
        Files.copy(dir.resolve("Bad.java"), dir.resolve("generated").resolve("Bad.java"));

        post("/api/scan", Map.of("path", dir.toString()));
        String everything = waitForScan();
        post("/api/scan", Map.of("path", dir.toString(), "exclude", "generated"));
        String withoutGenerated = waitForScan();

        assertThat(everything).contains("\"violations\":2", "\"scan\":1");
        assertThat(withoutGenerated).contains("\"violations\":1", "\"scan\":2");
        assertThat(get("/api/state").body()).contains("\"exclude\":\"generated\"");
    }

    // Ошибка в любом поле отменяет запуск целиком: настройки не меняются наполовину
    @Test
    void mistakeInTheFormStartsNothingAndChangesNothing() throws Exception {
        HttpResponse<String> unknownRule = post("/api/scan", Map.of(
                "path", dir.toString(), "minLevel", "CRITICAL", "onlyRules", "jr:99999"));
        HttpResponse<String> wrongThreads = post("/api/scan", Map.of(
                "path", dir.toString(), "minLevel", "CRITICAL", "threads", "many"));
        // Имя ветки попадает в командную строку git: ключи вместо имени не принимаются
        HttpResponse<String> wrongBranch = post("/api/scan", Map.of(
                "path", dir.toString(), "minLevel", "CRITICAL", "changedOnly", "true", "changedSince", "--output=x"));
        HttpResponse<String> noPath = post("/api/scan", Map.of("path", " "));
        HttpResponse<String> missingPath = post("/api/scan", Map.of("path", dir.resolve("missing").toString()));

        assertThat(unknownRule.statusCode()).isEqualTo(BAD_REQUEST);
        assertThat(unknownRule.body()).contains("jr:99999");
        assertThat(wrongThreads.statusCode()).isEqualTo(BAD_REQUEST);
        assertThat(wrongBranch.statusCode()).isEqualTo(BAD_REQUEST);
        assertThat(noPath.statusCode()).isEqualTo(BAD_REQUEST);
        assertThat(missingPath.statusCode()).isEqualTo(BAD_REQUEST);
        assertThat(session.getMinLevel()).isEqualTo(ErrorLevel.INFO);
        assertThat(get("/api/status").body()).contains("\"running\":false", "\"result\":null");
        // После отказа сканирование запускается как обычно
        assertThat(post("/api/scan", Map.of("path", dir.toString())).statusCode()).isEqualTo(OK);
        assertThat(waitForScan()).contains("\"violations\":1");
    }

    @Test
    void rulesAreListedWithTheirState() throws Exception {
        session.getDisabledRules().add(rule.code());

        String rules = get("/api/rules").body();

        assertThat(rules).contains(
                "\"code\":\"" + rule.code() + "\"", "\"name\":\"" + rule.name() + "\"", "\"active\":false");
    }

    @Test
    void stopRequestReleasesTheWaitingApplication() throws Exception {
        CompletableFuture<Void> waiting = CompletableFuture.runAsync(server::awaitStop);

        HttpResponse<String> reply = post("/api/stop", Map.of());
        waiting.get(SOCKET_TIMEOUT_MS, TimeUnit.MILLISECONDS);

        assertThat(reply.body()).contains("\"stopped\":true");
        assertThat(waiting.isDone()).isTrue();
    }

    @Test
    void portIsCheckedBeforeThePageOpens() {
        assertThat(UiServer.parsePort("")).isEqualTo(UiServer.DEFAULT_PORT);
        assertThat(UiServer.parsePort(" 0 ")).isZero();
        assertThat(UiServer.parsePort("9000")).isEqualTo(9000);
        assertThatThrownBy(() -> UiServer.parsePort("70000"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("70000");
        assertThatThrownBy(() -> UiServer.parsePort("http"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    private HttpRequest.Builder request(String path) {
        return HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path))
                .header(KEY_HEADER, server.token());
    }

    private HttpResponse<String> get(String path) throws IOException, InterruptedException {
        return client.send(request(path).build(), HttpResponse.BodyHandlers.ofString());
    }

    private HttpResponse<String> getWithoutKey(String path) throws IOException, InterruptedException {
        return client.send(
                HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path)).build(),
                HttpResponse.BodyHandlers.ofString());
    }

    private HttpResponse<String> post(String path, Map<String, String> form)
            throws IOException, InterruptedException {
        String body = form.entrySet().stream()
                .map(field -> encode(field.getKey()) + "=" + encode(field.getValue()))
                .collect(Collectors.joining("&"));
        HttpRequest request = request(path)
                .header("Content-Type", "application/x-www-form-urlencoded")
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build();
        return client.send(request, HttpResponse.BodyHandlers.ofString());
    }

    // Сканирование идет в своем потоке: страница узнает о его конце, опрашивая состояние, и тест делает так же
    private String waitForScan() throws IOException, InterruptedException {
        long deadline = System.currentTimeMillis() + SCAN_TIMEOUT_MS;
        while (System.currentTimeMillis() < deadline) {
            String status = get("/api/status").body();
            if (status.contains("\"running\":false")) {
                return status;
            }
            Thread.sleep(POLL_MS);
        }
        throw new AssertionError("Сканирование не закончилось за " + SCAN_TIMEOUT_MS + " мс");
    }

    private String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }
}
