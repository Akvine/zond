package ru.akvine.zond.rules;

import org.junit.jupiter.api.Test;
import ru.akvine.zond.enums.Confidence;
import ru.akvine.zond.models.Violation;
import ru.akvine.zond.rules.codesmell.MockitoMisuseRule;
import ru.akvine.zond.rules.concurrency.LockOrderInversionRule;
import ru.akvine.zond.rules.concurrency.PeriodicTaskWithoutCatchRule;
import ru.akvine.zond.rules.exceptions.SubscribeWithoutErrorHandlerRule;
import ru.akvine.zond.rules.logical.ComparatorContractRule;
import ru.akvine.zond.rules.logical.EntityHashCodeOnGeneratedIdRule;
import ru.akvine.zond.rules.logical.RegexMetacharacterLiteralRule;
import ru.akvine.zond.rules.performance.HttpClientPerCallRule;
import ru.akvine.zond.rules.resources.ByteBufLeakRule;
import ru.akvine.zond.rules.resources.UseAfterCloseRule;
import ru.akvine.zond.rules.security.CatastrophicRegexRule;
import ru.akvine.zond.rules.security.PolymorphicDeserializationRule;
import ru.akvine.zond.rules.security.TemplateInjectionRule;
import ru.akvine.zond.rules.security.TrustedForwardedHeaderRule;
import ru.akvine.zond.rules.security.UnboundedDecompressionRule;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Правила jr:437 - jr:451: ошибки, которые не видны сразу - пустой массив вместо частей строки, задача, которая
 * молча перестала запускаться, взаимная блокировка, потерянная ошибка, утечка буфера, уязвимости разбора данных
 */
class HiddenFailureRulesTest {

    @Test
    void regexMetacharacterAsLiteral() {
        String code = """
                class Parser {
                    void parse(String version, String line, String path) {
                        String[] parts = version.split(".");
                        String[] cells = line.split("|");
                        String clean = path.replaceAll(".", "/");
                        String[] safe = version.split("\\\\.");
                        String[] words = line.split(",");
                        String[] quoted = line.split(Pattern.quote("|"));
                        String[] utils = StringUtils.split(version, ".");
                    }
                }
                """;

        // Точка и черта - спецсимволы регулярного выражения; экранированные, обычные и StringUtils - нет
        assertThat(RuleTests.lines(new RegexMetacharacterLiteralRule(), code)).containsExactly(3, 4, 5);
    }

    @Test
    void comparatorWithoutZero() {
        String code = """
                class Sorting {
                    void sort(List<Order> orders) {
                        orders.sort((a, b) -> a.getPrice() > b.getPrice() ? 1 : -1);
                        orders.sort((a, b) -> a.getPrice() > b.getPrice() ? 1 : a.getPrice() < b.getPrice() ? -1 : 0);
                        orders.sort((a, b) -> Integer.compare(a.getPrice(), b.getPrice()));
                        Set<Order> sorted = new TreeSet<>((a, b) -> a.getId() < b.getId() ? -1 : 1);
                    }
                }
                class Version implements Comparable<Version> {
                    public int compareTo(Version other) {
                        return number >= other.number ? 1 : -1;
                    }
                }
                """;

        // Без ветки с нулем равные элементы "больше" друг друга; с нулем и через Integer.compare - верно
        assertThat(RuleTests.lines(new ComparatorContractRule(), code)).containsExactlyInAnyOrder(3, 6, 10);
    }

    @Test
    void entityHashCodeOnGeneratedId() {
        String code = """
                @Entity
                class Order {
                    @Id
                    @GeneratedValue(strategy = GenerationType.IDENTITY)
                    private Long id;
                    public int hashCode() {
                        return Objects.hash(id);
                    }
                }
                @Entity
                class Customer {
                    @Id
                    @GeneratedValue
                    private Long id;
                    public int hashCode() {
                        return getClass().hashCode();
                    }
                }
                @Entity
                class Country {
                    @Id
                    private String code;
                    public int hashCode() {
                        return code.hashCode();
                    }
                }
                """;

        // Постоянный хеш и id, заданный вручную, не меняются после save()
        assertThat(RuleTests.lines(new EntityHashCodeOnGeneratedIdRule(), code)).containsExactly(6);
    }

