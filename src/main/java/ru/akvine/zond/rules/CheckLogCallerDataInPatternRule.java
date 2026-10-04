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
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Component
public class CheckLogCallerDataInPatternRule extends AbstractContextRule {
    private static final String PATTERN = "pattern";

    // %C, %class, %M, %method, %L, %line, %F, %file, %caller, %location; в log4j2 еще и %l.
    // %c, %m и %level - это имя логгера, сообщение и уровень: они дешевые
    private static final Pattern CALLER_DATA = Pattern.compile(
            "%[-.\\d]*(C|class|M|method|L|line|F|file|caller|location|l)(?![A-Za-z])");

    @Override
    public String code() {
        return RuleCodes.CHECK_LOG_CALLER_DATA_IN_PATTERN_RULE_CODE;
    }

    @Override
    public String description() {
        return "Сканирует logback.xml и log4j2.xml и ищет в шаблоне вывода имя класса, метода или номер строки вызова";
    }

    @Override
    public List<Violation> checkContext(ScanContext context) {
        List<Violation> violations = new ArrayList<>();
        for (TextFile file : context.textFiles()) {
            LogConfigs.production(file).ifPresent(root -> {
                for (XmlElement element : root.descendants()) {
                    // <pattern>...</pattern> либо <PatternLayout pattern="..."/>
                    String pattern = element.name().equalsIgnoreCase(PATTERN) ? element.text() : element.attribute(PATTERN);
                    Matcher matcher = CALLER_DATA.matcher(pattern);
                    if (matcher.find()) {
                        violations.add(violation(file.path(), element.line(),
                                "Шаблон вывода содержит '" + matcher.group() + "': чтобы узнать место вызова,"
                                        + " на каждое сообщение строится стек вызовов - это замедляет логирование"
                                        + " в разы; уберите его, имени логгера (%logger) обычно достаточно"));
                    }
                }
            });
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
}
