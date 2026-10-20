package ru.akvine.zond.rules;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import ru.akvine.zond.rules.concurrency.FutureGetInLoopRule;
import ru.akvine.zond.rules.performance.BlockingCallInReactiveRule;
import ru.akvine.zond.rules.performance.DeepOffsetPaginationRule;
import ru.akvine.zond.rules.performance.ExplicitGcRule;
import ru.akvine.zond.rules.performance.ExpensiveLogArgumentRule;
import ru.akvine.zond.rules.performance.FunctionOnColumnInWhereRule;
import ru.akvine.zond.rules.performance.HttpCallInLoopRule;
import ru.akvine.zond.rules.performance.ListContainsInLoopRule;
import ru.akvine.zond.rules.performance.ListLoadedForCountRule;
import ru.akvine.zond.rules.performance.MapperPerCallRule;
import ru.akvine.zond.rules.performance.MissingReadOnlyTransactionRule;
import ru.akvine.zond.rules.performance.OrderByRandomRule;
import ru.akvine.zond.rules.performance.StreamQueryWithoutFetchSizeRule;
import ru.akvine.zond.rules.security.DebugSettingsInConfigRule;

import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Правила jr:427 и далее: скорость - сеть, база данных, реактивный код и логи
 */
class SpeedRulesTest {
    @TempDir
    Path dir;

    @Test
    void httpCallInLoop() {
        String code = """
                @Service
                class Orders {
                    private final RestTemplate restTemplate;
                    private final WebClient webClient;
                    void enrich(List<Order> orders) {
                        for (Order order : orders) {
                            order.setPrice(restTemplate.getForObject("/price/" + order.getId(), BigDecimal.class));
                            order.setRate(rate(order));
                        }
                        Flux.fromIterable(orders)
                                .flatMap(order -> webClient.get().uri("/x").retrieve().bodyToMono(String.class));
                        for (Order order : orders) {
                            webClient.get().uri("/x").retrieve().bodyToMono(String.class).block();
                        }
                        for (int attempt = 0; attempt < 3; attempt++) {
                            restTemplate.getForObject("/ping", String.class);
                        }
                        restTemplate.getForObject("/once", String.class);
                    }
                    private BigDecimal rate(Order order) {
                        return restTemplate.getForObject("/rate", BigDecimal.class);
                    }
                }
                """;

        // Запрос на каждый заказ - прямо и через rate(); асинхронный WebClient, попытки и одиночный вызов - нет
        assertThat(RuleTests.lines(new HttpCallInLoopRule(), code)).containsExactly(7, 8, 13);
    }

    @Test
    void networkCallsInWaitingAndPagingLoops() {
        String code = """
                class Storage {
                    private final RestTemplate restTemplate;
                    private final OSS ossClient;
                    void run(List<String> keys) throws Exception {
                        String state = status();
                        while (WAITING.contains(state)) {
                            TimeUnit.SECONDS.sleep(10);
                            state = restTemplate.getForObject("/status", String.class);
                        }
                        String continuationToken = null;
                        do {
                            ListObjectsV2Result result = ossClient.listObjectsV2(request(continuationToken));
                            continuationToken = result.getNextContinuationToken();
                        } while (continuationToken != null);
                        for (HttpMessageConverter<?> converter : restTemplate.getMessageConverters()) {
                            restTemplate.getMessageConverters().remove(converter);
                        }
                        for (String key : keys) {
                            ossClient.deleteObject(bucket, key);
                        }
                    }
                }
                """;

        // Ожидание статуса, листинг по токену и настройка клиента - не запрос на элемент; удаление по одному - да
        assertThat(RuleTests.lines(new HttpCallInLoopRule(), code)).containsExactly(19);
    }

