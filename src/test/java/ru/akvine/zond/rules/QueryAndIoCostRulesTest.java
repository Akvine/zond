package ru.akvine.zond.rules;

import org.junit.jupiter.api.Test;
import ru.akvine.zond.enums.Confidence;
import ru.akvine.zond.enums.ErrorLevel;
import ru.akvine.zond.models.Violation;
import ru.akvine.zond.rules.performance.AsyncAwaitedImmediatelyRule;
import ru.akvine.zond.rules.performance.CalendarForCurrentTimeRule;
import ru.akvine.zond.rules.performance.ConstantHelperPerCallRule;
import ru.akvine.zond.rules.performance.CopyOnWriteInLoopRule;
import ru.akvine.zond.rules.performance.EntitiesLoadedForIdsRule;
import ru.akvine.zond.rules.performance.ExceptionAsBranchInLoopRule;
import ru.akvine.zond.rules.performance.HiddenRegexInHotMethodRule;
import ru.akvine.zond.rules.performance.InMemoryLimitAfterQueryRule;
import ru.akvine.zond.rules.performance.PageCountWithJoinsRule;
import ru.akvine.zond.rules.performance.RepeatedQueryInMethodRule;
import ru.akvine.zond.rules.performance.SingleByteIoRule;
import ru.akvine.zond.rules.performance.UnbufferedFileStreamRule;
import ru.akvine.zond.rules.resources.TransactionalBlockingWaitRule;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * jr:510 - jr:521: лишние запросы к базе, ввод-вывод без буфера и ожидание там, где оно стоит дорого
 */
class QueryAndIoCostRulesTest {

    @Test
    void fileStreamUnderSmallPortionWrapperNeedsBuffer() {
        String code = """
                class Storage {
                    Object load(File file) throws Exception {
                        FileInputStream input = new FileInputStream(file);
                        ObjectInputStream objects = new ObjectInputStream(input);
                        return objects.readObject();
                    }
                    void pack(File source, String target) throws Exception {
                        OutputStream out = null;
                        out = new FileOutputStream(target);
                        GZIPOutputStream gzip = new GZIPOutputStream(out);
                        OutputStream buffered = new BufferedOutputStream(new FileOutputStream(target));
                        DataOutputStream data = new DataOutputStream(buffered);
                        GZIPInputStream sized = new GZIPInputStream(new FileInputStream(source), 65536);
                        ObjectOutputStream given = new ObjectOutputStream(response.getOutputStream());
                    }
                }
                """;

        // Буфер между оберткой и файлом, свой размер буфера и чужой поток находкой не считаются
        assertThat(RuleTests.lines(new UnbufferedFileStreamRule(), code)).containsExactly(4, 10);
    }

    @Test
    void singleByteLoopOverUnbufferedStream() {
        String code = """
                class Counter {
                    int count(File file) throws IOException {
                        int total = 0;
                        try (InputStream in = new FileInputStream(file)) {
                            while (in.read() != -1) {
                                total++;
                            }
                        }
                        return total;
                    }
                    int buffered(File file) throws IOException {
                        int total = 0;
                        try (InputStream in = new BufferedInputStream(new FileInputStream(file))) {
                            while (in.read() != -1) {
                                total++;
                            }
                        }
                        return total;
                    }
                    int given(InputStream in) throws IOException {
                        int total = 0;
                        while (in.read() != -1) {
                            total++;
                        }
                        return total;
                    }
                    void copy(File from, File to) throws IOException {
                        try (InputStream in = new BufferedInputStream(new FileInputStream(from));
                             OutputStream out = new FileOutputStream(to)) {
                            int b;
                            while ((b = in.read()) != -1) {
                                out.write(b);
                            }
                        }
                    }
                }
                """;

        assertThat(RuleTests.lines(new SingleByteIoRule(), code)).containsExactly(5, 32);
    }

