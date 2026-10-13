package ru.akvine.zond.rules;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import ru.akvine.zond.enums.Confidence;
import ru.akvine.zond.models.Violation;
import ru.akvine.zond.rules.performance.BulkOperationWithListRule;
import ru.akvine.zond.rules.performance.ExpensiveObjectInLoopRule;
import ru.akvine.zond.rules.performance.FixedThreadPoolSizeRule;
import ru.akvine.zond.rules.performance.ListShiftInLoopRule;
import ru.akvine.zond.rules.performance.MapDoubleLookupRule;
import ru.akvine.zond.rules.performance.RandomUuidInParallelTaskRule;
import ru.akvine.zond.rules.performance.SortForSingleElementRule;

import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Правила jr:416 - jr:422: лишняя работа на коллекциях, дорогие объекты в цикле и пулы потоков
 */
class HotPathRulesTest {

    @TempDir
    Path dir;

    @Test
    void doubleLookupInMapIsReported() {
        String code = """
                class Counters {
                    private final Map<String, Integer> counts = new HashMap<>();
                    private final Map<String, List<String>> groups = new HashMap<>();
                    private final ConcurrentHashMap<String, Integer> shared = new ConcurrentHashMap<>();
                    Integer read(String key) {
                        if (counts.containsKey(key)) {
                            return counts.get(key);
                        }
                        return 0;
                    }
                    void count(String key) {
                        if (counts.containsKey(key)) {
                            counts.put(key, counts.get(key) + 1);
                        } else {
                            counts.put(key, 1);
                        }
                    }
                    void group(String key, String value) {
                        if (!groups.containsKey(key)) {
                            groups.put(key, new ArrayList<>());
                        }
                        groups.get(key).add(value);
                    }
                    String ternary(Map<String, String> labels, String code) {
                        return labels.containsKey(code) ? labels.get(code) : code;
                    }
                    Integer guarded(String key) {
                        if (!counts.containsKey(key)) {
                            throw new IllegalArgumentException(key);
                        }
                        return counts.get(key);
                    }
                    boolean has(String key) {
                        return counts.containsKey(key);
                    }
                    void concurrent(String key) {
                        if (!shared.containsKey(key)) {
                            shared.put(key, 1);
                        }
                    }
                    void other(String key, String another) {
                        if (counts.containsKey(key)) {
                            counts.put(another, 1);
                        }
                    }
                }
                """;

        List<Violation> violations = RuleTests.check(new MapDoubleLookupRule(), code);

        // Одна проверка без обращения, другой ключ и конкурентная карта (о ней говорит jr:172) не считаются
        assertThat(violations).extracting(Violation::line).containsExactly(6, 12, 19, 25, 28);
        assertThat(violations.get(0).message()).contains("counts.get(key)").contains("getOrDefault");
        assertThat(violations.get(1).message()).contains("merge");
        assertThat(violations.get(2).message()).contains("groups.put(key, ...)").contains("computeIfAbsent");
        assertThat(violations.get(4).message()).contains("проверьте его на null");
    }

    @Test
    void bulkOperationSearchingInListIsReported() {
        String code = """
                class Filters {
                    void remove(Set<Long> active, List<Long> blocked) {
                        active.removeAll(blocked);
                    }
                    void keep(List<String> names, List<String> allowed) {
                        names.retainAll(allowed);
                    }
                    boolean covers(List<String> granted, Set<String> required) {
                        return granted.containsAll(required);
                    }
                    void bySet(List<Long> ids, Set<Long> blocked) {
                        ids.removeAll(blocked);
                    }
                    boolean setCovers(Set<String> granted, List<String> required) {
                        return granted.containsAll(required);
                    }
                    void literal(List<String> names) {
                        names.removeAll(List.of("a", "b"));
                    }
                }
                """;

        List<Violation> violations = RuleTests.check(new BulkOperationWithListRule(), code);

        // С множеством на месте списка поиск быстрый, а список из двух значений мал
        assertThat(violations).extracting(Violation::line).containsExactly(3, 6, 9);
        assertThat(violations.get(0).message()).contains("active.removeAll(new HashSet<>(blocked))");
        assertThat(violations.get(2).message()).contains("new HashSet<>(granted).containsAll(required)");
    }

