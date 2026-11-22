package ru.akvine.zond.rules.logical;

import org.springframework.stereotype.Component;
import ru.akvine.zond.enums.ErrorLevel;
import ru.akvine.zond.enums.ErrorType;
import ru.akvine.zond.models.ScanContext;
import ru.akvine.zond.models.TextFile;
import ru.akvine.zond.models.Violation;
import ru.akvine.zond.rules.AbstractContextRule;
import ru.akvine.zond.rules.RuleCodes;
import ru.akvine.zond.rules.files.Deployments;
import ru.akvine.zond.rules.files.Dockerfiles;
import ru.akvine.zond.rules.files.TextFiles;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * {@code JAVA_OPTS} - не переменная JVM, а договоренность: ее подставляет в команду запуска сценарий
 * ({@code catalina.sh}, сценарий базового образа) либо сама команда: {@code java $JAVA_OPTS -jar app.jar}. Если
 * образ запускает {@code java -jar app.jar} напрямую, переменная никем не читается: размер кучи, прокси,
 * кодировка и остальные параметры, заданные в манифесте, до JVM не доходят - и никакой ошибки при этом нет.
 * Сама JVM читает {@code JAVA_TOOL_OPTIONS} (и {@code JDK_JAVA_OPTIONS} с Java 9).
 */
@Component
public class JavaOptsNotAppliedRule extends AbstractContextRule {
    private static final String JAVA_OPTS = "JAVA_OPTS";
    private static final Set<String> START_INSTRUCTIONS = Set.of("ENTRYPOINT", "CMD");
    private static final String ENTRYPOINT = "ENTRYPOINT";
    // java -jar app.jar, exec java ..., ["java", "-jar", ...], ["/opt/java/bin/java", ...]
    private static final Pattern DIRECT_JAVA = Pattern.compile(
            "^(exec\\s+)?(\\S*/)?java\\s.*|^\\[\\s*\"(\\S*/)?java\"\\s*,.*");
    // $JAVA_OPTS, ${JAVA_OPTS}, ${JAVA_OPTS:-...}, $(JAVA_OPTS) в манифесте Kubernetes
    private static final Pattern REFERENCE = Pattern.compile("\\$[{(]?" + JAVA_OPTS + "\\b");
    // ENV JAVA_OPTS=..., environment: JAVA_OPTS: ..., - JAVA_OPTS=..., name: JAVA_OPTS
    private static final Pattern DOCKER_DEFINITION = Pattern.compile("(?i)^\\s*ENV\\s+" + JAVA_OPTS + "\\b.*");
    private static final Pattern YAML_DEFINITION = Pattern.compile(
            "^\\s*(-\\s*)?[\"']?" + JAVA_OPTS + "[\"']?\\s*[:=].*"
                    + "|^\\s*(-\\s*)?name:\\s*[\"']?" + JAVA_OPTS + "[\"']?\\s*$");
    // java_opts: ..., javaOpts: ... в values-файле Helm: шаблон превращает ключ в переменную окружения
    private static final Pattern HELM_DEFINITION = Pattern.compile("(?i)^\\s*(java_?opts)\\s*:.*");
    private static final Pattern ENV_NAME = Pattern.compile("^\\s*(-\\s*)?name:\\s*[\"']?" + JAVA_OPTS + "[\"']?\\s*$");

    /**
     * Команда запуска образа
     *
     * @param place где она записана: Dockerfile:7
     */
    private record Start(String command, String place) {
    }

    @Override
    public String code() {
        return RuleCodes.JAVA_OPTS_NOT_APPLIED_RULE_CODE;
    }

    @Override
    public String description() {
        return "Сканирует Dockerfile, docker-compose, манифесты Kubernetes и values-файлы Helm и ищет переменную"
                + " JAVA_OPTS, которую команда запуска образа не подставляет: параметры JVM из нее не применяются";
    }

