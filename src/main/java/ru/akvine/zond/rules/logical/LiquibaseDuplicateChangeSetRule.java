package ru.akvine.zond.rules.logical;

import org.springframework.stereotype.Component;
import ru.akvine.zond.enums.Confidence;
import ru.akvine.zond.enums.ErrorLevel;
import ru.akvine.zond.enums.ErrorType;
import ru.akvine.zond.enums.TextFileType;
import ru.akvine.zond.models.ScanContext;
import ru.akvine.zond.models.TextFile;
import ru.akvine.zond.models.Violation;
import ru.akvine.zond.rules.AbstractContextRule;
import ru.akvine.zond.rules.RuleCodes;
import ru.akvine.zond.rules.files.Liquibase;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Component
public class LiquibaseDuplicateChangeSetRule extends AbstractContextRule {
    @Override
    public String code() {
        return RuleCodes.LIQUIBASE_DUPLICATE_CHANGE_SET_RULE_CODE;
    }

    @Override
    public String description() {
        return "Сканирует журналы Liquibase и ищет наборы изменений с одинаковыми id и автором в одном файле";
    }

    @Override
    public Confidence confidence() {
        return Confidence.CONFIRMED;
    }

    @Override
    public List<Violation> checkContext(ScanContext context) {
        List<Violation> violations = new ArrayList<>();
        for (TextFile file : context.textFiles()) {
            if (file.type() != TextFileType.LIQUIBASE_XML && file.type() != TextFileType.LIQUIBASE_YAML) {
                continue;
            }
            // Liquibase различает наборы по id, автору и файлу: в разных файлах повтор допустим, в одном - нет
            Map<String, Integer> seen = new HashMap<>();
            for (Liquibase.ChangeSet changeSet : Liquibase.changeSets(file)) {
                if (changeSet.id().isBlank()) {
                    continue;
                }
                Integer first = seen.putIfAbsent(changeSet.id() + "\n" + changeSet.author(), changeSet.line());
                if (first != null) {
                    violations.add(violation(file.path(), changeSet.line(),
                            "Набор изменений с id '" + changeSet.id() + "' и автором '" + changeSet.author()
                                    + "' уже объявлен в этом файле на строке " + first + ": Liquibase остановится"
                                    + " с ошибкой проверки журнала, и приложение не запустится; дайте набору свой id"));
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
        return ErrorType.LOGICAL;
    }
}
