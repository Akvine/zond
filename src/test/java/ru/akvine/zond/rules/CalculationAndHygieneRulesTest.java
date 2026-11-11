package ru.akvine.zond.rules;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import ru.akvine.zond.enums.Confidence;
import ru.akvine.zond.models.Violation;
import ru.akvine.zond.rules.codesmell.InstanceConstantRule;
import ru.akvine.zond.rules.codesmell.NamingConventionRule;
import ru.akvine.zond.rules.logical.FloatingScaleTruncationRule;
import ru.akvine.zond.rules.logical.FormatPlaceholderInConcatenationRule;
import ru.akvine.zond.rules.logical.IntegerDivisionBeforeMultiplyRule;
import ru.akvine.zond.rules.logical.UnpaddedFractionRule;
import ru.akvine.zond.rules.security.DumpFileInProjectRule;

import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * jr:496 - jr:501: потеря дробной части в расчетах, незаполненная подстановка, константа без static и дампы
 * в папке проекта
 */
class CalculationAndHygieneRulesTest {
    @TempDir
    Path dir;

    @Test
    void integerDivisionBeforeMultiplicationLosesFraction() {
        List<Violation> violations = RuleTests.check(new IntegerDivisionBeforeMultiplyRule(), """
                class Progress {
                    private long done;
                    private long total;
                    int percent() {
                        return (int) (done / total * 100);
                    }
                    int mirrored(int part, int whole) {
                        return 100 * (part / whole);
                    }
                    long hundreds(long amount) {
                        return amount / 100 - amount / 1000 * 10;
                    }
                    long wholeMinutes(long seconds) {
                        return seconds / 60 * 60;
                    }
                    long multipliedFirst() {
                        return done * 100 / total;
                    }
                    double fraction(double part, double whole) {
                        return part / whole * 100;
                    }
                    double share() {
                        double share = done / total * 100;
                        return share;
                    }
                }
                """);

        // Деление, записанное в double, - находка jr:28; округление до минуты и выделение разряда задуманы
        assertThat(violations).extracting(Violation::line).containsExactly(5, 8);
        assertThat(violations.get(0).message()).contains("'done / total * 100'", "умножайте до деления");
        assertThat(new IntegerDivisionBeforeMultiplyRule().confidence()).isEqualTo(Confidence.PROBABLE);
    }

    @Test
    void placeholderIsNotFilledByConcatenation() {
        List<Violation> violations = RuleTests.check(new FormatPlaceholderInConcatenationRule(), """
                class Loader {
                    private static final String TEMPLATE = "Файл %s " + "размером %d";
                    void load(Path file, String name) {
                        try {
                            read(file);
                        } catch (IOException exception) {
                            throw new IllegalStateException("Не удалось прочитать [%s]" + exception.getMessage());
                        }
                        log.info(String.format("Файл %s прочитан: " + name, file));
                        report(new LoadError("Файл %s не найден: " + name, file));
                        String query = find("name LIKE '%search" + name);
                        String later = "Файл %s: " + name;
                        log.info(later.formatted(file));
                    }
                }
                """);

        // Шаблон из одних строк, строка в String.format, второй аргумент и переменная - подстановку заполнят
        assertThat(violations).extracting(Violation::line).containsExactly(7);
        assertThat(violations.get(0).message()).contains("Не удалось прочитать [%s]", "String.format");
    }

    @Test
    void scaledFractionIsTruncatedByCast() {
        assertThat(RuleTests.lines(new FloatingScaleTruncationRule(), """
                class MoneyConverter {
                    long toMinor(String amount) {
                        return (long) (Double.parseDouble(amount) * 100);
                    }
                    int percent(double ratio) {
                        return (int) (100 * ratio);
                    }
                    long rounded(double amount) {
                        return Math.round(amount * 100);
                    }
                    int scaled(int count) {
                        return (int) (count * 100L);
                    }
                    int random() {
                        return (int) (Math.random() * 100);
                    }
                }
                """)).containsExactly(3, 6);
    }

    @Test
    void fractionAfterSeparatorNeedsLeadingZeros() {
        assertThat(RuleTests.lines(new UnpaddedFractionRule(), """
                class Format {
                    String money(long minor) {
                        return minor / 100 + "." + minor % 100;
                    }
                    String weight(long grams) {
                        return "Вес: " + grams / 1000 + "," + (grams % 1000) + " кг";
                    }
                    String padded(long minor) {
                        return String.format("%d.%02d", minor / 100, minor % 100);
                    }
                    String tenths(long value) {
                        return value / 10 + "." + value % 10;
                    }
                    String key(long id) {
                        return "id-" + id % 1000;
                    }
                }
                """)).containsExactly(3, 6);
    }

    @Test
    void constantWithoutStaticBelongsToEveryObject() {
        String code = """
                class Validator {
                    private final int MAX_SIZE = 200;
                    private final String prefix = "id-";
                    private static final int LIMIT = 10;
                    private final List<String> names = new ArrayList<>();
                    @Builder.Default
                    private final int attempts = 3;
                    private final Clock clock = Clock.systemUTC();
                    private int counter = 0;
                }
                """;

        assertThat(RuleTests.lines(new InstanceConstantRule(), code)).containsExactly(2, 3);
        // Имя у MAX_SIZE верное - о нем говорит только jr:500
        assertThat(RuleTests.check(new NamingConventionRule(), code)).isEmpty();
    }

    @Test
    void dumpInProjectFolderGoesToRepository() {
        List<Violation> violations = new ProjectFixture(dir)
                .write(".gitignore", "# логи\n*.log\n/tmp/\n")
                .write("java_pid12876.hprof", "dump")
                .write("app.log", "log")
                .write("logs/sql.log.2024-07-09", "log")
                .write("data/trace.db", "db")
                .write("tmp/old.hprof", "dump")
                .write("build/heap.hprof", "dump")
                .write("src/main/resources/application.properties", "app.name=demo")
                .check(new DumpFileInProjectRule());

        // *.log закрывает логи на любой глубине, /tmp/ - папку в корне; build в репозиторий не попадает
        assertThat(violations).extracting(violation -> violation.file().getFileName().toString())
                .containsExactlyInAnyOrder("java_pid12876.hprof", "trace.db", "sql.log.2024-07-09");
        assertThat(violations).extracting(Violation::confidenceOrDefault).containsOnly(Confidence.PROBABLE);
    }
}