    @Test
    void useAfterClose() {
        String code = """
                class Export {
                    String read(Path file) throws IOException {
                        BufferedReader reader = Files.newBufferedReader(file);
                        String first = reader.readLine();
                        reader.close();
                        String second = reader.readLine();
                        return first + second;
                    }
                    byte[] write(Report report) throws IOException {
                        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
                        bytes.write(report.data());
                        bytes.close();
                        return bytes.toByteArray();
                    }
                    void reopen(Path file) throws IOException {
                        InputStream in = Files.newInputStream(file);
                        in.close();
                        in = Files.newInputStream(file);
                        in.read();
                    }
                    Path finish(FileOutErr outErr, XmlGenerator gen) {
                        outErr.close();
                        gen.open("network").node("port", 5701).close();
                        gen.close();
                        gen.node("required-member", "10.0.0.1");
                        return outErr.getErrorPath();
                    }
                }
                """;

        // Чтение после close() - находка; toByteArray у закрытого буфера, новый поток в той же переменной,
        // геттер итога и закрытие элемента XML - нет
        assertThat(RuleTests.lines(new UseAfterCloseRule(), code)).containsExactly(6);
    }

    @Test
    void periodicTaskWithoutCatch() {
        String code = """
                class Sync {
                    void start(ScheduledExecutorService executor, Timer timer, TaskScheduler scheduler) {
                        executor.scheduleAtFixedRate(() -> client.sync(), 0, 1, TimeUnit.MINUTES);
                        executor.scheduleWithFixedDelay(this::poll, 0, 5, TimeUnit.SECONDS);
                        executor.scheduleAtFixedRate(() -> {
                            try {
                                client.sync();
                            } catch (Exception e) {
                                log.error("sync failed", e);
                            }
                        }, 0, 1, TimeUnit.MINUTES);
                        executor.scheduleAtFixedRate(this::safePoll, 0, 5, TimeUnit.SECONDS);
                        timer.scheduleAtFixedRate(new TimerTask() {
                            public void run() {
                                client.sync();
                            }
                        }, 0, 1000);
                        scheduler.scheduleAtFixedRate(() -> client.sync(), Duration.ofMinutes(1));
                        executor.scheduleWithFixedDelay(() -> {
                            if (!enabled) {
                                return;
                            }
                            for (String group : groups) {
                                try {
                                    report(group);
                                } catch (Exception e) {
                                    log.warn("report failed", e);
                                }
                            }
                        }, 0, 1, TimeUnit.MINUTES);
                        executor.scheduleWithFixedDelay(this::updateMetrics, 0, 1, TimeUnit.MINUTES);
                        executor.scheduleWithFixedDelay(this::cleanup, 0, 1, TimeUnit.MINUTES);
                    }
                    private void poll() {
                        client.poll();
                    }
                    private void updateMetrics() {
                        updateLock.lock();
                        try {
                            notifier.publishMetrics();
                        } catch (Exception e) {
                            log.error("publish failed", e);
                        } finally {
                            updateLock.unlock();
                        }
                        notifyHealth();
                    }
                    private void notifyHealth() {
                        try {
                            notifier.publishHealth();
                        } catch (Exception e) {
                            log.error("health failed", e);
                        }
                    }
                    private void cleanup() {
                        try {
                            sessions.cleanup();
                        } catch (Exception e) {
                            log.error("cleanup failed", e);
                        }
                        webSocketService.cleanupIfStale();
                    }
                    private void safePoll() {
                        try {
                            client.poll();
                        } catch (RuntimeException e) {
                            log.warn("poll failed", e);
                        }
                    }
                }
                """;

        // TaskScheduler из Spring ловит ошибки сам; задачи с catch (Exception), свой метод с ним, цикл, каждая
        // итерация которого в try, lock() перед try и вызов своего защищенного метода после - не находка
        assertThat(RuleTests.lines(new PeriodicTaskWithoutCatchRule(), code)).containsExactly(3, 4, 13, 32);
    }