    @Test
    void configurationAndCustomerClientAreNotNetwork() {
        String code = """
                @Configuration
                class AuthorizationServerConfig {
                    void configure(ClientDetailsServiceConfigurer clients) {
                        for (OauthClient oauthClient : registeredClients) {
                            ClientBuilder withClient = clients.inMemory().withClient(oauthClient.getId());
                            withClient.additionalInformation(oauthClient.getInfo());
                        }
                    }
                }
                class Meetings {
                    void attend(List<Client> clients, Long groupId) {
                        for (Client client : clients) {
                            if (client.isChildOfGroup(groupId)) {
                                log(client);
                            }
                        }
                    }
                }
                """;

        // Построитель настроек при запуске и проверка свойства клиента-покупателя - не сетевые вызовы
        assertThat(RuleTests.lines(new HttpCallInLoopRule(), code)).isEmpty();
    }

    @Test
    void blockingCallInReactiveCode() {
        String code = """
                import reactor.core.publisher.Flux;
                import reactor.core.publisher.Mono;

                @RestController
                class Users {
                    private final UserRepository userRepository;
                    private final ReactiveOrderRepository orderRepository;
                    private final RestTemplate restTemplate;
                    private final WebClient webClient;

                    @GetMapping("/users/{id}")
                    Mono<User> get(@PathVariable Long id) {
                        User user = userRepository.findById(id).orElseThrow();
                        return Mono.just(user);
                    }
                    Mono<String> load(String id) {
                        return webClient.get().uri("/x").retrieve().bodyToMono(String.class)
                                .map(body -> restTemplate.getForObject("/y", String.class))
                                .doOnNext(body -> log(body));
                    }
                    Flux<Order> orders(Flux<Long> ids) {
                        return ids.flatMap(id -> orderRepository.findById(id));
                    }
                    Mono<String> offloaded() {
                        return Mono.fromCallable(() -> restTemplate.getForObject("/z", String.class))
                                .subscribeOn(Schedulers.boundedElastic());
                    }
                    Mono<String> waits() {
                        return Mono.just("a").map(a -> webClient.get().uri("/w").retrieve().bodyToMono(String.class).block());
                    }
                    String mvc() {
                        return webClient.get().uri("/m").retrieve().bodyToMono(String.class).block();
                    }
                }
                interface ReactiveOrderRepository extends ReactiveCrudRepository<Order, Long> {
                }
                """;

        // JPA в методе, который возвращает Mono, RestTemplate и block() в операторах; реактивный репозиторий,
        // вызов в boundedElastic и block() в обычном методе - нет
        assertThat(RuleTests.lines(new BlockingCallInReactiveRule(), code)).containsExactly(13, 18, 29);
    }

    @Test
    void missingReadOnlyTransaction() {
        String code = """
                import org.mapstruct.Mapper;

                @Service
                class OrderService {
                    private final OrderRepository orderRepository;
                    private final AuditService auditService;
                    private final OrderMapper orderMapper;

                    @Transactional
                    public OrderDto find(Long id) {
                        return orderMapper.toDto(orderRepository.findById(id).orElseThrow());
                    }
                    @Transactional
                    public void rename(Long id, String name) {
                        Order order = orderRepository.findById(id).orElseThrow();
                        order.setName(name);
                    }
                    @Transactional
                    public List<OrderDto> report() {
                        auditService.record("report");
                        return orderRepository.findAll().stream().map(orderMapper::toDto).toList();
                    }
                    @Transactional(readOnly = true)
                    public long count() {
                        return orderRepository.count();
                    }
                    @Transactional
                    public Order lockForPay(Long id) {
                        return orderRepository.findByIdForUpdate(id);
                    }
                    @Transactional
                    public List<Order> active() {
                        return loadActive();
                    }
                    private List<Order> loadActive() {
                        return orderRepository.findAllByActiveTrue();
                    }
                    @Transactional(propagation = Propagation.REQUIRES_NEW)
                    public List<Order> fresh() {
                        return orderRepository.findAll();
                    }
                }
                @Service
                class AuditService {
                    private final AuditRepository auditRepository;
                    public void record(String action) {
                        auditRepository.save(new Audit(action));
                    }
                }
                @Mapper
                interface OrderMapper {
                    OrderDto toDto(Order order);
                }
                """;

        // Чтение с маппером MapStruct и чтение через свой метод; сеттер сущности, запись в другом сервисе,
        // блокировка, readOnly и REQUIRES_NEW - нет
        assertThat(RuleTests.lines(new MissingReadOnlyTransactionRule(), code)).containsExactly(10, 32);
    }