    @Override
    public List<Violation> checkContext(ScanContext context) {
        List<TextFile> deployments = context.textFiles().stream().filter(Deployments::isDeployment).toList();
        // Переменную где-то подставляют: в команде образа, в command манифеста или в сценарии запуска. К какому
        // образу относится какой файл, по тексту не понять, поэтому одной подстановки хватает, чтобы не судить
        boolean referenced = deployments.stream()
                .flatMap(file -> Deployments.lines(file).stream())
                .anyMatch(line -> REFERENCE.matcher(line.text()).find());
        List<Start> starts = new ArrayList<>();
        boolean viaScript = false;
        for (TextFile file : deployments) {
            if (!TextFiles.isDockerfile(file)) {
                continue;
            }
            Optional<Dockerfiles.Instruction> start = startOf(file);
            if (start.isEmpty()) {
                continue;
            }
            if (DIRECT_JAVA.matcher(start.get().arguments()).matches()) {
                starts.add(new Start(start.get().keyword() + " " + start.get().arguments(),
                        file.path().getFileName() + ":" + start.get().line()));
            } else {
                // Запуск через свой сценарий или сценарий базового образа: он мог подставить переменную сам
                viaScript = true;
            }
        }
        if (referenced || viaScript || starts.isEmpty()) {
            return List.of();
        }

        List<Violation> violations = new ArrayList<>();
        for (TextFile file : deployments) {
            for (Deployments.Line line : Deployments.lines(file)) {
                if (defines(file, line, deployments)) {
                    violations.add(violation(file.path(), line.number(),
                            "Переменная JAVA_OPTS задана, но до JVM не дойдет: сама JVM ее не читает, а команда"
                                    + " запуска образа ее не подставляет ('" + starts.get(0).command() + "' в "
                                    + starts.get(0).place() + "). Параметры из нее - размер кучи, прокси, кодировка,"
                                    + " агенты - не применяются, и ошибки при этом нет. Переименуйте переменную в"
                                    + " JAVA_TOOL_OPTIONS - ее JVM читает сама - либо добавьте $JAVA_OPTS в"
                                    + " команду запуска"));
                }
            }
        }
        return violations;
    }

    @Override
    public ErrorLevel errorLevel() {
        return ErrorLevel.MAJOR;
    }

    @Override
    public ErrorType errorType() {
        return ErrorType.LOGICAL;
    }

    // Действует последний ENTRYPOINT итогового этапа, а без него - последний CMD
    private Optional<Dockerfiles.Instruction> startOf(TextFile file) {
        List<Dockerfiles.Instruction> starts = Dockerfiles.finalStage(Dockerfiles.instructions(file)).stream()
                .filter(instruction -> START_INSTRUCTIONS.contains(instruction.keyword()))
                .toList();
        return starts.stream()
                .filter(instruction -> ENTRYPOINT.equals(instruction.keyword()))
                .reduce((first, second) -> second)
                .or(() -> starts.stream().reduce((first, second) -> second));
    }

    private boolean defines(TextFile file, Deployments.Line line, List<TextFile> deployments) {
        if (TextFiles.isDockerfile(file)) {
            return DOCKER_DEFINITION.matcher(line.text()).matches();
        }
        if (!TextFiles.isHelmValues(file)) {
            return YAML_DEFINITION.matcher(line.text()).matches();
        }
        Matcher key = HELM_DEFINITION.matcher(line.text());
        return key.matches() && becomesVariable(key.group(1), deployments);
    }

    /**
     * @return true, если ключ values шаблоны превращают в переменную окружения, а не вставляют в команду: ключ в
     * шаблонах не назван вовсе (переменные собираются циклом по разделу env) либо стоит значением JAVA_OPTS
     */
    private boolean becomesVariable(String key, List<TextFile> deployments) {
        Pattern mention = Pattern.compile("\\.Values\\.[\\w.]*\\b" + Pattern.quote(key) + "\\b");
        for (TextFile file : deployments) {
            if (!TextFiles.isKubernetes(file)) {
                continue;
            }
            List<Deployments.Line> lines = Deployments.lines(file);
            for (int index = 0; index < lines.size(); index++) {
                if (mention.matcher(lines.get(index).text()).find()
                        && (index == 0 || !ENV_NAME.matcher(lines.get(index - 1).text()).matches())) {
                    return false;
                }
            }
        }
        return true;
    }
}
