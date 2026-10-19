package ru.akvine.zond.rules;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import ru.akvine.zond.models.Violation;
import ru.akvine.zond.rules.codesmell.UnusedCodeRule;
import ru.akvine.zond.rules.concurrency.MutableStateInSingletonBeanRule;
import ru.akvine.zond.rules.concurrency.NonThreadSafeCollectionInBeanRule;
import ru.akvine.zond.rules.concurrency.PutIfAbsentWithLogicRule;
import ru.akvine.zond.rules.concurrency.SwallowedInterruptRule;
import ru.akvine.zond.rules.exceptions.LostExceptionCauseRule;
import ru.akvine.zond.rules.logical.IndexOutOfBoundsRule;
import ru.akvine.zond.rules.logical.MessageBundleMismatchRule;
import ru.akvine.zond.rules.logical.NullInsteadOfEmptyCollectionRule;
import ru.akvine.zond.rules.logical.PossibleNullDereferenceRule;
import ru.akvine.zond.rules.performance.LoggingInLoopRule;
import ru.akvine.zond.rules.performance.RelationSizeInLoopRule;
import ru.akvine.zond.rules.performance.RepositoryCallInLoopRule;
import ru.akvine.zond.rules.performance.StreamInLoopRule;
import ru.akvine.zond.rules.security.ComposeSecretInEnvironmentRule;
import ru.akvine.zond.rules.security.HardcodedCredentialsRule;
import ru.akvine.zond.rules.security.InsecureHttpUrlRule;
import ru.akvine.zond.rules.security.PathTraversalRule;
import ru.akvine.zond.rules.security.SecretInConfigRule;
import ru.akvine.zond.rules.security.SsrfRule;
import ru.akvine.zond.rules.security.WeakHashRule;

import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Ложные срабатывания, найденные на JeecgBoot, Apache DolphinScheduler, Apereo CAS и XXL-JOB. Каждый случай
 * проверяется рядом с похожим кодом, о котором правило по-прежнему сообщает.
 */
class NewProjectsFalsePositivesTest {
    @TempDir
    Path dir;

    @Test
    void templateValuesAreNotSecrets() {
        SecretInConfigRule rule = new SecretInConfigRule();
        List<String> lines = new ProjectFixture(dir)
                .write("src/main/resources/application-prod.yml", """
                        justauth:
                          github:
                            client-secret: ??
                        ai:
                          apiKey: sk-??
                        security:
                          password: "{cas-cipher}d7H36d2JryjjaOq8HSN9Iw=="
                        druid:
                          loginPassword: 123456
                        """)
                .context().configFiles().stream()
                .flatMap(file -> rule.checkConfig(file).stream())
                .map(violation -> violation.file().getFileName() + ":" + violation.line())
                .toList();

        // Заглушки "??" и "sk-??" и зашифрованное значение - не секреты; пароль из цифр - секрет
        assertThat(lines).containsExactly("application-prod.yml:9");
    }

    @Test
    void placeholderAddressesAreNotExternal() {
        InsecureHttpUrlRule rule = new InsecureHttpUrlRule();
        List<String> lines = new ProjectFixture(dir)
                .write("src/main/resources/application-prod.yml", """
                        justauth:
                          github:
                            redirect-uri: http://sso.test.com:8080/jeecg-boot/sys/thirdLogin/github/callback
                        hessian:
                          credit:
                            return: http://credit.return.url
                        jeecg:
                          file-view-domain: http://fileview.jeecg.com
                        """)
                .context().configFiles().stream()
                .flatMap(file -> rule.checkConfig(file).stream())
                .map(violation -> violation.file().getFileName() + ":" + violation.line())
                .toList();

        // Заглушки вместо адреса - не внешний сервер; настоящий домен по http - внешний
        assertThat(lines).containsExactly("application-prod.yml:8");
    }

    @Test
    void inputErrorWithoutCauseConstructor() {
        String code = """
                class CronExpression {
                    void parse(String s, int i) throws ParseException {
                        try {
                            if (Integer.parseInt(s) > 5) {
                                throw new Exception();
                            }
                        } catch (Exception e) {
                            throw new ParseException("A numeric value between 1 and 5 must follow the '#' option", i);
                        }
                        try {
                            load(s);
                        } catch (Exception e) {
                            throw new IllegalStateException("Illegal cron expression " + s);
                        }
                    }
                }
                """;

        // У ParseException нет конструктора с причиной, и сообщает оно о неверном вводе
        assertThat(RuleTests.lines(new LostExceptionCauseRule(), code)).containsExactly(13);
    }

