package ru.akvine.zond.rules;

import org.junit.jupiter.api.Test;
import ru.akvine.zond.models.Violation;
import ru.akvine.zond.rules.logical.MoneyInFloatingPointRule;
import ru.akvine.zond.rules.logical.NarrowingCastRule;
import ru.akvine.zond.rules.logical.OddCheckWithModuloRule;
import ru.akvine.zond.rules.logical.UsuallyCheckedResultIgnoredRule;
import ru.akvine.zond.rules.performance.HttpCallInLoopRule;
import ru.akvine.zond.rules.performance.LoggingInLoopRule;
import ru.akvine.zond.rules.performance.OptionalOrElseCallRule;
import ru.akvine.zond.rules.performance.RegexInLoopRule;
import ru.akvine.zond.rules.performance.RepositoryCallInLoopRule;
import ru.akvine.zond.rules.performance.StreamInLoopRule;
import ru.akvine.zond.rules.performance.WholeUploadInMemoryRule;
import ru.akvine.zond.rules.streams.CollectUnboundedStreamRule;
import ru.akvine.zond.rules.streams.StreamCountForExistenceRule;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Ложные срабатывания, найденные прогоном Zond по собственному коду: правила о цене операции и о точности
 * вычислений принимали дешевое за дорогое, а ограниченное - за безграничное
 */
class FalsePositiveCostRulesTest {

    @Test
    void optionalChainIsNotLoop() {
        List<Violation> violations = RuleTests.checkProject(new RepositoryCallInLoopRule(), Map.of("OrderService.java", """
                @Service
                class OrderService {
                    private final OrderRepository orderRepository;
                    String name(Long id) {
                        return find(id).map(order -> orderRepository.findName(order)).orElse("");
                    }
                    Optional<String> title(Long id) {
                        return find(id).map(order -> orderRepository.findTitle(order));
                    }
                    List<String> names(List<Long> ids) {
                        return ids.stream().map(id -> orderRepository.findName(id)).toList();
                    }
                }
                """));

        assertThat(violations).extracting(Violation::line).containsExactly(11);
    }

    @Test
    void resultOfCallIsNotHttpClient() {
        List<Violation> violations = RuleTests.checkProject(new HttpCallInLoopRule(), Map.of("Reporter.java", """
                @Service
                class Reporter {
                    private final RestTemplate restTemplate;
                    void report(List<Call> calls, List<String> names) {
                        for (Call call : calls) {
                            describeClient(call).map(found -> found.name()).ifPresent(names::add);
                            restTemplate.getForObject("http://stats/" + call.id(), String.class);
                        }
                    }
                }
                """));

        assertThat(violations).extracting(Violation::line).containsExactly(7);
    }

    @Test
    void shortConstantIsCheapToWalk() {
        assertThat(RuleTests.lines(new StreamInLoopRule(), """
                class Names {
                    private static final Set<String> PREFIXES = Set.of("get", "is", "has");
                    long count(List<String> names, List<String> allowed) {
                        long total = 0;
                        for (String name : names) {
                            total += PREFIXES.stream().filter(prefix -> name.length() > prefix.length()).count();
                            total += allowed.stream().filter(item -> name.length() > item.length()).count();
                        }
                        return total;
                    }
                }
                """)).containsExactly(7);
    }

    @Test
    void matcherOfCompiledPatternDoesNotCompile() {
        assertThat(RuleTests.lines(new RegexInLoopRule(), """
                class Cleaner {
                    private static final Pattern SPACES = Pattern.compile("\\\\s+");
                    List<String> clean(List<String> lines) {
                        List<String> result = new ArrayList<>();
                        for (String line : lines) {
                            result.add(SPACES.matcher(line).replaceAll(" "));
                            result.add(line.replaceAll("#.*", ""));
                        }
                        return result;
                    }
                }
                """)).containsExactly(7);
    }

    @Test
    void recordPrintedOnceIsNotLogInLoop() {
        assertThat(RuleTests.lines(new LoggingInLoopRule(), """
                class Cleaner {
                    void clean(List<Block> blocks) {
                        boolean printed = false;
                        for (Block block : blocks) {
                            if (!printed) {
                                log.info("Cleaning started");
                                printed = true;
                            }
                            log.info("Block {} removed", block);
                        }
                    }
                }
                """)).containsExactly(9);
    }

    @Test
    void readingReadyValueIsNotComputation() {
        assertThat(RuleTests.lines(new OptionalOrElseCallRule(), """
                class Defaults {
                    String name(Optional<String> name, List<String> names, Stage stage) {
                        String first = name.orElse(names.get(0));
                        String label = name.orElse(stage.label());
                        String loaded = name.orElse(loadDefault());
                        return first + label + loaded + name.orElse(repository.findDefault());
                    }
                }
                """)).containsExactly(5, 6);
    }

