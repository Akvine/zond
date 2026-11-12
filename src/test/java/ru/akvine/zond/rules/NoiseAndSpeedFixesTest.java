package ru.akvine.zond.rules;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import ru.akvine.zond.enums.Confidence;
import ru.akvine.zond.enums.ErrorLevel;
import ru.akvine.zond.models.ScanOptions;
import ru.akvine.zond.models.Violation;
import ru.akvine.zond.rules.codesmell.MagicNumberRule;
import ru.akvine.zond.rules.codesmell.PublicStaticNonFinalFieldRule;
import ru.akvine.zond.rules.codesmell.SleepInTestRule;
import ru.akvine.zond.rules.datetime.StaticSimpleDateFormatRule;
import ru.akvine.zond.rules.logical.ReferenceEqualityRule;
import ru.akvine.zond.rules.performance.LogConcatenationRule;
import ru.akvine.zond.rules.logical.CaseWithoutLocaleRule;
import ru.akvine.zond.rules.logical.ConstantConditionRule;
import ru.akvine.zond.rules.logical.DuplicateSetterCallRule;
import ru.akvine.zond.rules.logical.EqualsInstanceofInHierarchyRule;
import ru.akvine.zond.rules.logical.ModifyingWithoutClearRule;
import ru.akvine.zond.rules.logical.PossibleNullDereferenceRule;
import ru.akvine.zond.rules.logical.SqlDestructiveStatementRule;
import ru.akvine.zond.rules.performance.HiddenRegexInHotMethodRule;
import ru.akvine.zond.rules.performance.RegexInLoopRule;
import ru.akvine.zond.rules.streams.FilterAfterSortRule;
import ru.akvine.zond.rules.support.MigrationHistory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * split с шаблоном, filter после sorted, сеттер дважды подряд (jr:522, jr:523), запуск выбранных правил и
 * понижение шумных находок типа LOGICAL
 */
class NoiseAndSpeedFixesTest {
    @TempDir
    Path directory;

    @Test
    void splitWithRegexCompilesPatternOnEveryIteration() {
        String code = """
                class Parser {
                    List<String[]> parse(List<String> rows) {
                        List<String[]> result = new ArrayList<>();
                        for (String row : rows) {
                            result.add(row.split("\\\\s*;\\\\s*"));
                            result.add(row.split(","));
                            result.add(row.split("\\\\|"));
                            result.add(SEPARATOR.split(row));
                        }
                        return result;
                    }
                }
                """;

        // Один обычный символ и экранированный знак String ищет без шаблона
        assertThat(RuleTests.lines(new RegexInLoopRule(), code)).containsExactly(5);
    }

    @Test
    void splitWithRegexInMethodCalledForEveryElement() {
        Map<String, String> files = Map.of("Rows.java", """
                class Rows {
                    List<List<String>> all(List<String> rows) {
                        return rows.stream().map(this::cells).toList();
                    }
                    private List<String> cells(String row) {
                        return List.of(row.split("\\\\s+"));
                    }
                    private List<String> fields(String row) {
                        return List.of(row.split("\\\\s+"));
                    }
                }
                """);

        List<Violation> violations = RuleTests.checkProject(new HiddenRegexInHotMethodRule(), files);

        assertThat(violations).extracting(Violation::line).containsExactly(6);
        assertThat(violations.get(0).message()).contains("split(...)");
    }

    @Test
    void filterRightAfterSortSortsDiscardedElements() {
        String code = """
                class Fields {
                    List<Field> mapped(List<Field> fields) {
                        return fields.stream()
                                .filter(Field::isPublic)
                                .sorted(Comparator.comparing(Field::getOrder))
                                .filter(this::isMapped)
                                .toList();
                    }
                    List<Field> top(List<Field> fields) {
                        return fields.stream().sorted(byOrder).limit(10).filter(this::isMapped).toList();
                    }
                    List<Field> firstOfEachKind(List<Field> fields) {
                        Set<String> seen = new HashSet<>();
                        return fields.stream().sorted(byOrder).filter(field -> seen.add(field.getKind())).toList();
                    }
                }
                """;

        // После limit отбор перед сортировкой не перенести, а отбор «первый каждого вида» зависит от порядка
        assertThat(RuleTests.lines(new FilterAfterSortRule(), code)).containsExactly(6);
    }

