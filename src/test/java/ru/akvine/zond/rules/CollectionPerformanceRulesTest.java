package ru.akvine.zond.rules;

import org.junit.jupiter.api.Test;
import ru.akvine.zond.enums.Confidence;
import ru.akvine.zond.enums.ErrorLevel;
import ru.akvine.zond.models.Violation;
import ru.akvine.zond.rules.performance.AggregateRecomputedInLoopRule;
import ru.akvine.zond.rules.performance.ContainsBeforeAddRule;
import ru.akvine.zond.rules.performance.IndexOfInLoopRule;
import ru.akvine.zond.rules.performance.ListContainsInLoopRule;
import ru.akvine.zond.rules.performance.RandomPickWithRetryRule;
import ru.akvine.zond.rules.performance.SharedRandomContentionRule;
import ru.akvine.zond.rules.performance.SortInLoopRule;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Правила jr:410 - jr:415: квадратичные алгоритмы на коллекциях и общий генератор случайных чисел
 */
class CollectionPerformanceRulesTest {
    private static final String UNIQUES = """
            class Uniques {
                private final List<String> registered = new ArrayList<>();
                private final Set<String> names = new HashSet<>();

                List<Long> generate(int size) {
                    List<Long> values = new ArrayList<>();
                    while (values.size() < size) {
                        long value = next();
                        if (values.contains(value)) {
                            continue;
                        }
                        values.add(value);
                    }
                    return values;
                }
                void register(String name) {
                    if (!registered.contains(name)) {
                        registered.add(name);
                    }
                }
                void remember(String name) {
                    if (!names.contains(name)) {
                        names.add(name);
                        log(name);
                    }
                }
                boolean firstTime(String name) {
                    if (names.contains(name)) {
                        return false;
                    }
                    names.add(name);
                    return true;
                }
                void validated(String name) {
                    if (names.contains(name)) {
                        return;
                    }
                    if (isValid(name)) {
                        names.add(name);
                    }
                }
                void conditional(String name) {
                    if (!names.contains(name) && isValid(name)) {
                        names.add(name);
                    }
                }
                void afterWork(String name) {
                    if (names.contains(name)) {
                        return;
                    }
                    process(name);
                    names.add(name);
                }
                void once(List<String> local, String name) {
                    if (!local.contains(name)) {
                        local.add(name);
                    }
                }
                List<String> sortedUnique(List<String> source) {
                    List<String> result = new ArrayList<>();
                    for (String item : source) {
                        if (!result.contains(item)) {
                            result.add(item);
                        }
                    }
                    Collections.sort(result);
                    return result;
                }
                boolean known(List<String> allowed, List<String> requested) {
                    for (String item : requested) {
                        if (!allowed.contains(item)) {
                            return false;
                        }
                    }
                    return true;
                }
            }
            """;

    @Test
    void duplicateCheckByContainsBeforeAddIsReported() {
        List<Violation> violations = RuleTests.check(new ContainsBeforeAddRule(), UNIQUES);

        // Не считаются: добавление под еще одним условием, проверка с дополнительным условием, добавление
        // после другой работы (одним add их не заменить) и разовая проверка локального списка вне цикла
        assertThat(violations).extracting(Violation::line).containsExactly(9, 17, 22, 28, 62);
        // Перебор списка - квадратичный алгоритм; двойной поиск в множестве - мелкая потеря
        assertThat(violations).extracting(Violation::errorLevel).containsExactly(
                ErrorLevel.MAJOR, ErrorLevel.MAJOR, ErrorLevel.MINOR, ErrorLevel.MINOR, ErrorLevel.MAJOR);
        // Список в поле проверяется по разу за вызов: растет ли он, по коду не видно
        assertThat(violations).extracting(Violation::confidence)
                .containsExactly(Confidence.PROBABLE, Confidence.SUSPICION, null, null, Confidence.PROBABLE);
        assertThat(violations.get(0).message())
                .contains("values.contains(value)").contains("values.add(value)").contains("if (!seen.add(x))");
        assertThat(violations.get(2).message()).contains("ищут элемент дважды").contains("if (set.add(x))");
        // Уникальные значения, которые потом сортируют, - готовый случай для TreeSet
        assertThat(violations.get(4).message()).contains("TreeSet");
        assertThat(violations.get(0).message()).doesNotContain("TreeSet");
    }

    @Test
    void plainContainsInLoopStaysWithOlderRuleAndPairIsNotReportedTwice() {
        // О проверке перед добавлением говорит jr:410, а jr:71 остается обычный поиск в цикле
        assertThat(RuleTests.lines(new ListContainsInLoopRule(), UNIQUES)).containsExactly(71);
    }

