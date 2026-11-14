package ru.akvine.zond.rules;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import ru.akvine.zond.enums.Confidence;
import ru.akvine.zond.models.Violation;
import ru.akvine.zond.rules.performance.BatchOutsideTransactionRule;
import ru.akvine.zond.rules.performance.ConnectionBypassesTransactionRule;
import ru.akvine.zond.rules.performance.EnumLookupByValuesScanRule;
import ru.akvine.zond.rules.performance.HiddenReflectionInHotMethodRule;
import ru.akvine.zond.rules.performance.IdentityInsertPerStatementRule;
import ru.akvine.zond.rules.performance.JdbcUrlWithoutBatchRewriteRule;
import ru.akvine.zond.rules.performance.KeyDerivedValuePerRowRule;
import ru.akvine.zond.rules.performance.RepeatedQueryAcrossCallsRule;
import ru.akvine.zond.rules.performance.UntypedBatchParametersRule;
import ru.akvine.zond.rules.performance.WorkBeforeCacheLookupUnderLockRule;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Правила jr:524 - jr:533: что замедляет пакетную запись в базу и подготовку строк к ней. В каждом тесте рядом
 * с кодом, о котором правило сообщает, стоит похожий код, о котором оно обязано молчать
 */
class BatchWriteOptimizationRulesTest {
    private static final String OLD_SPRING = "plugins {\n    id 'org.springframework.boot' version '2.7.0'\n}\n";
    private static final String NEW_SPRING = "plugins {\n    id 'org.springframework.boot' version '3.2.1'\n}\n";

    private static final String BATCHES = """
            class Rows {
                private NamedParameterJdbcTemplate namedJdbc;
                private JdbcTemplate jdbcTemplate;
                void maps(String sql, List<Map<String, Object>> rows) {
                    Map<String, Object>[] batch = new Map[rows.size()];
                    namedJdbc.batchUpdate(sql, batch);
                }
                void arrays(String sql, List<Object[]> args) {
                    jdbcTemplate.batchUpdate(sql, args);
                }
                void typedArrays(String sql, List<Object[]> args, int[] types) {
                    jdbcTemplate.batchUpdate(sql, args, types);
                }
                void typedSources(String sql, List<Row> rows) {
                    SqlParameterSource[] batch = rows.stream()
                            .map(row -> new MapSqlParameterSource().addValue("name", row.name(), Types.VARCHAR))
                            .toArray(SqlParameterSource[]::new);
                    namedJdbc.batchUpdate(sql, batch);
                }
                void untypedSources(String sql, List<Row> rows) {
                    SqlParameterSource[] batch = rows.stream()
                            .map(row -> new MapSqlParameterSource().addValue("name", row.name()))
                            .toArray(SqlParameterSource[]::new);
                    namedJdbc.batchUpdate(sql, batch);
                }
                void beans(String sql, List<Row> rows) {
                    namedJdbc.batchUpdate(sql, SqlParameterSourceUtils.createBatch(rows));
                }
                void createdFromMaps(String sql, List<Map<String, Object>> rows) {
                    namedJdbc.batchUpdate(sql, SqlParameterSourceUtils.createBatch(rows));
                }
                void setter(String sql, BatchPreparedStatementSetter setter) {
                    jdbcTemplate.batchUpdate(sql, setter);
                }
                void empty(String sql) {
                    namedJdbc.batchUpdate(sql, new Map[] {});
                }
            }
            """;

    @TempDir
    Path dir;

    @Test
    void batchValuesWithoutSqlTypes() {
        ProjectFixture project = new ProjectFixture(dir).write("build.gradle", OLD_SPRING).source("Rows.java", BATCHES);

        // Карты, массивы без типов, MapSqlParameterSource без типа и createBatch из карт; типы из бинов, из
        // третьего аргумента и из сеттера правило не трогает
        assertThat(project.lines(new UntypedBatchParametersRule()))
                .containsExactly("Rows.java:24", "Rows.java:30", "Rows.java:6", "Rows.java:9");
        assertThat(project.check(new UntypedBatchParametersRule()))
                .allMatch(violation -> violation.confidenceOrDefault() == Confidence.PROBABLE);
    }

