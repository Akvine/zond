package ru.akvine.zond.rules;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import ru.akvine.zond.rules.codesmell.BooleanLiteralComparisonRule;
import ru.akvine.zond.rules.codesmell.MagicNumberRule;
import ru.akvine.zond.rules.codesmell.NamingConventionRule;
import ru.akvine.zond.rules.codesmell.TestWithoutAssertionRule;
import ru.akvine.zond.rules.codesmell.UnusedCodeRule;
import ru.akvine.zond.rules.concurrency.SwallowedInterruptRule;
import ru.akvine.zond.rules.concurrency.ThisEscapeInConstructorRule;
import ru.akvine.zond.rules.logical.ConstantConditionRule;
import ru.akvine.zond.rules.logical.EqualsHashCodeRule;
import ru.akvine.zond.rules.logical.EqualsWrongSignatureRule;
import ru.akvine.zond.rules.logical.IntegerDivisionRule;
import ru.akvine.zond.rules.logical.MoneyInFloatingPointRule;
import ru.akvine.zond.rules.logical.NarrowingCastRule;
import ru.akvine.zond.rules.logical.NullableDereferenceRule;
import ru.akvine.zond.rules.logical.OptionalMisuseRule;
import ru.akvine.zond.rules.logical.PossibleNullDereferenceRule;
import ru.akvine.zond.rules.logical.ReferenceEqualityRule;
import ru.akvine.zond.rules.logical.SqlTableWithoutPrimaryKeyRule;
import ru.akvine.zond.rules.logical.SwitchFallThroughRule;
import ru.akvine.zond.rules.performance.HttpCallInLoopRule;
import ru.akvine.zond.rules.performance.RepositoryCallInLoopRule;
import ru.akvine.zond.rules.resources.UnclosedResourceRule;
import ru.akvine.zond.rules.security.ComposeSecretInEnvironmentRule;
import ru.akvine.zond.rules.security.DockerRootUserRule;
import ru.akvine.zond.rules.security.UnboundedRequestCollectionRule;

import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Ложные срабатывания, найденные на kkFileView, OpenRefine, Bazel, Elasticsearch и opendataloader-pdf. Каждый
 * случай проверяется рядом с похожим кодом, о котором правило по-прежнему сообщает.
 */
class ToolProjectsFalsePositivesTest {
    @TempDir
    Path dir;

    @Test
    void dataTablesAreNotMagicNumbers() {
        String code = """
                class EncodingDetector {
                    int[][] gbkFreq = new int[126][191];
                    void initialize() {
                        gbkFreq[54][125] = 493;
                        gbkFreq[79][105] = 492;
                        int[] weights = {3, 7, 13};
                        process(weights);
                        long[] statuses = new long[] {300, 700};
                        if (score > 37) {
                            pick();
                        }
                    }
                }
                """;

        // Ячейки таблицы и элементы массива - данные; пара кодов статусов и порог в условии - магические числа
        assertThat(RuleTests.lines(new MagicNumberRule(), code)).containsExactly(8, 8, 9);
    }

    @Test
    void mutableStaticFinalAndConstantWithoutFinal() {
        String code = """
                class DatabaseService {
                    private static final Map<String, DatabaseService> databaseServiceMap = new HashMap<>();
                    private static final DatabaseService instance = new DatabaseService();
                    private static final int maxRetries = 3;
                    public static int DEFAULT_PREVIEW_LIMIT = 100;
                    private String expression_x;
                }
                """;

        // Изменяемое значение static final - не константа; про public static без final говорит jr:35;
        // число в lowerCamelCase и snake_case в поле - нарушения
        assertThat(RuleTests.lines(new NamingConventionRule(), code)).containsExactly(4, 6);
    }

    @Test
    void assertionsInStaticHelperOfAnotherClass() {
        String code = """
                class SerializationAssertions {
                    static void canonicalSerialization(Class<?> type, Object value, String json) {
                        assertEquals(json, write(value));
                    }
                }
                class LibraryTest {
                    @Test
                    void testSerialize() {
                        SerializationAssertions.canonicalSerialization(Library.class, library, "{}");
                    }
                    @Test
                    void testNothing() {
                        library.load();
                    }
                }
                """;

        // Проверка спрятана в статическом методе другого класса проекта
        assertThat(RuleTests.lines(new TestWithoutAssertionRule(), code)).containsExactly(11);
    }

