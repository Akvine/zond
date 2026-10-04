package ru.akvine.zond.rules;

import org.springframework.stereotype.Component;
import ru.akvine.zond.enums.ErrorLevel;
import ru.akvine.zond.enums.ErrorType;
import ru.akvine.zond.models.ScanContext;
import ru.akvine.zond.models.TextFile;
import ru.akvine.zond.models.Violation;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Component
public class CheckDockerSecretInImageRule extends AbstractContextRule {
    private static final Set<String> VALUE_INSTRUCTIONS = Set.of("ENV", "ARG");

    // NAME=value, NAME="value"; у ENV допустима и запись через пробел: ENV NAME value
    private static final Pattern ASSIGNMENT = Pattern.compile("([A-Za-z_][A-Za-z0-9_]*)=(\"[^\"]*\"|'[^']*'|\\S*)");
    private static final Pattern SPACE_FORM = Pattern.compile("^([A-Za-z_][A-Za-z0-9_]*)\\s+(\\S.*)$");
    private static final String VARIABLE = "$";

    @Override
    public String code() {
        return RuleCodes.CHECK_DOCKER_SECRET_IN_IMAGE_RULE_CODE;
    }

    @Override
    public String description() {
        return "Сканирует Dockerfile и ищет пароли, токены и ключи, записанные в ENV и ARG";
    }

    @Override
    public List<Violation> checkContext(ScanContext context) {
        List<Violation> violations = new ArrayList<>();
        for (TextFile file : context.textFiles()) {
            if (!TextFiles.isDockerfile(file)) {
                continue;
            }
            for (Dockerfiles.Instruction instruction : Dockerfiles.instructions(file)) {
                if (!VALUE_INSTRUCTIONS.contains(instruction.keyword())) {
                    continue;
                }
                Matcher assignment = ASSIGNMENT.matcher(instruction.arguments());
                boolean found = false;
                while (assignment.find()) {
                    found = true;
                    report(file, instruction, assignment.group(1), assignment.group(2), violations);
                }
                Matcher spaceForm = SPACE_FORM.matcher(instruction.arguments());
                if (!found && spaceForm.matches()) {
                    report(file, instruction, spaceForm.group(1), spaceForm.group(2), violations);
                }
            }
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

    // Значение из переменной сборки (${TOKEN}) секретом в файле не является; пустое - тоже
    private void report(
            TextFile file, Dockerfiles.Instruction instruction, String name, String value, List<Violation> violations) {
        String plain = value.replaceAll("^[\"']|[\"']$", "");
        if (Secrets.isSecretName(name) && !plain.isEmpty() && !plain.contains(VARIABLE)) {
            violations.add(violation(file.path(), instruction.line(),
                    "Секрет '" + name + "' записан в " + instruction.keyword() + ": значение остается в слоях"
                            + " образа и в его истории, и его увидит каждый, у кого есть образ; передавайте"
                            + " секрет при запуске либо через --secret при сборке"));
        }
    }
}