    @Test
    void batchValuesWithoutSqlTypesDependOnSpringVersion() {
        // Spring Boot 3.2.1 сам не спрашивает тип у PostgreSQL и SQL Server
        assertThat(new ProjectFixture(dir.resolve("new")).write("build.gradle", NEW_SPRING).source("Rows.java", BATCHES)
                .check(new UntypedBatchParametersRule())).isEmpty();

        // Настройка уже задана
        assertThat(new ProjectFixture(dir.resolve("flag")).write("build.gradle", OLD_SPRING)
                .source("Rows.java", BATCHES)
                .source("Setup.java", """
                        class Setup {
                            static void apply() {
                                SpringProperties.setFlag("spring.jdbc.getParameterType.ignore");
                            }
                        }
                        """)
                .check(new UntypedBatchParametersRule())).isEmpty();

        // Версия Spring в сборке не названа: находка остается, но как подозрение
        assertThat(new ProjectFixture(dir.resolve("unknown")).write("readme.sql", "select 1;")
                .source("Rows.java", BATCHES)
                .check(new UntypedBatchParametersRule()))
                .hasSize(4)
                .allMatch(violation -> violation.confidenceOrDefault() == Confidence.SUSPICION);
    }

    @Test
    void jdbcUrlWithoutBatchRewrite() {
        String writer = """
                class Writer {
                    private JdbcTemplate jdbcTemplate;
                    void write(String sql, List<Object[]> rows) {
                        jdbcTemplate.batchUpdate(sql, rows);
                    }
                    String mysql(String host) {
                        return "jdbc:mysql://" + host + "/app";
                    }
                    String mssql(String host) {
                        return "jdbc:sqlserver://" + host + ";databaseName=app";
                    }
                }
                """;
        ProjectFixture project = new ProjectFixture(dir.resolve("plain"))
                .write("src/main/resources/application.properties",
                        "spring.application.name=app\nspring.datasource.url=jdbc:postgresql://db:5432/app\n")
                // Профиль тестов в рабочую среду не попадает
                .write("src/main/resources/application-test.properties",
                        "spring.datasource.url=jdbc:postgresql://localhost:5432/test\n")
                .source("Writer.java", writer);
        assertThat(project.lines(new JdbcUrlWithoutBatchRewriteRule()))
                .containsExactly("Writer.java:10", "Writer.java:7", "application.properties:2");
        // У массовой загрузки SQL Server есть ограничения - это только подозрение
        assertThat(project.check(new JdbcUrlWithoutBatchRewriteRule()).stream()
                .filter(violation -> violation.line() == 10)
                .map(Violation::confidenceOrDefault))
                .containsExactly(Confidence.SUSPICION);

        // Параметр в адресе либо в свойствах источника данных
        assertThat(new ProjectFixture(dir.resolve("url"))
                .write("src/main/resources/application.properties",
                        "spring.datasource.url=jdbc:postgresql://db:5432/app?reWriteBatchedInserts=true\n")
                .source("Writer.java", "class Writer { void write(JdbcTemplate jdbc) { jdbc.batchUpdate(\"x\"); } }")
                .check(new JdbcUrlWithoutBatchRewriteRule())).isEmpty();
        assertThat(new ProjectFixture(dir.resolve("hikari"))
                .write("src/main/resources/application.properties", """
                        spring.datasource.url=jdbc:postgresql://db:5432/app
                        spring.datasource.hikari.data-source-properties.reWriteBatchedInserts=true
                        """)
                .source("Writer.java", "class Writer { void write(JdbcTemplate jdbc) { jdbc.batchUpdate(\"x\"); } }")
                .check(new JdbcUrlWithoutBatchRewriteRule())).isEmpty();

        // Пакетами проект не пишет - параметр ничего бы не дал
        assertThat(new ProjectFixture(dir.resolve("single"))
                .write("src/main/resources/application.properties",
                        "spring.datasource.url=jdbc:postgresql://db:5432/app\n")
                .source("Writer.java", "class Writer { void write(JdbcTemplate jdbc) { jdbc.update(\"x\"); } }")
                .check(new JdbcUrlWithoutBatchRewriteRule())).isEmpty();

        // Пакеты Hibernate включены настройкой
        assertThat(new ProjectFixture(dir.resolve("hibernate"))
                .write("src/main/resources/application.properties", """
                        spring.datasource.url=jdbc:postgresql://db:5432/app
                        spring.jpa.properties.hibernate.jdbc.batch_size=50
                        """)
                .source("Writer.java", "class Writer { }")
                .lines(new JdbcUrlWithoutBatchRewriteRule())).containsExactly("application.properties:1");
    }