    @Test
    void singleByteLoopIsLessCertainForForeignStreamsAndCheaperForReaders() {
        String code = """
                class Response {
                    String text(HttpEntity entity) throws IOException {
                        StringBuilder text = new StringBuilder();
                        InputStreamReader reader = new InputStreamReader(entity.getContent(), "UTF-8");
                        int symbol;
                        while ((symbol = reader.read()) != -1) {
                            text.append((char) symbol);
                        }
                        return text.toString();
                    }
                    boolean hasLineBreak(String path) throws IOException {
                        try (InputStream stream = storage.openStream(path)) {
                            int symbol;
                            while ((symbol = stream.read()) != -1) {
                                if (symbol == 10) {
                                    return true;
                                }
                            }
                        }
                        return false;
                    }
                }
                """;

        List<Violation> violations = RuleTests.check(new SingleByteIoRule(), code);

        assertThat(violations).extracting(Violation::line).containsExactly(6, 14);
        assertThat(violations.get(0).errorLevel()).isEqualTo(ErrorLevel.INFO);
        assertThat(violations.get(1).confidence()).isEqualTo(Confidence.PROBABLE);
    }

    @Test
    void sameQueryTwiceWithoutChangesBetween() {
        String code = """
                class StatsService {
                    Report build(Long clientId, long day) {
                        Stats current = statsRepository.findByClientAndDay(clientId, day);
                        report.setCurrent(current.total());
                        Stats again = statsRepository.findByClientAndDay(clientId, day);
                        return new Report(current, again);
                    }
                    Report periods(Long clientId, long day) {
                        Stats current = statsRepository.findByClientAndDay(clientId, day);
                        day = previous(day);
                        Stats before = statsRepository.findByClientAndDay(clientId, day);
                        return new Report(current, before);
                    }
                    Stats afterWrite(Long clientId, long day) {
                        Stats current = statsRepository.findByClientAndDay(clientId, day);
                        statsRepository.save(current.next());
                        return statsRepository.findByClientAndDay(clientId, day);
                    }
                    Stats branches(Long clientId, long day, boolean strict) {
                        if (strict) {
                            return statsRepository.findByClientAndDay(clientId, day);
                        }
                        log.info("not strict");
                        return statsRepository.findByClientAndDay(clientId, day);
                    }
                    Stats changedFilter(Filter filter) {
                        Stats current = statsRepository.findByFilter(filter);
                        filter.setDay(filter.getDay() - 1);
                        return current.minus(statsRepository.findByFilter(filter));
                    }
                }
                """;

        assertThat(RuleTests.lines(new RepeatedQueryInMethodRule(), code)).containsExactly(5);
    }

    @Test
    void pageCountInheritsJoinThatDoesNotChangeRowCount() {
        Map<String, String> files = new LinkedHashMap<>();
        files.put("Order.java", """
                @Entity
                class Order {
                    @Id
                    private Long id;
                    @ManyToOne
                    private Client client;
                    @OneToMany
                    private List<Item> items;
                }
                """);
        files.put("OrderRepository.java", """
                interface OrderRepository extends JpaRepository<Order, Long> {
                    @Query("select o from Order o left join fetch o.client where o.status = :status")
                    Page<Order> findByStatus(String status, Pageable pageable);

                    @Query(value = "select o from Order o left join o.client c where o.status = :status",
                            countQuery = "select count(o) from Order o where o.status = :status")
                    Page<Order> findCounted(String status, Pageable pageable);

                    @Query("select o from Order o left join o.client c where c.name = :name")
                    Page<Order> findByClientName(String name, Pageable pageable);

                    @Query("select o from Order o left join o.items i where o.status = :status")
                    Page<Order> findWithItems(String status, Pageable pageable);

                    @Query("select o from Order o left join fetch o.client where o.status = :status")
                    List<Order> findAllByStatus(String status);

                    @Query("select o from Order o left join o.warehouse w where o.status = :status")
                    Page<Order> findWithUnknownRelation(String status, Pageable pageable);
                }
                """);

        List<Violation> violations = RuleTests.checkProject(new PageCountWithJoinsRule(), files);

        assertThat(violations).extracting(Violation::line).containsExactly(2, 18);
        assertThat(violations.get(0).message()).contains("'o.client'");
        // Связь к одной записи видна по сущности - остается уверенность самого правила
        assertThat(violations.get(0).confidence()).isNull();
        assertThat(new PageCountWithJoinsRule().confidence()).isEqualTo(Confidence.PROBABLE);
        assertThat(violations.get(1).confidence()).isEqualTo(Confidence.SUSPICION);
    }

