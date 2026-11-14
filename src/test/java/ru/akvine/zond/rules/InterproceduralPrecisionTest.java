package ru.akvine.zond.rules;

import org.junit.jupiter.api.Test;
import ru.akvine.zond.enums.Confidence;
import ru.akvine.zond.models.Violation;
import ru.akvine.zond.rules.concurrency.MutableStateInSingletonBeanRule;
import ru.akvine.zond.rules.concurrency.NonThreadSafeCollectionInBeanRule;
import ru.akvine.zond.rules.concurrency.UnsynchronizedLazyInitRule;
import ru.akvine.zond.rules.exceptions.EmptyCatchRule;
import ru.akvine.zond.rules.logical.EntityWithDataRule;
import ru.akvine.zond.rules.logical.MultipleWritesWithoutTransactionRule;
import ru.akvine.zond.rules.logical.OptionalMisuseRule;
import ru.akvine.zond.rules.logical.PossibleNullDereferenceRule;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Анализ между методами. Первая часть: вызванный метод разбирается для конкретного места вызова - с его
 * аргументами и с тем, что вызывающий уже проверил у полей своего объекта. Вторая часть: гонка в поле возможна,
 * только если до записи доходит больше одного потока, а это видно по цепочке вызовов от точек входа.
 * Третья: транзакция, начатая несколькими вызовами выше, и случаи, оставшиеся с прошлой партии.
 * В каждом тесте рядом с кодом, о котором правило теперь молчит либо говорит с уверенностью «подозрение»,
 * стоит код, о котором оно обязано сообщить в полную силу
 */
class InterproceduralPrecisionTest {

    @Test
    void nullOnlyForStateTheCallerHasChecked() {
        String code = """
                class Itinerary {
                    private List<String> legs = new ArrayList<>();
                    String finalStop() {
                        if (legs.isEmpty()) {
                            return "unknown";
                        } else {
                            return lastLeg().trim();
                        }
                    }
                    String finalStopUnchecked() {
                        return lastLeg().trim();
                    }
                    private String lastLeg() {
                        if (legs.isEmpty()) {
                            return null;
                        }
                        return legs.get(legs.size() - 1);
                    }
                }
                """;

        // lastLeg() возвращает null только для пустого списка, а finalStop это уже проверил
        assertThat(RuleTests.lines(new PossibleNullDereferenceRule(), code)).containsExactly(11);
    }

    @Test
    void nullOnlyForFieldTheCallerHasChecked() {
        String code = """
                class Holder {
                    private String cached;
                    int cachedLength() {
                        if (cached == null) {
                            return 0;
                        }
                        return cachedOrNull().length();
                    }
                    int cachedLengthUnchecked() {
                        return cachedOrNull().length();
                    }
                    int afterReset() {
                        if (cached == null) {
                            return 0;
                        }
                        reset();
                        return cachedOrNull().length();
                    }
                    private void reset() {
                        cached = null;
                    }
                    private String cachedOrNull() {
                        return cached == null ? null : cached.trim();
                    }
                }
                """;

        // После reset() проверка поля больше не действует
        assertThat(RuleTests.lines(new PossibleNullDereferenceRule(), code)).containsExactly(10, 17);
    }

    @Test
    void nullOnlyForOtherArguments() {
        String code = """
                class Page {
                    private List<String> data;
                    void add(String item) {
                        data(true).add(item);
                    }
                    int count() {
                        return data(false).size();
                    }
                    int countBy(boolean create) {
                        return data(create).size();
                    }
                    private List<String> data(boolean create) {
                        if (data == null && create) {
                            data = new ArrayList<>();
                        }
                        return data;
                    }
                }
                """;

        // data(true) список создаст; data(false) и data(create) могут вернуть null
        assertThat(RuleTests.lines(new PossibleNullDereferenceRule(), code)).containsExactly(7, 10);
    }

    @Test
    void fieldsOfAnotherObjectAreNotTransferred() {
        String code = """
                class Outer {
                    private String name;
                    private final Inner inner = new Inner();
                    int ownLength() {
                        if (name == null) {
                            return 0;
                        }
                        return inner.nameOrNull().length();
                    }
                    static class Inner {
                        private String name;
                        String nameOrNull() {
                            return name == null ? null : name.trim();
                        }
                    }
                }
                """;

        // Проверено поле name у Outer, а null возвращает Inner по своему полю
        assertThat(RuleTests.lines(new PossibleNullDereferenceRule(), code)).containsExactly(8);
    }

