package ru.akvine.zond.rules.performance;

import org.springframework.stereotype.Component;
import ru.akvine.zond.enums.ErrorLevel;
import ru.akvine.zond.enums.ErrorType;
import ru.akvine.zond.models.ScanContext;
import ru.akvine.zond.models.TextFile;
import ru.akvine.zond.models.Violation;
import ru.akvine.zond.parsers.XmlElement;
import ru.akvine.zond.rules.AbstractContextRule;
import ru.akvine.zond.rules.RuleCodes;
import ru.akvine.zond.rules.files.LogConfigs;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

@Component
public class CheckLogRootLevelDebugRule extends AbstractContextRule {
    private static final String ROOT = "root";
    private static final String LEVEL = "level";
    private static final String SPRING_PROFILE = "springProfile";
    private static final String NAME = "name";
    private static final Set<String> VERBOSE_LEVELS = Set.of("debug", "trace", "all");

    // <springProfile name="dev">: настройки, которые действуют только при разработке
    private static final Pattern DEVELOPMENT_PROFILE =
            Pattern.compile(".*\\b(dev|local|test|debug)\\b.*", Pattern.CASE_INSENSITIVE);

    @Override
    public String code() {
        return RuleCodes.CHECK_LOG_ROOT_LEVEL_DEBUG_RULE_CODE;
    }

    @Override
    public String description() {
        return "Сканирует logback.xml и log4j2.xml и ищет корневой логгер с уровнем DEBUG или TRACE";
    }

    @Override
    public List<Violation> checkContext(ScanContext context) {
        List<Violation> violations = new ArrayList<>();
        for (TextFile file : context.textFiles()) {
            LogConfigs.production(file).ifPresent(root -> check(file, root, violations));
        }
        return violations;
    }

    @Override
    public ErrorLevel errorLevel() {
        return ErrorLevel.MINOR;
    }

    @Override
    public ErrorType errorType() {
        return ErrorType.PERFORMANCE;
    }

    private void check(TextFile file, XmlElement element, List<Violation> violations) {
        for (XmlElement child : element.children()) {
            if (child.name().equals(SPRING_PROFILE) && DEVELOPMENT_PROFILE.matcher(child.attribute(NAME)).matches()) {
                continue;
            }
            String level = child.attribute(LEVEL).toLowerCase(Locale.ROOT);
            if (child.name().equalsIgnoreCase(ROOT) && VERBOSE_LEVELS.contains(level)) {
                violations.add(violation(file.path(), child.line(),
                        "Корневой логгер с уровнем " + level.toUpperCase(Locale.ROOT) + ": подробные сообщения пишут"
                                + " все библиотеки сразу - журнал растет на гигабайты, а приложение тратит время"
                                + " на вывод; оставьте INFO, а подробный уровень включайте для отдельных пакетов"));
            }
            check(file, child, violations);
        }
    }
}