    @Test
    void entitiesLoadedOnlyForIdentifiers() {
        String code = """
                class QrCodeService {
                    List<Long> ids(Long teamId) {
                        return qrCodeRepository.findByTeamId(teamId).stream().map(QrCode::getId).toList();
                    }
                    List<Long> viaVariable(Long teamId) {
                        List<QrCode> codes = qrCodeRepository.findByTeamId(teamId);
                        return codes.stream().map(code -> code.getId()).toList();
                    }
                    List<Long> alsoUsed(Long teamId) {
                        List<QrCode> codes = qrCodeRepository.findByTeamId(teamId);
                        audit(codes);
                        return codes.stream().map(QrCode::getId).toList();
                    }
                    List<String> names(Long teamId) {
                        return qrCodeRepository.findByTeamId(teamId).stream().map(QrCode::getName).toList();
                    }
                    List<Long> fromGiven(List<QrCode> codes) {
                        return codes.stream().map(QrCode::getId).toList();
                    }
                }
                """;

        assertThat(RuleTests.lines(new EntitiesLoadedForIdsRule(), code)).containsExactly(3, 6);
    }

    @Test
    void taskStartedInAnotherThreadAndAwaitedAtOnce() {
        String code = """
                class Loader {
                    Report direct(Long id) {
                        return CompletableFuture.supplyAsync(() -> load(id)).join();
                    }
                    Report viaVariable(Long id) throws Exception {
                        Future<Report> future = executor.submit(() -> load(id));
                        return future.get();
                    }
                    Report together(Long id) throws Exception {
                        Future<Report> future = executor.submit(() -> load(id));
                        Stats stats = loadStats(id);
                        return future.get().with(stats);
                    }
                    Report limited(Long id) throws Exception {
                        return executor.submit(() -> load(id)).get(5, TimeUnit.SECONDS);
                    }
                    long inOwnPool(List<Item> items) throws Exception {
                        return pool.submit(() -> items.parallelStream().filter(Item::isActive).count()).get();
                    }
                }
                """;

        assertThat(RuleTests.lines(new AsyncAwaitedImmediatelyRule(), code)).containsExactly(3, 7);
    }

    @Test
    void selectionIsCutInMemoryAfterQuery() {
        String code = """
                class OrderService {
                    List<Order> latest(String status) {
                        return orderRepository.findByStatus(status).stream()
                                .sorted(Comparator.comparing(Order::getCreated).reversed())
                                .limit(10)
                                .toList();
                    }
                    List<Order> firstPage() {
                        return orderRepository.findAll().subList(0, 20);
                    }
                    List<Order> paged(String status) {
                        return orderRepository.findByStatus(status, PageRequest.of(0, 100)).stream().limit(10).toList();
                    }
                    List<Order> filtered(String status) {
                        return orderRepository.findByStatus(status).stream().filter(Order::isPaid).limit(10).toList();
                    }
                    List<Order> local(List<Order> orders) {
                        return orders.stream().limit(10).toList();
                    }
                }
                """;

        List<Violation> violations = RuleTests.check(new InMemoryLimitAfterQueryRule(), code);

        assertThat(violations).extracting(Violation::line).containsExactly(5, 9);
        assertThat(violations.get(0).message()).contains("сортируются в памяти");
    }

    @Test
    void waitingInsideTransaction() {
        Map<String, String> files = Map.of("OrderService.java", """
                class OrderService {
                    @Transactional
                    public void process(Long id) throws Exception {
                        orderRepository.lock(id);
                        Thread.sleep(1000);
                        Future<Report> future = executor.submit(() -> load(id));
                        Report report = future.get();
                        Optional<Order> order = orderRepository.findById(id);
                        order.get();
                    }
                    public void outside() throws Exception {
                        Thread.sleep(1000);
                    }
                }
                """);

        assertThat(RuleTests.checkProject(new TransactionalBlockingWaitRule(), files))
                .extracting(Violation::line)
                .containsExactly(5, 7);
    }