    @Test
    void lockOrderInversion() {
        String code = """
                class Accounts {
                    private final Object from = new Object();
                    private final Object to = new Object();
                    private final ReentrantLock first = new ReentrantLock();
                    private final ReentrantLock second = new ReentrantLock();
                    void transfer() {
                        synchronized (from) {
                            synchronized (to) {
                                move();
                            }
                        }
                    }
                    void refund() {
                        synchronized (to) {
                            synchronized (from) {
                                move();
                            }
                        }
                    }
                    void report() {
                        synchronized (from) {
                            synchronized (to) {
                                print();
                            }
                        }
                    }
                    void lockBoth() {
                        first.lock();
                        try {
                            second.lock();
                        } finally {
                            first.unlock();
                        }
                    }
                    void lockInOrder() {
                        first.lock();
                        first.unlock();
                        second.lock();
                        first.lock();
                    }
                }
                """;

        // from/to в обратном порядке - находка один раз; second после отпущенного first - не вложенность, а
        // first под second - обратный порядок к lockBoth
        assertThat(RuleTests.lines(new LockOrderInversionRule(), code)).containsExactly(15, 39);
    }

    @Test
    void subscribeWithoutErrorHandler() {
        String code = """
                import reactor.core.publisher.Mono;
                class Notifier {
                    void send(Mono<Message> message, Flux<Event> events) {
                        message.subscribe();
                        events.subscribe(event -> log.info("{}", event));
                        message.subscribe(value -> log.info("{}", value), error -> log.error("failed", error));
                        events.doOnError(error -> log.error("failed", error)).subscribe();
                        Mono.just(1).subscribe(new LoggingSubscriber());
                        message.subscribe(this);
                    }
                }
                """;
        String withoutReactor = """
                class Bus {
                    void send(Topic topic) {
                        topic.subscribe(listener);
                    }
                }
                """;

        // Обработчик вторым аргументом, doOnError выше по цепочке и свой подписчик - ошибка не теряется
        assertThat(RuleTests.lines(new SubscribeWithoutErrorHandlerRule(), code)).containsExactly(4, 5);
        assertThat(RuleTests.lines(new SubscribeWithoutErrorHandlerRule(), withoutReactor)).isEmpty();
    }

    @Test
    void byteBufLeak() {
        String code = """
                class Encoder {
                    void encode(ChannelHandlerContext ctx, Message message) {
                        ByteBuf lost = ctx.alloc().buffer();
                        lost.writeInt(message.type());
                        ByteBuf sent = ctx.alloc().buffer();
                        sent.writeInt(message.type());
                        ctx.writeAndFlush(sent);
                        ByteBuf released = ctx.alloc().directBuffer();
                        try {
                            released.writeInt(1);
                        } finally {
                            released.release();
                        }
                        ByteBuf heap = Unpooled.buffer();
                        heap.writeInt(2);
                    }
                    ByteBuf build(ChannelHandlerContext ctx, boolean empty) {
                        ByteBuf returned = ctx.alloc().buffer();
                        return empty ? null : returned;
                    }
                }
                """;

        // Записан в канал, освобожден, возвращен - буфер не потерян; Unpooled.buffer() пулом не управляется
        assertThat(RuleTests.lines(new ByteBufLeakRule(), code)).containsExactly(3);
    }

    @Test
    void polymorphicDeserialization() {
        String code = """
                class JsonConfig {
                    ObjectMapper mapper() {
                        ObjectMapper mapper = new ObjectMapper();
                        mapper.enableDefaultTyping();
                        mapper.activateDefaultTyping(LaissezFaireSubTypeValidator.instance, DefaultTyping.NON_FINAL);
                        PolymorphicTypeValidator strict = BasicPolymorphicTypeValidator.builder()
                                .allowIfSubType("com.shop.events.").build();
                        mapper.activateDefaultTyping(strict, DefaultTyping.NON_FINAL);
                        return mapper;
                    }
                }
                class Event {
                    @JsonTypeInfo(use = JsonTypeInfo.Id.CLASS)
                    private Object payload;
                    @JsonTypeInfo(use = JsonTypeInfo.Id.NAME)
                    private Object named;
                }
                class Settings {
                    Map<String, Object> read(String text) {
                        Map<String, Object> unsafe = new Yaml().load(text);
                        Yaml safe = new Yaml(new SafeConstructor(new LoaderOptions()));
                        Yaml writer = new Yaml();
                        writer.dump(unsafe);
                        return safe.load(text);
                    }
                }
                """;

        // Проверка подтипов по пакету, Id.NAME, SafeConstructor и Yaml только для записи - безопасны
        assertThat(RuleTests.lines(new PolymorphicDeserializationRule(), code)).containsExactly(4, 5, 13, 20);
        assertThat(RuleTests.check(new PolymorphicDeserializationRule(), code))
                .filteredOn(violation -> violation.line() == 20)
                .extracting(Violation::confidenceOrDefault).containsExactly(Confidence.SUSPICION);
    }

