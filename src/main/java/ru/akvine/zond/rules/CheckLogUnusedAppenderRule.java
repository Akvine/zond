package ru.akvine.zond.rules;

import org.springframework.stereotype.Component;
import ru.akvine.zond.enums.ErrorLevel;
import ru.akvine.zond.enums.ErrorType;
import ru.akvine.zond.models.ScanContext;
import ru.akvine.zond.models.TextFile;
import ru.akvine.zond.models.Violation;
import ru.akvine.zond.parsers.XmlElement;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

@Component
public class CheckLogUnusedAppenderRule extends AbstractContextRule {
    private static final String APPENDER = "appender";
    private static final String APPENDERS = "Appenders";
    private static final String NAME = "name";
    private static final String REF = "ref";
    private static final Set<String> REFERENCES = Set.of("appender-ref", "AppenderRef");

    // Часть настроек лежит в другом файле - подключить аппендер могли там
    private static final Set<String> INCLUDES = Set.of("include", "import");

    @Override
    public String code() {
        return RuleCodes.CHECK_LOG_UNUSED_APPENDER_RULE_CODE;
    }

    @Override
    public String description() {
        return "Сканирует logback.xml и log4j2.xml и ищет аппендеры, которые не подключены ни к одному логгеру";
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
        return ErrorType.LOGICAL;
    }

    private void check(TextFile file, XmlElement root, List<Violation> violations) {
        List<XmlElement> all = root.descendants();
        Set<String> used = all.stream()
                .filter(element -> REFERENCES.contains(element.name()))
                .map(element -> element.attribute(REF))
                .collect(Collectors.toSet());
        // Имя из переменной (${APPENDER}) сопоставить с объявлением нельзя
        boolean isUnclear = all.stream().anyMatch(element -> INCLUDES.contains(element.name()))
                || used.stream().anyMatch(name -> name.contains("$"));
        if (isUnclear) {
            return;
        }

        List<XmlElement> appenders = LogConfigs.isLogback(file)
                ? root.descendants(APPENDER)
                : root.descendants(APPENDERS).stream().flatMap(block -> block.children().stream()).toList();
        for (XmlElement appender : appenders) {
            String name = appender.attribute(NAME);
            if (!name.isEmpty() && !used.contains(name)) {
                violations.add(violation(file.path(), appender.line(),
                        "Аппендер '" + name + "' объявлен, но не подключен ни к одному логгеру: сообщения в него"
                                + " не попадают; подключите его либо удалите"));
            }
        }
    }
}
