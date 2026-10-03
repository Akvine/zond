package ru.akvine.zond.rules;

import com.github.javaparser.ast.expr.Expression;
import com.github.javaparser.ast.expr.MethodCallExpr;
import org.springframework.stereotype.Component;
import ru.akvine.zond.enums.ErrorLevel;
import ru.akvine.zond.enums.ErrorType;
import ru.akvine.zond.models.SourceFile;
import ru.akvine.zond.models.Violation;

import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

@Component
public class CheckStatementInsteadOfPreparedRule extends AbstractRule {
    private static final String STATEMENT = "Statement";
    private static final String CREATE_STATEMENT = "createStatement";
    private static final Set<String> EXECUTE_METHODS = Set.of("executeQuery", "executeUpdate", "execute", "addBatch");

    // Запрос из константы - не пользовательский ввод
    private static final Pattern CONSTANT_NAME = Pattern.compile("^[A-Z][A-Z0-9_]*$");

    @Override
    public String code() {
        return RuleCodes.CHECK_STATEMENT_INSTEAD_OF_PREPARED_RULE_CODE;
    }

    @Override
    public String description() {
        return "Сканирует код и ищет выполнение собранного в коде SQL через Statement вместо PreparedStatement";
    }

    @Override
    public List<Violation> check(SourceFile sourceFile) {
        return sourceFile.unit().findAll(MethodCallExpr.class).stream()
                .filter(call -> EXECUTE_METHODS.contains(call.getNameAsString()) && !call.getArguments().isEmpty())
                .filter(call -> call.getScope().filter(this::isStatement).isPresent())
                .filter(call -> isBuiltInCode(call.getArgument(0)))
                .map(call -> violation(sourceFile, call,
                        "'" + call.getScope().get() + "." + call.getNameAsString() + "(...)' выполняет через"
                                + " Statement запрос, текст которого собирается в коде: значения попадают прямо в"
                                + " SQL, возможна инъекция; используйте PreparedStatement с параметрами"))
                .toList();
    }

    @Override
    public ErrorLevel errorLevel() {
        return ErrorLevel.CRITICAL;
    }

    @Override
    public ErrorType errorType() {
        return ErrorType.SECURITY;
    }

    // Переменная типа Statement либо connection.createStatement().execute(...)
    private boolean isStatement(Expression scope) {
        Expression value = Nodes.unwrap(scope);
        if (value.isMethodCallExpr()) {
            return CREATE_STATEMENT.equals(value.asMethodCallExpr().getNameAsString());
        }
        return LocalTypes.typeOf(value).filter(STATEMENT::equals).isPresent();
    }

    // Все, кроме строкового литерала и константы: переменная, конкатенация, вызов метода
    private boolean isBuiltInCode(Expression sql) {
        Expression value = Nodes.unwrap(sql);
        if (StringLiterals.textOf(value).isPresent()) {
            return false;
        }
        if (value.isNameExpr()) {
            return !CONSTANT_NAME.matcher(value.asNameExpr().getNameAsString()).matches();
        }
        if (value.isFieldAccessExpr()) {
            return !CONSTANT_NAME.matcher(value.asFieldAccessExpr().getNameAsString()).matches();
        }
        return true;
    }
}
