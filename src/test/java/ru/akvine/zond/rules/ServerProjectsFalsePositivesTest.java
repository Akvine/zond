package ru.akvine.zond.rules;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import ru.akvine.zond.loaders.FileSystemSourceLoader;
import ru.akvine.zond.models.SourceFile;
import ru.akvine.zond.models.Violation;
import ru.akvine.zond.rules.codesmell.AssertEqualsArgumentOrderRule;
import ru.akvine.zond.rules.codesmell.ConsoleOutputRule;
import ru.akvine.zond.rules.codesmell.TestWithoutAssertionRule;
import ru.akvine.zond.rules.codesmell.UnusedMethodRule;
import ru.akvine.zond.rules.logical.AssertionOnlyInCatchRule;
import ru.akvine.zond.rules.logical.ConstantAssertionRule;
import ru.akvine.zond.rules.logical.EqualsHashCodeRule;
import ru.akvine.zond.rules.logical.MessageBundleMismatchRule;
import ru.akvine.zond.rules.logical.PossibleNullDereferenceRule;
import ru.akvine.zond.rules.logical.ReferenceEqualityRule;
import ru.akvine.zond.rules.performance.HttpCallInLoopRule;
import ru.akvine.zond.rules.resources.UnclosedResourceRule;
import ru.akvine.zond.rules.exceptions.UnhandledRequestValueParseRule;
import ru.akvine.zond.rules.security.LogInjectionRule;
import ru.akvine.zond.rules.security.SsrfRule;
import ru.akvine.zond.rules.security.TemplateInjectionRule;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Ложные срабатывания, найденные на Nacos, Keycloak, Jenkins, RuoYi и mall
 */
class ServerProjectsFalsePositivesTest {
    @TempDir
    Path dir;

    @Test
    void assertTrueFalseIsOldStyleFail() {
        String code = """
                class ConfigServiceTest {
                    @Test
                    void failsOnConnectionError() {
                        try {
                            service.publish(config);
                            assertTrue(false);
                        } catch (Exception e) {
                            assertEquals("conn error", e.getMessage());
                        }
                    }
                    @Test
                    void doesNotThrow() {
                        try {
                            holder.register(pattern);
                        } catch (Exception e) {
                            Assertions.assertTrue(false);
                        }
                        assertTrue(true);
                    }
                }
                """;

        // assertTrue(false) - намеренное падение, как fail(); assertTrue(true) ничего не проверяет
        assertThat(RuleTests.lines(new ConstantAssertionRule(), code)).containsExactly(18);
        assertThat(RuleTests.lines(new AssertionOnlyInCatchRule(), code)).isEmpty();
    }

    @Test
    void constantUnderTestAndNotEqualsKeepArgumentOrder() {
        String code = """
                class DatasourceConstantsTest {
                    @Test
                    void keys() {
                        assertArrayEquals(new String[] {"id"}, PrimaryKeyConstant.LOWER_RETURN_PRIMARY_KEYS);
                        assertEquals(List.of("a", "b"), Names.DEFAULT_NAMES);
                        assertEquals(lock.getType(), LockConstants.NACOS_LOCK_TYPE);
                    }
                }
                """;

        // Первые две строки проверяют саму константу; в последней ожидаемое и проверяемое переставлены
        assertThat(RuleTests.lines(new AssertEqualsArgumentOrderRule(), code)).containsExactly(6);
    }

    @Test
    void methodReturnsNullOnlyForNullDefault() throws IOException {
        Files.writeString(dir.resolve("DirectoryBrowserSupport.java"), """
                class DirectoryBrowserSupport {
                    public static final String DEFAULT_CSP_VALUE = "sandbox; default-src 'none';";
                    void serve() {
                        String csp = SystemProperties.getString("csp", DEFAULT_CSP_VALUE);
                        if (!csp.trim().isEmpty()) {
                            send(csp);
                        }
                        String literal = SystemProperties.getString("mode", "fast");
                        send(literal.trim());
                        String missing = SystemProperties.getString("name");
                        send(missing.trim());
                    }
                }
                """);
        Files.writeString(dir.resolve("SystemProperties.java"), """
                class SystemProperties {
                    public static String getString(String key) {
                        return getString(key, null);
                    }
                    public static String getString(String key, @CheckForNull String def) {
                        return getString(key, def, 1);
                    }
                    public static String getString(String key, @CheckForNull String def, int level) {
                        String value = System.getProperty(key);
                        if (value != null) {
                            return value;
                        }
                        value = def;
                        return value;
                    }
                }
                """);
        List<SourceFile> sources = new FileSystemSourceLoader().load(dir).sources();

        // null бывает, только если запасное значение - null: так вызывает лишь getString("name")
        assertThat(new PossibleNullDereferenceRule().checkProject(sources)).extracting(Violation::line)
                .containsExactly(11);
    }

