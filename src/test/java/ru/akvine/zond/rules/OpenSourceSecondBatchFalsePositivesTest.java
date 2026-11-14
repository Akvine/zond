package ru.akvine.zond.rules;

import org.junit.jupiter.api.Test;
import ru.akvine.zond.enums.Confidence;
import ru.akvine.zond.models.Violation;
import ru.akvine.zond.rules.concurrency.MutableStateInSingletonBeanRule;
import ru.akvine.zond.rules.concurrency.NonThreadSafeCollectionInBeanRule;
import ru.akvine.zond.rules.concurrency.SynchronizedOnBadLockRule;
import ru.akvine.zond.rules.concurrency.ThisEscapeInConstructorRule;
import ru.akvine.zond.rules.concurrency.UnsynchronizedLazyInitRule;
import ru.akvine.zond.rules.concurrency.WaitWithoutTimeoutRule;
import ru.akvine.zond.rules.datetime.LocalDateTimeNowRule;
import ru.akvine.zond.rules.exceptions.LostExceptionCauseRule;
import ru.akvine.zond.rules.logical.IndexOutOfBoundsRule;
import ru.akvine.zond.rules.logical.ListGetFirstWithoutCheckRule;
import ru.akvine.zond.rules.logical.MultipleWritesWithoutTransactionRule;
import ru.akvine.zond.rules.logical.ReferenceEqualityRule;
import ru.akvine.zond.rules.performance.FindAllWithoutPagingRule;
import ru.akvine.zond.rules.performance.ListContainsInLoopRule;
import ru.akvine.zond.rules.performance.RepositoryCallInLoopRule;
import ru.akvine.zond.rules.resources.UnboundedExecutorRule;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Вторая партия ложных срабатываний, найденных прогоном по открытым проектам (halo, apollo, zaproxy, conductor,
 * spring-reactive-sample, commons-lang, newbee-mall и другим) и по рабочим проектам. В каждом тесте рядом с кодом,
 * о котором правило теперь молчит либо говорит с уверенностью «подозрение», стоит код, о котором оно по-прежнему
 * обязано сообщить в полную силу
 */
class OpenSourceSecondBatchFalsePositivesTest {

    @Test
    void setterOfDependencyAndWriteUnderLock() {
        String code = """
                @Service
                class PatService {
                    private Clock clock = Clock.systemUTC();
                    private int attempts;
                    private final Lock lock = new ReentrantLock();
                    private long total;
                    private Object cache;
                    void setClock(Clock clock) {
                        this.clock = clock;
                    }
                    void setAttempts(int attempts) {
                        this.attempts = attempts;
                    }
                    void handle(int value) {
                        setAttempts(value);
                        lock.lock();
                        try {
                            total += value;
                        } finally {
                            lock.unlock();
                        }
                    }
                    @TestOnly
                    void reset() {
                        cache = null;
                    }
                    @VisibleForTesting
                    void refresh() {
                        cache = new Object();
                    }
                }
                """;
        String hook = code.replace("""
                    @VisibleForTesting
                    void refresh() {
                        cache = new Object();
                    }
                """, "");

        // setClock никто не вызывает во время работы, total меняется под замком;
        // setAttempts вызывается из обработки запроса. @TestOnly - метод только для тестов, а @VisibleForTesting
        // ставят и на рабочие методы, которым лишь расширили видимость
        assertThat(RuleTests.lines(new MutableStateInSingletonBeanRule(), code)).containsExactly(4, 7);
        assertThat(RuleTests.lines(new MutableStateInSingletonBeanRule(), hook)).containsExactly(4);
    }

    @Test
    void collectionChangedUnderExplicitLock() {
        String code = """
                @Component
                class Registry {
                    private final StampedLock lock = new StampedLock();
                    private final Map<String, String> routes = new HashMap<>();
                    private final Map<String, String> names = new HashMap<>();
                    void register(String name, String route) {
                        long stamp = lock.writeLock();
                        try {
                            routes.put(name, route);
                        } finally {
                            lock.unlockWrite(stamp);
                        }
                        names.put(route, name);
                    }
                }
                """;

        assertThat(RuleTests.lines(new NonThreadSafeCollectionInBeanRule(), code)).containsExactly(5);
    }