    @Test
    void reactiveCodeWithOwnStreamsAndChatClients() {
        String code = """
                import reactor.core.publisher.Flux;

                class Chats {
                    Flux<String> send(UUID chatId, JsonNode request) {
                        return aiService.processStream(chatId, (client, token) -> client.sendChatMessage(chatId, request, token));
                    }
                    Flux<String> stream(String input) {
                        ChatClient chatClient = ChatClient.builder(model).build();
                        return chatClient.prompt(input).stream().content();
                    }
                }
                """;

        // Лямбду получил свой метод; ChatClient по одному имени - не блокирующий HTTP-клиент
        assertThat(RuleTests.lines(new BlockingCallInReactiveRule(), code)).isEmpty();
    }

    @Test
    void deleteWithReturningIsWrite() {
        String code = """
                @Repository
                class AttributeDao {
                    private final JdbcTemplate jdbcTemplate;
                    @Transactional
                    public List<Map<String, Object>> purge(UUID entityId) {
                        return jdbcTemplate.queryForList("DELETE FROM attribute_kv WHERE entity_id = ? RETURNING attribute_key", entityId);
                    }
                    @Transactional
                    public List<Map<String, Object>> removeAllByEntityId(UUID entityId) {
                        return jdbcTemplate.queryForList("SELECT * FROM attribute_kv WHERE entity_id = ?", entityId);
                    }
                }
                """;

        // DELETE через queryForList и метод с пишущим именем - не чтение
        assertThat(RuleTests.lines(new MissingReadOnlyTransactionRule(), code)).isEmpty();
    }

    @Test
    void readOnlyNotSuggestedForLocksAndEntityChanges() {
        String code = """
                @Service
                class StockService {
                    private final ProductRepository productRepository;
                    private final JdbcTemplate jdbcTemplate;
                    private final AlertDao alertDao;

                    @Transactional
                    public void reserveOptimistic(String sku, int quantity) {
                        Product product = productRepository.findById(sku).orElseThrow();
                        product.take(quantity);
                    }
                    @Transactional
                    public Order findByIdForUpdate(long id) {
                        return jdbcTemplate.queryForObject("SELECT * FROM orders WHERE id = ?", mapper, id);
                    }
                    @Transactional
                    public Order lock(long id) {
                        return jdbcTemplate.queryForObject("SELECT * FROM orders WHERE id = ? FOR UPDATE", mapper, id);
                    }
                    /**
                     * Транзакция нужна, чтобы запрос ушел на master, а не на реплику
                     */
                    @Transactional
                    public List<Alert> fetchPending(int offset) {
                        return alertDao.listPendingAlerts(offset);
                    }
                    @Transactional
                    public Product show(String sku) {
                        Product product = productRepository.findById(sku).orElseThrow();
                        product.getName();
                        return product;
                    }
                }
                """;

        // Изменение сущности своим методом, блокировка, маршрутизация на ведущую базу - нет; чтение с геттером - да
        assertThat(RuleTests.lines(new MissingReadOnlyTransactionRule(), code)).containsExactly(28);
    }

    @Test
    void inMemoryRepositoryIsNotStorage() {
        String code = """
                class LoadProtection {
                    private final IWorkflowRepository workflowRepository;
                    private final OrderRepository orderRepository;
                    boolean overloaded() {
                        return workflowRepository.getAll().size() > 100
                                || orderRepository.findAllByStatus(Status.NEW).size() > 100;
                    }
                }
                interface IWorkflowRepository {
                    List<Workflow> getAll();
                }
                interface OrderRepository extends JpaRepository<Order, Long> {
                    List<Order> findAllByStatus(Status status);
                }
                """;

        // Кеш в памяти - не таблица; репозиторий Spring Data - да
        assertThat(RuleTests.lines(new ListLoadedForCountRule(), code)).containsExactly(6);
    }

