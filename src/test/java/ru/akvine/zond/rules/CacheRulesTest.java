package ru.akvine.zond.rules;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import ru.akvine.zond.rules.logical.CacheEvictInTransactionRule;
import ru.akvine.zond.rules.logical.CacheWithoutEvictionRule;
import ru.akvine.zond.rules.logical.CachedValueModifiedRule;
import ru.akvine.zond.rules.resources.UnboundedMapCacheRule;

import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Правила jr:374 - jr:377: самодельный кеш без границ и ошибки работы с кешем Spring
 */
class CacheRulesTest {
    private static final String CATALOG = """
            @Service
            @CacheConfig(cacheNames = "catalog")
            class Catalog {
                @Cacheable("products")
                Product product(Long id) { return load(id); }
                @Cacheable(cacheNames = {"prices", "rates"})
                Price price(Long id) { return loadPrice(id); }
                @Cacheable
                List<Product> all() { return loadAll(); }
                @CacheEvict(value = "products", allEntries = true)
                void refresh() {}
                @Caching(evict = {@CacheEvict("rates")})
                void refreshRates() {}
                @Cacheable(Names.USERS)
                User user(Long id) { return loadUser(id); }
            }
            """;
    private static final String CLEANER = """
            class Cleaner {
                void drop() {
                    cacheManager.getCache("catalog").clear();
                }
            }
            """;
    private static final String PRODUCT_SERVICE = """
            class ProductService {
                private CacheService cacheService;
                @Transactional
                @CacheEvict("products")
                public void update(Product product) { save(product); }
                @CacheEvict("products")
                public void evictOnly() {}
                @Transactional(readOnly = true)
                @CachePut("products")
                public Product read(Long id) { return load(id); }
                @Transactional
                public void rename(Product product) {
                    save(product);
                    cacheService.drop(product.getId());
                }
                public void renameWithoutTransaction(Product product) {
                    cacheService.drop(product.getId());
                }
            }
            """;
    private static final String CACHE_SERVICE = """
            class CacheService {
                @CacheEvict("products")
                public void drop(Long id) {}
            }
            """;
    private static final String CATALOG_SERVICE = """
            class CatalogService {
                @Cacheable("products")
                public List<Product> products() { return load(); }
                @Cacheable("product")
                public Product product(Long id) { return loadOne(id); }
                public List<Product> fresh() { return load(); }
            }
            """;
    private static final String REPORT = """
            class Report {
                private CatalogService catalogService;
                void build() {
                    List<Product> products = catalogService.products();
                    products.sort(comparator);
                    products.add(new Product());
                    int size = products.size();
                }
                void copy() {
                    List<Product> products = new ArrayList<>(catalogService.products());
                    products.sort(comparator);
                }
                void rename(Long id) {
                    Product product = catalogService.product(id);
                    product.setName("new");
                }
                void chained() {
                    catalogService.products().clear();
                }
                void notCached() {
                    List<Product> products = catalogService.fresh();
                    products.clear();
                }
                void reassigned() {
                    List<Product> products = catalogService.products();
                    products = new ArrayList<>(products);
                    products.clear();
                }
                void sorted() {
                    List<Product> products = catalogService.products();
                    Collections.sort(products);
                }
            }
            """;

    @TempDir
    Path dir;

    @Test
    void mapThatOnlyGrowsIsMemoryLeak() {
        List<Integer> lines = RuleTests.lines(new UnboundedMapCacheRule(), """
                @Service
                class RateService {
                    private final Map<String, Rate> rates = new ConcurrentHashMap<>();
                    private final Map<String, Rate> bounded = new ConcurrentHashMap<>();
                    private final Map<Currency, Rate> byEnum = new HashMap<>();
                    private final Map<String, Handler> handlers = new HashMap<>();
                    private final Map<String, Rate> defaults = new HashMap<>();
                    private static final Map<Long, Session> SESSIONS = new HashMap<>();
                    public final Map<String, Rate> exposed = new HashMap<>();
                    private final Map<String, Rate> weak = new WeakHashMap<>();
                    Rate rate(String code) {
                        return rates.computeIfAbsent(code, this::load);
                    }
                    void remember(String code, Rate rate) {
                        bounded.put(code, rate);
                        byEnum.put(rate.currency(), rate);
                        defaults.put("default", rate);
                        exposed.put(code, rate);
                        weak.put(code, rate);
                    }
                    @Scheduled(fixedRate = 60000)
                    void evict() {
                        bounded.entrySet().removeIf(entry -> entry.getValue().isExpired());
                    }
                    @PostConstruct
                    void init() {
                        handlers.put(name(), new Handler());
                    }
                    static void open(Long id, Session session) {
                        SESSIONS.put(id, session);
                    }
                }
                enum Currency { USD, EUR }
                class Plain {
                    private final Map<String, Rate> local = new HashMap<>();
                    void add(String code, Rate rate) { local.put(code, rate); }
                }
                """);

        // Карту с очисткой, с ключом-перечислением, с постоянным ключом, наполняемую при запуске, открытую
        // наружу, WeakHashMap и карту обычного объекта правило не трогает
        assertThat(lines).containsExactly(3, 8);
    }