    @Test
    void lazyInitReachedOnlyFromMain() {
        Map<String, String> files = Map.of(
                "src/main/java/app/Tool.java", """
                        package app;
                        public class Tool {
                            public static void main(String[] args) {
                                Registry.get().size();
                                Shared.get().size();
                                new Thread(() -> Shared.get().clear()).start();
                            }
                        }
                        """,
                "src/main/java/app/Registry.java", """
                        package app;
                        public class Registry {
                            private static List<String> items;
                            static List<String> get() {
                                if (items == null) {
                                    items = new ArrayList<>();
                                }
                                return items;
                            }
                        }
                        """,
                "src/main/java/app/Shared.java", """
                        package app;
                        public class Shared {
                            private static List<String> items;
                            static List<String> get() {
                                if (items == null) {
                                    items = new ArrayList<>();
                                }
                                return items;
                            }
                        }
                        """);

        List<Violation> violations = RuleTests.checkProject(new UnsynchronizedLazyInitRule(), files);

        // Registry.get() вызывает только main; Shared.get() - еще и поток, созданный в main
        assertThat(violations).hasSize(2);
        assertThat(confidenceIn(violations, "Registry.java")).isEqualTo(Confidence.SUSPICION);
        assertThat(confidenceIn(violations, "Shared.java")).isNotEqualTo(Confidence.SUSPICION);
    }

    @Test
    void lazyInitInLibraryCodeStaysAsIs() {
        String code = """
                public class Fonts {
                    private static Font systemFont;
                    public static Font system() {
                        if (systemFont == null) {
                            systemFont = load();
                        }
                        return systemFont;
                    }
                }
                """;

        // Кто вызывает открытый метод, по коду не видно: оснований смягчать находку нет
        List<Violation> violations = RuleTests.check(new UnsynchronizedLazyInitRule(), code);
        assertThat(violations).hasSize(1);
        assertThat(violations.get(0).confidence()).isNotEqualTo(Confidence.SUSPICION);
    }

    @Test
    void beanStateWrittenOnlyByManagementOperation() {
        Map<String, String> files = Map.of(
                "src/main/java/app/Storage.java", """
                        package app;
                        @Component
                        public class Storage {
                            private String bucket;
                            private String region;
                            private final Map<String, String> clients = new HashMap<>();
                            private final Map<String, String> uploads = new HashMap<>();
                            private final Map<String, String> routes = new HashMap<>();
                            @ManagedOperation
                            public void refresh() {
                                reload();
                            }
                            private void reload() {
                                bucket = System.getProperty("bucket");
                                clients.put(bucket, "client");
                                routes.put(bucket, "route");
                            }
                            public void upload(String name) {
                                region = name;
                                uploads.put(name, routes.get(region));
                            }
                        }
                        """,
                "src/main/java/app/UploadController.java", """
                        package app;
                        @RestController
                        public class UploadController {
                            private final Storage storage;
                            UploadController(Storage storage) {
                                this.storage = storage;
                            }
                            @PostMapping("/upload")
                            public void upload(@RequestParam String name) {
                                storage.upload(name);
                            }
                        }
                        """);

        List<Violation> state = RuleTests.checkProject(new MutableStateInSingletonBeanRule(), files);
        List<Violation> collections = RuleTests.checkProject(new NonThreadSafeCollectionInBeanRule(), files);

        // bucket и clients меняет только операция JMX; region и uploads - обработчик запросов.
        // routes тоже пишет только операция JMX, но читает обработчик запросов: чтение во время записи портит HashMap
        assertThat(state.stream().map(Violation::line)).containsExactlyInAnyOrder(4, 5);
        assertThat(confidenceAt(state, 4)).isEqualTo(Confidence.SUSPICION);
        assertThat(confidenceAt(state, 5)).isNotEqualTo(Confidence.SUSPICION);
        assertThat(collections.stream().map(Violation::line)).containsExactlyInAnyOrder(6, 7, 8);
        assertThat(confidenceAt(collections, 6)).isEqualTo(Confidence.SUSPICION);
        assertThat(confidenceAt(collections, 7)).isNotEqualTo(Confidence.SUSPICION);
        assertThat(confidenceAt(collections, 8)).isNotEqualTo(Confidence.SUSPICION);
    }

