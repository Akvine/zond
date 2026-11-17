package ru.akvine.zond.rules;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import ru.akvine.zond.enums.Confidence;
import ru.akvine.zond.models.Violation;
import ru.akvine.zond.rules.concurrency.TimeoutWithoutCancelRule;
import ru.akvine.zond.rules.concurrency.WaitWithoutTimeoutRule;
import ru.akvine.zond.rules.logical.ProductOverflowRule;
import ru.akvine.zond.rules.resources.JvmWithoutExitOnOutOfMemoryRule;
import ru.akvine.zond.rules.security.RegexFromArgumentRule;
import ru.akvine.zond.rules.security.RegexFromRequestRule;
import ru.akvine.zond.rules.security.UnboundedAllocationSizeRule;
import ru.akvine.zond.rules.security.ValueBoundedLoopRule;
import ru.akvine.zond.rules.security.ValueDeepRecursionRule;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Правила jr:534 - jr:540 и дополнения jr:46: работа и память, размер которых задают данные, а не код, - цикл по
 * значению числа, рекурсия по значению, размер из данных, шаблон из аргументов, ожидание без отмены и без срока.
 * В каждом тесте рядом с кодом, о котором правило сообщает, стоит похожий код, о котором оно обязано молчать
 */
class UnboundedWorkRulesTest {
    private static final Path TEST_FILE = Path.of("src/test/java/ru/akvine/SampleTest.java");

    @TempDir
    Path dir;

    @Test
    void loopByElementValue() {
        String code = """
                class Converters {
                    List<Long> factorials(List<Long> values) {
                        return values.stream().map(value -> {
                            long result = 1;
                            for (long i = 2; i <= value; i++) {
                                result *= i;
                            }
                            return result;
                        }).toList();
                    }
                    List<Long> limited(List<Long> values) {
                        return values.stream().map(value -> {
                            if (value > 20) {
                                throw new IllegalArgumentException("too big");
                            }
                            long result = 1;
                            for (long i = 2; i <= value; i++) {
                                result *= i;
                            }
                            return result;
                        }).toList();
                    }
                    long squares(List<Integer> sizes) {
                        long sum = 0;
                        for (int size : sizes) {
                            for (int i = 0; i < size; i++) {
                                sum += (long) i * i;
                            }
                        }
                        return sum;
                    }
                    long triangle() {
                        return IntStream.range(0, 5).mapToLong(row -> {
                            long cells = 0;
                            for (int i = 0; i < row; i++) {
                                cells++;
                            }
                            return cells;
                        }).sum();
                    }
                }
                """;
        List<Violation> found = RuleTests.check(new ValueBoundedLoopRule(), code);

        // Цикл до значения элемента - с проверкой границы и по счетчику IntStream.range не считаются
        assertThat(found).extracting(Violation::line).containsExactly(5, 26);
        assertThat(found.get(0).message()).contains("'value' - элемент данных").doesNotContain("OutOfMemoryError");
        assertThat(found.get(0).confidenceOrDefault()).isEqualTo(Confidence.PROBABLE);
        assertThat(RuleTests.check(new ValueBoundedLoopRule(), TEST_FILE, code)).isEmpty();
    }

    @Test
    void loopByParsedValue() {
        String code = """
                class Parsed {
                    private static final int MAX_COUNT = 1000;
                    List<String> copies(String text, String item) {
                        int count = Integer.parseInt(text);
                        List<String> result = new ArrayList<>();
                        for (int i = 0; i < count; i++) {
                            result.add(item);
                        }
                        return result;
                    }
                    List<String> clamped(String text, String item) {
                        int count = Math.min(Integer.parseInt(text), MAX_COUNT);
                        List<String> result = new ArrayList<>();
                        for (int i = 0; i < count; i++) {
                            result.add(item);
                        }
                        return result;
                    }
                    List<String> checked(String text, String item) {
                        int count = Integer.parseInt(text);
                        checkLimit(count);
                        List<String> result = new ArrayList<>();
                        for (int i = 0; i < count; i++) {
                            result.add(item);
                        }
                        return result;
                    }
                    List<String> onlySign(String text, String item) {
                        int count = Integer.parseInt(text);
                        checkNonNegative(count);
                        if (count == 0) {
                            return List.of();
                        }
                        List<String> result = new ArrayList<>();
                        for (int i = 0; i < count; i++) {
                            result.add(item);
                        }
                        return result;
                    }
                    long countdown(String text) {
                        long left = Long.parseLong(text);
                        long steps = 0;
                        while (left > 0) {
                            steps++;
                            left--;
                        }
                        return steps;
                    }
                    long halving(String text) {
                        long left = Long.parseLong(text);
                        long steps = 0;
                        while (left > 0) {
                            steps++;
                            left /= 2;
                        }
                        return steps;
                    }
                    long range(String text) {
                        long last = Long.parseLong(text);
                        return LongStream.rangeClosed(1, last).sum();
                    }
                    long indexes(List<String> items) {
                        return IntStream.range(0, items.size()).filter(i -> items.get(i).isEmpty()).count();
                    }
                }
                """;
        List<Violation> found = RuleTests.check(new ValueBoundedLoopRule(), code);

        assertThat(found).extracting(Violation::line).containsExactly(6, 35, 43, 60);
        // Цикл копит элементы: кроме потока, страдает память
        assertThat(found.get(0).message()).contains("'count' получено разбором строки", "OutOfMemoryError");
        assertThat(found.get(2).message()).contains("'left' получено разбором строки");
        assertThat(found.get(3).message()).contains("LongStream.rangeClosed(1, last)");
    }