    @Test
    void regexIsCompiledInMethodCalledForEveryElement() {
        Map<String, String> files = Map.of("Cleaner.java", """
                class Cleaner {
                    List<String> cleanAll(List<String> names) {
                        List<String> result = new ArrayList<>();
                        for (String name : names) {
                            result.add(slug(normalize(name)));
                        }
                        return result;
                    }
                    List<String> codes(List<String> raw) {
                        return raw.stream().filter(this::isCode).toList();
                    }
                    private String normalize(String name) {
                        return name.trim().replaceAll("\\\\s+", " ");
                    }
                    private boolean isCode(String value) {
                        return value.matches("[A-Z]{3}");
                    }
                    String once(String title) {
                        return title.replaceAll("\\\\s+", " ");
                    }
                    private String slug(String name) {
                        return name.replaceAll("_", "-").replaceAll(" ", "");
                    }
                }
                """);

        List<Violation> violations = RuleTests.checkProject(new HiddenRegexInHotMethodRule(), files);

        assertThat(violations).extracting(Violation::line).containsExactly(13, 16, 22);
        assertThat(violations.get(0).message()).contains("Pattern.compile").contains("Cleaner.cleanAll");
        // Цепочка из двух вызовов - одна находка, и шаблона в ней нет: хватит обычного replace
        assertThat(violations.get(2).message()).contains("и еще 1").contains("replace(...)");
    }

    @Test
    void calendarCreatedOnlyForCurrentMoment() {
        String code = """
                class Clock {
                    Date now() {
                        return Calendar.getInstance().getTime();
                    }
                    long millis() {
                        return Calendar.getInstance().getTimeInMillis();
                    }
                    Date tomorrow() {
                        Calendar calendar = Calendar.getInstance();
                        calendar.add(Calendar.DAY_OF_MONTH, 1);
                        return calendar.getTime();
                    }
                }
                """;

        assertThat(RuleTests.lines(new CalendarForCurrentTimeRule(), code)).containsExactly(3, 6);
    }

    @Test
    void copyOnWriteCollectionFilledInLoop() {
        String code = """
                class Registry {
                    private final List<Listener> listeners = new CopyOnWriteArrayList<>();
                    private final List<Listener> plain = new ArrayList<>();
                    void registerAll(List<Listener> added) {
                        for (Listener listener : added) {
                            listeners.add(listener);
                            plain.add(listener);
                        }
                    }
                    void register(Listener listener) {
                        listeners.add(listener);
                    }
                }
                """;

        assertThat(RuleTests.lines(new CopyOnWriteInLoopRule(), code)).containsExactly(6);
    }

    @Test
    void exceptionIsOrdinaryBranchOfLoop() {
        String code = """
                class Importer {
                    List<Integer> numbers(List<String> cells) {
                        List<Integer> result = new ArrayList<>();
                        for (String cell : cells) {
                            try {
                                result.add(Integer.parseInt(cell));
                            } catch (NumberFormatException e) {
                                continue;
                            }
                        }
                        return result;
                    }
                    List<Integer> reported(List<String> cells) {
                        List<Integer> result = new ArrayList<>();
                        for (String cell : cells) {
                            try {
                                result.add(Integer.parseInt(cell));
                            } catch (NumberFormatException e) {
                                log.warn("bad cell {}", cell);
                            }
                        }
                        return result;
                    }
                    Integer single(String cell) {
                        try {
                            return Integer.parseInt(cell);
                        } catch (NumberFormatException e) {
                            return null;
                        }
                    }
                    long timeout(List<Header> headers) {
                        for (Header header : headers) {
                            if (header.getName().equals("timeout")) {
                                try {
                                    return Long.parseLong(header.getValue());
                                } catch (NumberFormatException e) {
                                    return 0;
                                }
                            }
                        }
                        return 0;
                    }
                }
                """;

        assertThat(RuleTests.lines(new ExceptionAsBranchInLoopRule(), code)).containsExactly(7);
    }

    @Test
    void legacyDateFormatCreatedOnEveryCall() {
        String code = """
                class Report {
                    String title(Date date) {
                        DateFormat format = new SimpleDateFormat("dd.MM.yyyy");
                        return format.format(date);
                    }
                    void apply(ObjectMapper mapper) {
                        mapper.setDateFormat(new SimpleDateFormat("yyyy-MM-dd"));
                    }
                    List<String> all(List<Date> dates) {
                        List<String> result = new ArrayList<>();
                        for (Date date : dates) {
                            result.add(new SimpleDateFormat("dd.MM.yyyy").format(date));
                        }
                        return result;
                    }
                }
                """;

        List<Violation> violations = RuleTests.check(new ConstantHelperPerCallRule(), code);

        assertThat(violations).extracting(Violation::line).containsExactly(3);
        assertThat(violations.get(0).errorLevel()).isEqualTo(ErrorLevel.INFO);
    }
}