    @Test
    void testHelpersFixturesAndDisabledTests() {
        String code = """
                class RewindingHelper {
                    void runNoLossSmokeTest() {
                        assertThat(run()).isTrue();
                    }
                }
                class RewindingTest {
                    private final RewindingHelper helper = new RewindingHelper();
                    private final EvaluationTestCase ev = new EvaluationTestCase();
                    @Test
                    void noLossSmokeTest() {
                        helper.runNoLossSmokeTest();
                    }
                    @Test
                    void parameterizedMock() {
                        ev.new Scenario().setUp("result = mock.method('bar')").testLookup("result", "bar");
                    }
                    @Test
                    @Ignore
                    void alwaysIgnored() {
                    }
                    @Test
                    void safelyHandleModuleInfo() {
                        // We don't need to assert anything - just make sure the tool doesn't throw
                        tool.read(moduleInfo);
                    }
                    @Test
                    void justRuns() {
                        tool.read(moduleInfo);
                    }
                    public static class PassingTest {
                        @Test
                        public void alwaysPasses() {
                        }
                    }
                }
                """;

        // Помощник по полю, проверка testLookup, @Ignore, объяснение в комментарии и заготовка для запускателя
        assertThat(RuleTests.lines(new TestWithoutAssertionRule(), code)).containsExactly(26);
    }

    @Test
    void serviceMethodNamedTestIsNotAssertion() {
        String code = """
                class SampleTest {
                    @Test
                    public void testTran() {
                        jeecgDemoService.testTran();
                    }
                    @Test
                    public void testDict() {
                        this.testDict("sys_user", "username");
                    }
                    private void testDict(String table, String code) {
                        System.out.println(table + code);
                    }
                    @Test
                    public void inherited() {
                        testExpression("1 + 1", 2);
                    }
                    @Test
                    public void testNulls() {
                        NullPointerTester tester = new NullPointerTester();
                        tester.testAllPublicConstructors(ExtraDataList.class);
                    }
                }
                """;

        // Метод сервиса и свой помощник без проверок ничего не проверяют; testExpression из базового класса
        // библиотеки и NullPointerTester из Guava посмотреть нельзя - по имени они проверяют сами
        assertThat(RuleTests.lines(new TestWithoutAssertionRule(), code)).containsExactly(2, 6);
    }

    @Test
    void ownMethodsNamedEqual() {
        String code = """
                class StarlarkFloat {
                    private static boolean equal(double x, double y) {
                        return x == y;
                    }
                    public int equal(ReferenceValue other) {
                        return other.equal(this);
                    }
                    public boolean equal(Object other) {
                        return this == other;
                    }
                    public int hashcode() {
                        return 1;
                    }
                }
                """;

        // Статический помощник и метод с другим типом результата - свои методы; опечатки - последние два
        assertThat(RuleTests.lines(new EqualsWrongSignatureRule(), code)).containsExactly(8, 11);
    }

    @Test
    void autoValueAndAbstractHashCode() {
        String code = """
                @AutoValue
                abstract class PlatformProperties {
                    @Memoized
                    @Override
                    public abstract int hashCode();
                }
                abstract class MemberInfo {
                    @Override
                    public abstract int hashCode();
                }
                class Label {
                    @Override
                    public int hashCode() {
                        return 1;
                    }
                }
                """;

        // equals и hashCode создает AutoValue; объявление без тела ничего не переопределяет
        assertThat(RuleTests.lines(new EqualsHashCodeRule(), code)).containsExactly(12);
    }