    @Test
    void unboundedDecompression() {
        String code = """
                class Import {
                    byte[] unpack(InputStream upload) throws IOException {
                        return IOUtils.toByteArray(new GZIPInputStream(upload));
                    }
                    void extract(InputStream upload, OutputStream out) throws IOException {
                        try (ZipInputStream zis = new ZipInputStream(upload)) {
                            byte[] buffer = new byte[8192];
                            int length;
                            while ((length = zis.read(buffer)) > 0) {
                                out.write(buffer, 0, length);
                            }
                        }
                    }
                    void extractLimited(InputStream upload, OutputStream out) throws IOException {
                        try (ZipInputStream zis = new ZipInputStream(upload)) {
                            byte[] buffer = new byte[8192];
                            long total = 0;
                            int length;
                            while ((length = zis.read(buffer)) > 0) {
                                total += length;
                                if (total > MAX_UNPACKED_SIZE) {
                                    throw new IOException("archive is too large");
                                }
                                out.write(buffer, 0, length);
                            }
                        }
                    }
                    byte[] plain(InputStream in) throws IOException {
                        return in.readAllBytes();
                    }
                }
                """;

        // Распаковка с пределом и чтение обычного потока - не находка
        assertThat(RuleTests.lines(new UnboundedDecompressionRule(), code)).containsExactly(3, 9);
    }

    @Test
    void unboundedDecompressionOfTrustedArchives() {
        String code = """
                class Packer {
                    private byte[] compressedBytes;
                    private int uncompressedSize;
                    void pack(byte[] data) throws IOException {
                        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
                        try (GZIPOutputStream gzip = new GZIPOutputStream(bytes)) {
                            gzip.write(data);
                        }
                        compressedBytes = bytes.toByteArray();
                    }
                    void unpack(OutputStream out) throws IOException {
                        try (GZIPInputStream in = new GZIPInputStream(new ByteArrayInputStream(compressedBytes))) {
                            ByteStreams.copy(in, out);
                        }
                    }
                    void builtins(ClassLoader loader, Path target) throws IOException {
                        try (InputStream zipped = loader.getResourceAsStream("builtins.zip");
                             ZipInputStream zip = new ZipInputStream(zipped)) {
                            Files.copy(zip, target);
                        }
                    }
                    byte[] exact(InputStream source) throws IOException {
                        byte[] result = new byte[uncompressedSize];
                        int total = 0;
                        int read;
                        try (GZIPInputStream in = new GZIPInputStream(source)) {
                            while (total < uncompressedSize && (read = in.read(result, total, 100)) != -1) {
                                total += read;
                            }
                        }
                        return result;
                    }
                    byte[] config(Path archive) throws IOException {
                        try (ZipInputStream zip = new ZipInputStream(Files.newInputStream(archive))) {
                            return zip.readAllBytes();
                        }
                    }
                }
                """;

        // Свои сжатые данные, ресурс приложения и цикл со счетчиком - не находки; локальный архив - подозрение
        assertThat(RuleTests.check(new UnboundedDecompressionRule(), code))
                .extracting(violation -> violation.line() + " " + violation.confidenceOrDefault())
                .containsExactly("35 SUSPICION");
    }

