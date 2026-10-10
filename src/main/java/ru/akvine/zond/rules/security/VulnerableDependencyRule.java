package ru.akvine.zond.rules.security;

import org.springframework.stereotype.Component;
import ru.akvine.zond.enums.Confidence;
import ru.akvine.zond.enums.ErrorLevel;
import ru.akvine.zond.enums.ErrorType;
import ru.akvine.zond.models.ScanContext;
import ru.akvine.zond.models.TextFile;
import ru.akvine.zond.models.Violation;
import ru.akvine.zond.rules.AbstractContextRule;
import ru.akvine.zond.rules.RuleCodes;
import ru.akvine.zond.rules.files.BuildFiles;
import ru.akvine.zond.rules.files.TextFiles;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Зависимости с известными уязвимостями. Список встроенный и намеренно короткий: только громкие уязвимости
 * с выполнением чужого кода, для которых точно известны затронутые версии. Сеть правилу не нужна.
 */
@Component
public class VulnerableDependencyRule extends AbstractContextRule {
    private static final Pattern NUMBERS = Pattern.compile("^(\\d+(?:\\.\\d+)*)");
    private static final String ANY = null;

    private static final String SPRING_GROUP = "org.springframework";
    private static final String SPRING4SHELL = "Spring4Shell (CVE-2022-22965): выполнение чужого кода через привязку"
            + " параметров запроса";
    private static final List<Range> SPRING4SHELL_RANGES = List.of(new Range("5.3.0", "5.3.18"), new Range("5.2.0", "5.2.20"));

    // Spring Boot тянет за собой версию Spring Framework: уязвимая приходит с этими версиями Boot
    private static final String BOOT_PARENT = "spring-boot-starter-parent";
    private static final List<Range> BOOT_RANGES = List.of(new Range("2.6.0", "2.6.6"), new Range("2.5.0", "2.5.12"));
    private static final Pattern VERSION_TAG = Pattern.compile("<version>\\s*([^<\\s]+)\\s*</version>");
    private static final int PARENT_SPAN = 4;
    private static final Pattern BOOT_PLUGIN = Pattern.compile(
            "org\\.springframework\\.boot['\"]?\\)?\\s+version\\s+['\"]([^'\"]+)['\"]");

    private static final List<Advisory> ADVISORIES = List.of(
            new Advisory("org.apache.logging.log4j", Set.of("log4j-core"),
                    List.of(new Range("2.0", "2.17.1")), Set.of("2.3.2", "2.12.4"), "2.17.1",
                    "Log4Shell (CVE-2021-44228) и уязвимости, найденные следом: выполнение чужого кода через"
                            + " строку, попавшую в лог"),
            new Advisory("log4j", Set.of("log4j"), List.of(new Range(ANY, ANY)), Set.of(), null,
                    "Log4j 1.x снят с поддержки в 2015 году, уязвимости в нем не исправляют (CVE-2019-17571,"
                            + " CVE-2021-4104)"),
            new Advisory("org.apache.commons", Set.of("commons-text"),
                    List.of(new Range("1.5", "1.10.0")), Set.of(), "1.10.0",
                    "Text4Shell (CVE-2022-42889): выполнение чужого кода при подстановке значений в строку"),
            new Advisory("org.yaml", Set.of("snakeyaml"), List.of(new Range(ANY, "2.0")), Set.of(), "2.0",
                    "CVE-2022-1471: выполнение чужого кода при разборе YAML из недоверенного источника"),
            new Advisory(SPRING_GROUP, Set.of("spring-beans", "spring-core", "spring-web", "spring-webmvc", "spring-webflux"),
                    SPRING4SHELL_RANGES, Set.of(), "5.3.18 (для ветки 5.2 - 5.2.20)", SPRING4SHELL),
            new Advisory("org.springframework.cloud", Set.of("spring-cloud-function-context", "spring-cloud-function-core"),
                    List.of(new Range(ANY, "3.1.7"), new Range("3.2.0", "3.2.3")), Set.of(), "3.1.7 или 3.2.3",
                    "CVE-2022-22963: выполнение чужого кода через заголовок маршрутизации"),
            new Advisory("org.springframework.cloud", Set.of("spring-cloud-gateway-server", "spring-cloud-starter-gateway"),
                    List.of(new Range(ANY, "3.0.7"), new Range("3.1.0", "3.1.1")), Set.of(), "3.0.7 или 3.1.1",
                    "CVE-2022-22947: выполнение чужого кода через управляющий интерфейс шлюза"),
            new Advisory("commons-collections", Set.of("commons-collections"),
                    List.of(new Range(ANY, "3.2.2")), Set.of(), "3.2.2",
                    "CVE-2015-7501: выполнение чужого кода при десериализации"),
            new Advisory("org.apache.commons", Set.of("commons-collections4"),
                    List.of(new Range(ANY, "4.1")), Set.of(), "4.1",
                    "CVE-2015-7501: выполнение чужого кода при десериализации"),
            new Advisory("com.alibaba", Set.of("fastjson"), List.of(new Range(ANY, "1.2.83")), Set.of(), "1.2.83",
                    "CVE-2022-25845: выполнение чужого кода при разборе JSON"),
            new Advisory("com.h2database", Set.of("h2"), List.of(new Range(ANY, "2.1.210")), Set.of(), "2.1.210",
                    "CVE-2021-42392 и CVE-2022-23221: выполнение чужого кода через строку подключения и консоль H2"),
            new Advisory("org.postgresql", Set.of("postgresql"),
                    List.of(new Range(ANY, "42.2.25"), new Range("42.3.0", "42.3.2")), Set.of(), "42.2.25 или 42.3.2",
                    "CVE-2022-21724: выполнение чужого кода через параметры строки подключения"),
            new Advisory("com.thoughtworks.xstream", Set.of("xstream"), List.of(new Range(ANY, "1.4.18")), Set.of(), "1.4.18",
                    "серия CVE-2021-39139 и следующих: выполнение чужого кода при разборе XML"));

