package ru.akvine.zond.rules;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Правила jr:177 - jr:186: общая Java
 */
class JavaCoreRulesTest {

    @Test
    void defaultCharset() {
        assertThat(RuleTests.lines(new CheckDefaultCharsetRule(), """
                class Sample {
                    void run(String text, byte[] data, File file, MultipartFile upload) throws IOException {
                        byte[] a = text.getBytes();
                        String b = new String(data);
                        Reader c = new FileReader(file);
                        byte[] ok1 = text.getBytes(StandardCharsets.UTF_8);
                        String ok2 = new String(data, StandardCharsets.UTF_8);
                        byte[] ok3 = upload.getBytes();
                        String ok4 = new String("copy");
                    }
                }
                """)).containsExactly(3, 4, 5);
    }

    @Test
    void bigDecimalDivide() {
        assertThat(RuleTests.lines(new CheckBigDecimalDivideRule(), """
                class Sample {
                    void run(BigDecimal total, BigDecimal count, int parts) {
                        BigDecimal a = total.divide(count);
                        BigDecimal b = BigDecimal.ONE.divide(count);
                        BigDecimal ok1 = total.divide(count, 2, RoundingMode.HALF_UP);
                        BigDecimal ok2 = total.divide(count, MathContext.DECIMAL64);
                        int ok3 = calculator.divide(parts);
                    }
                }
                """)).containsExactly(3, 4);
    }

    @Test
    void streamReuse() {
        assertThat(RuleTests.lines(new CheckStreamReuseRule(), """
                class Sample {
                    void run(List<String> items) {
                        Stream<String> stream = items.stream();
                        long count = stream.count();
                        List<String> list = stream.toList();
                        Stream<String> once = items.stream();
                        once.forEach(this::use);
                        Stream<String> chained = items.stream();
                        chained = chained.filter(String::isEmpty);
                        chained.count();
                    }
                }
                """)).containsExactly(5);
    }

    @Test
    void ignoredBooleanResult() {
        assertThat(RuleTests.lines(new CheckIgnoredBooleanResultRule(), """
                class Sample {
                    void run(File file, Lock lock, BlockingQueue<String> queue, List<String> list) throws Exception {
                        file.delete();
                        file.mkdirs();
                        lock.tryLock();
                        queue.offer("x");
                        boolean deleted = file.delete();
                        if (lock.tryLock()) {
                            work();
                        }
                        list.add("x");
                        Files.delete(file.toPath());
                    }
                }
                """)).containsExactly(3, 4, 5, 6);
    }

    @Test
    void equalsWrongSignature() {
        assertThat(RuleTests.lines(new CheckEqualsWrongSignatureRule(), """
                class Order {
                    public boolean equals(Order other) { return true; }
                    public int hashcode() { return 1; }
                    public String tostring() { return ""; }
                }
                class Good {
                    public boolean equals(Object other) { return true; }
                    public boolean equals(Good other) { return true; }
                    public int hashCode() { return 1; }
                }
                """)).containsExactly(2, 3, 4);
    }

    @Test
    void internalCollectionExposure() {
        assertThat(RuleTests.lines(new CheckInternalCollectionExposureRule(), """
                class Registry {
                    private final List<String> names = new ArrayList<>();
                    private final List<String> fixed = List.of("a");
                    private String title;
                    private byte[] data;
                    public List<String> getNames() { return names; }
                    public List<String> getFixed() { return fixed; }
                    public String getTitle() { return title; }
                    public byte[] getData() { return this.data; }
                    public List<String> copy() { return List.copyOf(names); }
                }
                @Entity
                class Order {
                    private List<Item> items;
                    public List<Item> getItems() { return items; }
                }
                """)).containsExactly(6, 9);
    }

    @Test
    void caseWithoutLocale() {
        assertThat(RuleTests.lines(new CheckCaseWithoutLocaleRule(), """
                class Sample {
                    void run(String text) {
                        String a = text.toLowerCase();
                        String b = text.toUpperCase();
                        String ok = text.toLowerCase(Locale.ROOT);
                    }
                }
                """)).containsExactly(3, 4);
    }

    @Test
    void instantUnsupportedUnit() {
        assertThat(RuleTests.lines(new CheckInstantUnsupportedUnitRule(), """
                class Sample {
                    void run(Instant moment) {
                        Instant a = moment.plus(1, ChronoUnit.MONTHS);
                        Instant b = Instant.now().minus(1, ChronoUnit.YEARS);
                        Instant ok1 = moment.plus(1, ChronoUnit.DAYS);
                        LocalDate ok2 = date.plus(1, ChronoUnit.MONTHS);
                    }
                }
                """)).containsExactly(3, 4);
    }

    @Test
    void absOfHashCode() {
        assertThat(RuleTests.lines(new CheckAbsOfHashCodeRule(), """
                class Sample {
                    int run(String key, Random random, int buckets) {
                        int a = Math.abs(key.hashCode()) % buckets;
                        int b = Math.abs(random.nextInt()) % buckets;
                        int ok1 = Math.abs(random.nextInt(10));
                        int ok2 = Math.floorMod(key.hashCode(), buckets);
                        return Math.abs(buckets);
                    }
                }
                """)).containsExactly(3, 4);
    }

    @Test
    void builderDefault() {
        assertThat(RuleTests.lines(new CheckBuilderDefaultRule(), """
                @Builder
                class Settings {
                    private int retries = 3;
                    @Builder.Default
                    private int timeout = 30;
                    private String name;
                    private final String kind = "x";
                    private static int counter = 0;
                }
                class Plain {
                    private int retries = 3;
                }
                """)).containsExactly(3);
    }
}
