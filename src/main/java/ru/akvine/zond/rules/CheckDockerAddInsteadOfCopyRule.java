package ru.akvine.zond.rules;

import org.springframework.stereotype.Component;
import ru.akvine.zond.enums.ErrorLevel;
import ru.akvine.zond.enums.ErrorType;
import ru.akvine.zond.models.ScanContext;
import ru.akvine.zond.models.TextFile;
import ru.akvine.zond.models.Violation;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

@Component
public class CheckDockerAddInsteadOfCopyRule extends AbstractContextRule {
    private static final String ADD = "ADD";

    // ADD нужен для двух вещей: скачать по адресу и распаковать архив
    private static final Pattern NEEDS_ADD = Pattern.compile(
            ".*(https?://|\\.tar\\b|\\.tar\\.\\w+\\b|\\.tgz\\b|\\.zip\\b).*", Pattern.CASE_INSENSITIVE);

    @Override
    public String code() {
        return RuleCodes.CHECK_DOCKER_ADD_INSTEAD_OF_COPY_RULE_CODE;
    }

    @Override
    public String description() {
        return "Сканирует Dockerfile и ищет ADD там, где достаточно COPY";
    }

    @Override
    public List<Violation> checkContext(ScanContext context) {
        List<Violation> violations = new ArrayList<>();
        for (TextFile file : context.textFiles()) {
            if (!TextFiles.isDockerfile(file)) {
                continue;
            }
            for (Dockerfiles.Instruction instruction : Dockerfiles.instructions(file)) {
                if (ADD.equals(instruction.keyword()) && !NEEDS_ADD.matcher(instruction.arguments()).matches()) {
                    violations.add(violation(file.path(), instruction.line(),
                            "ADD для обычного копирования файлов: в отличие от COPY он умеет скачивать и"
                                    + " распаковывать, и что именно произойдет, из инструкции не видно;"
                                    + " используйте COPY"));
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
        return ErrorType.CODE_SMELL;
    }
}