    @Test
    void transactionStartedSeveralCallsAbove() {
        String code = """
                @Service
                class OrderFacade {
                    private OrderService orderService;
                    @Transactional
                    public void place(Order order) {
                        orderService.create(order);
                    }
                }
                @Service
                class OrderService {
                    private StockService stockService;
                    public void create(Order order) {
                        stockService.reserve(order);
                    }
                }
                @Service
                class StockService {
                    private StockRepository stockRepository;
                    private AuditRepository auditRepository;
                    public void reserve(Order order) {
                        stockRepository.save(new Stock(order));
                        auditRepository.save(new Audit(order));
                    }
                    public void release(Order order) {
                        stockRepository.delete(new Stock(order));
                        auditRepository.save(new Audit(order));
                    }
                }
                @RestController
                class StockController {
                    private StockService stockService;
                    @PostMapping("/release")
                    public void release(@RequestBody Order order) {
                        stockService.release(order);
                    }
                }
                """;

        // reserve() вызывается только по цепочке от транзакционного place(); release() - из контроллера без транзакции
        assertThat(RuleTests.lines(new MultipleWritesWithoutTransactionRule(), code)).containsExactly(24);
    }

    @Test
    void expectedExceptionsAndOptionalOf() {
        String emptyCatch = """
                class Finder {
                    User find(EntityManager manager, String name) {
                        User user = null;
                        try {
                            user = manager.createQuery("from User").getSingleResult();
                        } catch (javax.persistence.NoResultException e) {
                        }
                        try {
                            Thread.sleep(100);
                        } catch (InterruptedException e) {
                        }
                        try {
                            manager.flush();
                        } catch (Exception e) {
                        }
                        return user;
                    }
                }
                """;
        String optional = """
                class Resources {
                    void use(Repository repository) {
                        Optional<Resource> resource = Optional.empty();
                        try {
                            resource = Optional.of(acquire());
                            utilize(resource.get());
                        } finally {
                            resource.ifPresent(Resources::close);
                        }
                        Optional<Resource> found = repository.findByName("a");
                        utilize(found.get());
                    }
                }
                """;

        List<Violation> swallowed = RuleTests.check(new EmptyCatchRule(), emptyCatch);

        // «Не найдено» - ожидаемый ответ: подозрение. О прерывании скажет правило про InterruptedException
        assertThat(swallowed.stream().map(Violation::line)).containsExactly(6, 14);
        assertThat(confidenceAt(swallowed, 6)).isEqualTo(Confidence.SUSPICION);
        assertThat(confidenceAt(swallowed, 14)).isNotEqualTo(Confidence.SUSPICION);
        // В resource только что положили Optional.of(...), в found - ответ хранилища
        assertThat(RuleTests.lines(new OptionalMisuseRule(), optional)).containsExactly(11);
    }

    @Test
    void dataOnEntityWithoutRelations() {
        String code = """
                @Data
                @Entity
                class Country {
                    @Id
                    private Long id;
                    private String name;
                }
                @Data
                @Entity
                class City {
                    @Id
                    private Long id;
                    @ManyToOne(fetch = FetchType.LAZY)
                    private Country country;
                }
                """;

        List<Violation> violations = RuleTests.check(new EntityWithDataRule(), code);

        // Без связей нет ленивых загрузок и рекурсии: остается подозрение из-за hashCode
        assertThat(violations).hasSize(2);
        assertThat(violations.get(0).confidence()).isEqualTo(Confidence.SUSPICION);
        assertThat(violations.get(1).confidence()).isNotEqualTo(Confidence.SUSPICION);
    }

    private Confidence confidenceIn(List<Violation> violations, String file) {
        return violations.stream()
                .filter(violation -> violation.file().toString().endsWith(file))
                .findFirst()
                .orElseThrow()
                .confidence();
    }

    private Confidence confidenceAt(List<Violation> violations, int line) {
        return violations.stream()
                .filter(violation -> violation.line() == line)
                .findFirst()
                .orElseThrow()
                .confidence();
    }
}