    @Test
    void englishMessagesAreFallbackWithoutBaseFile() {
        List<Violation> found = new ProjectFixture(dir)
                .write("themes/login/messages/messages_en.properties", """
                        doLogIn=Sign In
                        doCancel=Cancel
                        """)
                .write("themes/login/messages/messages_ar.properties", """
                        doLogIn=تسجيل الدخول
                        """)
                .write("themes/login/messages/messages_de.properties", """
                        doLogIn=Anmelden
                        doCancel=Abbrechen
                        oldKey=Alt
                        """)
                .check(new MessageBundleMismatchRule());

        // Недостающее в арабском Keycloak возьмет из английского; в английском нет только устаревшего oldKey
        assertThat(found).extracting(violation -> violation.file().getFileName().toString())
                .containsExactly("messages_en.properties");
        assertThat(found.get(0).message()).contains("oldKey");
    }

    @Test
    void commandOfConsoleProgramPrintsToConsole() {
        String command = """
                import org.apache.commons.cli.CommandLine;
                public class TopicListSubCommand implements SubCommand {
                    public void execute(CommandLine commandLine, Options options) {
                        System.out.printf("%-20s %n", "topic");
                    }
                }
                """;
        String broker = """
                public class BrokerStartup {
                    public void start() {
                        System.out.println("broker started");
                    }
                }
                """;

        // Команда mqadmin на Commons CLI пишет в консоль по назначению; рабочий класс брокера - нет
        assertThat(RuleTests.lines(new ConsoleOutputRule(), command)).isEmpty();
        assertThat(RuleTests.lines(new ConsoleOutputRule(), broker)).containsExactly(3);
    }

    @Test
    void compiledFreemarkerTemplateGetsModelNotText() {
        String code = """
                @RestController
                class MailController {
                    @PostMapping("/mail")
                    String send(@RequestBody Email email) throws Exception {
                        Template htmlTemplate = configuration.getTemplate("mail/" + "welcome.ftl");
                        StringWriter out = new StringWriter();
                        htmlTemplate.process(email.getTokens(), out);
                        Template fromText = new Template("t", new StringReader(email.getBody()), configuration);
                        fromText.process(email.getTokens(), out);
                        return templateEngine.process(email.getBody(), context);
                    }
                }
                """;

        // Модель данных в собранном шаблоне не опасна; текст шаблона из запроса - опасен
        assertThat(RuleTests.lines(new TemplateInjectionRule(), code)).containsExactly(8, 10);
    }

    @Test
    void urlReturnedByCalledApiIsNotSsrf() {
        String code = """
                @RestController
                class UploadController {
                    @PostMapping("/upload")
                    void upload(@RequestParam String target, @RequestBody byte[] content) {
                        String uploadUrl = restTemplate.getForObject("https://api.example.com/uploads/url", String.class);
                        restTemplate.put(uploadUrl, content);
                        restTemplate.getForObject(target, String.class);
                    }
                }
                """;

        // Адрес для загрузки вернул сам API; адрес из параметра запроса - SSRF
        assertThat(RuleTests.lines(new SsrfRule(), code)).containsExactly(7);
    }

    @Test
    void jaxRsExceptionMapperHandlesParseErrors() {
        Map<String, String> files = Map.of(
                "TellerApiResource.java", """
                        @Path("/tellers")
                        class TellerApiResource {
                            @GET
                            public String find(@QueryParam("from") String fromDateStr, @QueryParam("owner") String owner) {
                                LocalDate from = LocalDate.parse(fromDateStr);
                                LockOwner number = LockOwner.valueOf(owner);
                                return from.toString() + number;
                            }
                        }
                        """,
                "LockOwner.java", """
                        enum LockOwner { BATCH, USER }
                        """,
                "DateParseMapper.java", """
                        @Provider
                        class DateParseMapper implements ExceptionMapper<DateTimeParseException> {
                            public Response toResponse(DateTimeParseException exception) {
                                return Response.status(400).build();
                            }
                        }
                        """);

        // Ошибку разбора даты отвечает обработчик JAX-RS; для valueOf перечисления обработчика нет
        assertThat(RuleTests.checkProject(new UnhandledRequestValueParseRule(), files))
                .extracting(Violation::line)
                .containsExactly(6);
    }

    @Test
    void wrappersOverMemoryAndStandardStreamsHoldNothing() {
        String code = """
                class SevenZOutputFile {
                    byte[] header() throws IOException {
                        final ByteArrayOutputStream baos = new ByteArrayOutputStream();
                        final DataOutputStream out = new DataOutputStream(baos);
                        out.writeInt(1);
                        return baos.toByteArray();
                    }
                    void help() {
                        PrintWriter pw = new PrintWriter(System.out);
                        pw.println("usage");
                        pw.flush();
                    }
                    void save(File file) throws IOException {
                        DataOutputStream out = new DataOutputStream(new FileOutputStream(file));
                        out.writeInt(1);
                    }
                }
                """;

        // Буфер в памяти и System.out закрывать не нужно; поток в файл - нужно
        assertThat(RuleTests.lines(new UnclosedResourceRule(), code)).containsExactly(14);
    }