    @Test
    void loopLimitedByItsInput() {
        String code = """
                class Decoder {
                    List<String> names(String[] values) {
                        int index = 0;
                        int count = Integer.parseInt(values[index++]);
                        List<String> names = new ArrayList<>();
                        for (int i = 0; i < count; i++) {
                            names.add(values[index++]);
                        }
                        return names;
                    }
                    String prefix(String text, String length) {
                        int count = Integer.parseInt(length);
                        StringBuilder prefix = new StringBuilder();
                        for (int i = 0; i < count; i++) {
                            prefix.append(text.charAt(i));
                        }
                        return prefix.toString();
                    }
                    List<String> frames(DataInputStream in) throws IOException {
                        int count = in.readInt();
                        List<String> frames = new ArrayList<>();
                        for (int i = 0; i < count; i++) {
                            frames.add(in.readUTF());
                        }
                        return frames;
                    }
                    List<Integer> numbers(DataInputStream in, Random random) throws IOException {
                        int count = in.readInt();
                        List<Integer> numbers = new ArrayList<>();
                        for (int i = 0; i < count; i++) {
                            numbers.add(random.nextInt());
                        }
                        return numbers;
                    }
                    int flags(DataInputStream in) throws IOException {
                        int count = in.readUnsignedByte();
                        int flags = 0;
                        for (int i = 0; i < count; i++) {
                            flags = flags * 2 + 1;
                        }
                        return flags;
                    }
                }
                """;

        // Позиция в массиве и строке, чтение очередной части входа ограничивают цикл сами; random.nextInt() ничего
        // не читает; больше 255 шагов по числу из одного байта не бывает
        assertThat(RuleTests.lines(new ValueBoundedLoopRule(), code)).containsExactly(30);
    }

    @Test
    void loopByLongParameter() {
        String code = """
                class Maths {
                    long power(long base, long exponent) {
                        long result = 1;
                        for (long i = 0; i < exponent; i++) {
                            result *= base;
                        }
                        return result;
                    }
                    long limitedPower(long base, @Max(62) long exponent) {
                        long result = 1;
                        for (long i = 0; i < exponent; i++) {
                            result *= base;
                        }
                        return result;
                    }
                    long sum(int count) {
                        long sum = 0;
                        for (int i = 0; i < count; i++) {
                            sum += i;
                        }
                        return sum;
                    }
                    long fixed() {
                        long sum = 0;
                        for (long i = 0; i < 1000; i++) {
                            sum += i;
                        }
                        return sum;
                    }
                }
                """;
        List<Violation> found = RuleTests.check(new ValueBoundedLoopRule(), code);

        // До int цикл доходит за секунды, а параметр long мог проверить вызывающий код - отсюда лишь подозрение
        assertThat(found).extracting(Violation::line).containsExactly(4);
        assertThat(found.get(0).confidenceOrDefault()).isEqualTo(Confidence.SUSPICION);
        assertThat(found.get(0).message()).contains("'exponent' - параметр типа long");
    }

