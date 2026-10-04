package ru.akvine.zond.rules;

import org.springframework.stereotype.Component;
import ru.akvine.zond.enums.ErrorLevel;
import ru.akvine.zond.enums.ErrorType;
import ru.akvine.zond.models.ScanContext;
import ru.akvine.zond.models.TextFile;
import ru.akvine.zond.models.Violation;
import ru.akvine.zond.parsers.YamlNode;

import java.util.ArrayList;
import java.util.List;

@Component
public class CheckComposeUnpinnedImageRule extends AbstractContextRule {
    private static final String IMAGE = "image";

    @Override
    public String code() {
        return RuleCodes.CHECK_COMPOSE_UNPINNED_IMAGE_RULE_CODE;
    }

    @Override
    public String description() {
        return "Сканирует docker-compose и ищет образы сервисов без тега или с тегом latest";
    }

    @Override
    public List<Violation> checkContext(ScanContext context) {
        List<Violation> violations = new ArrayList<>();
        for (TextFile file : context.textFiles()) {
            ComposeFiles.services(file).forEach((name, service) -> {
                YamlNode image = service.get(IMAGE);
                if (Images.isUnpinned(image.text())) {
                    violations.add(violation(file.path(), image.line(),
                            "Сервис '" + name + "' использует образ '" + image.text() + "' без точной версии:"
                                    + " после очередного docker compose pull под тем же именем придет другой образ;"
                                    + " укажите конкретный тег"));
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
        return ErrorType.LOGICAL;
    }
}
