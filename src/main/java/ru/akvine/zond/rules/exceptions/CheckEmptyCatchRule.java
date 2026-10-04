package ru.akvine.zond.rules.exceptions;

import com.github.javaparser.ast.stmt.CatchClause;
import org.springframework.stereotype.Component;
import ru.akvine.zond.enums.ErrorLevel;
import ru.akvine.zond.enums.ErrorType;
import ru.akvine.zond.models.SourceFile;
import ru.akvine.zond.models.Violation;
import ru.akvine.zond.rules.AbstractRule;
import ru.akvine.zond.rules.RuleCodes;

import java.util.List;
import java.util.Set;

@Component
public class CheckEmptyCatchRule extends AbstractRule {
    // Имя переменной прямо говорит, что исключение пропускают намеренно
    private static final Set<String> IGNORED_NAMES = Set.of("ignored", "ignore", "expected");

    @Override
    public String code() {
        return RuleCodes.CHECK_EMPTY_CATCH_RULE_CODE;
    }

    @Override
    public String description() {
        return "Сканирует код и ищет пустые блоки catch";
    }

    @Override
    public List<Violation> check(SourceFile sourceFile) {
        // Комментарий внутри блока считаем объяснением, почему исключение пропущено
        return sourceFile.unit().findAll(CatchClause.class).stream()
                .filter(clause -> clause.getBody().getStatements().isEmpty())
                .filter(clause -> clause.getBody().getAllContainedComments().isEmpty())
                .filter(clause -> !IGNORED_NAMES.contains(clause.getParameter().getNameAsString()))
                .map(clause -> violation(sourceFile, clause,
                        "Пустой catch (" + clause.getParameter().getType() + "): исключение проглатывается,"
                                + " ошибка останется незамеченной; обработайте его или хотя бы запишите в лог"))
                .toList();
    }

    @Override
    public ErrorLevel errorLevel() {
        return ErrorLevel.MAJOR;
    }

    @Override
    public ErrorType errorType() {
        return ErrorType.EXCEPTION;
    }
}