    @Test
    void expensiveLogArgument() {
        String code = """
                class Orders {
                    void save(Order order, List<Item> items) throws Exception {
                        log.debug("order {}", objectMapper.writeValueAsString(order));
                        log.trace("ids {}", items.stream().map(Item::getId).collect(Collectors.toList()));
                        log.info("order {}", objectMapper.writeValueAsString(order));
                        if (log.isDebugEnabled()) {
                            log.debug("order {}", objectMapper.writeValueAsString(order));
                        }
                        log.atDebug().addArgument(() -> objectMapper.writeValueAsString(order)).log("order {}");
                        log.debug("order {}", order.getId());
                    }
                }
                """;

        // Сериализация и стрим в выключенном уровне; info включен, проверка уровня и ленивый аргумент - нет
        assertThat(RuleTests.lines(new ExpensiveLogArgumentRule(), code)).containsExactly(3, 4);
    }

    @Test
    void deepOffsetPagination() {
        String code = """
                class Exporter {
                    void exportAll() {
                        int page = 0;
                        Page<User> users;
                        do {
                            users = userRepository.findAll(PageRequest.of(page++, 500));
                            write(users.getContent());
                        } while (users.hasNext());
                        for (int i = 0; i < total; i++) {
                            List<User> chunk = userRepository.findAll(PageRequest.of(i, 100)).getContent();
                        }
                        Pageable pageable = PageRequest.of(0, 100);
                        Page<Order> orders = orderRepository.findAll(pageable);
                        while (orders.hasNext()) {
                            orders = orderRepository.findAll(orders.nextPageable());
                        }
                        int offset = 0;
                        while (true) {
                            List<Row> rows = jdbcTemplate.query("SELECT * FROM t ORDER BY id LIMIT ? OFFSET ?", mapper, 100, offset);
                            if (rows.isEmpty()) break;
                            offset += 100;
                        }
                        Long lastId = 0L;
                        while (true) {
                            List<Row> rows = jdbcTemplate.query("SELECT * FROM t WHERE id > ? ORDER BY id LIMIT ?", mapper, lastId, 100);
                            if (rows.isEmpty()) break;
                            lastId = rows.get(rows.size() - 1).getId();
                        }
                        Page<User> first = userRepository.findAll(PageRequest.of(0, 20));
                        for (Batch batch : batches) {
                            userRepository.findAll(PageRequest.of(0, 50));
                        }
                    }
                }
                """;

        // Номер страницы и смещение растут в цикле; keyset, одна страница и всегда первая страница - нет
        assertThat(RuleTests.lines(new DeepOffsetPaginationRule(), code)).containsExactly(6, 10, 15, 19);
    }

    @Test
    void xmlFactoriesPerCall() {
        String code = """
                class Invoices {
                    private static final JAXBContext CONTEXT = createContext();
                    String write(Invoice invoice) throws Exception {
                        JAXBContext context = JAXBContext.newInstance(Invoice.class);
                        DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
                        return marshal(context, invoice);
                    }
                    @Bean
                    TransformerFactory transformerFactory() {
                        return TransformerFactory.newInstance();
                    }
                }
                """;

        // Контекст JAXB и фабрика XML в обычном методе; в @Bean - один раз
        assertThat(RuleTests.lines(new MapperPerCallRule(), code)).containsExactly(4, 5);
    }