    @Test
    void sameSetterCalledTwiceInRow() {
        String code = """
                class ResultBuilder {
                    Result build(Task task) {
                        Result result = new Result();
                        result.setStepCount(task.getStepCount());
                        result.setItemsCount(task.getItemsCount());
                        result.setItemsCount(task.getItemsCount());
                        result.setTitle(task.getTitle());
                        result.setTitle(task.getName());
                        cell.setCellValue("");
                        cell.setCellValue(cell.getStringCellValue());
                        graphics.setRenderingHint(KEY_ANTIALIASING, VALUE_ON);
                        graphics.setRenderingHint(KEY_RENDERING, VALUE_QUALITY);
                        return result;
                    }
                }
                """;

        List<Violation> violations = RuleTests.check(new DuplicateSetterCallRule(), code);

        assertThat(violations).extracting(Violation::line).containsExactly(6, 8);
        assertThat(violations.get(0).message()).contains("с одним и тем же значением");
        assertThat(violations.get(1).message()).contains("затирается");
    }

    @Test
    void generatedEqualsMattersOnlyWhereObjectsAreCompared() {
        Map<String, String> files = new LinkedHashMap<>();
        files.put("BaseResponse.java", """
                @Data
                class BaseResponse {
                    private String requestId;
                }
                """);
        files.put("AuthResponse.java", """
                @Data
                class AuthResponse extends BaseResponse {
                    private String token;
                }
                """);
        files.put("Money.java", """
                @Data
                class Money extends BaseResponse {
                    private long amount;
                }
                """);
        files.put("Wallet.java", """
                class Wallet {
                    private final Set<Money> coins = new HashSet<>();
                }
                """);

        List<Violation> violations = RuleTests.checkProject(new EqualsInstanceofInHierarchyRule(), files);

        assertThat(violations).extracting(Violation::errorLevel).containsExactly(ErrorLevel.INFO, ErrorLevel.MINOR);
        assertThat(violations.get(0).message()).contains("AuthResponse").contains("не сравнивают");
        assertThat(violations.get(1).message()).contains("Money");
    }

    @Test
    void caseWithoutLocaleIsDefectOnlyWhereResultIsCompared() {
        String code = """
                class Roles {
                    boolean isAdmin(String role) {
                        return role.toLowerCase().trim().equals("admin");
                    }
                    Kind kind(String name) {
                        return Kind.valueOf(name.toUpperCase());
                    }
                    boolean known(String name) {
                        String key = name.toLowerCase();
                        return roles.containsKey(key);
                    }
                    String title(String name) {
                        return name.toUpperCase();
                    }
                    void rename(User user, String name) {
                        user.setName(name.toLowerCase());
                    }
                }
                """;

        List<Violation> violations = RuleTests.check(new CaseWithoutLocaleRule(), code);

        assertThat(violations).extracting(Violation::line).containsExactly(3, 6, 9, 13, 16);
        assertThat(violations).extracting(Violation::errorLevel).containsExactly(
                ErrorLevel.MINOR, ErrorLevel.MINOR, ErrorLevel.MINOR, ErrorLevel.INFO, ErrorLevel.INFO);
    }

    @Test
    void modifyingQueryIsRiskyWhereEntityIsAlreadyLoaded() {
        Map<String, String> files = new LinkedHashMap<>();
        files.put("UserRepository.java", """
                interface UserRepository extends JpaRepository<User, Long> {
                    @Modifying
                    @Query("update User u set u.active = false where u.id = :id")
                    void deactivate(Long id);

                    @Modifying
                    @Query("delete from User u where u.created < :before")
                    void deleteOld(Instant before);
                }
                """);
        files.put("UserService.java", """
                class UserService {
                    private final UserRepository userRepository;
                    void block(Long id) {
                        User user = userRepository.findById(id).orElseThrow();
                        userRepository.deactivate(id);
                        audit(user);
                    }
                    void cleanup(Instant before) {
                        userRepository.deleteOld(before);
                    }
                }
                """);

        List<Violation> violations = RuleTests.checkProject(new ModifyingWithoutClearRule(), files);

        assertThat(violations).extracting(Violation::line).containsExactly(2, 6);
        assertThat(violations.get(0).confidence()).isNull();
        assertThat(violations.get(1).confidence()).isEqualTo(Confidence.SUSPICION);
    }

