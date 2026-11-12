package ru.akvine.zond.rules.logical;

import com.github.javaparser.ast.expr.Expression;
import com.github.javaparser.ast.expr.MethodCallExpr;
import com.github.javaparser.ast.stmt.BlockStmt;
import com.github.javaparser.ast.stmt.Statement;
import org.springframework.stereotype.Component;
import ru.akvine.zond.enums.ErrorLevel;
import ru.akvine.zond.enums.ErrorType;
import ru.akvine.zond.models.SourceFile;
import ru.akvine.zond.models.Violation;
import ru.akvine.zond.rules.AbstractRule;
import ru.akvine.zond.rules.RuleCodes;
import ru.akvine.zond.rules.support.TestClasses;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * Один и тот же сеттер вызван у объекта два раза подряд:
 * <pre>
 * result.setWriterItemsCount(task.getWriterItemsCount());
 * result.setWriterItemsCount(task.getWriterItemsCount());
 * </pre>
 * Первое значение тут же затирается вторым. Обычно это след копирования строки: во второй собирались
 * заполнить другое поле, и оно осталось пустым.
 */
@Component
public class DuplicateSetterCallRule extends AbstractRule {
    private static final Pattern SETTER = Pattern.compile("set[A-Z]\\w*");

    @Override
    public String code() {
        return RuleCodes.DUPLICATE_SETTER_CALL_RULE_CODE;
    }

    @Override
    public String description() {
        return "Сканирует код и ищет один и тот же сеттер, вызванный у объекта два раза подряд: первое значение"
                + " сразу затирается вторым";
    }

    @Override
    public List<Violation> check(SourceFile sourceFile) {
        List<Violation> violations = new ArrayList<>();
        for (BlockStmt block : sourceFile.unit().findAll(BlockStmt.class)) {
            if (TestClasses.isInside(block)) {
                continue;
            }
            List<Statement> statements = block.getStatements();
            for (int index = 1; index < statements.size(); index++) {
                Optional<MethodCallExpr> first = setterCall(statements.get(index - 1));
                Optional<MethodCallExpr> second = setterCall(statements.get(index));
                if (first.isPresent() && second.isPresent() && overwrites(first.get(), second.get())) {
                    violations.add(violation(sourceFile, second.get(), message(first.get(), second.get())));
                }
            }
        }
        return violations;
    }

    @Override
    public ErrorLevel errorLevel() {
        return ErrorLevel.MINOR;
    }

    @Override
    public ErrorType errorType() {
        return ErrorType.LOGICAL;
    }

    // Отдельный оператор вида object.setValue(value) с одним аргументом
    private Optional<MethodCallExpr> setterCall(Statement statement) {
        return Optional.of(statement)
                .filter(Statement::isExpressionStmt)
                .map(found -> found.asExpressionStmt().getExpression())
                .filter(Expression::isMethodCallExpr)
                .map(Expression::asMethodCallExpr)
                .filter(call -> call.getScope().isPresent() && call.getArguments().size() == 1
                        && SETTER.matcher(call.getNameAsString()).matches());
    }

    private boolean overwrites(MethodCallExpr first, MethodCallExpr second) {
        String receiver = first.getScope().orElseThrow().toString();
        return first.getNameAsString().equals(second.getNameAsString())
                && receiver.equals(second.getScope().orElseThrow().toString())
                // cell.setCellValue(""); cell.setCellValue(cell.getStringCellValue()): второе значение читает
                // то, что записал первый вызов
                && !second.getArgument(0).toString().contains(receiver + ".");
    }

    private String message(MethodCallExpr first, MethodCallExpr second) {
        String setter = "'" + second.getScope().orElseThrow() + "." + second.getNameAsString() + "(...)'";
        return first.getArgument(0).equals(second.getArgument(0))
                ? setter + " вызван два раза подряд с одним и тем же значением: похоже на скопированную строку, в"
                + " которой забыли поменять поле - проверьте, какое поле осталось незаполненным"
                : setter + " вызван два раза подряд: значение из первого вызова сразу затирается вторым; уберите"
                + " лишний вызов либо поправьте имя сеттера";
    }
}