    @Test
    void identityInsertInsideStatementText() {
        String queries = """
                class Queries {
                    private static final String PRE = "set identity_insert %s on\\n";
                    private static final String POST = "\\nset identity_insert %s off";
                    static String wrapped(String table, String insert) {
                        return String.format(PRE, table) + insert + String.format(POST, table);
                    }
                    static void separate(Statement statement, String table) throws SQLException {
                        statement.execute("SET IDENTITY_INSERT " + table + " ON");
                        statement.executeBatch();
                        statement.execute("SET IDENTITY_INSERT " + table + " OFF");
                    }
                }
                """;
        String writer = "class Writer { void write(JdbcTemplate jdbc, String sql) { jdbc.batchUpdate(sql); } }";

        // Переключатель отдельными командами вокруг пакета - как раз то, что нужно
        assertThat(RuleTests.checkProject(new IdentityInsertPerStatementRule(),
                Map.of("Queries.java", queries, "Writer.java", writer)))
                .extracting(Violation::line).containsExactly(5);
        // Без пакетов переключатель в тексте запроса ничего не замедляет
        assertThat(RuleTests.checkProject(new IdentityInsertPerStatementRule(), Map.of("Queries.java",
                queries.replace("statement.executeBatch();", "statement.execute(table);")))).isEmpty();
    }

    @Test
    void batchOutsideTransaction() {
        String writer = """
                class Writer {
                    private JdbcTemplate jdbcTemplate;
                    private TransactionTemplate transactionTemplate;
                    private DataSource dataSource;
                    void plain(String sql, List<Object[]> rows) {
                        jdbcTemplate.batchUpdate(sql, rows);
                    }
                    @Transactional
                    void annotated(String sql, List<Object[]> rows) {
                        jdbcTemplate.batchUpdate(sql, rows);
                    }
                    void template(String sql, List<Object[]> rows) {
                        transactionTemplate.executeWithoutResult(status -> jdbcTemplate.batchUpdate(sql, rows));
                    }
                    void raw(String sql) throws SQLException {
                        try (Connection connection = dataSource.getConnection();
                             PreparedStatement statement = connection.prepareStatement(sql)) {
                            statement.addBatch();
                            statement.executeBatch();
                        }
                    }
                    void rawInTransaction(String sql) throws SQLException {
                        try (Connection connection = dataSource.getConnection();
                             PreparedStatement statement = connection.prepareStatement(sql)) {
                            connection.setAutoCommit(false);
                            statement.addBatch();
                            statement.executeBatch();
                            connection.commit();
                        }
                    }
                    void given(PreparedStatement statement) throws SQLException {
                        statement.executeBatch();
                    }
                    void calledInTransaction(String sql, List<Object[]> rows) {
                        jdbcTemplate.batchUpdate(sql, rows);
                    }
                }
                """;
        String facade = """
                class Facade {
                    private Writer writer;
                    @Transactional
                    void run(String sql, List<Object[]> rows) {
                        writer.calledInTransaction(sql, rows);
                    }
                }
                """;
        ProjectFixture mysql = new ProjectFixture(dir.resolve("mysql"))
                .write("src/main/resources/application.properties", "spring.datasource.url=jdbc:mysql://db:3306/app\n")
                .source("Writer.java", writer).source("Facade.java", facade);
        // Аннотация, TransactionTemplate, ручная транзакция и транзакция вызывающего метода - пакет уже в транзакции;
        // соединение, полученное параметром, мог настроить вызывающий код
        assertThat(mysql.lines(new BatchOutsideTransactionRule())).containsExactly("Writer.java:19", "Writer.java:6");
        assertThat(mysql.check(new BatchOutsideTransactionRule()))
                .allMatch(violation -> violation.confidenceOrDefault() == Confidence.PROBABLE);

        // PostgreSQL выполняет пакет одной неявной транзакцией - ускорять нечего
        assertThat(new ProjectFixture(dir.resolve("postgres"))
                .write("src/main/resources/application.properties",
                        "spring.datasource.url=jdbc:postgresql://db:5432/app\n")
                .source("Writer.java", writer).source("Facade.java", facade)
                .check(new BatchOutsideTransactionRule())).isEmpty();

        // База по проекту не видна
        assertThat(new ProjectFixture(dir.resolve("unknown")).write("readme.sql", "select 1;")
                .source("Writer.java", writer).source("Facade.java", facade)
                .check(new BatchOutsideTransactionRule()))
                .hasSize(2)
                .allMatch(violation -> violation.confidenceOrDefault() == Confidence.SUSPICION);
    }