    @Test
    void recursionByValue() {
        String code = """
                class Recursion {
                    long factorial(long n) {
                        return n <= 1 ? 1 : n * factorial(n - 1);
                    }
                    long fibonacci(int n) {
                        if (n < 2) {
                            return n;
                        }
                        return fibonacci(n - 1) + fibonacci(n - 2);
                    }
                    long limited(int n) {
                        if (n > 20) {
                            throw new IllegalArgumentException("too big");
                        }
                        return n <= 1 ? 1 : n * limited(n - 1);
                    }
                    void retry(int attempts) {
                        if (attempts == 0) {
                            return;
                        }
                        retry(attempts - 1);
                    }
                    int size(Node node, int depth) {
                        return node == null ? 0 : 1 + size(node.next(), depth - 1);
                    }
                    String header() {
                        return "-".repeat(dashes(3));
                    }
                    private int dashes(int n) {
                        return n == 0 ? 0 : 2 + dashes(n - 1);
                    }
                }
                """;
        List<Violation> found = RuleTests.check(new ValueDeepRecursionRule(), code);

        // С проверкой границы, по счетчику попыток, по структуре и с константой в единственном вызове - молчит
        assertThat(found).extracting(Violation::line).containsExactly(3, 9);
        assertThat(found.get(0).message())
                .contains("'factorial' вызывает сам себя с 'n - 1'", "StackOverflowError")
                .doesNotContain("экспоненциально");
        assertThat(found.get(1).message()).contains("рекурсивных вызовов здесь 2", "экспоненциально");
        assertThat(RuleTests.check(new ValueDeepRecursionRule(), TEST_FILE, code)).isEmpty();
    }

    @Test
    void productOverflow() {
        String code = """
                class Products {
                    long factorial(int n) {
                        long result = 1;
                        for (int i = 2; i <= n; i++) {
                            result *= i;
                        }
                        return result;
                    }
                    long exact(int n) {
                        long result = 1;
                        for (int i = 2; i <= n; i++) {
                            result = Math.multiplyExact(result, i);
                        }
                        return result;
                    }
                    long guarded(int n) {
                        long result = 1;
                        for (int i = 2; i <= n; i++) {
                            if (result > Long.MAX_VALUE / i) {
                                throw new ArithmeticException("overflow");
                            }
                            result *= i;
                        }
                        return result;
                    }
                    long small() {
                        long result = 1;
                        for (int i = 2; i <= 10; i++) {
                            result *= i;
                        }
                        return result;
                    }
                    long power(long base, int exponent) {
                        long result = 1;
                        for (int i = 0; i < exponent; i++) {
                            result = result * base;
                        }
                        return result;
                    }
                    int hash(int[] values) {
                        int result = 17;
                        for (int value : values) {
                            result = result * 31 + value;
                        }
                        return result;
                    }
                    long pause(int attempts, int factor) {
                        long delay = 100;
                        for (int attempt = 0; attempt < attempts; attempt++) {
                            delay *= factor;
                        }
                        return delay;
                    }
                    double scale(double[] factors) {
                        double result = 1;
                        for (double factor : factors) {
                            result *= factor;
                        }
                        return result;
                    }
                    long recursive(long n) {
                        return n <= 1 ? 1 : n * recursive(n - 1);
                    }
                    long product(List<Long> values) {
                        return values.stream().reduce(1L, (a, b) -> a * b);
                    }
                    BigInteger big(List<BigInteger> values) {
                        return values.stream().reduce(BigInteger.ONE, (a, b) -> a.multiply(b));
                    }
                }
                """;
        List<Violation> found = RuleTests.check(new ProductOverflowRule(), code);

        assertThat(found).extracting(Violation::line).containsExactly(5, 36, 65, 62);
        // Множитель - счетчик цикла либо сам аргумент рекурсии: это факториал. В остальных случаях - подозрение
        assertThat(found).extracting(Violation::confidenceOrDefault).containsExactly(
                Confidence.PROBABLE, Confidence.SUSPICION, Confidence.SUSPICION, Confidence.PROBABLE);
        assertThat(found.get(0).message()).contains("'result' типа long", "Math.multiplyExact");
        assertThat(RuleTests.check(new ProductOverflowRule(), TEST_FILE, code)).isEmpty();
    }

