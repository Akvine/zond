package ru.akvine.zond.rules.security;

import org.springframework.stereotype.Component;
import ru.akvine.zond.enums.ErrorLevel;
import ru.akvine.zond.enums.ErrorType;
import ru.akvine.zond.models.ScanContext;
import ru.akvine.zond.models.TextFile;
import ru.akvine.zond.models.Violation;
import ru.akvine.zond.parsers.YamlNode;
import ru.akvine.zond.rules.AbstractContextRule;
import ru.akvine.zond.rules.RuleCodes;
import ru.akvine.zond.rules.files.CiFiles;
import ru.akvine.zond.rules.support.Secrets;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

@Component
public class CiSecretInVariablesRule extends AbstractContextRule {
    // variables - GitLab CI; env и with - GitHub Actions
    private static final Set<String> VARIABLE_BLOCKS = Set.of("variables", "env", "with");
    private static final String VALUE = "value";

    @Override
    public String code() {
        return RuleCodes.CI_SECRET_IN_VARIABLES_RULE_CODE;
    }

    @Override
    public String description() {
        return "Сканирует файлы CI (GitLab CI, GitHub Actions) и ищет пароли и ключи, записанные в переменные открытым текстом";
    }

    @Override
    public List<Violation> checkContext(ScanContext context) {
        List<Violation> violations = new ArrayList<>();
        for (TextFile file : context.textFiles()) {
            for (YamlNode document : CiFiles.documents(file)) {
                document.visit((key, block) -> {
                    if (VARIABLE_BLOCKS.contains(key)) {
                        block.entries().forEach((name, variable) -> check(file, name, variable, violations));
                    }
                });
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

    private void check(TextFile file, String name, YamlNode variable, List<Violation> violations) {
        // В GitLab CI переменную записывают и словарем: {value: ..., description: ...}
        String value = variable.isScalar() ? variable.text() : variable.get(VALUE).text();
        // $DB_PASSWORD и ${{ secrets.TOKEN }} - значение хранится в настройках CI, в репозитории его нет
        if (Secrets.isSecretName(name) && !value.contains("$") && Secrets.isSecretValue(value)) {
            violations.add(violation(file.path(), variable.line(),
                    "Секрет '" + name + "' записан в файле CI открытым текстом: его видит каждый, у кого есть"
                            + " доступ к репозиторию, и он остается в истории; перенесите значение в защищенные"
                            + " переменные CI и считайте прежнее скомпрометированным"));
        }
    }
}