    @Test
    void valueDerivedFromMapKeyOnEveryRow() {
        String exporter = """
                class Exporter {
                    void export(List<Map<String, Object>> rows) {
                        for (Map<String, Object> row : rows) {
                            Map<String, Object> hashed = new HashMap<>();
                            row.forEach((column, value) -> hashed.put(Hasher.hash(column), value));
                            row.forEach((column, value) -> hashed.put(column.toLowerCase(), value));
                            for (Map.Entry<String, Object> entry : row.entrySet()) {
                                hashed.put(DigestUtils.sha256Hex(entry.getKey()), entry.getValue());
                                hashed.put(entry.getKey(), DigestUtils.sha256Hex(entry.getValue().toString()));
                            }
                        }
                    }
                    void once(Map<String, Object> row, Map<String, Object> hashed) {
                        row.forEach((column, value) -> hashed.put(Hasher.hash(column), value));
                    }
                }
                """;
        String hasher = """
                class Hasher {
                    static String hash(String name) {
                        return DigestUtils.sha256Hex(name.getBytes());
                    }
                }
                """;

        // Хеш значения у каждой строки свой, а обход одной карты - не обход строк
        List<Violation> violations = RuleTests.checkProject(new KeyDerivedValuePerRowRule(),
                Map.of("Exporter.java", exporter, "Hasher.java", hasher));
        assertThat(violations).extracting(Violation::line).containsExactly(5, 8);
        assertThat(violations.get(0).message()).contains("hash → DigestUtils.sha256Hex");
    }

    @Test
    void connectionTakenPastTransaction() {
        String saver = """
                class Saver {
                    private DataSource dataSource;
                    private JdbcTemplate jdbcTemplate;
                    private TransactionTemplate transactionTemplate;
                    @Transactional
                    void annotated() throws SQLException {
                        try (Connection connection = dataSource.getConnection()) {
                            connection.createStatement();
                        }
                    }
                    void template() {
                        transactionTemplate.execute(status -> {
                            try (Connection connection = jdbcTemplate.getDataSource().getConnection()) {
                                return connection.createStatement();
                            } catch (SQLException exception) {
                                throw new IllegalStateException(exception);
                            }
                        });
                    }
                    void plain() throws SQLException {
                        try (Connection connection = dataSource.getConnection()) {
                            connection.createStatement();
                        }
                    }
                    @Transactional
                    void bound() {
                        Connection connection = DataSourceUtils.getConnection(dataSource);
                    }
                    @Transactional
                    void otherDatabase(DataSource target) throws SQLException {
                        try (Connection connection = target.getConnection()) {
                            connection.createStatement();
                        }
                    }
                    @Transactional(propagation = Propagation.NOT_SUPPORTED)
                    void suspended() throws SQLException {
                        try (Connection connection = dataSource.getConnection()) {
                            connection.createStatement();
                        }
                    }
                }
                """;

        // Вне транзакции, через DataSourceUtils и к чужой базе - второе соединение никому не мешает
        assertThat(RuleTests.lines(new ConnectionBypassesTransactionRule(), saver)).containsExactly(7, 13);
        // Такой источник данных сам отдает соединение транзакции
        assertThat(RuleTests.checkProject(new ConnectionBypassesTransactionRule(), Map.of("Saver.java", saver,
                "Config.java", """
                        class Config {
                            DataSource aware(DataSource target) {
                                return new TransactionAwareDataSourceProxy(target);
                            }
                        }
                        """))).isEmpty();
    }