    @Test
    void hashCodeOfSubclassUsesInheritedEquals() {
        String code = """
                class FunctionType extends PrototypeObjectType {
                    @Override
                    public int hashCode() {
                        return isInstanceType() ? getInstanceType().hashCode() : System.identityHashCode(this);
                    }
                }
                class Definition {
                    @Override
                    public boolean equals(Object other) {
                        return other instanceof Definition;
                    }
                }
                """;

        // equals без hashCode нарушает договор HashMap; hashCode наследника - нет: equals объявлен у предка
        assertThat(RuleTests.lines(new EqualsHashCodeRule(), code)).containsExactly(8);
    }

    @Test
    void referenceComparisonAsFastPathOrSentinel() {
        String code = """
                class DOMNodePointer {
                    private static final String UNKNOWN_NAMESPACE = "<<unknown namespace>>";
                    private static final String DEFAULT = "default";
                    private Integer iPivotYear;
                    private final Map<String, String> namespaces = new HashMap<>();
                    boolean same(Integer pivotYear, String pattern, String other) {
                        return iPivotYear == pivotYear || (iPivotYear != null && iPivotYear.equals(pivotYear));
                    }
                    private static boolean equalStrings(String s1, String s2) {
                        if (s1 == s2) {
                            return true;
                        }
                        return s1 != null && s1.equals(s2);
                    }
                    String namespace(String prefix) {
                        String namespace = namespaces.get(prefix);
                        if (namespace == null) {
                            namespaces.put(prefix, UNKNOWN_NAMESPACE);
                        }
                        if (namespace == UNKNOWN_NAMESPACE || namespace == DEFAULT) {
                            return null;
                        }
                        return namespace;
                    }
                }
                """;

        // Быстрый ответ перед equals и метка, которую класс сам кладет в карту; DEFAULT меткой не служит
        assertThat(RuleTests.lines(new ReferenceEqualityRule(), code)).containsExactly(20);
    }

    @Test
    void methodWhoseResultProjectNeverChecksIsConvention() {
        String code = """
                class TreeBuilder {
                    private final List<Element> stack = new ArrayList<>();
                    Element currentElement() {
                        int size = stack.size();
                        return size > 0 ? stack.get(size - 1) : null;
                    }
                    Element lookup(String name) {
                        for (Element element : stack) {
                            if (element.name().equals(name)) {
                                return element;
                            }
                        }
                        return null;
                    }
                    void a() { currentElement().append("a"); }
                    void b() { currentElement().append("b"); }
                    void c() { currentElement().append("c"); }
                    void d() { currentElement().append("d"); }
                    void e() { currentElement().append("e"); }
                    void f() { Element found = lookup("f"); if (found != null) { found.append("f"); } }
                    void g() { Element found = lookup("g"); if (found == null) { return; } found.append("g"); }
                    void h() { lookup("h").append("h"); }
                    void i() { Element found = lookup("i"); if (found != null) { found.append("i"); } }
                    void j() { Element found = lookup("j"); if (found != null) { found.append("j"); } }
                }
                """;

        // currentElement() не проверяет никто - так принято в проекте; lookup() проверяют почти все, кроме h()
        assertThat(RuleTests.lines(new PossibleNullDereferenceRule(), code)).containsExactly(22);
    }

    @Test
    void preconditionsAndFlagTernaryProveNotNull() {
        String code = """
                class TypedScopeCreator {
                    void define(Node rValue, Node root) {
                        boolean isFnLiteral = rValue != null && rValue.isFunction();
                        Node parametersNode = isFnLiteral ? rValue.getFirstChild() : null;
                        Node fnBlock = isFnLiteral ? parametersNode.getNext() : null;
                        String sourceName = (String) root.getProp(SOURCENAME);
                        Preconditions.checkState(sourceName != null);
                        Preconditions.checkState(!sourceName.isEmpty());
                        Node other = root.getNext() == null ? null : root.getNext();
                        String name = other.getString();
                    }
                }
                """;

        // Тот же флаг isFnLiteral и checkState(x != null) доказывают, что значение не null; other - нет
        assertThat(RuleTests.lines(new PossibleNullDereferenceRule(), code)).containsExactly(10);
    }