    @Test
    void timeoutWithoutCancel() {
        String code = """
                class Reports {
                    Report abandoned(Future<Report> future) throws Exception {
                        try {
                            return future.get(5, TimeUnit.SECONDS);
                        } catch (TimeoutException exception) {
                            throw new IllegalStateException("too slow", exception);
                        }
                    }
                    Report cancelled(Future<Report> future) throws Exception {
                        try {
                            return future.get(5, TimeUnit.SECONDS);
                        } catch (TimeoutException exception) {
                            future.cancel(true);
                            throw new IllegalStateException("too slow", exception);
                        }
                    }
                    Report stopped(Future<Report> future, ExecutorService executor) throws Exception {
                        try {
                            return future.get(5, TimeUnit.SECONDS);
                        } catch (InterruptedException | TimeoutException exception) {
                            throw new IllegalStateException("too slow", exception);
                        } finally {
                            executor.shutdownNow();
                        }
                    }
                    Report handedOver(Future<Report> future) throws Exception {
                        try {
                            return future.get(5, TimeUnit.SECONDS);
                        } catch (TimeoutException exception) {
                            return onTimeout(future);
                        }
                    }
                    Report swallowed(Future<Report> future, long timeout, TimeUnit unit) {
                        try {
                            return future.get(timeout, unit);
                        } catch (Exception exception) {
                            return null;
                        }
                    }
                    Report cached(Cache<String, Report> cache, String key) {
                        try {
                            return cache.get(key, () -> new Report());
                        } catch (Exception exception) {
                            return null;
                        }
                    }
                    Report sent(Client client) throws Exception {
                        try {
                            return client.send().get(5, TimeUnit.SECONDS);
                        } catch (TimeoutException exception) {
                            throw new IllegalStateException("too slow", exception);
                        }
                    }
                }
                """;
        List<Violation> found = RuleTests.check(new TimeoutWithoutCancelRule(), code);

        // Отмена в catch или finally, передача задачи обработчику, get(key, loader) у кеша и ожидание без переменной,
        // которую можно отменить, - не находки
        assertThat(found).extracting(Violation::line).containsExactly(4, 35);
        assertThat(found.get(0).message()).contains("'future.get(5, TimeUnit.SECONDS)'", "future.cancel(true)");
        // Задача пришла параметром, а вызовов метода в проекте нет: отменяют ли ее снаружи, неизвестно
        assertThat(found).extracting(Violation::confidenceOrDefault)
                .containsExactly(Confidence.SUSPICION, Confidence.SUSPICION);
        assertThat(RuleTests.check(new TimeoutWithoutCancelRule(), TEST_FILE, code)).isEmpty();
    }

    @Test
    void timeoutCancelledByTaskOwner() {
        String code = """
                class Generation {
                    private final ExecutorService executor = Executors.newFixedThreadPool(4);
                    private Future<Report> background;
                    private Future<Report> watched;

                    Report own(Callable<Report> task) throws Exception {
                        Future<Report> future = executor.submit(task);
                        try {
                            return future.get(5, TimeUnit.SECONDS);
                        } catch (TimeoutException exception) {
                            throw new IllegalStateException("too slow", exception);
                        }
                    }
                    Report generate(Callable<Report> task) {
                        Future<Report> future = executor.submit(task);
                        try {
                            return collect(future);
                        } catch (RuntimeException exception) {
                            future.cancel(true);
                            throw exception;
                        }
                    }
                    private Report collect(Future<Report> future) {
                        return await(future);
                    }
                    private Report await(Future<Report> future) {
                        try {
                            return future.get(5, TimeUnit.SECONDS);
                        } catch (TimeoutException | InterruptedException | ExecutionException exception) {
                            throw new IllegalStateException("too slow", exception);
                        }
                    }
                    Report careless(Callable<Report> task) {
                        return poll(executor.submit(task));
                    }
                    private Report poll(Future<Report> future) {
                        try {
                            return future.get(5, TimeUnit.SECONDS);
                        } catch (TimeoutException | InterruptedException | ExecutionException exception) {
                            throw new IllegalStateException("too slow", exception);
                        }
                    }
                    Report peek() throws Exception {
                        try {
                            return background.get(1, TimeUnit.SECONDS);
                        } catch (TimeoutException exception) {
                            return null;
                        }
                    }
                    Report look() throws Exception {
                        try {
                            return watched.get(1, TimeUnit.SECONDS);
                        } catch (TimeoutException exception) {
                            return null;
                        }
                    }
                    void close() {
                        watched.cancel(true);
                    }
                }
                """;
        List<Violation> found = RuleTests.check(new TimeoutWithoutCancelRule(), code);

        // await: задачу отменяет тот, кто ее запустил, - в catch двумя вызовами выше. poll: вызывающий не отменяет.
        // watched отменяют в close(), но до тех пор задача выполняется - подозрение
        assertThat(found).extracting(Violation::line).containsExactly(9, 38, 45, 52);
        assertThat(found).extracting(Violation::confidenceOrDefault).containsExactly(
                Confidence.PROBABLE, Confidence.PROBABLE, Confidence.PROBABLE, Confidence.SUSPICION);
    }