    @Test
    void localTimeOnlyForTextIsSuspicion() {
        String text = """
                class Reports {
                    String title() {
                        return "Report " + LocalDateTime.now().format(FORMAT);
                    }
                    int hour() {
                        LocalDateTime now = LocalDateTime.now();
                        return now.getHour();
                    }
                }
                """;
        String stored = """
                class Tokens {
                    String label() {
                        return LocalDateTime.now().format(FORMAT);
                    }
                    void expire(Token token) {
                        token.setExpiredAt(LocalDateTime.now().plusDays(1));
                    }
                    boolean isExpired(Token token) {
                        LocalDateTime now = LocalDateTime.now();
                        return token.getExpiredAt().isBefore(now);
                    }
                }
                """;

        List<Violation> onlyText = RuleTests.check(new LocalDateTimeNowRule(), text);
        assertThat(onlyText).extracting(Violation::line).containsExactly(3);
        assertThat(onlyText.get(0).confidenceOrDefault()).isEqualTo(Confidence.SUSPICION);
        // Показано место, где время сохраняют, а не первое по порядку
        List<Violation> kept = RuleTests.check(new LocalDateTimeNowRule(), stored);
        assertThat(kept).extracting(Violation::line).containsExactly(6);
        assertThat(kept.get(0).confidenceOrDefault()).isEqualTo(Confidence.PROBABLE);
        assertThat(kept.get(0).message()).contains("еще 2 таких мест");
    }

    @Test
    void lookupByNameFailed() {
        String code = """
                class Handlers {
                    Method find(Class<?> type, String name) {
                        try {
                            return type.getDeclaredMethod(name);
                        } catch (NoSuchMethodException e) {
                            throw new IllegalStateException("destroy method invalid: " + type + "#" + name);
                        }
                    }
                    URL parse(String address) {
                        try {
                            return new URL(address);
                        } catch (MalformedURLException e) {
                            throw new IllegalArgumentException("bad address " + address);
                        }
                    }
                    byte[] read(Path path) {
                        try {
                            return Files.readAllBytes(path);
                        } catch (IOException e) {
                            throw new IllegalStateException("can not read " + path);
                        }
                    }
                }
                """;

        assertThat(RuleTests.lines(new LostExceptionCauseRule(), code)).containsExactly(20);
    }

    @Test
    void actionOnCodeRepositoryIsNotDataAccess() {
        String code = """
                class Builds {
                    private OrderRepository orderRepository;
                    void cloneAll(List<String> urls, File dir) throws Exception {
                        for (String url : urls) {
                            Git.cloneRepository().setURI(url).setDirectory(dir).call();
                        }
                    }
                    void load(List<Long> ids) {
                        for (Long id : ids) {
                            orderRepository.findById(id);
                        }
                    }
                }
                """;

        assertThat(RuleTests.lines(new RepositoryCallInLoopRule(), code)).containsExactly(10);
    }

    @Test
    void reactiveStreamAndSmallDictionary() {
        String code = """
                class Posts {
                    private PostRepository postRepository;
                    private OrderRepository orderRepository;
                    private CardTypeRepository cardTypeRepository;
                    Flux<Post> all() {
                        return postRepository.findAll();
                    }
                    Mono<List<Post>> list() {
                        return postRepository.findAll().collectList();
                    }
                    List<Order> orders() {
                        return orderRepository.findAll();
                    }
                    void warm(Map<String, CardType> cache) {
                        cardTypeRepository.findAll().forEach(type -> cache.put(type.getCode(), type));
                    }
                }
                """;

        // Flux отдает строки потоком; collectList собирает их все в памяти
        List<Violation> violations = RuleTests.check(new FindAllWithoutPagingRule(), code);
        assertThat(violations).extracting(Violation::line).containsExactly(9, 12, 15);
        assertThat(violations).extracting(Violation::confidenceOrDefault)
                .containsExactly(Confidence.PROBABLE, Confidence.PROBABLE, Confidence.SUSPICION);
    }