    @Test
    void nullCheckHiddenInAnotherVariableGuardsDereference() {
        Map<String, String> files = Map.of("Plugins.java", """
                class Plugins {
                    InputStream open(JarFile jarFile, String name) throws IOException {
                        JarEntry entry = name != null && jarFile != null ? jarFile.getJarEntry(name) : null;
                        if (entry != null) {
                            return jarFile.getInputStream(entry);
                        }
                        return null;
                    }
                    Key single(Iterable<Key> keys) {
                        long loaded = keys == null ? 0 : count(keys);
                        if (loaded != 1) {
                            throw new IllegalStateException("one key expected");
                        }
                        return keys.iterator().next();
                    }
                    String shortName(Attributes attributes) {
                        boolean first = attributes != null && attributes.containsKey("First");
                        return attributes.getValue("Short-Name") + first;
                    }
                }
                """);

        // В первых двух методах к объекту обращаются только под защитой переменной, в которую спрятана проверка
        assertThat(RuleTests.checkProject(new PossibleNullDereferenceRule(), files))
                .extracting(Violation::line)
                .containsExactly(18);
    }

    @Test
    void defensiveCheckOfNonNullParameterIsOnlySuspicion() {
        Map<String, String> files = Map.of("Actions.java", """
                class Actions {
                    void add(@NonNull Action action) {
                        if (action == null) {
                            throw new IllegalArgumentException("action");
                        }
                        actions.add(action);
                    }
                }
                """);

        List<Violation> violations = RuleTests.checkProject(new ConstantConditionRule(), files);

        assertThat(violations).extracting(Violation::line).containsExactly(3);
        assertThat(violations.get(0).confidence()).isEqualTo(Confidence.SUSPICION);
    }

    @Test
    void virtualClockInTestIsNotRealSleep() {
        String code = """
                class ExpirationTest {
                    private final MockTime time = new MockTime();
                    @Test
                    void expires() throws Exception {
                        time.sleep(5000);
                        context.time.sleep(100);
                        Thread.sleep(100);
                        TimeUnit.SECONDS.sleep(1);
                    }
                }
                """;

        // Тестовые часы переводят виртуальное время и поток не останавливают
        assertThat(RuleTests.lines(new SleepInTestRule(), code)).containsExactly(7, 8);
    }

    @Test
    void concatenatedLogMessageUnderLevelCheckCostsNothing() {
        String code = """
                class Pointcut {
                    void parse(String expression) {
                        if (logger.isDebugEnabled()) {
                            logger.debug("Rejected [" + expression + "]");
                        }
                        logger.debug("Parsed [" + expression + "]");
                    }
                }
                """;

        assertThat(RuleTests.lines(new LogConcatenationRule(), code)).containsExactly(6);
    }

    @Test
    void namesakeOfStatefulJdkClassInStaticField() {
        String foreign = """
                import org.springframework.cglib.core.Signature;
                class BeanCopier {
                    private static final Signature COPY = new Signature("copy", Type.VOID_TYPE, new Type[0]);
                }
                """;
        String jdk = """
                import java.security.Signature;
                class Signer {
                    private static final Signature SIGNATURE = create();
                }
                """;

        assertThat(RuleTests.lines(new StaticSimpleDateFormatRule(), foreign)).isEmpty();
        assertThat(RuleTests.lines(new StaticSimpleDateFormatRule(), jdk)).containsExactly(3);
    }

    @Test
    void numbersOfBinaryFormatAndScreenLayoutNeedNoNames() {
        String code = """
                class StatusDecoder {
                    Position decode(Channel channel, Object msg) {
                        ByteBuf buf = (ByteBuf) msg;
                        if (buf.readableBytes() < 15 + 3) {
                            return null;
                        }
                        int type = buf.readUnsignedByte() & 0x0F;
                        switch (type) {
                            case 0x12:
                                position.setSpeed(buf.readUnsignedByte() * 1.852);
                                break;
                            default:
                                break;
                        }
                        return position;
                    }
                    void layout(JDialog dialog) {
                        dialog.setSize(300, 200);
                        StringBuilder text = new StringBuilder(250);
                        byte[] buffer = new byte[4096];
                        dialog.setTimeout(45);
                    }
                }
                """;

        // Остаются множитель единицы измерения и число в постороннем вызове
        assertThat(RuleTests.lines(new MagicNumberRule(), code)).containsExactly(10, 21);
    }

