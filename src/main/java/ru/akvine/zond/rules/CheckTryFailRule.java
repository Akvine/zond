package ru.akvine.zond.rules;

import com.github.javaparser.ast.stmt.Statement;
import com.github.javaparser.ast.stmt.TryStmt;
import org.springframework.stereotype.Component;
import ru.akvine.zond.enums.ErrorLevel;
import ru.akvine.zond.enums.ErrorType;
import ru.akvine.zond.models.SourceFile;
import ru.akvine.zond.models.Violation;

import java.util.List;

@Component
public class CheckTryFailRule extends AbstractRule {
    private static final String FAIL = "fail";

    @Override
    public String code() {
        return RuleCodes.CHECK_TRY_FAIL_RULE_CODE;
    }

    @Override
    public String description() {
        return "Сканирует код и ищет проверку исключения через try / fail() / catch вместо assertThrows";
    }

    @Override
    public List<Violation> check(SourceFile sourceFile) {
        return sourceFile.unit().findAll(TryStmt.class).stream()
                .filter(tryStmt -> !tryStmt.getCatchClauses().isEmpty() && endsWithFail(tryStmt))
                .map(tryStmt -> violation(sourceFile, tryStmt,
                        "Исключение проверяется через try / fail() / catch: если забыть fail(), тест пройдет и без"
                                + " исключения, а если catch слишком широкий - поймает и сам fail();"
                                + " используйте assertThrows(...) или assertThatThrownBy(...)"))
                .toList();
    }

    @Override
    public ErrorLevel errorLevel() {
        return ErrorLevel.MINOR;
    }

    @Override
    public ErrorType errorType() {
        return ErrorType.CODE_SMELL;
    }

    // Последний оператор блока try - вызов fail(...)
    private boolean endsWithFail(TryStmt tryStmt) {
        List<Statement> statements = tryStmt.getTryBlock().getStatements();
        if (statements.isEmpty() || !statements.get(statements.size() - 1).isExpressionStmt()) {
            return false;
        }
        return statements.get(statements.size() - 1).asExpressionStmt().getExpression().toMethodCallExpr()
                .filter(call -> FAIL.equals(call.getNameAsString()))
                .isPresent();
    }
}