    @Test
    void nullInputGivesNullOutput() {
        String code = """
                class Masks {
                    Map<String, Object> mask(Map<String, Object> source) {
                        if (source == null) {
                            return null;
                        }
                        return new HashMap<>(source);
                    }
                    static <T> byte[] toJson(T obj) {
                        if (obj == null) return null;
                        return write(obj);
                    }
                    String[] codes(String[] texts) {
                        if (texts == null || texts.length == 0) {
                            return null;
                        }
                        return texts;
                    }
                }
                """;

        // На null - null, а пустой массив на входе превращается в null на выходе
        assertThat(RuleTests.lines(new NullInsteadOfEmptyCollectionRule(), code)).containsExactly(14);
    }

    @Test
    void threadStopsOnInterrupt() {
        String code = """
                class Application {
                    public static void main(String[] args) {
                        while (true) {
                            try {
                                TimeUnit.HOURS.sleep(1);
                            } catch (InterruptedException e) {
                                break;
                            }
                        }
                    }
                    void serve() {
                        Thread thread = new Thread(new Runnable() {
                            @Override
                            public void run() {
                                try {
                                    future.channel().closeFuture().sync();
                                } catch (InterruptedException e) {
                                    logger.info("server stop");
                                }
                            }
                        });
                    }
                    void destroy() {
                        try {
                            oldJobThread.join();
                        } catch (InterruptedException e) {
                            logger.error("join error", e);
                        }
                    }
                }
                """;

        // Поток заканчивает работу в ответ на прерывание; destroy() продолжает работу, а флаг потерян
        assertThat(RuleTests.lines(new SwallowedInterruptRule(), code)).containsExactly(26);
    }

    @Test
    void consistentHashingIsNotSecurity() {
        String code = """
                class ExecutorRouteConsistentHash {
                    long hash(String key) throws Exception {
                        MessageDigest md5 = MessageDigest.getInstance("MD5");
                        return md5.digest(key.getBytes())[0];
                    }
                }
                class Tokens {
                    String sign(String token) { return DigestUtils.md5Hex(token); }
                }
                """;

        // Кольцо хешей для выбора узла - не защита; хеш токена - защита
        assertThat(RuleTests.lines(new WeakHashRule(), code)).containsExactly(8);
    }

    @Test
    void putIfAbsentThenAtomicIncrement() {
        String code = """
                class Counters {
                    private final ConcurrentMap<Integer, AtomicInteger> timeouts = new ConcurrentHashMap<>();
                    private final ConcurrentMap<Integer, Session> sessions = new ConcurrentHashMap<>();
                    void count(int jobId) {
                        AtomicInteger timeoutCount = timeouts.putIfAbsent(jobId, new AtomicInteger(1));
                        if (timeoutCount != null) {
                            timeoutCount.incrementAndGet();
                        }
                        Session session = new Session();
                        Session previous = sessions.putIfAbsent(jobId, session);
                        if (previous == null) {
                            session.init();
                        }
                    }
                }
                """;

        // Счетчик уже лежит в map и обновляется атомарно; новую сессию другие потоки увидят до init()
        assertThat(RuleTests.lines(new PutIfAbsentWithLogicRule(), code)).containsExactly(11);
    }

    @Test
    void batchLoopQueriesOncePerBatch() {
        String code = """
                class WorkflowInstanceService {
                    @Autowired
                    private WorkflowInstanceDao workflowInstanceDao;
                    public void deleteByCode(long code) {
                        while (true) {
                            List<Instance> instances = workflowInstanceDao.queryByWorkflowDefinitionCode(code, 100);
                            if (CollectionUtils.isEmpty(instances)) {
                                break;
                            }
                            log.info("Begin to delete workflow instance, code: {}", code);
                            for (Instance instance : instances) {
                                workflowInstanceDao.deleteById(instance.getId());
                            }
                        }
                    }
                }
                """;

        // Пачка в 100 записей - один запрос и одна запись в лог; удаление каждой записи - N+1
        assertThat(RuleTests.lines(new RepositoryCallInLoopRule(), code)).containsExactly(12);
        assertThat(RuleTests.lines(new LoggingInLoopRule(), code)).isEmpty();
    }