    @Test
    void boundCheckedByCalledMethodOrCaller() {
        String code = """
                class Limits {
                    private static final int MAX_REPEAT = 1000;
                    private static final long MAX_STEPS = 1_000_000;
                    String guarded(String text, String times) {
                        int count = Integer.parseInt(times);
                        allow(count);
                        return text.repeat(count);
                    }
                    String logged(String text, String times) {
                        int count = Integer.parseInt(times);
                        note(count);
                        return text.repeat(count);
                    }
                    private void allow(int value) {
                        within(value, MAX_REPEAT);
                    }
                    private void within(int value, int limit) {
                        if (value > limit) {
                            throw new IllegalArgumentException("too many");
                        }
                    }
                    private void note(int value) {
                        System.out.println("repeat " + value);
                    }
                    long steps(String text) {
                        long limit = Long.parseLong(text);
                        if (limit > MAX_STEPS) {
                            throw new IllegalArgumentException("too many");
                        }
                        return count(limit);
                    }
                    private long count(long limit) {
                        long steps = 0;
                        for (long i = 0; i < limit; i++) {
                            steps += i % 7;
                        }
                        return steps;
                    }
                    long open(String text) {
                        return walk(Long.parseLong(text));
                    }
                    private long walk(long limit) {
                        long steps = 0;
                        for (long i = 0; i < limit; i++) {
                            steps += i % 7;
                        }
                        return steps;
                    }
                }
                """;

        // allow(count) сравнивает значение с границей двумя вызовами ниже, note(count) только пишет в журнал
        assertThat(RuleTests.lines(new UnboundedAllocationSizeRule(), code)).containsExactly(12);
        // В count(...) приходит уже проверенное значение, в walk(...) - любое
        assertThat(RuleTests.lines(new ValueBoundedLoopRule(), code)).containsExactly(44);
    }

    @Test
    void allocationSizeFromData() {
        String code = """
                class Texts {
                    private static final int MAX_REPEAT = 1000;
                    List<String> repeat(List<String> input, String[] arguments) {
                        int count;
                        if (arguments.length != 0) {
                            count = Integer.parseInt(arguments[0]);
                        } else {
                            count = 1;
                        }
                        return input.stream().map(value -> value.repeat(Math.max(0, count))).toList();
                    }
                    List<String> limited(List<String> input, String[] arguments) {
                        int count = Integer.parseInt(arguments[0]);
                        if (count > MAX_REPEAT) {
                            throw new IllegalArgumentException("too many");
                        }
                        return input.stream().map(value -> value.repeat(count)).toList();
                    }
                    String clamped(String text, String times) {
                        return text.repeat(Math.min(Integer.parseInt(times), MAX_REPEAT));
                    }
                    String line() {
                        return "-".repeat(40);
                    }
                    String padded(String text, int width) {
                        return StringUtils.leftPad(text, width);
                    }
                    String mask(String prefix) {
                        return StringUtils.leftPad("", Integer.parseInt(prefix), "1");
                    }
                    List<String> lines(List<Integer> widths) {
                        return widths.stream().map(width -> "=".repeat(width)).toList();
                    }
                    String indent(Optional<Integer> width) {
                        return width.map(size -> " ".repeat(size)).orElse("");
                    }
                    String tail(String used) {
                        return ".".repeat(80 - Integer.parseInt(used));
                    }
                }
                """;
        List<Violation> found = RuleTests.check(new UnboundedAllocationSizeRule(), code);

        // Значение Optional - не элемент данных, а 80 - x сверху ограничено константой
        assertThat(found).extracting(Violation::line).containsExactly(10, 29, 32);
        assertThat(found.get(0).message())
                .contains("'repeat(Math.max(0, count))'", "'count' получено разбором строки", "OutOfMemoryError");
        assertThat(found.get(2).message()).contains("'width' - элемент данных");
        assertThat(RuleTests.check(new UnboundedAllocationSizeRule(), TEST_FILE, code)).isEmpty();
    }