    @Test
    void rememberedInterruptIsRestoredLater() {
        String code = """
                class Waiter {
                    void sleepUninterruptibly(long end) {
                        boolean interrupted = false;
                        try {
                            while (true) {
                                try {
                                    TimeUnit.NANOSECONDS.sleep(end - System.nanoTime());
                                    return;
                                } catch (InterruptedException e) {
                                    interrupted = true;
                                }
                            }
                        } finally {
                            if (interrupted) {
                                Thread.currentThread().interrupt();
                            }
                        }
                    }
                    void lose() {
                        boolean interrupted = false;
                        try {
                            Thread.sleep(10);
                        } catch (InterruptedException e) {
                            interrupted = true;
                        }
                    }
                }
                """;

        // Прерывание запомнили и восстановили в finally; во втором методе флаг так и остался в переменной
        assertThat(RuleTests.lines(new SwallowedInterruptRule(), code)).containsExactly(23);
    }

    @Test
    void thisInPreconditionMessageDoesNotEscape() {
        String code = """
                class ActionExecutedEvent {
                    ActionExecutedEvent(Exception exception, Registry registry) {
                        this.exception = exception;
                        Preconditions.checkState(this.exception == null, this);
                        checkArgument(exception != null, "Bad event: %s", this);
                        registry.register(this);
                    }
                }
                """;

        // В сообщение проверки объект только печатают; регистрация выносит его наружу
        assertThat(RuleTests.lines(new ThisEscapeInConstructorRule(), code)).containsExactly(6);
    }

    @Test
    void cachedConnectionOfManagerIsShared() {
        String code = """
                class ConnectionManager {
                    private Connection connection;
                    Connection getConnection(Config config) throws SQLException {
                        if (connection != null && !connection.isClosed()) {
                            return connection;
                        }
                        connection = DriverManager.getConnection(config.url());
                        return connection;
                    }
                }
                class Service {
                    private final ConnectionManager manager = new ConnectionManager();
                    private final DataSource dataSource;
                    void query(Config config) throws SQLException {
                        Connection shared = manager.getConnection(config);
                        shared.createStatement().close();
                        Connection own = dataSource.getConnection();
                        own.createStatement().close();
                    }
                }
                """;

        // Менеджер отдает закешированное соединение - закрывать его нельзя; соединение из пула - нужно
        assertThat(RuleTests.lines(new UnclosedResourceRule(), code)).containsExactly(17);
    }

    @Test
    void streamOfNonEmptyCollection() {
        String code = """
                class Results {
                    void add(List<Integer> rowIds, Set<String> names, String name, List<Integer> others) {
                        if (!rowIds.isEmpty()) {
                            int first = rowIds.stream().min(Comparator.naturalOrder()).get();
                        }
                        names.add(name);
                        String example = names.stream().findFirst().get();
                        int any = others.stream().filter(id -> id > 0).findFirst().get();
                    }
                }
                """;

        // Поток непустой коллекции; после filter значения может не оказаться
        assertThat(RuleTests.lines(new OptionalMisuseRule(), code)).containsExactly(8);
    }

    @Test
    void analyticalTablesAndStandScripts() {
        List<String> lines = new ProjectFixture(dir)
                .write("fe/src/main/resources/db/migration/V1__init.sql", """
                        CREATE TABLE orders (
                            order_id INT,
                            price DECIMAL(10, 5)
                        )
                        UNIQUE KEY(order_id)
                        DISTRIBUTED BY HASH(order_id) BUCKETS 3;
                        CREATE TABLE events (
                            id Int64,
                            name String
                        ) ENGINE = MergeTree ORDER BY id;
                        CREATE TABLE audit (
                            id BIGINT,
                            action VARCHAR(64)
                        );
                        """)
                .write("docker/thirdparties/docker-compose/clickhouse/init/03-create-table.sql", """
                        CREATE TABLE doris_test.type (
                            k1 Bool,
                            k2 Date
                        );
                        """)
                .lines(new SqlTableWithoutPrimaryKeyRule());

        // Ключ модели Doris и движок ClickHouse - свой ключ; таблица стенда - не схема продукта
        assertThat(lines).containsExactly("V1__init.sql:11");
    }