    @Test
    void collectorFunctionRunsForEachElement() {
        String code = """
                class Consumer {
                    @Autowired
                    private MessageDao messageDao;
                    void consume() {
                        while (!stopped) {
                            List<Message> msgs = consumer.poll(pollDuration);
                            Map<Long, Message> byId = msgs.stream()
                                    .collect(Collectors.toMap(m -> m.getId(), m -> messageDao.findById(m.getId())));
                        }
                    }
                }
                """;

        // Цикл событий - не перебор, а функция значения в toMap выполняется для каждого сообщения
        assertThat(RuleTests.lines(new RepositoryCallInLoopRule(), code)).containsExactly(8);
    }

    @Test
    void mapEntryAndEnumConstantsInCollectors() {
        String code = """
                class Profiling {
                    @Transactional
                    Map<String, Integer> sizes(Map<String, List<String>> result, List<Order> orders, List<Converter> generic) {
                        for (Order order : orders) {
                            log(order.getItems().size());
                        }
                        Map<Type, Long> counts = Arrays.stream(Type.values())
                                .collect(Collectors.toMap(Function.identity(), type -> generic.stream().count()));
                        Map<Long, Long> perOrder = orders.stream()
                                .collect(Collectors.toMap(Order::getId, order -> generic.stream().count()));
                        return result.entrySet().stream()
                                .collect(Collectors.toMap(Map.Entry::getKey, en -> en.getValue().size()));
                    }
                }
                """;

        // Значение записи Map - не связь сущности; констант перечисления немного, а заказов - сколько угодно
        assertThat(RuleTests.lines(new RelationSizeInLoopRule(), code)).containsExactly(5);
        assertThat(RuleTests.lines(new StreamInLoopRule(), code)).containsExactly(10);
    }

    @Test
    void cacheKeyPrefixIsNotSecret() {
        String code = """
                interface CommonConstant {
                    String LOGIN_QRCODE_TOKEN = "LQT:";
                    String DEFAULT_PASSWORD = "123456";
                }
                """;

        assertThat(RuleTests.lines(new HardcodedCredentialsRule(), code)).containsExactly(3);
    }

    @Test
    void parsedAddressAndFixedHostAreNotSsrf() {
        String code = """
                class Links {
                    @GetMapping("/callback")
                    String callback(@RequestParam String state, @RequestParam String taskId, @RequestParam String url) throws Exception {
                        URI uri = new URI(state);
                        if (uri.getHost() == null || !uri.getScheme().matches("(?i)https?")) {
                            return "invalid";
                        }
                        HttpRequest request = HttpRequest.newBuilder()
                                .uri(URI.create(baseUrl + "/async-result/" + taskId))
                                .build();
                        restTemplate.getForObject(url, String.class);
                        return "";
                    }
                }
                """;

        // Разбор и проверка адреса - не запрос; узел из настроек с путем от клиента - не SSRF
        assertThat(RuleTests.lines(new SsrfRule(), code)).containsExactly(11);
    }

    @Test
    void fileNameOnlyAndValidatedRequestAreSafe() {
        String code = """
                class Resources {
                    @GetMapping("/download")
                    void download(@RequestParam String path, @RequestParam String other) {
                        String name = new File(path).getName();
                        File file = new File(other);
                    }
                }
                """;

        // От пути взято только имя файла; другой путь используется как есть
        assertThat(RuleTests.lines(new PathTraversalRule(), code)).containsExactly(5);
    }

    @Test
    void searchCheckedByComparisonOrRegex() {
        String code = """
                class Paths {
                    String strip(String requestPath, String orgName, Matcher matcher, String other) {
                        if (requestPath.indexOf(SymbolConstant.AND) > -1) {
                            requestPath = requestPath.substring(0, requestPath.indexOf("&"));
                        }
                        String fileName = orgName.indexOf(".") == -1
                                ? orgName
                                : orgName.substring(0, orgName.lastIndexOf("."));
                        String variable = matcher.group();
                        String inner = variable.substring(variable.indexOf("{") + 1, variable.indexOf("}"));
                        return other.substring(0, other.indexOf("?"));
                    }
                }
                """;

        assertThat(RuleTests.lines(new IndexOutOfBoundsRule(), code)).containsExactly(11);
    }

    @Test
    void packageAnnotationUsesImport() {
        String code = """
                @NullMarked
                package org.apereo.cas.web.cookie;

                import org.jspecify.annotations.NullMarked;
                import org.jspecify.annotations.Nullable;
                """;

        assertThat(RuleTests.lines(new UnusedCodeRule(), code)).containsExactly(5);
    }