    @Test
    void trustedForwardedHeader() {
        String code = """
                class LoginGuard {
                    boolean allow(HttpServletRequest request) {
                        String ip = request.getHeader("X-Forwarded-For");
                        if (blockedAddresses.contains(ip)) {
                            return false;
                        }
                        return rateLimiter.tryAcquire(ip);
                    }
                }
                class IpUtils {
                    static String clientIp(HttpServletRequest request) {
                        String ip = request.getHeader("X-Real-IP");
                        if (ip == null || ip.isEmpty()) {
                            ip = request.getRemoteAddr();
                        }
                        return ip;
                    }
                }
                class Audit {
                    void log(HttpServletRequest request) {
                        String forwarded = request.getHeader("X-Forwarded-For");
                        log.info("request from {}", forwarded);
                    }
                }
                """;

        // Блокировка по адресу из заголовка - вероятно; метод, который его отдает, - подозрение; лог - не находка
        assertThat(RuleTests.check(new TrustedForwardedHeaderRule(), code))
                .extracting(violation -> violation.line() + ":" + violation.confidenceOrDefault())
                .containsExactly("3:" + Confidence.PROBABLE, "12:" + Confidence.SUSPICION);
    }

    @Test
    void catastrophicRegex() {
        String code = """
                class Validator {
                    private static final String WORDS = "^(\\\\w+\\\\s?)+$";
                    @Pattern(regexp = "^([a-z]+)*@example\\\\.com$")
                    private String email;
                    boolean check(String value) {
                        boolean words = Pattern.compile(WORDS).matcher(value).matches();
                        boolean any = value.matches("(.*a)+");
                        boolean version = value.matches("^(\\\\d+\\\\.)+\\\\d+$");
                        boolean possessive = value.matches("^(\\\\w++\\\\s?)+$");
                        boolean atomic = value.matches("^(?>\\\\w+\\\\s?)+$");
                        boolean simple = value.matches("^[a-z0-9._%+-]+@[a-z0-9.-]+\\\\.[a-z]{2,}$");
                        boolean name = value.matches("^[A-Za-z]+(?:\\\\s+[A-Za-z]+)*$");
                        String prefix = value.replaceFirst("^([^#0,.']*('[^']*')?)*", "$0-");
                        String tail = value.replaceAll("([^,]*,?)*;", "");
                        return words && any && version && possessive && atomic && simple && name;
                    }
                }
                """;

        // Повтор внутри повтора, который делится на части несколькими способами; (\\d+\\.)+ и (\\s+[A-Za-z]+)*
        // однозначны, притяжательный квантификатор и атомарная группа возвратов не делают, а поиск с вложенным
        // повтором в самом конце шаблона удается сразу
        assertThat(RuleTests.lines(new CatastrophicRegexRule(), code)).containsExactlyInAnyOrder(3, 6, 7, 14);
    }

    @Test
    void templateInjection() {
        Map<String, String> files = Map.of(
                "TemplateController.java", """
                        @RestController
                        class TemplateController {
                            @PostMapping("/preview")
                            String preview(@RequestParam String body) throws Exception {
                                Template template = new Template("preview", new StringReader(body), configuration);
                                Template fixed = new Template("fixed", new StringReader(FIXED_TEMPLATE), configuration);
                                return render(template) + render(fixed);
                            }
                            @PostMapping("/velocity")
                            String velocity(@RequestParam String text) {
                                Velocity.evaluate(context, writer, "preview", text);
                                return writer.toString();
                            }
                        }
                        """,
                "PageController.java", """
                        @Controller
                        class PageController {
                            @GetMapping("/page")
                            String page(@RequestParam String section) {
                                return "pages/" + section;
                            }
                            @GetMapping("/home")
                            String home() {
                                return "home";
                            }
                        }
                        """);

        // Текст шаблона и имя представления из запроса - находка; шаблон из константы и постоянное имя - нет
        assertThat(RuleTests.checkProject(new TemplateInjectionRule(), files))
                .extracting(violation -> violation.file().getFileName() + ":" + violation.line())
                .containsExactlyInAnyOrder("TemplateController.java:5", "TemplateController.java:11",
                        "PageController.java:5");
    }