    @Test
    void demoComposeAndBuildImages() {
        String dockerfile = """
                FROM eclipse-temurin:21
                COPY app.jar /app.jar
                ENTRYPOINT ["java", "-jar", "/app.jar"]
                """;
        List<String> rootUser = new ProjectFixture(dir)
                .write("docker/runtime/fe/Dockerfile", dockerfile)
                .write("docker/compilation/Dockerfile", dockerfile)
                .write("docker/runtime/all-in-one/Dockerfile.dockerignore", "*.log\n")
                .lines(new DockerRootUserRule());
        List<String> secrets = new ProjectFixture(dir.resolve("compose"))
                .write("docker/runtime/docker-compose-demo/example/mysql/docker-compose.yaml", """
                        services:
                          mysql:
                            image: mysql:8.0
                            environment:
                              - MYSQL_ROOT_PASSWORD=123456
                        """)
                .lines(new ComposeSecretInEnvironmentRule());

        // Образ продукта от root - находка; образ сборки, .dockerignore и демо-стенд - нет
        assertThat(rootUser).containsExactly("Dockerfile:1");
        assertThat(secrets).isEmpty();
    }

    @Test
    void codeRepositoryIsNotDatabase() {
        String code = """
                package com.google.devtools.build.docgen;

                import com.google.devtools.build.lib.cmdline.Label;
                import com.google.devtools.build.lib.cmdline.RepositoryName;

                class BuildDocCollector {
                    void collect(List<Label> labels) {
                        for (Label label : labels) {
                            RepositoryName repository = label.getRepository();
                            String name = repository.getName();
                            String canonical = label.getPackageIdentifier().getRepository().getCanonicalForm();
                        }
                    }
                }
                """;

        // Репозиторий кода Bazel - не таблица: тип импортирован и на репозиторий не похож, а файл с БД не работает
        assertThat(RuleTests.lines(new RepositoryCallInLoopRule(), code)).isEmpty();
    }

    @Test
    void integerDivisionInHugeTableMethod() {
        StringBuilder table = new StringBuilder();
        for (int index = 0; index < 2000; index++) {
            table.append("        freq[").append(index % 90).append("][").append(index % 190).append("] = ")
                    .append(index).append(";\n");
        }
        String code = "class Detector {\n    int[][] freq;\n    double ratio(int hits, int total) {\n"
                + table + "        double ratio = hits / total;\n        return ratio;\n    }\n}\n";

        // Тип места назначения ищется только для выражения с делением: тысячи присваиваний таблицы не тормозят
        long start = System.nanoTime();
        assertThat(RuleTests.lines(new IntegerDivisionRule(), code)).containsExactly(2004);
        assertThat(System.nanoTime() - start).isLessThan(20_000_000_000L);
    }

    @Test
    void negationByComparisonIsProjectConvention() {
        StringBuilder convention = new StringBuilder("class Checks {\n");
        for (int index = 0; index < 10; index++) {
            convention.append("    boolean empty").append(index).append("(List<String> list) { return list.isEmpty() == false; }\n");
        }
        convention.append("    boolean ready(boolean flag) { return flag == true; }\n}\n");
        String single = """
                class Checks {
                    boolean empty(List<String> list) { return list.isEmpty() == false; }
                    boolean ready(boolean flag) { return !flag; }
                }
                """;

        // Так в Elasticsearch пишут каждое отрицание; '== true' по-прежнему лишнее, как и случайное '== false'
        assertThat(RuleTests.lines(new BooleanLiteralComparisonRule(), convention.toString())).containsExactly(12);
        assertThat(RuleTests.lines(new BooleanLiteralComparisonRule(), single)).containsExactly(2);
    }

    @Test
    void privateMethodLookedUpByName() {
        String code = """
                class DefMath {
                    private static int rsh(int a, int b) { return a >> b; }
                    private static int lsh(int a, int b) { return a << b; }
                    static MethodHandle shift(MethodType type) throws Exception {
                        return MethodHandles.lookup().findStatic(DefMath.class, "rsh", type);
                    }
                }
                """;

        // rsh берут через MethodHandles по имени, lsh не нужен никому
        assertThat(RuleTests.lines(new UnusedCodeRule(), code)).containsExactly(3);
    }

