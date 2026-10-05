package ru.akvine.zond.rules.security;

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
import java.util.Optional;
import java.util.Set;

@Component
public class DockerRootUserRule extends AbstractContextRule {
    private static final String USER = "USER";
    private static final Set<String> ROOT_USERS = Set.of("root", "0", "0:0", "root:root");

    @Override
    public String code() {
        return RuleCodes.DOCKER_ROOT_USER_RULE_CODE;
    }

    @Override
    public String description() {
        return "Сканирует Dockerfile и ищет образы, в которых приложение запускается от root";
    }

    @Override
    public List<Violation> checkContext(ScanContext context) {
        List<Violation> violations = new ArrayList<>();
        for (TextFile file : context.textFiles()) {
            if (!TextFiles.isDockerfile(file)) {
                continue;
            }
            List<Dockerfiles.Instruction> stage = Dockerfiles.finalStage(Dockerfiles.instructions(file));
            if (stage.isEmpty()) {
                continue;
            }
            // Действует последняя инструкция USER итогового этапа
            Optional<Dockerfiles.Instruction> user = stage.stream()
                    .filter(instruction -> USER.equals(instruction.keyword()))
                    .reduce((first, second) -> second);
            if (user.isEmpty() || ROOT_USERS.contains(user.get().arguments())) {
                violations.add(violation(file.path(), user.map(Dockerfiles.Instruction::line).orElse(stage.get(0).line()),
                        "Приложение в образе запускается от root: если его взломают, злоумышленник получит"
                                + " полные права в контейнере и больше шансов выйти из него; создайте"
                                + " пользователя и переключитесь на него инструкцией USER"));
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
        return ErrorType.SECURITY;
    }
}