    @Test
    void shiftingListHeadInLoopIsReported() {
        String code = """
                class Queues {
                    void drain(List<Task> queue) {
                        while (!queue.isEmpty()) {
                            Task task = queue.remove(0);
                            run(task);
                        }
                    }
                    List<String> reversed(List<String> source) {
                        List<String> result = new ArrayList<>();
                        for (String item : source) {
                            result.add(0, item);
                        }
                        return result;
                    }
                    void linked(List<Task> source) {
                        List<Task> queue = new LinkedList<>(source);
                        while (!queue.isEmpty()) {
                            run(queue.remove(0));
                        }
                    }
                    void deque(Deque<Task> queue) {
                        while (!queue.isEmpty()) {
                            run(queue.removeFirst());
                        }
                    }
                    Task first(List<Task> queue) {
                        return queue.remove(0);
                    }
                    void perGroup(List<Group> groups) {
                        for (Group group : groups) {
                            List<Task> own = group.tasks();
                            own.remove(0);
                        }
                    }
                    void byValue(List<Integer> numbers, List<Integer> toRemove) {
                        for (Integer number : toRemove) {
                            numbers.remove(number);
                        }
                    }
                }
                """;

        List<Violation> violations = RuleTests.check(new ListShiftInLoopRule(), code);

        // Связный список и очередь берут из начала за O(1); разовое удаление и свой список у каждой
        // итерации не считаются
        assertThat(violations).extracting(Violation::line).containsExactly(4, 11);
        assertThat(violations.get(0).message()).contains("ArrayDeque").contains("pollFirst");
        assertThat(violations.get(1).message()).contains("Collections.reverse");
    }

    @Test
    void expensiveObjectCreatedOnEveryIterationIsReported() {
        String code = """
                class Importer {
                    void parse(List<Row> rows) throws Exception {
                        for (Row row : rows) {
                            LocalDate date = LocalDate.parse(row.date(), DateTimeFormatter.ofPattern("dd.MM.yyyy"));
                            MessageDigest digest = MessageDigest.getInstance("SHA-256");
                            Field[] fields = Row.class.getDeclaredFields();
                            DateFormat format = new SimpleDateFormat("yyyy-MM-dd");
                            HttpClient client = HttpClient.newHttpClient();
                        }
                    }
                    void dependent(List<Row> rows) {
                        for (Row row : rows) {
                            DateTimeFormatter own = DateTimeFormatter.ofPattern(row.pattern());
                            Field[] fields = row.getClass().getDeclaredFields();
                        }
                    }
                    void hoisted(List<Row> rows) {
                        DateTimeFormatter formatter = DateTimeFormatter.ofPattern("dd.MM.yyyy");
                        for (Row row : rows) {
                            LocalDate.parse(row.date(), formatter);
                        }
                    }
                    void lambda(List<Row> rows) {
                        rows.forEach(row -> row.setDate(LocalDate.parse(row.date(), DateTimeFormatter.ofPattern("dd.MM.yyyy"))));
                    }
                    void hierarchy(Class<?> type, List<Field> fields) {
                        Class<?> parent = type;
                        while (parent != null) {
                            fields.addAll(List.of(parent.getDeclaredFields()));
                            parent = parent.getSuperclass();
                        }
                    }
                }
                """;

        List<Violation> violations = RuleTests.check(new ExpensiveObjectInLoopRule(), code);

        // Объект, который зависит от текущей строки, из цикла не вынести; вынесенный заранее - не находка.
        // Зависит от итерации и переменная, объявленная до цикла, если в цикле ей присваивают
        assertThat(violations).extracting(Violation::line).containsExactlyInAnyOrder(4, 5, 6, 7, 8, 24);
        assertThat(violations).filteredOn(violation -> violation.line() == 4).extracting(Violation::message)
                .allMatch(message -> message.contains("потокобезопасен") && message.contains("в константе"));
        assertThat(violations).filteredOn(violation -> violation.line() == 5).extracting(Violation::message)
                .allMatch(message -> message.contains("не потокобезопасен"));
        assertThat(violations).filteredOn(violation -> violation.line() == 8).extracting(Violation::message)
                .allMatch(message -> message.contains("пул соединений"));
    }