    @Test
    void continuedMessageLines() {
        List<String> lines = new ProjectFixture(dir)
                .write("src/main/resources/messages.properties", """
                        screen.welcome.forcedsso=Welcome back, {0}. However, \\
                          you are asked to re-authenticate at {1}.
                        screen.other=Hello {0}
                        """)
                .write("src/main/resources/messages_de.properties", """
                        screen.welcome.forcedsso=Willkommen zurueck, {0}. Bitte bei {1} erneut anmelden.
                        screen.other=Hallo {0} {1}
                        """)
                .lines(new MessageBundleMismatchRule());

        // Значение продолжается на следующей строке через \\: подстановка {1} в нем есть
        assertThat(lines).containsExactly("messages_de.properties:2");
    }

    @Test
    void testInfrastructureIsNotProduction() {
        String compose = """
                services:
                  postgres:
                    image: postgres:16
                    environment:
                      - POSTGRES_PASSWORD=pass
                """;
        List<String> lines = new ProjectFixture(dir)
                .write("ci/tests/grouper/docker-compose.yml", compose)
                .write(".github/workflows/cluster-test/docker-compose-base.yaml", compose)
                .write("deploy/docker-compose.yml", compose)
                .lines(new ComposeSecretInEnvironmentRule());

        assertThat(lines).containsExactly("docker-compose.yml:5");
    }

    @Test
    void defensiveDefaultAndEmptyArgumentsReturnNull() {
        String code = """
                class Cron {
                    TreeSet<Integer> getSet(int type) {
                        switch (type) {
                            case SECOND:
                                return seconds;
                            case MINUTE:
                                return minutes;
                            default:
                                return null;
                        }
                    }
                    static ExecutorBiz getExecutorBiz(String address, Group group) {
                        if (StringTool.isBlank(address) || group == null) {
                            return null;
                        }
                        return cache.get(address);
                    }
                    void use(String address, Group group) {
                        getSet(MINUTE).add(1);
                        getExecutorBiz(address, group).beat();
                    }
                }
                """;

        assertThat(RuleTests.lines(new PossibleNullDereferenceRule(), code)).isEmpty();
    }

    @Test
    void staticPoolOfWebSocketEndpointIsShared() {
        String code = """
                @Component
                @ServerEndpoint("/vxeSocket/{userId}")
                class VxeSocket {
                    private static Map<String, VxeSocket> socketPool = new HashMap<>();
                    private List<String> pages = new ArrayList<>();
                    @OnOpen
                    public void onOpen(Session session) {
                        socketPool.put(session.getId(), this);
                        pages.add(session.getId());
                    }
                }
                """;

        // Свой список у каждого подключения - не гонка; статический пул меняют все подключения сразу
        assertThat(RuleTests.lines(new NonThreadSafeCollectionInBeanRule(), code)).containsExactly(4);
    }

    @Test
    void validatedObjectAndPropertyCheck() {
        String code = """
                class Files {
                    @PostMapping("/download")
                    void download(@RequestBody DownloadRequest request) {
                        DownloadFileDto dto = new DownloadFileDto(request.getPath());
                        downloadFileDtoValidator.validate(dto);
                        File file = new File(dto.getPath());
                    }
                    @PostMapping("/fetch")
                    void fetch(@RequestBody DownloadRequest request) {
                        DownloadFileDto dto = new DownloadFileDto(request.getPath());
                        if ("OK".equals(dto.getStatus())) {
                            File file = new File(dto.getPath());
                        }
                    }
                }
                class DownloadFileDto {
                    private final String path;
                    private String status;
                    DownloadFileDto(String path) { this.path = path; }
                    String getPath() { return path; }
                    String getStatus() { return status; }
                }
                """;

        // Объект целиком отдан валидатору; проверка его статуса путь не проверяет
        assertThat(RuleTests.lines(new PathTraversalRule(), code)).containsExactly(12);
    }