    @Test
    void staticFieldAcknowledgedForSpotBugsStaysMutableOnPurpose() {
        String code = """
                class Launcher {
                    @SuppressFBWarnings(value = "MS_SHOULD_BE_FINAL", justification = "for script console")
                    public static boolean showFullPath = false;
                    public static boolean debug = false;
                }
                """;

        assertThat(RuleTests.lines(new PublicStaticNonFinalFieldRule(), code)).containsExactly(4);
    }

    @Test
    void sameOperandsOfReferenceComparisonDoNotBreakScan() {
        String code = """
                class Suppliers {
                    boolean same(Supplier<Processor> supplier) {
                        return supplier.get() == supplier.get() || supplier.get().equals(supplier.get());
                    }
                }
                """;

        // Раньше на одинаковых операндах правило падало с IllegalArgumentException и останавливало сканирование
        assertThat(RuleTests.check(new ReferenceEqualityRule(), code).size() <= 1).isTrue();
    }

    @Test
    void onlySelectedRulesRun() {
        ScanOptions all = ScanOptions.defaults();
        ScanOptions selected = all.withOnlyRules("jr:510, RegexInLoopRule");

        assertThat(all.allows("jr:1", "AnyRule", ErrorLevel.MINOR)).isTrue();
        assertThat(selected.allows("jr:510", "UnbufferedFileStreamRule", ErrorLevel.MINOR)).isTrue();
        assertThat(selected.allows("jr:239", "RegexInLoopRule", ErrorLevel.MINOR)).isTrue();
        assertThat(selected.allows("jr:1", "AnyRule", ErrorLevel.MINOR)).isFalse();
    }

    @Test
    void settledMigrationBecomesNoteWhileNewOneStaysDefect() throws Exception {
        // Без git истории нет - этот случай проверяет следующий тест
        if (!git(null, "init", "-q", ".")) {
            return;
        }
        git(null, "config", "user.email", "test@example.com");
        git(null, "config", "user.name", "test");
        Path settled = Files.writeString(directory.resolve("V1__old.sql"), "DROP TABLE a;\n");
        git(null, "add", "-A");
        git("2020-01-10T10:00:00", "commit", "-q", "-m", "old");
        Path fresh = Files.writeString(directory.resolve("V2__new.sql"), "DROP TABLE b;\n");

        List<Violation> softened = MigrationHistory.soften(directory, List.of(finding(settled), finding(fresh)));

        assertThat(softened).extracting(Violation::errorLevel).containsExactly(ErrorLevel.INFO, ErrorLevel.MAJOR);
        assertThat(softened.get(0).message()).contains("справка");
    }

    @Test
    void migrationFindingsStayAsTheyAreOutsideGitRepository() {
        // Корень файловой системы в репозитории git не лежит: истории нет, и понижать находки не по чему
        Path root = Path.of("/").toAbsolutePath().getRoot();
        Violation violation = finding(root.resolve("V1__drop.sql"));

        assertThat(MigrationHistory.soften(root, List.of(violation))).containsExactly(violation);
    }

    private Violation finding(Path file) {
        return new Violation(
                ErrorLevel.MAJOR, new SqlDestructiveStatementRule().errorType(), "jr:290",
                "SqlDestructiveStatementRule", file, 1, "DROP TABLE");
    }

    // date - дата коммита: по ней миграция считается давней
    private boolean git(String date, String... arguments) throws InterruptedException {
        List<String> command = new ArrayList<>(List.of("git"));
        command.addAll(List.of(arguments));
        ProcessBuilder builder = new ProcessBuilder(command).directory(directory.toFile()).redirectErrorStream(true);
        if (date != null) {
            builder.environment().put("GIT_AUTHOR_DATE", date);
            builder.environment().put("GIT_COMMITTER_DATE", date);
        }
        try {
            Process process = builder.start();
            process.getInputStream().readAllBytes();
            return process.waitFor() == 0;
        } catch (IOException exception) {
            return false;
        }
    }
}