    @Test
    void castAfterAssertedRange() {
        String code = """
                class ReleasableByteArray {
                    Cursor get(long index, int len) {
                        assert indexIsInt(index);
                        return ref.slice((int) index, len);
                    }
                    Cursor unchecked(long index, int len) {
                        return ref.slice((int) index, len);
                    }
                }
                """;

        assertThat(RuleTests.lines(new NarrowingCastRule(), code)).containsExactly(7);
    }

    @Test
    void assertedNotNullAndIndexWithinSize() {
        String code = """
                class Health {
                    Executor executor(IndexShard shard) {
                        assert shard != null;
                        return shard.isSystem() ? system : regular;
                    }
                    Executor checked(IndexShard shard) {
                        if (shard == null) {
                            log.warn("no shard");
                        }
                        return shard.isSystem() ? system : regular;
                    }
                    void collect(IndexRoutingTable table) {
                        for (int i = 0; i < table.size(); i++) {
                            ShardTable shard = table.shard(i);
                            ids.add(shard.shardId());
                        }
                        ShardTable first = table.shard(5);
                        ids.add(first.shardId());
                    }
                    String timezone(String json) {
                        ScheduleParam param = JSONUtils.parseObject(json, ScheduleParam.class);
                        assert param != null;
                        return param.getTimezoneId();
                    }
                    void fail(IndexRoutingTable table, int id) {
                        assert table.shard(id).shardId() != null;
                    }
                }
                class IndexRoutingTable {
                    private ShardTable[] shards;
                    int size() { return shards.length; }
                    @Nullable
                    ShardTable shard(int id) {
                        if (id > shards.length - 1) {
                            return null;
                        }
                        return shards[id];
                    }
                }
                """;

        // assert - обещание, что null здесь нет; shard(i) в цикле по size() того же объекта в пределах таблицы.
        // Разбор чужого JSON assert не защищает: в рабочей среде утверждения выключены. Обращение внутри самого
        // assert выполняется только в тестах
        assertThat(RuleTests.lines(new PossibleNullDereferenceRule(), code)).containsExactly(10, 18, 23);
    }

    @Test
    void enumConstantOverridesMethod() {
        String code = """
                enum IndexMode {
                    STANDARD,
                    COLUMNAR {
                        @Override
                        boolean isColumnar() { return true; }
                    };
                    boolean isColumnar() { return false; }
                    boolean isLegacy() { return false; }
                }
                class Settings {
                    void apply(IndexMode mode) {
                        if (mode.isColumnar() == false) {
                            plain();
                        }
                        if (mode.isLegacy() == false) {
                            modern();
                        }
                    }
                }
                """;

        // isColumnar() константа COLUMNAR переопределяет, а isLegacy() у всех констант одно
        assertThat(RuleTests.lines(new ConstantConditionRule(), code)).containsExactly(15);
    }

    @Test
    void checkRepeatedForDisabledAssertions() {
        String code = """
                class Metrics {
                    void record(Supplier<List<Double>> observer, long total) {
                        List<Double> all = observer.get();
                        assert all != null : "must not pass null values";
                        if (all == null) {
                            return;
                        }
                        assert total >= 0 : "expected positive total";
                        if (total < 0) {
                            log.error("negative total [{}]", total);
                        }
                        List<Double> copy = new ArrayList<>();
                        if (copy == null) {
                            return;
                        }
                    }
                }
                """;

        // Утверждения обычно выключены: проверка после assert - защита на этот случай, а не лишнее условие
        assertThat(RuleTests.lines(new ConstantConditionRule(), code)).containsExactly(13);
    }

    @Test
    void intendedReferenceComparisons() {
        String code = """
                class Settings {
                    void normalize(Map<String, Object> map, String prefix, Integer total, Integer failed, Integer ok,
                                   Boolean first, Boolean second, String name, String other) {
                        for (Map.Entry<String, Object> entry : map.entrySet()) {
                            String normalizedKey = normalizeKeyPrefix(entry.getKey(), prefix);
                            if (normalizedKey != entry.getKey()) {
                                replace(normalizedKey);
                            }
                        }
                        assert name == TIMESTAMP_FIELD_NAME;
                        if (name == name.intern()) {}
                        if (total == failed + ok) {}
                        if (first != second) {}
                        if (name == other) {}
                        if (total == failed) {}
                    }
                }
                """;

        // Тот же объект после нормализации, интернирование, assert, арифметика и Boolean - не ошибка
        assertThat(RuleTests.lines(new ReferenceEqualityRule(), code)).containsExactly(14, 15);
    }