    @Test
    void lockFieldThatIsNeverReplaced() {
        String code = """
                class Blocks {
                    private Map<String, Long> cache = new HashMap<>();
                    private Map<String, Long> swapped = new HashMap<>();
                    protected Map<String, Long> open = new HashMap<>();
                    void put(String key) {
                        synchronized (cache) {
                            cache.put(key, 1L);
                        }
                        synchronized (swapped) {
                            swapped.put(key, 1L);
                        }
                        synchronized (open) {
                            open.put(key, 1L);
                        }
                    }
                    void reload() {
                        swapped = new HashMap<>();
                    }
                }
                """;

        // cache закрыто и нигде не заменяется; swapped заменяют в reload, а open может заменить наследник
        List<Violation> violations = RuleTests.check(new SynchronizedOnBadLockRule(), code);
        assertThat(violations).extracting(Violation::line).containsExactly(6, 9, 12);
        assertThat(violations).extracting(Violation::confidenceOrDefault)
                .containsExactly(Confidence.SUSPICION, Confidence.PROBABLE, Confidence.PROBABLE);
    }

    @Test
    void thisGivenToOwnHelperOrOwnMethod() {
        String code = """
                class Breaker extends Filter {
                    private final PropertyChangeSupport changes;
                    private final RouterFunction routes;
                    Breaker(Registry registry) {
                        changes = new PropertyChangeSupport(this);
                        setMatcher(this::matches);
                        routes = RouterFunctions.route().GET("/status", this::status).build();
                        registry.register(this);
                        new Thread(this::run).start();
                    }
                }
                class Storage extends Construct {
                    Storage(Construct scope, String id) {
                        super(scope, id);
                        new Bucket(this, "files");
                    }
                }
                """;

        // Помощник в своем поле, свой метод и строитель - подозрение; чужой объект и новый поток - утечка;
        // дерево ресурсов CDK строят именно так
        List<Violation> violations = RuleTests.check(new ThisEscapeInConstructorRule(), code);
        assertThat(violations).extracting(Violation::line).containsExactly(5, 6, 7, 8, 9);
        assertThat(violations).extracting(Violation::confidenceOrDefault).containsExactly(
                Confidence.SUSPICION, Confidence.SUSPICION, Confidence.SUSPICION, Confidence.PROBABLE, Confidence.PROBABLE);
    }

    @Test
    void ownEmptinessCheckOfTheSameList() {
        String code = """
                class Container {
                    private List<String> references = new ArrayList<>();
                    private List<String> others = new ArrayList<>();
                    boolean isEmpty() {
                        return references.isEmpty();
                    }
                    String first() {
                        if (!isEmpty()) {
                            return references.get(0);
                        }
                        return null;
                    }
                    String other() {
                        if (!isEmpty()) {
                            return others.get(0);
                        }
                        return null;
                    }
                }
                """;

        // isEmpty() смотрит в references, о списке others он ничего не говорит
        assertThat(RuleTests.lines(new ListGetFirstWithoutCheckRule(), code)).containsExactly(15);
    }

    @Test
    void sameReferenceIsOnlyShortcut() {
        String code = """
                class Texts {
                    private Long id;
                    int compare(String s1, String s2) {
                        if (s1 == s2) {
                            return 0;
                        }
                        if (s1 == null) {
                            return -1;
                        }
                        return s2 == null ? 1 : s1.compareTo(s2);
                    }
                    Texts withId(Long id) {
                        return this.id == id ? this : new Texts(id);
                    }
                    boolean isOwner(Long userId) {
                        return this.id == userId;
                    }
                    int rank(String s1, String s2) {
                        if (s1 == s2) {
                            return 0;
                        }
                        return s1.length() - s2.length();
                    }
                }
                """;

        // В compare содержимое сравнивает compareTo, в withId та же ссылка лишь избавляет от копии;
        // в isOwner и rank сравнение ссылок и есть весь ответ
        assertThat(RuleTests.lines(new ReferenceEqualityRule(), code)).containsExactly(16, 19);
    }

    @Test
    void seedDataAtStartupIsSuspicion() {
        String code = """
                @Component
                class DataInitializer implements CommandLineRunner {
                    private PostRepository postRepository;
                    private TagRepository tagRepository;
                    @Override
                    public void run(String... args) {
                        postRepository.deleteAll();
                        tagRepository.save(new Tag("java"));
                    }
                    public void publish(Post post) {
                        postRepository.save(post);
                        tagRepository.save(new Tag(post.getTitle()));
                    }
                }
                """;

        List<Violation> violations = RuleTests.check(new MultipleWritesWithoutTransactionRule(), code);
        assertThat(violations).extracting(Violation::line).containsExactly(5, 10);
        assertThat(violations).extracting(Violation::confidenceOrDefault)
                .containsExactly(Confidence.SUSPICION, Confidence.PROBABLE);
    }