    @Test
    void randomUuidInLoopOfParallelTaskIsReported() {
        ProjectFixture project = new ProjectFixture(dir)
                .source("ColumnFacade.java", """
                        class ColumnFacade {
                            private final ExecutorService executor;
                            private final UuidGenerator uuids;
                            List<CompletableFuture<List<String>>> generate(List<Column> columns) {
                                List<CompletableFuture<List<String>>> futures = new ArrayList<>();
                                for (Column column : columns) {
                                    futures.add(CompletableFuture.supplyAsync(() -> uuids.generate(column.size()), executor));
                                }
                                return futures;
                            }
                            List<String> inline(int size) throws Exception {
                                return executor.submit(() -> {
                                    List<String> ids = new ArrayList<>();
                                    for (int i = 0; i < size; i++) {
                                        ids.add(UUID.randomUUID().toString());
                                    }
                                    return ids;
                                }).get();
                            }
                            void transactional(int size) {
                                transactionTemplate.execute(status -> {
                                    for (int i = 0; i < size; i++) {
                                        save(UUID.randomUUID());
                                    }
                                    return null;
                                });
                            }
                        }
                        """)
                .source("UuidGenerator.java", """
                        class UuidGenerator {
                            List<String> generate(int size) {
                                List<String> values = new ArrayList<>();
                                for (int i = 0; i < size; i++) {
                                    values.add(UUID.randomUUID().toString());
                                }
                                return values;
                            }
                        }
                        """)
                .source("WrappedFacade.java", """
                        class WrappedFacade {
                            private final TaskExecutor taskExecutor;
                            private final SerialGenerator serials;
                            Map<Integer, CompletableFuture<List<?>>> generate(List<Column> columns) {
                                Map<Integer, CompletableFuture<List<?>>> futures = new HashMap<>();
                                for (int index = 0; index < columns.size(); index++) {
                                    Column column = columns.get(index);
                                    futures.put(index, submit(() -> serials.generate(column.size())));
                                }
                                return futures;
                            }
                            private CompletableFuture<List<?>> submit(Supplier<List<?>> task) {
                                return CompletableFuture.supplyAsync(task, taskExecutor.executor());
                            }
                            void deferred(int size, List<UUID> ids) {
                                Runnable job = () -> {
                                    for (int i = 0; i < size; i++) {
                                        ids.add(UUID.randomUUID());
                                    }
                                };
                                taskExecutor.execute(job);
                            }
                            void local(int size, List<UUID> ids) {
                                Runnable job = () -> {
                                    for (int i = 0; i < size; i++) {
                                        ids.add(UUID.randomUUID());
                                    }
                                };
                                job.run();
                            }
                        }
                        """)
                .source("SerialGenerator.java", """
                        class SerialGenerator {
                            List<String> generate(int size) {
                                List<String> values = new ArrayList<>();
                                for (int i = 0; i < size; i++) {
                                    values.add(UUID.randomUUID().toString());
                                }
                                return values;
                            }
                        }
                        """)
                .source("Sequential.java", """
                        class Sequential {
                            List<UUID> ids(int size) {
                                List<UUID> ids = new ArrayList<>();
                                for (int i = 0; i < size; i++) {
                                    ids.add(UUID.randomUUID());
                                }
                                return ids;
                            }
                            UUID one() {
                                return UUID.randomUUID();
                            }
                        }
                        """);

        // Находка есть в коде задачи и в методе, который задача вызывает, в том числе когда задачу отдает
        // исполнителю свой метод-обертка либо она сначала лежит в переменной. Цикл в обычном методе,
        // в transactionTemplate.execute и в лямбде, которую запускают тут же, не считаются
        assertThat(project.lines(new RandomUuidInParallelTaskRule()))
                .containsExactly("ColumnFacade.java:15", "SerialGenerator.java:5", "UuidGenerator.java:5",
                        "WrappedFacade.java:18");
    }

