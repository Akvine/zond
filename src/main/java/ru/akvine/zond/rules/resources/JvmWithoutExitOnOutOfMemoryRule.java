package ru.akvine.zond.rules.resources;

import org.springframework.stereotype.Component;
import ru.akvine.zond.enums.Confidence;
import ru.akvine.zond.enums.ErrorLevel;
import ru.akvine.zond.enums.ErrorType;
import ru.akvine.zond.enums.TextFileType;
import ru.akvine.zond.models.ScanContext;
import ru.akvine.zond.models.TextFile;
import ru.akvine.zond.models.Violation;
import ru.akvine.zond.rules.AbstractContextRule;
import ru.akvine.zond.rules.RuleCodes;
import ru.akvine.zond.rules.files.Dockerfiles;
import ru.akvine.zond.rules.files.TextFiles;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * {@code ENTRYPOINT ["java", "-jar", "/app.jar"]}: если приложению не хватит памяти, OutOfMemoryError получит тот
 * поток, которому не повезло, - в том числе служебный поток веб-сервера, принимающий соединения. Процесс при этом
 * остается жив: контейнер считается работающим, но на запросы уже не отвечает, и перезапускать его некому. С флагом
 * -XX:+ExitOnOutOfMemoryError JVM завершается при первой же нехватке памяти, и контейнер поднимают заново.
 */
@Component
public class JvmWithoutExitOnOutOfMemoryRule extends AbstractContextRule {
    private static final Set<String> START_INSTRUCTIONS = Set.of("ENTRYPOINT", "CMD");
    // java -jar app.jar, ["java", "-jar"], exec java $JAVA_OPTS, /opt/java/bin/java
    private static final Pattern JAVA_COMMAND = Pattern.compile("(^|[\\s\"'\\[/,])java([\\s\"',]|$)");
    // command: ["bash", "-c", "java -Xmx1g -jar app.jar"] в docker-compose и манифесте Kubernetes: команда образа
    // заменена своей
    private static final Pattern JAVA_LAUNCH =
            Pattern.compile("(^|[\\s\"'\\[/,])java[\\s\"',].*-(jar|cp|classpath)([\\s\"',]|$)");
    private static final String YAML_COMMENT = "#";
    // Любой из флагов завершает или перезапускает процесс при нехватке памяти
    private static final List<String> EXIT_FLAGS =
            List.of("ExitOnOutOfMemoryError", "CrashOnOutOfMemoryError", "OnOutOfMemoryError=");
    // java $JAVA_OPTS -jar app.jar: параметры JVM задает окружение, и флаг может прийти оттуда
    private static final Pattern OPTIONS_VARIABLE = Pattern.compile("\\$\\{?\\w*(OPTS|OPTIONS|ARGS|FLAGS)\\w*");

    @Override
    public String code() {
        return RuleCodes.JVM_WITHOUT_EXIT_ON_OUT_OF_MEMORY_RULE_CODE;
    }

    @Override
    public String description() {
        return "Сканирует Dockerfile, docker-compose и манифесты Kubernetes и ищет запуск JVM без флага"
                + " -XX:+ExitOnOutOfMemoryError: после нехватки памяти процесс остается жив, но перестает отвечать";
    }

    @Override
    public List<Violation> checkContext(ScanContext context) {
        List<Violation> violations = new ArrayList<>();
        // Флаг могут передать и не в Dockerfile: переменной JAVA_TOOL_OPTIONS в docker-compose или манифесте
        // Kubernetes, настройкой сборки образа. К какому образу такая настройка относится, по файлам не понять,
        // поэтому считаем, что о флаге в проекте позаботились
        boolean setOutside = context.textFiles().stream()
                .anyMatch(file -> file.type() != TextFileType.DOCKERFILE && mentionsFlag(file));
        if (setOutside) {
            return violations;
        }

        for (TextFile file : context.textFiles()) {
            if (!TextFiles.isDockerfile(file) || mentionsFlag(file)) {
                continue;
            }
            // Действует последняя команда запуска итогового этапа
            Dockerfiles.finalStage(Dockerfiles.instructions(file)).stream()
                    .filter(instruction -> START_INSTRUCTIONS.contains(instruction.keyword())
                            && JAVA_COMMAND.matcher(instruction.arguments()).find())
                    .reduce((first, second) -> second)
                    .ifPresent(start -> violations.add(violation(file, start.line(), start.arguments())));
        }
        for (TextFile file : context.textFiles()) {
            if (!TextFiles.isCompose(file) && !TextFiles.isKubernetes(file)) {
                continue;
            }
            for (int index = 0; index < file.lines().size(); index++) {
                String line = file.lines().get(index);
                if (!line.trim().startsWith(YAML_COMMENT) && JAVA_LAUNCH.matcher(line).find()) {
                    violations.add(violation(file, index + 1, line));
                }
            }
        }
        return violations;
    }

    @Override
    public ErrorLevel errorLevel() {
        return ErrorLevel.MINOR;
    }

    @Override
    public ErrorType errorType() {
        return ErrorType.RESOURCE;
    }

    private Violation violation(TextFile file, int line, String command) {
        Violation violation = violation(file.path(), line,
                "JVM в контейнере запускается без -XX:+ExitOnOutOfMemoryError: после OutOfMemoryError процесс"
                        + " может остаться жив, но перестать отвечать - ошибка достается и служебным потокам"
                        + " сервера, - а раз контейнер работает, перезапускать его некому; добавьте флаг в команду"
                        + " запуска или в переменную JAVA_TOOL_OPTIONS, чтобы процесс завершался и его поднимали"
                        + " заново");
        return OPTIONS_VARIABLE.matcher(command).find() ? violation.withConfidence(Confidence.SUSPICION) : violation;
    }

    private boolean mentionsFlag(TextFile file) {
        return file.lines().stream().anyMatch(line -> EXIT_FLAGS.stream().anyMatch(line::contains));
    }
}
