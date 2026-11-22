package ru.akvine.zond.rules.security;

import org.springframework.stereotype.Component;
import ru.akvine.zond.enums.Confidence;
import ru.akvine.zond.enums.ErrorLevel;
import ru.akvine.zond.enums.ErrorType;
import ru.akvine.zond.models.ScanContext;
import ru.akvine.zond.models.TextFile;
import ru.akvine.zond.models.Violation;
import ru.akvine.zond.parsers.YamlNode;
import ru.akvine.zond.parsers.YamlParser;
import ru.akvine.zond.rules.AbstractContextRule;
import ru.akvine.zond.rules.RuleCodes;
import ru.akvine.zond.rules.files.Deployments;
import ru.akvine.zond.rules.files.TextFiles;
import ru.akvine.zond.rules.support.Secrets;

import java.util.ArrayList;
import java.util.List;

/**
 * {@code values.yaml} чарта Helm лежит в хранилище вместе с шаблонами. Пароль, записанный в нем значением,
 * читает каждый, у кого есть доступ к коду, и он остается в истории git после замены. Манифест с
 * {@code secretKeyRef} при этом выглядит правильным: секрет подставляется в него уже из values.
 */
@Component
public class HelmValuesSecretRule extends AbstractContextRule {
    private static final String SEPARATOR = ".";

    @Override
    public String code() {
        return RuleCodes.HELM_VALUES_SECRET_RULE_CODE;
    }

    @Override
    public String description() {
        return "Сканирует values.yaml чартов Helm и ищет пароли, токены и ключи, записанные открытым текстом";
    }

    @Override
    public List<Violation> checkContext(ScanContext context) {
        List<Violation> violations = new ArrayList<>();
        for (TextFile file : context.textFiles()) {
            if (!TextFiles.isHelmValues(file)) {
                continue;
            }
            for (YamlNode document : YamlParser.parse(file.lines()).orElse(List.of())) {
                check(file, "", document, violations);
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

    private void check(TextFile file, String path, YamlNode node, List<Violation> violations) {
        node.entries().forEach((key, value) -> {
            String name = path.isEmpty() ? key : path + SEPARATOR + key;
            if (value.isScalar() && isPlainSecret(key, value.text())) {
                Violation violation = violation(file.path(), value.line(),
                        "Секрет '" + name + "' записан в values-файле Helm открытым текстом: файл лежит в хранилище"
                                + " вместе с чартом, значение увидит каждый, у кого есть доступ к коду, и оно"
                                + " останется в истории git; держите в values только имя секрета (existingSecret),"
                                + " а значение передавайте при развертывании: --set из защищенных переменных CI,"
                                + " helm-secrets (SOPS) либо External Secrets");
                // Значения стенда разработчика и общеизвестные пароли образов рабочей среды обычно не касаются
                boolean weakEvidence = Deployments.isDevelopment(file.path())
                        || Secrets.isWellKnownDefault(value.text());
                violations.add(weakEvidence ? violation.withConfidence(Confidence.SUSPICION) : violation);
            }
            check(file, name, value, violations);
        });
        node.items().forEach(item -> check(file, path, item, violations));
    }

    // Подстановка ({{ .Values.x }}, ${VAR}) - не само значение
    private boolean isPlainSecret(String key, String value) {
        return Secrets.isSecretName(key) && !value.contains("{{") && !value.contains("${")
                && Secrets.isSecretValue(value);
    }
}