    @Test
    void sortingForSingleElementIsReported() {
        String code = """
                class Extremes {
                    int smallest(List<Integer> source) {
                        List<Integer> values = new ArrayList<>(source);
                        Collections.sort(values);
                        return values.get(0);
                    }
                    String latest(List<String> source) {
                        List<String> names = new ArrayList<>(source);
                        names.sort(Comparator.naturalOrder());
                        return names.get(names.size() - 1);
                    }
                    int range(int[] source) {
                        int[] numbers = source.clone();
                        Arrays.sort(numbers);
                        return numbers[numbers.length - 1] - numbers[0];
                    }
                    List<Integer> sorted(List<Integer> source) {
                        List<Integer> values = new ArrayList<>(source);
                        Collections.sort(values);
                        return values;
                    }
                    int median(List<Integer> source) {
                        List<Integer> values = new ArrayList<>(source);
                        Collections.sort(values);
                        return values.get(values.size() / 2);
                    }
                    int parameter(List<Integer> values) {
                        Collections.sort(values);
                        return values.get(0);
                    }
                    void all(List<Integer> source) {
                        List<Integer> values = new ArrayList<>(source);
                        Collections.sort(values);
                        for (Integer value : values) {
                            print(value);
                        }
                    }
                }
                """;

        List<Violation> violations = RuleTests.check(new SortForSingleElementRule(), code);

        // Не считаются: отсортированный список отдают дальше или обходят, берут элемент из середины,
        // сортируют чужой список (вызывающий может ждать его отсортированным)
        assertThat(violations).extracting(Violation::line).containsExactly(4, 9, 14);
        assertThat(violations.get(0).message()).contains("первого элемента").contains("Collections.min(values)");
        assertThat(violations.get(1).message()).contains("последнего элемента").contains("Collections.max(names)");
        assertThat(violations.get(2).message()).contains("первого и последнего");
    }

    @Test
    void largeConstantThreadPoolIsReported() {
        ProjectFixture project = new ProjectFixture(dir)
                .source("Pools.java", """
                        class Pools {
                            private static final int WORKERS = 64;
                            ExecutorService large = Executors.newFixedThreadPool(100);
                            ExecutorService constant = Executors.newFixedThreadPool(WORKERS);
                            ExecutorService small = Executors.newFixedThreadPool(8);
                            ExecutorService byCores = Executors.newFixedThreadPool(Runtime.getRuntime().availableProcessors());
                            ExecutorService custom = new ThreadPoolExecutor(10, 200, 60L, TimeUnit.SECONDS, new ArrayBlockingQueue<>(100));
                            void configure(ThreadPoolTaskExecutor executor, int configured) {
                                executor.setCorePoolSize(50);
                                executor.setMaxPoolSize(configured);
                            }
                        }
                        """)
                .write("src/main/resources/application.properties", """
                        parallel.executor.threads-count=100
                        spring.task.execution.pool.max-size=64
                        spring.task.execution.pool.core-size=8
                        server.tomcat.threads.max=200
                        spring.datasource.hikari.maximum-pool-size=50
                        parallel.executor.queue-capacity=1000
                        """)
                .write("src/main/resources/application-dev.properties", "parallel.executor.threads-count=500\\n");

        FixedThreadPoolSizeRule rule = new FixedThreadPoolSizeRule();

        // Не считаются: малый пул, размер от числа ядер и из параметра, потоки веб-сервера, пул
        // соединений с базой, размер очереди и настройки профиля разработки
        assertThat(project.lines(rule)).containsExactly(
                "Pools.java:3", "Pools.java:4", "Pools.java:7", "Pools.java:9",
                "application.properties:1", "application.properties:2");
        List<Violation> violations = project.check(rule);
        assertThat(violations).filteredOn(violation -> violation.file().toString().endsWith(".properties"))
                .extracting(Violation::confidence).containsOnly(Confidence.SUSPICION);
        assertThat(violations).filteredOn(violation -> violation.line() == 7)
                .extracting(Violation::message).allMatch(message -> message.contains("из 200 потоков"));
    }
}