    @Test
    void workUnderLockBeforeCacheLookup() {
        String code = """
                class Pools {
                    private static final Logger log = LoggerFactory.getLogger(Pools.class);
                    private final Map<String, Pool> pools = new HashMap<>();
                    private Decryptor decryptor;
                    synchronized Pool get(Settings settings) {
                        Settings plain = decryptor.decrypt(settings);
                        String key = plain.url();
                        if (!pools.containsKey(key)) {
                            pools.put(key, new Pool(plain));
                        }
                        return pools.get(key);
                    }
                    synchronized Pool lookupFirst(Settings settings) {
                        Pool pool = pools.get(settings.id());
                        if (pool == null) {
                            pool = new Pool(decryptor.decrypt(settings));
                            pools.put(settings.id(), pool);
                        }
                        return pool;
                    }
                    Pool logged(Settings settings) {
                        synchronized (pools) {
                            log.debug("lookup {}", settings.id());
                            if (!pools.containsKey(settings.id())) {
                                pools.put(settings.id(), new Pool(settings));
                            }
                            return pools.get(settings.id());
                        }
                    }
                    Pool unlocked(Settings settings) {
                        Settings plain = decryptor.decrypt(settings);
                        return pools.computeIfAbsent(plain.url(), key -> new Pool(plain));
                    }
                }
                """;

        // Расшифровка при промахе, запись в журнал и код без блокировки - не то
        assertThat(RuleTests.lines(new WorkBeforeCacheLookupUnderLockRule(), code)).containsExactly(6);
    }

    @Test
    void sameQueryInsideTwoCalledMethods() {
        String masker = """
                class Masker {
                    private Dictionary dictionary;
                    private Preparer preparer;
                    String mask(String key, String value) {
                        String known = dictionary.findValue(key, value);
                        if (known != null) {
                            return known;
                        }
                        String table = preparer.prepare(key);
                        return table + value;
                    }
                    String rewrite(String key) {
                        String before = dictionary.tableName(key);
                        dictionary.register(key);
                        return before + preparer.prepare(key);
                    }
                    String other(String key, String anotherKey) {
                        String before = dictionary.tableName(key);
                        return before + preparer.prepare(anotherKey);
                    }
                    String differentPairs(String key, String first, String second) {
                        String one = dictionary.byPair(key, first);
                        return one + dictionary.byPair(key, second);
                    }
                    String samePairs(Pair pair) {
                        String one = dictionary.byPair(pair.getKey(), pair.getValue());
                        return one + dictionary.byPair(pair.getKey(), pair.getValue());
                    }
                }
                """;
        String dictionary = """
                class Dictionary {
                    private JdbcTemplate jdbcTemplate;
                    String findValue(String key, String value) {
                        String table = tableName(key);
                        return jdbcTemplate.queryForObject("select v from " + table + " where k = ?", String.class, value);
                    }
                    String tableName(String key) {
                        return jdbcTemplate.queryForObject("select name from tables where key = ?", String.class, key);
                    }
                    void register(String key) {
                        jdbcTemplate.update("insert into tables(key) values (?)", key);
                    }
                    String byPair(String key, String value) {
                        return jdbcTemplate.queryForObject("select v from pairs where k = ? and v = ?", String.class,
                                key, value);
                    }
                }
                """;
        String preparer = """
                class Preparer {
                    private Dictionary dictionary;
                    String prepare(String key) {
                        String table = dictionary.tableName(key);
                        return table == null ? "new" : table;
                    }
                }
                """;

        // Между вызовами в таблицу записали либо аргумент другой - второй запрос не повтор. Совпасть должны все
        // аргументы запроса: у differentPairs общий только ключ
        List<Violation> violations = RuleTests.checkProject(new RepeatedQueryAcrossCallsRule(),
                Map.of("Masker.java", masker, "Dictionary.java", dictionary, "Preparer.java", preparer));
        assertThat(violations).extracting(Violation::line).containsExactly(9, 27);
        assertThat(violations.get(0).message()).contains("Dictionary.tableName").contains("строка 5");
        assertThat(violations.get(1).message()).contains("с теми же 'pair.getKey()', 'pair.getValue()'");
    }

