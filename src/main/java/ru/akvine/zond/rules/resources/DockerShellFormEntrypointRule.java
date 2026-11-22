package ru.akvine.zond.rules.resources;

import org.springframework.stereotype.Component;
import ru.akvine.zond.enums.ErrorLevel;
import ru.akvine.zond.enums.ErrorType;
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
 * {@code ENTRYPOINT java -jar app.jar} - запись без квадратных скобок. Docker выполняет такую команду как
 * {@code /bin/sh -c "java -jar app.jar"}: первым процессом контейнера становится оболочка, а JVM - ее потомком.
 * Сигнал остановки получает первый процесс, и до JVM он может не дойти: приложение не завершит запросы и не
 * закроет соединения, а по истечении отведенного времени контейнер убьют.
 */
@Component
public class DockerShellFormEntrypointRule extends AbstractContextRule {
    private static final Set<String> START_INSTRUCTIONS = Set.of("ENTRYPOINT", "CMD");
    private static final String ENTRYPOINT = "ENTRYPOINT";
    private static final String EXEC_FORM_START = "[";
    // java -jar app.jar, /opt/java/bin/java -cp ..., TZ=UTC java -jar app.jar; с exec в начале оболочку заменяет
    // сама JVM
    private static final Pattern JAVA = Pattern.compile("(\\S*/)?java");
    private static final Pattern ASSIGNMENT = Pattern.compile("\\w+=\\S*");
    private static final Pattern SPACES = Pattern.compile("\\s+");

    @Override
    public String code() {
        return RuleCodes.DOCKER_SHELL_FORM_ENTRYPOINT_RULE_CODE;
    }

    @Override
    public String description() {
        return "Сканирует Dockerfile и ищет запуск JVM командой без квадратных скобок и без exec: первым процессом"
                + " контейнера становится оболочка, и сигнал остановки до приложения может не дойти";
    }

    @Override
    public List<Violation> checkContext(ScanContext context) {
        List<Violation> violations = new ArrayList<>();
        for (TextFile file : context.textFiles()) {
            if (!TextFiles.isDockerfile(file)) {
                continue;
            }
            List<Dockerfiles.Instruction> starts = Dockerfiles.finalStage(Dockerfiles.instructions(file)).stream()
                    .filter(instruction -> START_INSTRUCTIONS.contains(instruction.keyword()))
                    .toList();
            // Действует последний ENTRYPOINT, а без него - последний CMD
            starts.stream()
                    .filter(instruction -> ENTRYPOINT.equals(instruction.keyword()))
                    .reduce((first, second) -> second)
                    .or(() -> starts.stream().reduce((first, second) -> second))
                    .filter(start -> !start.arguments().startsWith(EXEC_FORM_START)
                            && startsJavaWithoutExec(start.arguments()))
                    .ifPresent(start -> violations.add(violation(file.path(), start.line(),
                            "'" + start.keyword() + " " + start.arguments() + "' записан без квадратных скобок: такую"
                                    + " команду Docker выполняет через /bin/sh -c, первым процессом контейнера"
                                    + " становится оболочка, и сигнал остановки (SIGTERM) до JVM может не дойти -"
                                    + " это зависит от оболочки образа. Тогда приложение не завершит запросы, а"
                                    + " контейнер убьют по истечении времени на остановку. Пишите команду"
                                    + " массивом: " + start.keyword() + " [\"java\", \"-jar\", \"app.jar\"] - либо"
                                    + " начинайте ее с exec")));
        }
        return violations;
    }

    // Перед командой могут стоять переменные: TZ=UTC java -jar app.jar
    private boolean startsJavaWithoutExec(String command) {
        String[] words = SPACES.split(command.trim());
        int first = 0;
        while (first < words.length && ASSIGNMENT.matcher(words[first]).matches()) {
            first++;
        }
        return first < words.length - 1 && JAVA.matcher(words[first]).matches();
    }

    @Override
    public ErrorLevel errorLevel() {
        return ErrorLevel.MINOR;
    }

    @Override
    public ErrorType errorType() {
        return ErrorType.RESOURCE;
    }
}