    @Test
    void bufferSizeFromStream() {
        String code = """
                class Frames {
                    private static final int MAX_FRAME = 1 << 20;
                    byte[] read(DataInputStream in) throws IOException {
                        int length = in.readInt();
                        byte[] body = new byte[length];
                        in.readFully(body);
                        return body;
                    }
                    byte[] readChecked(DataInputStream in) throws IOException {
                        int length = in.readInt();
                        if (length < 0 || length > MAX_FRAME) {
                            throw new IOException("frame is too big");
                        }
                        byte[] body = new byte[length];
                        in.readFully(body);
                        return body;
                    }
                    byte[] readShort(DataInputStream in) throws IOException {
                        byte[] body = new byte[in.readUnsignedShort()];
                        in.readFully(body);
                        return body;
                    }
                    List<String> names(ByteBuffer buffer) {
                        int count = buffer.getInt();
                        return new ArrayList<>(count);
                    }
                    ByteBuffer copy(String size) {
                        return ByteBuffer.allocate(Integer.parseInt(size));
                    }
                    StringBuilder text(String source) {
                        return new StringBuilder(source);
                    }
                    int[] counters(ResultSet row) throws SQLException {
                        return new int[row.getInt("size")];
                    }
                }
                """;

        // Размер с проверкой границы, из двух байтов, из колонки своей базы и new StringBuilder(text) - не находки
        assertThat(RuleTests.lines(new UnboundedAllocationSizeRule(), code)).containsExactly(28, 5, 25);
    }

    @Test
    void bigNumberGrowth() {
        String code = """
                class Powers {
                    private static final int MAX_EXPONENT = 4096;
                    BigInteger power(String base, String exponent) {
                        return new BigInteger(base).pow(Integer.parseInt(exponent));
                    }
                    BigInteger shifted(long value, String bits) {
                        int shift = Integer.parseInt(bits);
                        return BigInteger.valueOf(value).shiftLeft(shift);
                    }
                    BigInteger limited(BigInteger base, String exponent) {
                        int power = Integer.parseInt(exponent);
                        if (power > MAX_EXPONENT) {
                            throw new IllegalArgumentException("exponent is too big");
                        }
                        return base.pow(power);
                    }
                    BigInteger square(BigInteger base) {
                        return base.pow(2);
                    }
                    double plain(double base, String exponent) {
                        return Math.pow(base, Integer.parseInt(exponent));
                    }
                }
                """;
        List<Violation> found = RuleTests.check(new UnboundedAllocationSizeRule(), code);

        // Math.pow считает в double и памяти не просит
        assertThat(found).extracting(Violation::line).containsExactly(4, 8);
        assertThat(found.get(0).message()).contains("Показатель в 'pow(Integer.parseInt(exponent))'");
    }

    @Test
    void regexFromArguments() {
        String code = """
                class ReplaceAllConverter {
                    List<String> convert(List<String> input, String[] arguments) {
                        return input.stream().map(value -> value.replaceAll(arguments[0], arguments[1])).toList();
                    }
                    List<String> quoted(List<String> input, String[] arguments) {
                        return input.stream()
                                .map(value -> value.replaceAll(Pattern.quote(arguments[0]), arguments[1]))
                                .toList();
                    }
                    List<String> plain(List<String> input, String[] arguments) {
                        return input.stream().map(value -> value.replace(arguments[0], arguments[1])).toList();
                    }
                    Pattern compiled(List<String> arguments) {
                        return Pattern.compile(arguments.get(0));
                    }
                    boolean byRule(MaskRule rule, String text) {
                        return text.matches(rule.getPattern());
                    }
                    boolean byName(MaskRule rule, String text) {
                        return text.matches(rule.getName());
                    }
                    String[] cells(String line) {
                        return line.split("\\\\s*;\\\\s*");
                    }
                    String[] parts(Pattern pattern, String[] arguments) {
                        return pattern.split(arguments[0]);
                    }
                }
                """;
        List<Violation> found = RuleTests.checkProject(new RegexFromArgumentRule(), Map.of("Sample.java", code));

        // Pattern.quote, replace без шаблона, шаблон из кода, текст в pattern.split(text) и поле с обычным именем -
        // не находки
        assertThat(found).extracting(Violation::line).containsExactly(3, 14, 17);
        assertThat(found).extracting(Violation::confidenceOrDefault)
                .containsExactly(Confidence.PROBABLE, Confidence.PROBABLE, Confidence.SUSPICION);
        assertThat(found.get(0).message()).contains("'arguments[0]'", "элемент параметра 'arguments'", "RE2/J");
        assertThat(found.get(2).message()).contains("поле объекта 'rule'");
        assertThat(RuleTests.checkProject(new RegexFromArgumentRule(), Map.of(TEST_FILE.toString(), code))).isEmpty();
    }

    @Test
    void regexTracedToRequestIsLeftToItsOwnRule() {
        Map<String, String> project = Map.of("ReplaceController.java", """
                @RestController
                class ReplaceController {
                    @PostMapping("/replace")
                    String replace(@RequestParam String text, @RequestBody String[] arguments) {
                        return text.replaceAll(arguments[0], arguments[1]);
                    }
                }
                """);

        // Шаблон прослежен до запроса: об этом уже сообщает jr о регулярном выражении из запроса
        assertThat(RuleTests.checkProject(new RegexFromRequestRule(), project))
                .extracting(Violation::line).containsExactly(5);
        assertThat(RuleTests.checkProject(new RegexFromArgumentRule(), project)).isEmpty();
    }