    @Test
    void waitingWrapperHasNoTimeoutByContract() {
        String code = """
                class FutureProxy<V> implements Future<V> {
                    private final Future<V> future;
                    @Override
                    public V get() throws InterruptedException, ExecutionException {
                        return future.get();
                    }
                    static <T> T join(CompletableFuture<T> future) {
                        return future.join();
                    }
                    V load(CompletableFuture<V> task) {
                        return task.join();
                    }
                }
                """;

        // get() и join(future) сами и есть ожидание без предела; load ждет чужую задачу под другим именем
        assertThat(RuleTests.lines(new WaitWithoutTimeoutRule(), code)).containsExactly(11);
    }

    @Test
    void searchResultComparedWithNamedConstantOrCheckedEarlier() {
        String code = """
                class Texts {
                    static String before(String str, String find) {
                        int pos = str.indexOf(find);
                        if (pos == INDEX_NOT_FOUND) {
                            return str;
                        }
                        return str.substring(0, pos);
                    }
                    static boolean isCandidate(String name) {
                        if (name == null || !name.contains(".")) {
                            return false;
                        }
                        String packageName = name.substring(0, name.lastIndexOf('.'));
                        return !"java.lang".equals(packageName);
                    }
                    static String raw(String name, int limit) {
                        int pos = name.indexOf(':');
                        if (pos == limit) {
                            return name;
                        }
                        return name.substring(0, pos);
                    }
                    static String content(String wkt) {
                        if (!wkt.startsWith("POLYGON")) {
                            throw new IllegalArgumentException("Mismatch geometry type");
                        }
                        return wkt.substring(0, wkt.indexOf(")"));
                    }
                }
                """;

        // limit - не признак "не найдено"; начало строки проверено, а скобки в ней может и не быть
        assertThat(RuleTests.lines(new IndexOutOfBoundsRule(), code)).containsExactly(21, 27);
    }

    @Test
    void executorThatGetsItsOnlyTaskAtStartup() {
        String code = """
                @Component
                class ConfigWatcher {
                    private final ExecutorService watcher = Executors.newSingleThreadExecutor();
                    private final ExecutorService workers = Executors.newFixedThreadPool(4);
                    @PostConstruct
                    void init() {
                        watcher.submit(this::watchFiles);
                    }
                    void handle(Runnable task) {
                        workers.submit(task);
                    }
                }
                """;

        // watcher - выделенный поток под один вечный цикл; workers получает задачу на каждый запрос
        assertThat(RuleTests.lines(new UnboundedExecutorRule(), code)).containsExactly(4);
    }

    @Test
    void lazyValueSetInStartupCallback() {
        String code = """
                @Component
                class RestartEndpoint implements ApplicationListener<ApplicationStartedEvent> {
                    private ApplicationContext context;
                    private RestTemplate template;
                    @Override
                    public void onApplicationEvent(ApplicationStartedEvent event) {
                        if (this.context == null) {
                            this.context = event.getApplicationContext();
                        }
                    }
                    RestTemplate template() {
                        if (template == null) {
                            template = new RestTemplate();
                        }
                        return template;
                    }
                }
                """;

        assertThat(RuleTests.lines(new UnsynchronizedLazyInitRule(), code)).containsExactly(12);
    }

    @Test
    void listBuiltFromVarargs() {
        String code = """
                class Beans {
                    Map<String, Object> copy(List<Property> properties, String... ignored) {
                        Map<String, Object> map = new HashMap<>();
                        List<String> skip = new ArrayList<>(Arrays.asList(ignored));
                        skip.add("class");
                        for (Property property : properties) {
                            if (!skip.contains(property.name())) {
                                map.put(property.name(), property.value());
                            }
                        }
                        return map;
                    }
                    Map<String, Object> filter(List<Property> properties, List<String> names) {
                        Map<String, Object> map = new HashMap<>();
                        for (Property property : properties) {
                            if (names.contains(property.name())) {
                                map.put(property.name(), property.value());
                            }
                        }
                        return map;
                    }
                }
                """;

        assertThat(RuleTests.lines(new ListContainsInLoopRule(), code)).containsExactly(16);
    }
}