    @Test
    void mockitoMisuse() {
        Map<String, String> files = Map.of(
                "OrderServiceTest.java", """
                        import static org.mockito.Mockito.verify;
                        class OrderServiceTest {
                            @Mock
                            private OrderRepository repository;
                            @Test
                            void saves() {
                                service.save(order);
                                verify(repository);
                                verify(repository).save(order);
                            }
                        }
                        """,
                "PaymentServiceTest.java", """
                        @ExtendWith(MockitoExtension.class)
                        class PaymentServiceTest {
                            @Mock
                            private PaymentGateway gateway;
                            @Test
                            void charges() {
                                Order order = new Order();
                                Order spied = spy(new Order());
                                when(order.total()).thenReturn(BigDecimal.TEN);
                                when(spied.total()).thenReturn(BigDecimal.ONE);
                                when(gateway.charge(order)).thenReturn(true);
                                verify(gateway, times(1)).charge(order);
                            }
                        }
                        """,
                "BaseTest.java", """
                        @ExtendWith(MockitoExtension.class)
                        abstract class BaseTest {
                        }
                        """,
                "InvoiceServiceTest.java", """
                        class InvoiceServiceTest extends BaseTest {
                            @Mock
                            private InvoiceRepository repository;
                            @Test
                            void issues() {
                                verify(repository).save(invoice);
                            }
                        }
                        """,
                "ReportTest.java", """
                        @SpringBootTest
                        class ReportTest {
                            @Mock
                            private Clock clock;
                        }
                        """,
                "PhoneHomeTest.java", """
                        import static com.github.tomakehurst.wiremock.client.WireMock.verify;
                        class PhoneHomeTest {
                            @Mock
                            private Node node;
                            @Before
                            public void setUp() {
                                node = mock(Node.class);
                            }
                            @Test
                            public void pings() {
                                verify(1, postRequestedFor(urlPathEqualTo("/ping")));
                                verify(node);
                            }
                        }
                        """);

        // Расширение у базового класса, контекст Spring и присваивание в setUp заполняют моки; verify с методом,
        // verify из WireMock и when у spy - верно
        assertThat(RuleTests.checkProject(new MockitoMisuseRule(), files))
                .extracting(violation -> violation.file().getFileName() + ":" + violation.line())
                .containsExactlyInAnyOrder("OrderServiceTest.java:3", "OrderServiceTest.java:8",
                        "PaymentServiceTest.java:9");
    }

    @Test
    void httpClientPerCall() {
        String code = """
                @Service
                class RatesClient {
                    private final RestTemplate shared = new RestTemplate();
                    private WebClient lazy;
                    BigDecimal rate(String currency) {
                        RestTemplate restTemplate = new RestTemplate();
                        return restTemplate.getForObject("/rates/" + currency, BigDecimal.class);
                    }
                    String fetch(URI uri) throws Exception {
                        HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
                        return client.send(HttpRequest.newBuilder(uri).build(), BodyHandlers.ofString()).body();
                    }
                    WebClient webClient() {
                        if (lazy == null) {
                            lazy = WebClient.create("https://rates.example.com");
                        }
                        return lazy;
                    }
                    RestTemplate restTemplate() {
                        return new RestTemplate();
                    }
                    @Bean
                    RestClient restClient() {
                        RestClient client = RestClient.create();
                        return client;
                    }
                }
                """;

        // Поле, ленивое создание в поле, фабрика и @Bean создают клиент один раз
        assertThat(RuleTests.lines(new HttpClientPerCallRule(), code)).containsExactly(6, 10);
    }

    @Test
    void httpClientPerCallIgnoresSameNamedClassesOfOtherLibraries() {
        String code = """
                import com.hazelcast.spi.utils.RestClient;
                import org.springframework.web.client.RestTemplate;
                class KubernetesClient {
                    String token(String url) {
                        return RestClient.create(url).get().getBody();
                    }
                    String status(String url) {
                        RestTemplate template = new RestTemplate();
                        return template.getForObject(url, String.class);
                    }
                }
                """;

        // RestClient у Hazelcast - обертка над HttpURLConnection без пула, RestTemplate - из Spring
        assertThat(RuleTests.lines(new HttpClientPerCallRule(), code)).containsExactly(8);
    }
}
