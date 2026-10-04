package ru.akvine.zond.rules.security;

import org.springframework.stereotype.Component;
import ru.akvine.zond.enums.ErrorLevel;
import ru.akvine.zond.enums.ErrorType;
import ru.akvine.zond.models.ScanContext;
import ru.akvine.zond.models.TextFile;
import ru.akvine.zond.models.Violation;
import ru.akvine.zond.rules.AbstractContextRule;
import ru.akvine.zond.rules.RuleCodes;
import ru.akvine.zond.rules.files.ComposeFiles;
import ru.akvine.zond.rules.support.Secrets;

import java.util.ArrayList;
import java.util.List;

@Component
public class CheckComposeSecretInEnvironmentRule extends AbstractContextRule {
    @Override
    public String code() {
        return RuleCodes.CHECK_COMPOSE_SECRET_IN_ENVIRONMENT_RULE_CODE;
    }

    @Override
    public String description() {
        return "Сканирует docker-compose и ищет пароли и ключи, записанные в переменные окружения открытым текстом";
    }

    @Override
    public List<Violation> checkContext(ScanContext context) {
        List<Violation> violations = new ArrayList<>();
        for (TextFile file : context.textFiles()) {
            ComposeFiles.services(file).forEach((service, node) ->
                    ComposeFiles.environment(node).forEach((name, variable) -> {
                        String value = ComposeFiles.valueOf(name, variable);
                        // ${DB_PASSWORD} - значение придет из окружения или файла .env, в репозитории его нет
                        if (Secrets.isSecretName(name) && !value.contains("$") && Secrets.isSecretValue(value)) {
                            violations.add(violation(file.path(), variable.line(),
                                    "Секрет '" + name + "' сервиса '" + service + "' записан в файле открытым"
                                            + " текстом и попадет в репозиторий; подставляйте его из окружения"
                                            + " (${" + name + "}) либо используйте secrets"));
                        }
                    }));
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
