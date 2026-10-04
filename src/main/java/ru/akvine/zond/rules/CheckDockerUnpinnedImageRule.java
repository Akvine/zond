package ru.akvine.zond.rules;

import org.springframework.stereotype.Component;
import ru.akvine.zond.enums.ErrorLevel;
import ru.akvine.zond.enums.ErrorType;
import ru.akvine.zond.models.ScanContext;
import ru.akvine.zond.models.TextFile;
import ru.akvine.zond.models.Violation;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

@Component
public class CheckDockerUnpinnedImageRule extends AbstractContextRule {
    private static final String SCRATCH = "scratch";
    private static final String LATEST = "latest";
    private static final String FLAG_PREFIX = "--";
    private static final String AS = "as";

    @Override
    public String code() {
        return RuleCodes.CHECK_DOCKER_UNPINNED_IMAGE_RULE_CODE;
    }

    @Override
    public String description() {
        return "Сканирует Dockerfile и ищет базовые образы без тега или с тегом latest";
    }

    @Override
    public List<Violation> checkContext(ScanContext context) {
        List<Violation> violations = new ArrayList<>();
        for (TextFile file : context.textFiles()) {
            if (!TextFiles.isDockerfile(file)) {
                continue;
            }
            // Имена этапов сборки: FROM builder - это не образ из реестра
            Set<String> stages = new HashSet<>();
            for (Dockerfiles.Instruction instruction : Dockerfiles.instructions(file)) {
                if (!Dockerfiles.FROM.equals(instruction.keyword())) {
                    continue;
                }
                String[] words = instruction.arguments().split("\\s+");
                int imageIndex = words[0].startsWith(FLAG_PREFIX) && words.length > 1 ? 1 : 0;
                String image = words[imageIndex];
                if (isUnpinned(image) && !stages.contains(image.toLowerCase(Locale.ROOT))) {
                    violations.add(violation(file.path(), instruction.line(),
                            "Базовый образ '" + image + "' без точной версии: при следующей сборке под тем же"
                                    + " именем придет другой образ, и поведение изменится без единой правки"
                                    + " в коде; укажите конкретный тег, а лучше дайджест"));
                }
                if (words.length > imageIndex + 2 && AS.equalsIgnoreCase(words[imageIndex + 1])) {
                    stages.add(words[imageIndex + 2].toLowerCase(Locale.ROOT));
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

    // Образ из переменной (${BASE_IMAGE}) проверить нельзя; образ с дайджестом (@sha256:...) закреплен намертво
    private boolean isUnpinned(String image) {
        if (image.equalsIgnoreCase(SCRATCH) || image.contains("$") || image.contains("@")) {
            return false;
        }
        // Тег стоит после двоеточия в последней части имени: registry:5000/app - это порт, а не тег
        String lastPart = image.substring(image.lastIndexOf('/') + 1);
        int colon = lastPart.lastIndexOf(':');
        return colon < 0 || LATEST.equals(lastPart.substring(colon + 1));
    }
}