    @Test
    void fieldsChangedOnlyOnStartupAndShutdown() {
        String code = """
                @Component
                class MasterServer {
                    @Autowired
                    private RegistryClient registryClient;
                    @PostConstruct
                    public void run() {
                        registryClient.start();
                        registryClient.setRegistryStoppable(this);
                    }
                }
                @Component
                class RegistryClient implements AutoCloseable {
                    private HeartBeatTask heartBeatTask;
                    private IStoppable stoppable;
                    private boolean flag;
                    private String lastKey;
                    public void start() {
                        heartBeatTask = new HeartBeatTask();
                        flag = true;
                        new Thread(this::loop).start();
                    }
                    public void setRegistryStoppable(IStoppable stoppable) {
                        setStoppable(stoppable);
                    }
                    public void setStoppable(IStoppable stoppable) {
                        this.stoppable = stoppable;
                    }
                    private void loop() {
                        while (flag) {
                            heartBeatTask.beat();
                        }
                    }
                    @Override
                    public void close() {
                        heartBeatTask = null;
                        flag = false;
                    }
                    public void remember(String key) {
                        lastKey = key;
                    }
                    void onRequest() {
                        remember("key");
                    }
                }
                class HeartBeatThread extends Thread {
                    @Override
                    public synchronized void start() {
                        super.start();
                    }
                }
                class Pool {
                    void submit(TaskWorker worker) {
                        executor.submit(worker::start);
                    }
                }
                class RegistryClientTest {
                    @Test
                    void starts() {
                        client.start();
                    }
                }
                """;

        // Запуск из @PostConstruct другого бина (в том числе через цепочку методов) и close() у AutoCloseable;
        // super.start() и worker::start ведут в методы других классов
        // выполняются вне работы; флаг цикла при остановке без volatile поток может не увидеть
        List<Violation> violations = RuleTests.check(new MutableStateInSingletonBeanRule(), code);
        assertThat(violations).extracting(Violation::line).containsExactly(15, 16);
        assertThat(violations.get(0).message()).contains("'close'").doesNotContain("'start'").contains("volatile");
    }

    @Test
    void startupChainAndEventLoopAreNotNPlusOne() {
        String code = """
                @Component
                class AlertServer {
                    @Autowired
                    private AlertPluginManager alertPluginManager;
                    @PostConstruct
                    public void run() {
                        alertPluginManager.start();
                    }
                }
                @Component
                class AlertPluginManager {
                    @Autowired
                    private PluginDao pluginDao;
                    public void start() {
                        installAlertPlugin();
                    }
                    private void installAlertPlugin() {
                        for (PluginDefine define : defines) {
                            pluginDao.addOrUpdatePluginDefine(define);
                        }
                    }
                    public void refresh(List<PluginDefine> defines) {
                        for (PluginDefine define : defines) {
                            pluginDao.addOrUpdatePluginDefine(define);
                        }
                    }
                }
                @Component
                class EventLoop {
                    private final AtomicBoolean runningFlag = new AtomicBoolean(true);
                    @Autowired
                    private AlertDao alertDao;
                    public void loop() {
                        while (runningFlag.get()) {
                            Event event = eventPendingQueue.take();
                            alertDao.save(event);
                        }
                    }
                    public void drain() {
                        while (!queue.isEmpty()) {
                            Event event = queue.take();
                            alertDao.save(event);
                        }
                    }
                }
                """;

        // Установка плагинов из start(), который вызывает @PostConstruct другого бина, - один раз при запуске;
        // цикл событий делает запрос на каждое пришедшее событие. Обновление списка и разбор очереди - N+1
        assertThat(RuleTests.lines(new RepositoryCallInLoopRule(), code)).containsExactly(24, 42);
    }

    @Test
    void sameCallAtStartupAndAtRuntime() {
        String code = """
                @Component
                class Starter {
                    @Autowired private Cache cache;
                    @PostConstruct public void init() { cache.reload(); }
                }
                @Component
                class Refresher {
                    @Autowired private Cache cache;
                    public void onRequest() { cache.reload(); }
                }
                @Component
                class Cache {
                    private Map<String, String> values;
                    public void reload() { values = load(); }
                }
                """;

        // Тот же по тексту вызов во время работы: перезагрузка идет не только при запуске
        assertThat(RuleTests.lines(new MutableStateInSingletonBeanRule(), code)).containsExactly(13);
    }

    @Test
    void webSocketEndpointHasInstancePerConnection() {
        String code = """
                @Component
                @ServerEndpoint("/vxeSocket/{userId}/{pageId}")
                class VxeSocket {
                    private Session session;
                    @OnOpen
                    public void onOpen(Session session) {
                        this.session = session;
                    }
                }
                """;

        assertThat(RuleTests.lines(new MutableStateInSingletonBeanRule(), code)).isEmpty();
    }
}