    @Test
    void testsCheckingThroughNewTesterOrConditionalThrow() {
        String tests = """
                class CompactHashSetCodecTest {
                    @Test
                    void codecRoundTrips() throws Exception {
                        new SerializationTester(CompactHashSet.create("a")).runTests();
                    }
                    @Test
                    void requireMetadata() throws Exception {
                        EvaluationResult result = loadRootDataBzl();
                        if (result.hasError()) {
                            throw result.getError().getException();
                        }
                    }
                    @Test
                    void nothing() {
                        loadRootDataBzl();
                    }
                    EvaluationResult loadRootDataBzl() {
                        return evaluator.evaluate();
                    }
                }
                """;
        String tester = """
                class SerializationTester {
                    public void runTests() throws Exception {
                        runTests(true);
                    }
                    private void runTests(boolean stable) throws Exception {
                        roundTrip();
                    }
                    private void roundTrip() {
                        assertThat(deserialize(serialize())).isEqualTo(subject);
                    }
                }
                """;

        // Проверки - во вложенных методах тестера и в условном throw; nothing() не проверяет ничего
        assertThat(RuleTests.checkProject(new TestWithoutAssertionRule(),
                Map.of("CompactHashSetCodecTest.java", tests, "SerializationTester.java", tester)))
                .extracting(Violation::line)
                .containsExactly(13);
    }

    @Test
    void ownMethodNamedLikeRequestReaderIsNotSource() {
        String code = """
                class RestUtil {
                    public static HttpHeaders getHeader(String mediaType) {
                        HttpHeaders headers = new HttpHeaders();
                        headers.setContentType(MediaType.parseMediaType(mediaType));
                        return headers;
                    }
                    public static void send(HttpServletRequest request) {
                        HttpHeaders headers = getHeader(MediaType.APPLICATION_JSON_VALUE);
                        log.info("headers: " + headers);
                        log.info("client: " + request.getParameter("client"));
                        log.info("query: " + request.getQueryString() + request.getHeader("X-Client"));
                    }
                }
                class XssRequestWrapper extends HttpServletRequestWrapper {
                    void trace() {
                        log.info("name: " + getParameter("name"));
                    }
                }
                """;

        // Свой getHeader(mediaType) запрос не читает; обертка над запросом и getParameter - читают. В
        // нераскодированной строке запроса и в заголовке сырого перевода строки не бывает
        assertThat(RuleTests.lines(new LogInjectionRule(), code)).containsExactly(10, 16);
    }

    @Test
    void httpClientDescriptionIsNotHttpCall() {
        String code = """
                class KeycloakSubsystemParser {
                    void readElement(XMLExtendedStreamReader reader, List<ModelNode> list) {
                        while (reader.hasNext()) {
                            HttpClientDefinition.lookup(reader.getLocalName());
                            httpClientConfig.update(reader.getAttributeValue(0));
                            httpClient.execute(new HttpGet(reader.getElementText()));
                        }
                    }
                }
                """;

        // Описание и настройки клиента в цикле разбора XML - не запросы; httpClient.execute - запрос
        assertThat(RuleTests.lines(new HttpCallInLoopRule(), code)).containsExactly(6);
    }

    @Test
    void staplerWebMethodsAndViewTemplatesUseMethods() throws IOException {
        Path module = dir.resolve("core");
        Path views = module.resolve("src/main/resources/hudson/console/ConsoleAnnotationDescriptor");
        Files.createDirectories(views);
        Files.writeString(views.resolve("script.jelly"), """
                <j:jelly xmlns:j="jelly:core">
                  <j:if test="${it.hasScript()}">
                    <script src="${h.defaulted(it.scriptUrl, '/default.js')}"/>
                  </j:if>
                </j:jelly>
                """);
        List<SourceFile> sources = List.of(
                RuleTests.parse(module.resolve("src/main/java/hudson/console/ConsoleAnnotationDescriptor.java"), """
                        package hudson.console;
                        import org.kohsuke.stapler.QueryParameter;
                        public class ConsoleAnnotationDescriptor {
                            public boolean hasScript() {
                                return true;
                            }
                            public FormValidation doCheckUrl(@QueryParameter String value) {
                                return FormValidation.ok();
                            }
                            public void forgotten() {
                            }
                        }
                        """),
                RuleTests.parse(module.resolve("src/main/java/hudson/Functions.java"), """
                        package hudson;
                        public class Functions {
                            public static <T> T defaulted(T value, T defaultValue) {
                                return value == null ? defaultValue : value;
                            }
                        }
                        """),
                RuleTests.parse(dir.resolve("war/src/main/java/executable/Main.java"), """
                        package executable;
                        public class Main {
                            public static void main(String[] args) {
                                new hudson.console.ConsoleAnnotationDescriptor();
                                new hudson.Functions();
                            }
                        }
                        """));

        // hasScript и defaulted вызываются из шаблона Jelly, doCheckUrl - по адресу запроса через Stapler
        assertThat(new UnusedMethodRule().checkProject(sources)).extracting(Violation::message)
                .singleElement().asString().contains("'ConsoleAnnotationDescriptor.forgotten'");
    }
}
