package ru.akvine.zond.rules;

import com.github.javaparser.ast.expr.LambdaExpr;
import com.github.javaparser.ast.expr.ObjectCreationExpr;
import com.github.javaparser.ast.stmt.ExpressionStmt;
import org.springframework.stereotype.Component;
import ru.akvine.zond.enums.ErrorLevel;
import ru.akvine.zond.enums.ErrorType;
import ru.akvine.zond.models.SourceFile;
import ru.akvine.zond.models.Violation;

import java.util.List;
import java.util.regex.Pattern;

@Component
public class CheckExceptionNotThrownRule extends AbstractRule {
    private static final Pattern EXCEPTION_TYPE = Pattern.compile(".*(Exception|Error)$");

    @Override
    public String code() {
        return RuleCodes.CHECK_EXCEPTION_NOT_THROWN_RULE_CODE;
    }

    @Override
    public String description() {
        return "Сканирует код и ищет исключения, которые созданы, но не брошены";
    }

    @Override
    public List<Violation> check(SourceFile sourceFile) {
        // new IllegalStateException("...") отдельным оператором: объект создан и тут же забыт
        return sourceFile.unit().findAll(ExpressionStmt.class).stream()
                .filter(statement -> statement.getExpression().isObjectCreationExpr())
                // orElseThrow(() -> new NotFoundException()): тело лямбды-выражения тоже хранится как оператор,
                // но созданное исключение там возвращается, а не теряется
                .filter(statement -> statement.getParentNode().filter(parent -> parent instanceof LambdaExpr).isEmpty())
                .map(statement -> statement.getExpression().asObjectCreationExpr())
                .filter(creation -> EXCEPTION_TYPE.matcher(creation.getType().getNameAsString()).matches())
                .map(creation -> report(sourceFile, creation))
                .toList();
    }

    @Override
    public ErrorLevel errorLevel() {
        return ErrorLevel.CRITICAL;
    }

    @Override
    public ErrorType errorType() {
        return ErrorType.LOGICAL;
    }

    private Violation report(SourceFile sourceFile, ObjectCreationExpr creation) {
        return violation(sourceFile, creation,
                "'" + creation.getType().getNameAsString() + "' создано, но не брошено: выполнение идет дальше,"
                        + " как будто ошибки нет; добавьте throw");
    }
}