    /**
     * @param from   первая затронутая версия; null - все более ранние тоже затронуты
     * @param before первая исправленная версия; null - исправления нет
     */
    private record Range(String from, String before) {
        private boolean contains(List<Integer> version) {
            return (from == null || compare(version, numbers(from).orElseThrow()) >= 0)
                    && (before == null || compare(version, numbers(before).orElseThrow()) < 0);
        }
    }

    /**
     * @param safe  исправленные версии старых веток, которые по номеру попадают в затронутый диапазон
     * @param fixed до какой версии обновляться; null - исправленной версии нет
     */
    private record Advisory(String group, Set<String> artifacts, List<Range> ranges, Set<String> safe, String fixed, String title) {
        private boolean affects(BuildFiles.Dependency dependency) {
            if (!group.equals(dependency.group()) || !artifacts.contains(dependency.artifact())
                    || safe.contains(dependency.version())) {
                return false;
            }
            Optional<List<Integer>> version = numbers(dependency.version());
            return version.isPresent() && ranges.stream().anyMatch(range -> range.contains(version.get()));
        }
    }

    @Override
    public String code() {
        return RuleCodes.VULNERABLE_DEPENDENCY_RULE_CODE;
    }

    @Override
    public String description() {
        return "Сканирует pom.xml и build.gradle и ищет версии библиотек с известными уязвимостями (Log4Shell, Spring4Shell и другие)";
    }

    // Версия записана в файле сборки, а затронутые версии известны точно
    @Override
    public Confidence confidence() {
        return Confidence.CONFIRMED;
    }

    @Override
    public List<Violation> checkContext(ScanContext context) {
        List<Violation> violations = new ArrayList<>();
        for (TextFile file : context.textFiles()) {
            for (BuildFiles.Dependency dependency : BuildFiles.dependencies(file, context.textFiles())) {
                // Библиотека только для тестов в работающее приложение не попадает
                if (dependency.isTestOnly()) {
                    continue;
                }
                ADVISORIES.stream().filter(advisory -> advisory.affects(dependency)).findFirst().ifPresent(advisory ->
                        violations.add(violation(file.path(), dependency.line(),
                                "Зависимость " + dependency.coordinates() + ":" + dependency.version() + " уязвима - "
                                        + advisory.title() + "; " + (advisory.fixed() == null
                                        ? "замените библиотеку на поддерживаемую (Logback, Log4j 2)"
                                        : "обновите до " + advisory.fixed() + " или новее"))));
            }
            checkSpringBoot(file, violations);
        }
        return violations;
    }

    @Override
    public ErrorLevel errorLevel() {
        return ErrorLevel.CRITICAL;
    }

    @Override
    public ErrorType errorType() {
        return ErrorType.SECURITY;
    }

    private void checkSpringBoot(TextFile file, List<Violation> violations) {
        List<String> lines = file.lines();
        for (int index = 0; index < lines.size(); index++) {
            Optional<String> version = Optional.empty();
            if (TextFiles.isPom(file) && lines.get(index).contains(BOOT_PARENT)) {
                version = versionNear(lines, index);
            } else if (TextFiles.isGradle(file)) {
                Matcher plugin = BOOT_PLUGIN.matcher(lines.get(index));
                version = plugin.find() ? Optional.of(plugin.group(1)) : Optional.empty();
            }
            Optional<List<Integer>> numbers = version.flatMap(VulnerableDependencyRule::numbers);
            if (numbers.isPresent() && BOOT_RANGES.stream().anyMatch(range -> range.contains(numbers.get()))) {
                violations.add(violation(file.path(), index + 1,
                        "Spring Boot " + version.get() + " подключает уязвимую версию Spring Framework - "
                                + SPRING4SHELL + "; обновите Spring Boot до 2.5.12, 2.6.6 или новее"));
            }
        }
    }

    // <parent> занимает несколько строк: версия стоит рядом с artifactId, чуть выше или ниже
    private Optional<String> versionNear(List<String> lines, int index) {
        for (int offset = 0; offset <= PARENT_SPAN; offset++) {
            for (int line : List.of(index + offset, index - offset)) {
                if (line >= 0 && line < lines.size()) {
                    Matcher version = VERSION_TAG.matcher(lines.get(line));
                    if (version.find()) {
                        return Optional.of(version.group(1));
                    }
                }
            }
        }
        return Optional.empty();
    }

    /**
     * @return числа версии: 2.14.1 -> [2, 14, 1], 42.2.5.jre7 -> [42, 2, 5]; пусто, если версия задана не числом
     */
    private static Optional<List<Integer>> numbers(String version) {
        Matcher numbers = NUMBERS.matcher(version == null ? "" : version.trim());
        if (!numbers.find()) {
            return Optional.empty();
        }
        List<Integer> parts = new ArrayList<>();
        for (String part : numbers.group(1).split("\\.")) {
            // Число длиннее девяти цифр версией быть не может: это дата сборки или хеш
            if (part.length() > 9) {
                return Optional.empty();
            }
            parts.add(Integer.valueOf(part));
        }
        return Optional.of(parts);
    }

    private static int compare(List<Integer> left, List<Integer> right) {
        for (int index = 0; index < Math.max(left.size(), right.size()); index++) {
            int difference = Integer.compare(
                    index < left.size() ? left.get(index) : 0,
                    index < right.size() ? right.get(index) : 0);
            if (difference != 0) {
                return difference;
            }
        }
        return 0;
    }
}
