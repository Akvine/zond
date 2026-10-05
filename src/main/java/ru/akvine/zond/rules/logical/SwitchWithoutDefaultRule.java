package ru.akvine.zond.rules.logical;

import com.github.javaparser.ast.stmt.SwitchStmt;
import org.springframework.stereotype.Component;
import ru.akvine.zond.enums.ErrorLevel;
import ru.akvine.zond.enums.ErrorType;
import ru.akvine.zond.models.SourceFile;
import ru.akvine.zond.models.Violation;
import ru.akvine.zond.rules.AbstractRule;
import ru.akvine.zond.rules.RuleCodes;

import java.util.List;

@Component
public class SwitchWithoutDefaultRule extends AbstractRule {

    @Override
    public String code() {
        return RuleCodes.SWITCH_WITHOUT_DEFAULT_RULE_CODE;
    }

    @Override
    public String description() {
        return "Сканирует код и ищет оператор switch без ветки default";
    }

    @Override
    public List<Violation> check(SourceFile sourceFile) {
        // switch-выражение не трогаем: компилятор сам требует от него полноты
        return sourceFile.unit().findAll(SwitchStmt.class).stream()
                .filter(switchStmt -> switchStmt.getEntries().stream().noneMatch(entry -> entry.getLabels().isEmpty() || entry.isDefault()))
                .map(switchStmt -> violation(sourceFile, switchStmt,
                        "switch по '" + switchStmt.getSelector() + "' без default: значение, для которого нет"
                                + " ветки, будет молча пропущено; добавьте default, хотя бы с исключением"))
                .toList();
    }

    @Override
    public ErrorLevel errorLevel() {
        return ErrorLevel.MINOR;
    }

    @Override
    public ErrorType errorType() {
        return ErrorType.LOGICAL;
    }
}