    @Test
    void linesOfStringAreAlreadyInMemory() {
        assertThat(RuleTests.lines(new CollectUnboundedStreamRule(), """
                class Texts {
                    List<String> split(String text) {
                        return text.lines().collect(Collectors.toList());
                    }
                    List<String> read(BufferedReader reader) {
                        return reader.lines().collect(Collectors.toList());
                    }
                }
                """)).containsExactly(6);
    }

    @Test
    void processOutputIsNotUpload() {
        assertThat(RuleTests.lines(new WholeUploadInMemoryRule(), """
                class Runner {
                    byte[] output(Process process) throws IOException {
                        return process.getInputStream().readAllBytes();
                    }
                    byte[] upload(MultipartFile file) throws IOException {
                        return file.getInputStream().readAllBytes();
                    }
                }
                """)).containsExactly(6);
    }

    @Test
    void exactlyOneIsNotExistenceCheck() {
        assertThat(RuleTests.lines(new StreamCountForExistenceRule(), """
                class Checks {
                    boolean single(List<Item> items) { return items.stream().filter(Item::isActive).count() == 1; }
                    boolean several(List<Item> items) { return items.stream().filter(Item::isActive).count() > 1; }
                    boolean any(List<Item> items) { return items.stream().filter(Item::isActive).count() > 0; }
                    boolean none(List<Item> items) { return 1 > items.stream().filter(Item::isActive).count(); }
                    boolean some(List<Item> items) { return items.stream().filter(Item::isActive).count() >= 1L; }
                }
                """)).containsExactly(4, 5, 6);
    }

    @Test
    void countOfCollectionFitsInt() {
        assertThat(RuleTests.lines(new NarrowingCastRule(), """
                class Counters {
                    int active(List<Item> items) {
                        long found = items.stream().filter(Item::isActive).count();
                        return (int) found + 1;
                    }
                    int total(List<Item> items, List<Item> others) {
                        long first = items.stream().count();
                        long second = others.stream().filter(Item::isActive).count();
                        return (int) (first + second);
                    }
                    int nested(List<Order> orders) {
                        long items = orders.stream().flatMap(order -> order.items().stream()).count();
                        return (int) items;
                    }
                    int grown(List<Item> items, long extra) {
                        long found = items.stream().count();
                        found = found * extra;
                        return (int) found;
                    }
                    int size(long length) {
                        return (int) length;
                    }
                }
                """)).containsExactly(13, 18, 21);
    }

    @Test
    void shareIsCoefficient() {
        List<Violation> violations = RuleTests.checkProject(new MoneyInFloatingPointRule(), Map.of("Split.java", """
                @Entity
                class Split {
                    private double discountShare;
                    private double sharePrice;
                }
                """));

        assertThat(violations).extracting(Violation::line).containsExactly(4);
    }

    @Test
    void counterFromZeroIsNeverNegative() {
        assertThat(RuleTests.lines(new OddCheckWithModuloRule(), """
                class Rows {
                    void stripe(List<Row> rows) {
                        int index = 0;
                        for (Row row : rows) {
                            if (index % 2 == 1) {
                                row.shade();
                            }
                            index++;
                        }
                    }
                    boolean isOdd(int value) {
                        return value % 2 == 1;
                    }
                }
                """)).containsExactly(12);
    }

    @Test
    void secondWayOfCallingIsNotOversight() {
        StringBuilder checked = new StringBuilder();
        for (int index = 0; index < 20; index++) {
            checked.append("        if (rules.evaluate(order)) { count++; }\n");
            checked.append("        if (rules.validate(order)) { count++; }\n");
        }
        List<Violation> violations = RuleTests.checkProject(new UsuallyCheckedResultIgnoredRule(), Map.of(
                "Rules.java", """
                        class Rules {
                            boolean evaluate(Order order) {
                                return order.total() > 0;
                            }
                            boolean validate(Order order) {
                                return order.total() > 0;
                            }
                        }
                        """,
                "Checker.java", """
                        class Checker {
                            private final Rules rules;
                            private int count;
                            void check(Order order) {
                        """ + checked + """
                                rules.evaluate(order);
                                rules.evaluate(order);
                                rules.evaluate(order);
                                rules.evaluate(order);
                                rules.validate(order);
                            }
                        }
                        """));

        // Четыре вызова evaluate без результата - уже второй способ вызова; один validate - недосмотр
        assertThat(violations).extracting(Violation::line).containsExactly(49);
    }
}
