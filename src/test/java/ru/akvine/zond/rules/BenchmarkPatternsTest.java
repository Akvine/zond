package ru.akvine.zond.rules;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import ru.akvine.zond.loaders.FileSystemSourceLoader;
import ru.akvine.zond.models.SourceFile;
import ru.akvine.zond.models.Violation;
import ru.akvine.zond.rules.security.CommandInjectionRule;
import ru.akvine.zond.rules.security.InsecureRandomRule;
import ru.akvine.zond.rules.security.ResponseWriteWithoutEscapingRule;
import ru.akvine.zond.rules.security.SqlConcatenationRule;
import ru.akvine.zond.rules.security.TrustBoundaryViolationRule;
import ru.akvine.zond.rules.security.WeakCipherRule;
import ru.akvine.zond.rules.security.WeakHashRule;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Шаблоны, на которых анализ пропускал уязвимости OWASP Benchmark: одноименные внутренние классы, проверка
 * свойства вместо значения, вызов процедуры, format(...) в ответ, экранирование перед сессией, алгоритм из файла
 * настроек, nextBytes(...), команда из значений, которые до нее не доходят
 */
class BenchmarkPatternsTest {
    @TempDir
    Path dir;

    @Test
    void sameNamedInnerClassesAreResolvedWithinTheirFile() {
        String passing = """
                class PassingController {
                    @GetMapping("/a")
                    void handle(@RequestParam String param) {
                        String bar = new Test().doSomething(param);
                        jdbc.query("select * from users where name = '" + bar + "'", mapper);
                    }
                    private class Test {
                        String doSomething(String param) { return param; }
                    }
                }
                """;
        String constant = """
                class ConstantController {
                    @GetMapping("/b")
                    void handle(@RequestParam String param) {
                        String bar = new Test().doSomething(param);
                        jdbc.query("select * from users where name = '" + bar + "'", mapper);
                    }
                    private class Test {
                        String doSomething(String param) { return "safe"; }
                    }
                }
                """;

        // Класс Test есть в обоих файлах: метод берется из своего файла, как у компилятора
        assertThat(RuleTests.checkProject(new SqlConcatenationRule(),
                Map.of("PassingController.java", passing, "ConstantController.java", constant)))
                .extracting(violation -> violation.file().getFileName() + ":" + violation.line())
                .containsExactly("PassingController.java:5");
    }

    @Test
    void checkOfCookieNameDoesNotCheckItsValue() {
        assertThat(RuleTests.lines(new SqlConcatenationRule(), """
                class CookieController {
                    void handle(HttpServletRequest request) {
                        String param = "";
                        for (Cookie cookie : request.getCookies()) {
                            if (cookie.getName().equals("id")) {
                                param = cookie.getValue();
                            }
                        }
                        jdbc.query("select * from users where id = '" + param + "'", mapper);
                    }
                }
                """)).containsExactly(9);
    }

    @Test
    void storedProcedureCallIsSql() {
        assertThat(RuleTests.lines(new SqlConcatenationRule(), """
                class Procedures {
                    void call(HttpServletRequest request) throws SQLException {
                        String name = request.getParameter("name");
                        connection.prepareCall("{call " + name + "}").execute();
                        connection.prepareCall("{? = call total(" + name + ")}").execute();
                    }
                }
                """)).containsExactly(4, 5);
    }

    @Test
    void formattedOutputGoesToResponse() {
        assertThat(RuleTests.lines(new ResponseWriteWithoutEscapingRule(), """
                class Greeting {
                    void greet(HttpServletRequest request, HttpServletResponse response) throws IOException {
                        String name = request.getParameter("name");
                        response.getWriter().format("Hello, %s", name);
                        PrintWriter out = response.getWriter();
                        out.format(Locale.US, name, "a");
                    }
                }
                """)).containsExactly(4, 6);
    }

    @Test
    void htmlEscapingDoesNotMakeSessionValueTrusted() {
        assertThat(RuleTests.lines(new TrustBoundaryViolationRule(), """
                class Login {
                    void remember(HttpServletRequest request) {
                        String user = HtmlUtils.htmlEscape(request.getParameter("user"));
                        request.getSession().setAttribute("userId", user);
                    }
                }
                """)).containsExactly(4);
    }

    @Test
    void algorithmFromOwnPropertiesFile() throws IOException {
        Path resources = Files.createDirectories(dir.resolve("src/main/resources"));
        Files.writeString(resources.resolve("crypto.properties"), "hashAlg=MD5\nsafeHash=SHA-256\ncipher=DES/ECB/PKCS5Padding\n");
        Path sources = Files.createDirectories(dir.resolve("src/main/java/demo"));
        Files.writeString(sources.resolve("Crypto.java"), """
                package demo;
                class Crypto {
                    byte[] digest(byte[] data) throws Exception {
                        java.util.Properties props = new java.util.Properties();
                        props.load(getClass().getClassLoader().getResourceAsStream("crypto.properties"));
                        String algorithm = props.getProperty("hashAlg", "SHA-512");
                        String safe = props.getProperty("safeHash", "SHA-512");
                        MessageDigest.getInstance(safe);
                        Cipher.getInstance(props.getProperty("cipher"));
                        return MessageDigest.getInstance(algorithm).digest(data);
                    }
                }
                """);
        List<SourceFile> files = new FileSystemSourceLoader().load(dir).sources();

        // В файле hashAlg=MD5 и cipher=DES; safeHash - SHA-256
        assertThat(files.stream().flatMap(file -> new WeakHashRule().check(file).stream()).map(Violation::line))
                .containsExactly(10);
        assertThat(files.stream().flatMap(file -> new WeakCipherRule().check(file).stream()).map(Violation::line))
                .containsExactly(9);
    }

    @Test
    void randomBytesBecomeSecret() {
        assertThat(RuleTests.lines(new InsecureRandomRule(), """
                class Keys {
                    String issue() {
                        byte[] bytes = new byte[16];
                        new java.util.Random().nextBytes(bytes);
                        String rememberMeKey = Base64.getEncoder().encodeToString(bytes);
                        return rememberMeKey;
                    }
                    String issueAgain() {
                        Random random = new Random();
                        byte[] bytes = new byte[16];
                        random.nextBytes(bytes);
                        String resetKey = Base64.getEncoder().encodeToString(bytes);
                        return resetKey;
                    }
                }
                """)).containsExactly(4, 9);
    }

    @Test
    void commandFromValuesThatDoNotReachIt() {
        assertThat(RuleTests.lines(new CommandInjectionRule(), """
                class Runner {
                    void run(HttpServletRequest request) throws IOException {
                        String param = request.getHeader("x");
                        String bar;
                        int num = 86;
                        if ((7 * 42) - num > 200) bar = "always"; else bar = param;
                        String other = new Helper().same(param);
                        Runtime.getRuntime().exec("echo " + bar);
                        Runtime.getRuntime().exec("echo " + other);
                        Runtime.getRuntime().exec("echo " + param);
                    }
                    private class Helper {
                        String same(String value) { return "constant"; }
                    }
                }
                """)).containsExactly(10);
    }
}