    @Test
    void listLoadedForCount() {
        String code = """
                class Orders {
                    void check(Long ownerId, Status status) {
                        int active = orderRepository.findAllByStatus(status).size();
                        if (orderRepository.findByOwnerId(ownerId).isEmpty()) {
                            throw new NotFoundException();
                        }
                        List<Order> pending = orderRepository.findByStatus(Status.PENDING);
                        if (!pending.isEmpty()) {
                            log.info("pending {}", pending.size());
                        }
                        List<Order> recent = orderRepository.findByCreatedAfter(yesterday);
                        if (!recent.isEmpty()) {
                            notify(recent);
                        }
                        long all = orderRepository.findAll().stream().count();
                        Order order = orderRepository.findById(1L).orElseThrow();
                        int items = order.getItems().size();
                    }
                }
                """;

        // Список только ради числа или проверки наличия; список, который отдают дальше, и findById - нет
        assertThat(RuleTests.lines(new ListLoadedForCountRule(), code)).containsExactly(3, 4, 7, 15);
    }

    @Test
    void streamQueryWithoutFetchSize() {
        String code = """
                interface OrderRepository extends JpaRepository<Order, Long> {
                    Stream<Order> streamAllByStatus(Status status);
                    @QueryHints(@QueryHint(name = HINT_FETCH_SIZE, value = "500"))
                    Stream<Order> streamAllByOwner(Long owner);
                    List<Order> findAllByStatus(Status status);
                }
                class Export {
                    void run() {
                        jdbcTemplate.queryForStream("SELECT * FROM orders", mapper).forEach(this::write);
                    }
                }
                """;
        List<String> withoutSetting = new ProjectFixture(dir.resolve("plain"))
                .source("OrderRepository.java", code)
                .lines(new StreamQueryWithoutFetchSizeRule());
        List<String> withSetting = new ProjectFixture(dir.resolve("configured"))
                .source("OrderRepository.java", code)
                .write("src/main/resources/application.yml", """
                        spring:
                          jpa:
                            properties:
                              hibernate.jdbc.fetch_size: 500
                        """)
                .lines(new StreamQueryWithoutFetchSizeRule());

        // Stream без подсказки и queryForStream; с подсказкой и с настройкой на все приложение - нет
        assertThat(withoutSetting).containsExactly("OrderRepository.java:2", "OrderRepository.java:9");
        assertThat(withSetting).isEmpty();
    }

    @Test
    void hibernateStatisticsInProduction() {
        DebugSettingsInConfigRule rule = new DebugSettingsInConfigRule();
        List<String> lines = new ProjectFixture(dir)
                .write("src/main/resources/application.yml", """
                        spring:
                          jpa:
                            properties:
                              hibernate:
                                generate_statistics: true
                        """)
                .write("src/main/resources/application-dev.yml", """
                        spring:
                          jpa:
                            properties:
                              hibernate.generate_statistics: true
                        """)
                .context().configFiles().stream()
                .flatMap(file -> rule.checkConfig(file).stream())
                .map(violation -> violation.file().getFileName() + ":" + violation.line())
                .toList();

        // В рабочих настройках - находка, в профиле разработки - нет
        assertThat(lines).containsExactly("application.yml:5");
    }

    @Test
    void kafkaSendAwaitedInLoop() {
        String code = """
                class Publisher {
                    void publish(List<Event> events) throws Exception {
                        for (Event event : events) {
                            kafkaTemplate.send("events", event).get();
                        }
                        for (Event event : events) {
                            var result = producer.send(new ProducerRecord<>("events", event));
                            result.get();
                        }
                        List<CompletableFuture<?>> sent = new ArrayList<>();
                        for (Event event : events) {
                            sent.add(kafkaTemplate.send("events", event));
                        }
                        for (CompletableFuture<?> future : sent) {
                            future.get();
                        }
                        for (Event event : events) {
                            mailService.send(event).get();
                        }
                    }
                }
                """;

        // Ожидание подтверждения каждого сообщения; сначала отправить все, потом ждать - нормально
        assertThat(RuleTests.lines(new FutureGetInLoopRule(), code)).containsExactly(4, 8);
    }