    @Test
    void jvmWithoutExitOnOutOfMemory() {
        List<Violation> found = new ProjectFixture(dir.resolve("plain"))
                .write("service/Dockerfile", """
                        FROM eclipse-temurin:21 AS build
                        RUN java -version
                        CMD ["java", "-version"]
                        FROM eclipse-temurin:21-jre
                        COPY app.jar /app.jar
                        ENTRYPOINT ["java", "-jar", "/app.jar"]
                        """)
                .write("gateway/Dockerfile", """
                        FROM eclipse-temurin:21-jre
                        COPY app.jar /app.jar
                        ENTRYPOINT exec java $JAVA_OPTS -jar /app.jar
                        """)
                .write("safe/Dockerfile", """
                        FROM eclipse-temurin:21-jre
                        COPY app.jar /app.jar
                        ENTRYPOINT ["java", "-XX:+ExitOnOutOfMemoryError", "-jar", "/app.jar"]
                        """)
                .write("env/Dockerfile", """
                        FROM eclipse-temurin:21-jre
                        ENV JAVA_TOOL_OPTIONS="-XX:MaxRAMPercentage=75 -XX:+ExitOnOutOfMemoryError"
                        COPY app.jar /app.jar
                        CMD ["sh", "-c", "java -jar /app.jar"]
                        """)
                .write("web/Dockerfile", """
                        FROM nginx:1.27
                        COPY dist /usr/share/nginx/html
                        CMD ["nginx", "-g", "daemon off;"]
                        """)
                .write("scripts/Dockerfile", """
                        FROM node:22
                        COPY . /app
                        ENTRYPOINT ["/app/javascript-runner", "--java"]
                        """)
                .check(new JvmWithoutExitOnOutOfMemoryRule());

        // Запуск не JVM, флаг в команде и в JAVA_TOOL_OPTIONS - не находки; этап сборки не запускается вовсе
        assertThat(found).extracting(violation -> violation.file().getParent().getFileName() + ":" + violation.line())
                .containsExactlyInAnyOrder("service:6", "gateway:3");
        Violation plain = found.stream().filter(violation -> violation.line() == 6).findFirst().orElseThrow();
        Violation fromEnvironment = found.stream().filter(violation -> violation.line() == 3).findFirst().orElseThrow();
        assertThat(plain.message()).contains("-XX:+ExitOnOutOfMemoryError", "JAVA_TOOL_OPTIONS");
        assertThat(plain.confidenceOrDefault()).isEqualTo(Confidence.PROBABLE);
        // Параметры JVM приходят из окружения, и флаг может оказаться там
        assertThat(fromEnvironment.confidenceOrDefault()).isEqualTo(Confidence.SUSPICION);
    }

    @Test
    void jvmExitFlagSetOutsideDockerfile() {
        String dockerfile = """
                FROM eclipse-temurin:21-jre
                COPY app.jar /app.jar
                ENTRYPOINT ["java", "-jar", "/app.jar"]
                """;
        List<String> withCompose = new ProjectFixture(dir.resolve("compose"))
                .write("Dockerfile", dockerfile)
                .write("docker-compose.yml", """
                        services:
                          app:
                            build: .
                            environment:
                              JAVA_TOOL_OPTIONS: "-XX:+ExitOnOutOfMemoryError"
                        """)
                .lines(new JvmWithoutExitOnOutOfMemoryRule());
        List<String> withoutFlag = new ProjectFixture(dir.resolve("bare"))
                .write("Dockerfile", dockerfile)
                .write("docker-compose.yml", """
                        services:
                          app:
                            build: .
                            environment:
                              JAVA_TOOL_OPTIONS: "-Xmx512m"
                        """)
                .lines(new JvmWithoutExitOnOutOfMemoryRule());

        assertThat(withCompose).isEmpty();
        assertThat(withoutFlag).containsExactly("Dockerfile:3");
    }