    @Test
    void uniqueElementsPickedByRandomIndexWithRetryAreReported() {
        String code = """
                class Sampler {
                    List<String> pick(List<String> source, int count) {
                        List<String> result = new ArrayList<>();
                        while (result.size() < count) {
                            String candidate = source.get(random.nextInt(source.size()));
                            if (!result.contains(candidate)) {
                                result.add(candidate);
                            }
                        }
                        return result;
                    }
                    List<String> byIndex(List<String> source, int count) {
                        Set<Integer> used = new HashSet<>();
                        List<String> result = new ArrayList<>();
                        for (int i = 0; i < count; i++) {
                            int index = random.nextInt(source.size());
                            if (!used.add(index)) {
                                i--;
                                continue;
                            }
                            result.add(source.get(index));
                        }
                        return result;
                    }
                    String next(String[] source, Set<String> taken) {
                        String candidate;
                        do {
                            candidate = source[(int) (Math.random() * source.length)];
                        } while (taken.contains(candidate));
                        return candidate;
                    }
                    List<String> withRepeats(List<String> source, int count) {
                        List<String> result = new ArrayList<>();
                        for (int i = 0; i < count; i++) {
                            result.add(source.get(random.nextInt(source.size())));
                        }
                        return result;
                    }
                    List<String> shuffled(List<String> source, int count) {
                        List<String> copy = new ArrayList<>(source);
                        Collections.shuffle(copy, random);
                        return copy.subList(0, count);
                    }
                }
                """;

        List<Violation> violations = RuleTests.check(new RandomPickWithRetryRule(), code);

        // Выбор с повторами и перемешивание находкой не являются
        assertThat(violations).extracting(Violation::line).containsExactly(5, 21, 28);
        assertThat(violations.get(0).message())
                .contains("result.contains(candidate)").contains("Collections.shuffle");
        assertThat(violations.get(1).message()).contains("used.add(index)");
    }

    @Test
    void sortRepeatedInLoopIsReported() {
        String code = """
                class Ranking {
                    void insertSorted(List<Integer> source) {
                        List<Integer> sorted = new ArrayList<>();
                        for (Integer value : source) {
                            sorted.add(value);
                            Collections.sort(sorted);
                        }
                    }
                    void repeated(List<String> names, List<Order> orders) {
                        for (Order order : orders) {
                            names.sort(Comparator.naturalOrder());
                            order.setFirst(names.get(0));
                        }
                    }
                    void once(List<Integer> values) {
                        Collections.sort(values);
                    }
                    void perGroup(List<Group> groups) {
                        for (Group group : groups) {
                            List<Item> items = group.items();
                            items.sort(Comparator.comparing(Item::name));
                            Collections.sort(group.codes());
                        }
                    }
                    void arrays(int[] buffer, List<int[]> chunks) {
                        for (int[] chunk : chunks) {
                            Arrays.sort(buffer);
                        }
                    }
                }
                """;

        List<Violation> violations = RuleTests.check(new SortInLoopRule(), code);

        // Разовая сортировка и сортировка своего списка на каждой итерации - обычное дело
        assertThat(violations).extracting(Violation::line).containsExactly(6, 11, 27);
        assertThat(violations.get(0).message()).contains("после каждой вставки").contains("TreeSet");
        assertThat(violations.get(1).message()).contains("не меняется").contains("перед циклом");
    }

    @Test
    void sharedUnseededGeneratorIsReported() {
        String generator = """
                @Service
                class DataGenerator {
                    private static final SecureRandom SECURE = new SecureRandom();
                    private static final Random RANDOM = new Random();
                    private static final Random SEEDED = new Random(42);
                    private final Random single = new Random();
                    List<Integer> numbers(int count) {
                        List<Integer> result = new ArrayList<>();
                        for (int i = 0; i < count; i++) {
                            result.add(RANDOM.nextInt());
                        }
                        return result;
                    }
                    Config config() {
                        return new Config(SECURE);
                    }
                    int jitter() {
                        return single.nextInt(100);
                    }
                    long reproducible() {
                        long sum = 0;
                        for (int i = 0; i < 10; i++) {
                            sum += SEEDED.nextLong();
                        }
                        return sum;
                    }
                    void run(List<Task> tasks) {
                        tasks.parallelStream().forEach(Task::run);
                    }
                }
                """;
        String tokens = """
                @Service
                class TokenService {
                    private static final SecureRandom RANDOM = new SecureRandom();
                    String token() {
                        byte[] bytes = new byte[32];
                        for (int i = 0; i < 4; i++) {
                            RANDOM.nextBytes(bytes);
                        }
                        return encode(bytes);
                    }
                }
                """;

        List<Violation> violations = RuleTests.checkProject(new SharedRandomContentionRule(),
                Map.of("DataGenerator.java", generator, "TokenService.java", tokens));

        // Не считаются: генератор с seed (нужна воспроизводимость), генератор, из которого берут одно
        // число за вызов, и генератор токенов - ему положено быть SecureRandom
        assertThat(violations).extracting(Violation::line).containsExactly(3, 4);
        // Менять SecureRandom на быстрый генератор можно, только убедившись, что это не секрет
        assertThat(violations).extracting(Violation::confidence)
                .containsExactly(Confidence.SUSPICION, Confidence.PROBABLE);
        assertThat(violations.get(0).message()).contains("'SECURE'").contains("под общей блокировкой");
        assertThat(violations.get(1).message()).contains("L64X128MixRandom").contains("ThreadLocalRandom");
    }

