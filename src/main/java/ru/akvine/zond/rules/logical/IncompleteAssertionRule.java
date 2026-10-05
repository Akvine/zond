package ru.akvine.zond.rules.logical;

import com.github.javaparser.ast.stmt.ExpressionStmt;
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
public class IncompleteAssertionRule extends AbstractRule {
    // AssertJ и Truth: сам вызов ничего не проверяет, проверка - в методе, который идет следом
    private static final Set<String> ASSERTION_STARTS = Set.of("assertThat", "assertThatThrownBy", "assertThatCode");

    @Override
    public String code() {
        return RuleCodes.INCOMPLETE_ASSERTION_RULE_CODE;
    }

    @Override
    public String description() {
        return "Сканирует код и ищет assertThat(...) без вызова проверки";
    }

    @Override
    public List<Violation> check(SourceFile sourceFile) {
        // Оператор целиком - это assertThat(value); за ним нет isEqualTo, isTrue и т.п.
        return sourceFile.unit().findAll(ExpressionStmt.class).stream()
                .filter(statement -> statement.getExpression().isMethodCallExpr())
                .map(statement -> statement.getExpression().asMethodCallExpr())
                .filter(call -> ASSERTION_STARTS.contains(call.getNameAsString()))
                .map(call -> violation(sourceFile, call,
                        "'" + call + "' без проверки: вызов только готовит проверку, но ничего не сравнивает -"
                                + " тест пройдет при любом значении; добавьте проверку: .isEqualTo(...),"
                                + " .isTrue(), .isNotNull()"))
                .toList();
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