    @Test
    void hashTailCascadeAndFallthruComment() {
        String code = """
                class Murmur {
                    long tail(byte[] data, int length, int mode) {
                        long k = 0;
                        switch (length & 7) {
                            case 3:
                                k ^= data[2] << 16;
                            case 2:
                                k ^= data[1] << 8;
                            case 1:
                                k ^= data[0];
                                break;
                            default:
                                break;
                        }
                        switch (mode) {
                            case 1:
                                start();
                            case 5:
                                stop();
                                break;
                            case 6:
                                if (data.length > 0) {
                                    stop();
                                    break;
                                } // else fallthru, lenient parsing
                            default:
                                reset();
                        }
                        return k;
                    }
                }
                """;

        // Хвост хеша по убыванию меток и помеченное проваливание - намеренные; 1 -> 5 - нет
        assertThat(RuleTests.lines(new SwitchFallThroughRule(), code)).containsExactly(16);
    }

    @Test
    void floatingFieldsOutsideMoney() {
        String statistics = """
                class InternalStats {
                    private double sum;
                    private double min;
                    private float indexBalanceFactor;
                    private static final float MATCH_COST = 1000f;
                    private final double discount;
                    private double syntaxCost;
                }
                """;
        String invoice = """
                class Invoice {
                    private double total;
                    private double discount;
                    private double price;
                    private double priceWeight;
                }
                """;

        // Сумма гистограммы, коэффициент балансировки и стоимость запроса - не деньги; в счете - деньги
        assertThat(RuleTests.lines(new MoneyInFloatingPointRule(), statistics)).isEmpty();
        assertThat(RuleTests.lines(new MoneyInFloatingPointRule(), invoice)).containsExactly(2, 3, 4);
    }

    @Test
    void transportRequestReadsItself() {
        String code = """
                class GetAliasesRequest extends LocalClusterStateRequest {
                    private List<String> indices;
                    GetAliasesRequest(StreamInput in) throws IOException {
                        indices = in.readStringCollectionAsList();
                    }
                    public void writeTo(StreamOutput out) throws IOException {
                        out.writeStringCollection(indices);
                    }
                }
                class GenerateRequest {
                    private List<Column> columns;
                }
                """;

        // Запрос транспорта собирает себя из потока сам - Bean Validation до него не доходит
        assertThat(RuleTests.lines(new UnboundedRequestCollectionRule(), code)).containsExactly(11);
    }

    @Test
    void asyncCallWithListenerInLoop() {
        String code = """
                class Downloader {
                    void deleteAll(List<String> ids, List<Order> orders) {
                        for (String id : ids) {
                            client.execute(DeleteAction.INSTANCE, new DeleteRequest(id), listener);
                            projectClient.admin().indices().delete(new DeleteIndexRequest(id), ActionListener.wrap(r -> {}, e -> {}));
                        }
                        for (Order order : orders) {
                            paymentClient.charge(order);
                        }
                    }
                }
                """;

        // Ответ придет в обработчик, цикл его не ждет; списание у платежного клиента ждет ответа
        assertThat(RuleTests.lines(new HttpCallInLoopRule(), code)).containsExactly(8);
    }

    @Test
    void standardSystemPropertyIsAlwaysSet() {
        String code = """
                class Platform {
                    static Platform current() {
                        String os = System.getProperty("os.name");
                        String custom = System.getProperty("es.platform");
                        if (os.startsWith("Linux") || custom.isEmpty()) {
                            return LINUX;
                        }
                        return OTHER;
                    }
                }
                """;

        assertThat(RuleTests.lines(new NullableDereferenceRule(), code)).containsExactly(5);
    }
}