    @Test
    void sharedGeneratorInSingleThreadedProjectIsOnlySuspicion() {
        String code = """
                class Dice {
                    private static final Random RANDOM = new Random();
                    int[] roll(int count) {
                        int[] values = new int[count];
                        for (int i = 0; i < count; i++) {
                            values[i] = RANDOM.nextInt(6);
                        }
                        return values;
                    }
                }
                """;

        // В проекте нет ни потоков, ни обработчиков запросов: ждать генератор некому
        assertThat(RuleTests.check(new SharedRandomContentionRule(), code))
                .extracting(Violation::confidence).containsExactly(Confidence.SUSPICION);
    }

    @Test
    void aggregateRecomputedInLoopIsReported() {
        String code = """
                class Stats {
                    void prefix(List<Integer> values) {
                        for (int i = 0; i < values.size(); i++) {
                            int max = Collections.max(values.subList(0, i + 1));
                            process(max);
                        }
                    }
                    void growing(List<Integer> source) {
                        List<Integer> seen = new ArrayList<>();
                        for (Integer value : source) {
                            seen.add(value);
                            report(Collections.min(seen));
                        }
                    }
                    void invariant(List<Double> values) {
                        for (Double value : values) {
                            normalized.add(value / Collections.max(values));
                        }
                    }
                    void streamSlice(List<Integer> values) {
                        for (int i = 1; i <= values.size(); i++) {
                            int sum = values.subList(0, i).stream().mapToInt(Integer::intValue).sum();
                            process(sum);
                        }
                    }
                    int once(List<Integer> values) {
                        return Collections.max(values);
                    }
                    void perGroup(List<Group> groups) {
                        for (Group group : groups) {
                            int top = Collections.max(group.scores());
                            List<Integer> own = group.scores();
                            int low = Collections.min(own);
                        }
                    }
                    void running(List<Integer> values) {
                        int max = Integer.MIN_VALUE;
                        for (int value : values) {
                            max = Math.max(max, value);
                            process(max);
                        }
                    }
                }
                """;

        List<Violation> violations = RuleTests.check(new AggregateRecomputedInLoopRule(), code);

        // Разовое вычисление, своя коллекция на каждой итерации и максимум, который ведут на лету,
        // находкой не являются
        assertThat(violations).extracting(Violation::line).containsExactly(4, 12, 17, 22);
        assertThat(violations.get(0).message()).contains("по срезу списка 'values'").contains("Math.max");
        assertThat(violations.get(1).message()).contains("пополняется в этом же цикле");
        assertThat(violations.get(2).message()).contains("в цикле не меняется").contains("один раз перед циклом");
        assertThat(violations.get(3).message()).contains("по срезу списка 'values'");
    }

    @Test
    void indexOfInLoopIsReported() {
        String code = """
                class Positions {
                    void current(List<Item> items) {
                        for (Item item : items) {
                            int position = items.indexOf(item);
                            item.setPosition(position);
                        }
                    }
                    void lookup(List<String> columnNames, List<String> dependencies) {
                        for (String name : dependencies) {
                            int index = columnNames.indexOf(name);
                            use(index);
                        }
                    }
                    void lambda(List<String> order, List<Item> items) {
                        items.forEach(item -> item.setRank(order.indexOf(item.code())));
                    }
                    int once(List<String> names, String name) {
                        return names.indexOf(name);
                    }
                    void text(String line, List<String> words) {
                        for (String word : words) {
                            int at = line.indexOf(word);
                        }
                    }
                    void own(List<Group> groups) {
                        for (Group group : groups) {
                            List<String> codes = group.codes();
                            int at = codes.indexOf("X");
                        }
                    }
                }
                """;

        List<Violation> violations = RuleTests.check(new IndexOfInLoopRule(), code);

        // Разовый поиск, поиск в строке и в списке, который свой у каждой итерации, не считаются
        assertThat(violations).extracting(Violation::line).containsExactly(4, 10, 15);
        assertThat(violations.get(0).message()).contains("ведите счетчик").contains("неверный индекс");
        assertThat(violations.get(1).message()).contains("Map<элемент, индекс>");
    }
}