    @Test
    void enumLookupByScanningValuesOnEveryElement() {
        String kinds = """
                enum Kind {
                    A("a"), B("b"), C("c"), D("d");
                    private final String code;
                    Kind(String code) {
                        this.code = code;
                    }
                    static Kind from(String code) {
                        for (Kind kind : values()) {
                            if (kind.code.equals(code)) {
                                return kind;
                            }
                        }
                        return null;
                    }
                    static Kind viaHelper(String code) {
                        return Arrays.stream(Kind.values()).filter(kind -> kind.code.equals(code)).findFirst().orElse(null);
                    }
                    static Kind rare(String code) {
                        return Arrays.stream(Kind.values()).filter(kind -> kind.code.equals(code)).findFirst().orElse(null);
                    }
                }
                """;
        String small = """
                enum Small {
                    YES("y"), NO("n");
                    private final String code;
                    Small(String code) {
                        this.code = code;
                    }
                    static Small from(String code) {
                        for (Small small : values()) {
                            if (small.code.equals(code)) {
                                return small;
                            }
                        }
                        return null;
                    }
                }
                """;
        String converter = """
                class Converter {
                    List<Object> convert(List<String> codes) {
                        List<Object> result = new ArrayList<>();
                        for (String code : codes) {
                            result.add(Kind.from(code));
                            result.add(Small.from(code));
                        }
                        return result;
                    }
                    Kind one(String code) {
                        return Kind.rare(code);
                    }
                    List<Kind> all(List<String> codes) {
                        return codes.stream().map(this::parse).toList();
                    }
                    private Kind parse(String code) {
                        return Kind.viaHelper(code);
                    }
                }
                """;

        // Метод, который вызывают один раз, и перечисление из двух констант правило не трогает
        List<Violation> violations = RuleTests.checkProject(new EnumLookupByValuesScanRule(),
                Map.of("Kind.java", kinds, "Small.java", small, "Converter.java", converter));
        assertThat(violations)
                .extracting(violation -> violation.file().getFileName() + ":" + violation.line())
                .containsExactly("Kind.java:8", "Kind.java:16");
        // Из стрима вызывается не сам поиск, а метод, который его вызывает: выполняется ли он на каждом
        // элементе, по коду не видно
        assertThat(violations.get(1).confidenceOrDefault()).isEqualTo(Confidence.SUSPICION);
        assertThat(violations.get(1).message()).contains("Converter.all → Converter.parse");
    }

    @Test
    void reflectionInMethodCalledOnEveryElement() {
        String fields = """
                class Fields {
                    private static final Map<Class<?>, List<Field>> CACHE = new ConcurrentHashMap<>();
                    static List<Field> all(Class<?> type) {
                        List<Field> fields = new ArrayList<>();
                        while (type != null) {
                            fields.addAll(Arrays.asList(type.getDeclaredFields()));
                            type = type.getSuperclass();
                        }
                        return fields;
                    }
                    static List<Field> cached(Class<?> type) {
                        return CACHE.computeIfAbsent(type, key -> Arrays.asList(key.getDeclaredFields()));
                    }
                    static List<Field> rare(Class<?> type) {
                        return Arrays.asList(type.getDeclaredFields());
                    }
                }
                """;
        String mapper = """
                class Mapper {
                    private final Map<Class<?>, List<Field>> known = new HashMap<>();
                    void map(List<Object> items) {
                        for (Object item : items) {
                            Fields.all(item.getClass());
                            Fields.cached(item.getClass());
                        }
                    }
                    void once(Object item) {
                        Fields.rare(item.getClass());
                    }
                    void describe(Class<?> type) {
                        for (Field field : type.getDeclaredFields()) {
                            Fields.rare(field.getType());
                        }
                    }
                }
                """;

        // Кеш по классу, метод, который вызывают один раз, и обход полей класса - не то
        List<Violation> violations = RuleTests.checkProject(new HiddenReflectionInHotMethodRule(),
                Map.of("Fields.java", fields, "Mapper.java", mapper));
        assertThat(violations).extracting(Violation::line).containsExactly(6);
        assertThat(violations.get(0).message()).contains("Mapper.map");
    }
}