    @Test
    void mapValueSearchInLoop() {
        String code = """
                class Matcher {
                    private final Map<Long, String> names = new HashMap<>();
                    void match(List<String> candidates) {
                        for (String candidate : candidates) {
                            if (names.values().contains(candidate)) {
                                log(candidate);
                            }
                            if (names.containsValue(candidate)) {
                                log(candidate);
                            }
                            if (names.containsKey(1L)) {
                                log(candidate);
                            }
                        }
                        boolean once = names.containsValue("x");
                    }
                }
                """;

        // Поиск по значениям в цикле; поиск по ключу и одиночный поиск - нет
        assertThat(RuleTests.lines(new ListContainsInLoopRule(), code)).containsExactly(5, 8);
    }

    @Test
    void explicitGc() {
        String code = """
                class Cleaner {
                    void free() {
                        cache.clear();
                        System.gc();
                        Runtime.getRuntime().gc();
                        collector.gc();
                    }
                    public void contextDestroyed(ServletContextEvent event) {
                        System.gc();
                    }
                }
                """;

        // При остановке приложения пауза никому не мешает
        assertThat(RuleTests.lines(new ExplicitGcRule(), code)).containsExactly(4, 5);
    }

    @Test
    void orderByRandom() {
        List<String> lines = new ProjectFixture(dir)
                .source("Repo.java", """
                        interface UserRepository {
                            @Query(value = "SELECT * FROM users ORDER BY RAND() LIMIT 5", nativeQuery = true)
                            List<User> random();
                            @Query("SELECT u FROM User u ORDER BY u.name")
                            List<User> sorted();
                        }
                        """)
                .write("src/main/resources/mapper/UserMapper.xml", """
                        <?xml version="1.0" encoding="UTF-8"?>
                        <mapper namespace="org.demo.UserMapper">
                            <select id="random" resultType="User">
                                SELECT * FROM users
                                ORDER BY random()
                                LIMIT 3
                            </select>
                        </mapper>
                        """)
                .lines(new OrderByRandomRule());

        // Запрос в аннотации и в маппере MyBatis; обычная сортировка - нет
        assertThat(lines).containsExactly("Repo.java:2", "UserMapper.xml:3");
    }

    @Test
    void functionOnColumnInWhere() {
        List<String> lines = new ProjectFixture(dir)
                .source("Repo.java", """
                        interface UserRepository {
                            @Query("SELECT u FROM User u WHERE lower(u.email) = lower(:email)")
                            User byEmail(String email);
                            @Query(value = "SELECT * FROM orders WHERE DATE(created_at) = :day", nativeQuery = true)
                            List<Order> byDay(LocalDate day);
                            @Query("SELECT u FROM User u WHERE u.email = lower(:email)")
                            User exact(String email);
                            @Query(value = "SELECT * FROM users WHERE UPPER(name) LIKE :name", nativeQuery = true)
                            List<User> byName(String name);
                            @Query(value = "SELECT * FROM events e WHERE LOWER(e.action) LIKE LOWER(CONCAT('%', :text, '%'))", nativeQuery = true)
                            List<Event> search(String text);
                            @Query(value = "SELECT * FROM rules r WHERE CAST(r.config AS text) LIKE :text", nativeQuery = true)
                            List<Rule> byConfig(String text);
                            @Query(value = "SELECT * FROM idx WHERE COALESCE(idx.system_required, 'N') = 'N'", nativeQuery = true)
                            List<Index> userIndexes();
                        }
                        """)
                .write("src/main/resources/db/migration/V1__init.sql", """
                        CREATE TABLE users (id BIGINT PRIMARY KEY, email VARCHAR(255), name VARCHAR(255));
                        CREATE INDEX idx_users_email_lower ON users (lower(email));
                        """)
                .lines(new FunctionOnColumnInWhereRule());

        // Для lower(email) есть индекс по выражению; функция над параметром, поиск подстроки и COALESCE
        // над флагом индексу не мешают
        assertThat(lines).containsExactly("Repo.java:4", "Repo.java:8");
    }
}