    @Test
    void cacheMustBeEvictedOrExpire() {
        ProjectFixture project = new ProjectFixture(dir.resolve("plain"))
                .source("Catalog.java", CATALOG)
                .source("Cleaner.java", CLEANER);
        // products и rates сбрасываются аннотациями, catalog - вручную через менеджер; имя-константа берется как есть
        assertThat(project.lines(new CacheWithoutEvictionRule())).containsExactly("Catalog.java:14", "Catalog.java:6");
        assertThat(project.check(new CacheWithoutEvictionRule())).extracting(violation -> violation.message())
                .anyMatch(message -> message.contains("'prices'"))
                .anyMatch(message -> message.contains("'USERS'"));

        ProjectFixture withTtlInCode = new ProjectFixture(dir.resolve("code"))
                .source("Catalog.java", CATALOG)
                .source("CacheConfig.java", """
                        @Configuration
                        class CacheConfig {
                            @Bean
                            Caffeine<Object, Object> caffeine() {
                                return Caffeine.newBuilder().expireAfterWrite(10, TimeUnit.MINUTES);
                            }
                        }
                        """);
        assertThat(withTtlInCode.lines(new CacheWithoutEvictionRule())).isEmpty();

        ProjectFixture withTtlInConfig = new ProjectFixture(dir.resolve("config"))
                .source("Catalog.java", CATALOG)
                .write("src/main/resources/application.properties", "spring.cache.redis.time-to-live=10m\n");
        assertThat(withTtlInConfig.lines(new CacheWithoutEvictionRule())).isEmpty();
    }

    @Test
    void cacheMustNotChangeBeforeCommit() {
        ProjectFixture project = new ProjectFixture(dir)
                .source("ProductService.java", PRODUCT_SERVICE)
                .source("CacheService.java", CACHE_SERVICE);
        // Сброс без транзакции, транзакция только на чтение и вызов вне транзакции безопасны
        assertThat(project.lines(new CacheEvictInTransactionRule()))
                .containsExactly("ProductService.java:14", "ProductService.java:3");

        ProjectFixture aware = new ProjectFixture(dir)
                .source("ProductService.java", PRODUCT_SERVICE)
                .source("CacheService.java", CACHE_SERVICE)
                .source("CacheConfig.java", """
                        @Configuration
                        class CacheConfig {
                            @Bean
                            CacheManager cacheManager(CacheManager target) {
                                return new TransactionAwareCacheManagerProxy(target);
                            }
                        }
                        """);
        assertThat(aware.lines(new CacheEvictInTransactionRule())).isEmpty();
    }

    @Test
    void cachedObjectMustNotBeModified() {
        ProjectFixture inMemory = new ProjectFixture(dir.resolve("memory"))
                .source("CatalogService.java", CATALOG_SERVICE)
                .source("Report.java", REPORT);
        // Копия, результат некешируемого метода и переменная, которой присвоили копию, - уже не объект из кеша
        assertThat(inMemory.lines(new CachedValueModifiedRule())).containsExactly(
                "Report.java:15", "Report.java:18", "Report.java:31", "Report.java:5", "Report.java:6");

        // Кеш вне памяти приложения каждый раз отдает новый объект
        ProjectFixture remote = new ProjectFixture(dir.resolve("redis"))
                .source("CatalogService.java", CATALOG_SERVICE)
                .source("Report.java", REPORT)
                .write("src/main/resources/application.yml", "spring:\n  cache:\n    type: redis\n");
        assertThat(remote.lines(new CachedValueModifiedRule())).isEmpty();
    }
}
