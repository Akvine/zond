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

@Component
public class CheckLogFileWithoutRotationRule extends AbstractContextRule {
    private static final String APPENDER = "appender";
    private static final String CLASS = "class";
    private static final String NAME = "name";
    private static final String FILE_APPENDER = ".FileAppender";
    private static final String ROLLING_FILE_APPENDER = "RollingFileAppender";

    // Любая из этих настроек logback ограничивает число или объем архивов
    private static final Set<String> LIMITS = Set.of("maxHistory", "totalSizeCap", "maxIndex");

    // Аппендеры log4j2, которые пишут в один файл без ротации
    private static final Set<String> LOG4J2_PLAIN_FILES = Set.of("File", "RandomAccessFile", "MemoryMappedFile");

    @Override
    public String code() {
        return RuleCodes.CHECK_LOG_FILE_WITHOUT_ROTATION_RULE_CODE;
    }

    @Override
    public String description() {
        return "Сканирует logback.xml и log4j2.xml и ищет запись в файл без ротации либо без ограничения на число архивов";
    }

    @Override
    public List<Violation> checkContext(ScanContext context) {
        List<Violation> violations = new ArrayList<>();
        for (TextFile file : context.textFiles()) {
            LogConfigs.production(file).ifPresent(root -> {
                if (LogConfigs.isLogback(file)) {
                    checkLogback(file, root, violations);
                } else {
                    checkLog4j2(file, root, violations);
                }
            });
        }
        return violations;
    }

    @Override
    public ErrorLevel errorLevel() {
        return ErrorLevel.MAJOR;
    }

    @Override
    public ErrorType errorType() {
        return ErrorType.RESOURCE;
    }

    private void checkLogback(TextFile file, XmlElement root, List<Violation> violations) {
        for (XmlElement appender : root.descendants(APPENDER)) {
            String type = appender.attribute(CLASS);
            if (type.endsWith(FILE_APPENDER)) {
                violations.add(violation(file.path(), appender.line(), withoutRotation(appender.attribute(NAME))));
            } else if (type.endsWith(ROLLING_FILE_APPENDER)
                    && appender.descendants().stream().noneMatch(element -> LIMITS.contains(element.name()))) {
                violations.add(violation(file.path(), appender.line(),
                        "Аппендер '" + appender.attribute(NAME) + "' делит журнал на файлы, но число архивов"
                                + " не ограничено: старые файлы копятся, пока не кончится место на диске;"
                                + " задайте maxHistory и totalSizeCap"));
            }
        }
    }

    private void checkLog4j2(TextFile file, XmlElement root, List<Violation> violations) {
        for (XmlElement element : root.descendants()) {
            if (LOG4J2_PLAIN_FILES.contains(element.name()) && !element.attribute(NAME).isEmpty()) {
                violations.add(violation(file.path(), element.line(), withoutRotation(element.attribute(NAME))));
            }
        }
    }

    private String withoutRotation(String appender) {
        return "Аппендер '" + appender + "' пишет в один файл без ротации: файл растет, пока не кончится место"
                + " на диске, и приложение остановится; используйте аппендер с ротацией и ограничьте число архивов";
    }
}
