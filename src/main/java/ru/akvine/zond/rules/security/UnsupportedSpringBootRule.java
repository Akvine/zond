package ru.akvine.zond.rules.security;

import org.springframework.stereotype.Component;
import ru.akvine.zond.enums.ErrorLevel;
import ru.akvine.zond.enums.ErrorType;
import ru.akvine.zond.models.ScanContext;
import ru.akvine.zond.models.Violation;
import ru.akvine.zond.rules.AbstractContextRule;
import ru.akvine.zond.rules.RuleCodes;
import ru.akvine.zond.rules.files.SpringVersions;

import java.nio.file.Path;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.stream.Collectors;

/**
 * Ветка Spring Boot, у которой закончилась открытая поддержка: исправления ошибок и уязвимостей для нее больше
 * не публикуются. Найденная завтра уязвимость в самом Spring Boot, Spring Framework или Tomcat до такого
 * приложения не дойдет в виде обновления - придется сразу переходить на другую ветку.
 * <p>
 * Сроки встроены в правило и взяты из таблицы поддержки на spring.io. О ветке, которой в таблице нет (она новее
 * правила), ничего не сообщается
 */
@Component
public class UnsupportedSpringBootRule extends AbstractContextRule {
    private static final String[] MONTHS = {
            "января", "февраля", "марта", "апреля", "мая", "июня", "июля", "августа", "сентября", "октября",
            "ноября", "декабря"};

    // Ветка -> последний месяц открытой поддержки
    private static final Map<String, YearMonth> SUPPORT_ENDS = new TreeMap<>(Map.of(
            "2.7", YearMonth.of(2023, 6),
            "3.0", YearMonth.of(2023, 12),
            "3.1", YearMonth.of(2024, 6),
            "3.2", YearMonth.of(2024, 12),
            "3.3", YearMonth.of(2025, 6),
            "3.4", YearMonth.of(2025, 12),
            "3.5", YearMonth.of(2026, 6),
            "4.0", YearMonth.of(2026, 12),
            "4.1", YearMonth.of(2027, 7)));
    // Все, что старше 2.7, поддержку потеряло раньше нее
    private static final SpringVersions.Version OLDEST_KNOWN = new SpringVersions.Version(2, 7, 0);

    @Override
    public String code() {
        return RuleCodes.UNSUPPORTED_SPRING_BOOT_RULE_CODE;
    }

    @Override
    public String description() {
        return "Сканирует файлы сборки и ищет версию Spring Boot из ветки, у которой закончилась открытая поддержка";
    }

    @Override
    public List<Violation> checkContext(ScanContext context) {
        YearMonth today = today();
        String supported = SUPPORT_ENDS.entrySet().stream()
                .filter(branch -> !branch.getValue().isBefore(today))
                .map(branch -> branch.getKey() + " (до конца " + month(branch.getValue()) + ")")
                .collect(Collectors.joining(", "));
        List<Violation> violations = new ArrayList<>();
        // В одном файле версия может быть названа дважды: в свойстве и в самом родителе
        Set<Path> reported = new HashSet<>();
        for (SpringVersions.Declared declared : SpringVersions.declarations(context.textFiles())) {
            SpringVersions.Version version = declared.version();
            String branch = version.major() + "." + version.minor();
            YearMonth end = SUPPORT_ENDS.get(branch);
            boolean ancient = !version.isAtLeast(OLDEST_KNOWN.major(), OLDEST_KNOWN.minor(), OLDEST_KNOWN.patch());
            // Версию, которой в файле нет (ее задает платформа вроде Jmix), обновляют вместе с платформой
            if (declared.inferred() || !ancient && (end == null || !end.isBefore(today))
                    || !reported.add(declared.file().path())) {
                continue;
            }
            String when = ancient
                    ? "закончилась раньше, чем у ветки 2.7 (" + month(SUPPORT_ENDS.get("2.7")) + ")"
                    : "закончилась в конце " + month(end);
            violations.add(violation(declared.file().path(), declared.line(),
                    "Spring Boot " + version.major() + "." + version.minor() + "." + version.patch()
                            + ": открытая поддержка ветки " + branch + " " + when + " - исправления ошибок и"
                            + " уязвимостей для нее больше не публикуются (платная поддержка длится дольше);"
                            + " переходите на поддерживаемую ветку"
                            + (supported.isEmpty()
                            ? ""
                            : ". Из известных правилу сейчас поддерживаются: " + supported)));
        }
        return violations;
    }

    @Override
    public ErrorLevel errorLevel() {
        return ErrorLevel.MINOR;
    }

    @Override
    public ErrorType errorType() {
        return ErrorType.SECURITY;
    }

    /**
     * @return месяц, на который смотрит проверка; вынесен, чтобы результат не зависел от дня запуска тестов
     */
    protected YearMonth today() {
        return YearMonth.now();
    }

    // июня 2025 года
    private String month(YearMonth month) {
        return MONTHS[month.getMonthValue() - 1] + " " + month.getYear() + " года";
    }
}