    @Test
    void jvmStartedByContainerCommand() {
        List<String> found = new ProjectFixture(dir.resolve("commands"))
                .write("docker-compose.yml", """
                        services:
                          app:
                            image: registry.local/app:1.4.2
                            command: ["java", "-Xmx512m", "-jar", "/app.jar"]
                          tool:
                            image: registry.local/tool:2.0.1
                            # command: java -jar /tool.jar
                            command: ["/tool/run.sh", "--java-home", "/opt/java"]
                        """)
                .write("deploy/deployment.yaml", """
                        apiVersion: apps/v1
                        kind: Deployment
                        metadata:
                          name: cdc
                        spec:
                          template:
                            spec:
                              containers:
                                - name: cdc
                                  image: registry.local/cdc:1.0.0
                                  command: ["bash", "-c", "java -Dsun.net.inetaddr.ttl=30 -jar *.jar"]
                        """)
                .lines(new JvmWithoutExitOnOutOfMemoryRule());

        // Команда контейнера заменяет команду образа; закомментированная строка и запуск не JVM - не находки
        assertThat(found).containsExactly("deployment.yaml:11", "docker-compose.yml:4");
    }

    @Test
    void waitForFuturesOfCollection() {
        String code = """
                class Facade {
                    void collect(Map<Integer, CompletableFuture<List<?>>> futures, List<List<?>> columns) {
                        futures.forEach((index, future) -> {
                            columns.set(index, future.join());
                        });
                    }
                    void wait(List<Future<String>> futures) throws Exception {
                        for (var future : futures) {
                            future.get();
                        }
                    }
                    void afterAllOf(List<CompletableFuture<String>> futures) throws Exception {
                        CompletableFuture.allOf(futures.toArray(new CompletableFuture[0])).get(1, TimeUnit.MINUTES);
                        futures.forEach(future -> System.out.println(future.join()));
                    }
                    void suppliers(List<Supplier<String>> suppliers) {
                        suppliers.forEach(supplier -> System.out.println(supplier.get()));
                    }
                    void holders(Map<String, AtomicReference<String>> holders) {
                        holders.forEach((name, holder) -> System.out.println(holder.get()));
                    }
                    List<String> results(List<CompletableFuture<String>> futures) {
                        return futures.stream().map(CompletableFuture::join).toList();
                    }
                    List<String> timedResults(List<CompletableFuture<String>> futures) {
                        return futures.stream()
                                .map(future -> future.orTimeout(1, TimeUnit.MINUTES))
                                .map(CompletableFuture::join)
                                .toList();
                    }
                    List<String> joined(List<Thread> threads) {
                        return threads.stream().map(Thread::getName).toList();
                    }
                    void released(List<CompletableFuture<String>> futures) throws Exception {
                        var all = CompletableFuture.allOf(futures.toArray(new CompletableFuture[0]));
                        all.completeOnTimeout(null, 1, TimeUnit.MINUTES).join();
                        for (CompletableFuture<String> future : futures) {
                            System.out.println(future.get());
                        }
                    }
                    void defaulted(List<CompletableFuture<String>> futures) {
                        futures.forEach(future -> future.completeOnTimeout("", 1, TimeUnit.MINUTES));
                        futures.forEach(future -> System.out.println(future.join()));
                    }
                }
                """;

        // Тип элемента виден по коллекции. После allOf(...).get(timeout, unit) и orTimeout ожидание уже ограничено,
        // а allOf(...).completeOnTimeout(...) задачи не завершает: get() в цикле после него ждет без предела
        assertThat(RuleTests.lines(new WaitWithoutTimeoutRule(), code)).containsExactlyInAnyOrder(4, 9, 23, 38);
    }

    @Test
    void invokeAllWithoutTimeout() {
        String code = """
                class Batch {
                    private ExecutorService delegate;
                    List<Future<String>> run(ExecutorService executor, List<Callable<String>> tasks) throws Exception {
                        return executor.invokeAll(tasks);
                    }
                    String first(ExecutorService executor, List<Callable<String>> tasks) throws Exception {
                        return executor.invokeAny(tasks);
                    }
                    List<Future<String>> timed(ExecutorService pool, List<Callable<String>> tasks) throws Exception {
                        return pool.invokeAll(tasks, 1, TimeUnit.MINUTES);
                    }
                    <T> List<Future<T>> invokeAll(Collection<? extends Callable<T>> tasks) throws InterruptedException {
                        return delegate.invokeAll(tasks);
                    }
                    void handlers(HandlerChain chain, List<Handler> handlers) {
                        chain.invokeAll(handlers);
                    }
                }
                """;
        List<Violation> found = RuleTests.check(new WaitWithoutTimeoutRule(), code);

        // Обертка над пулом с тем же методом и invokeAll у чужого типа - не находки
        assertThat(found).extracting(Violation::line).containsExactly(4, 7);
        assertThat(found.get(0).message()).contains("invokeAll(tasks, timeout, unit)");
    }
}
