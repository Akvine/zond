package ru.akvine.zond.rules.security;

import com.github.javaparser.ast.expr.Expression;
import com.github.javaparser.ast.expr.MethodCallExpr;
import org.springframework.stereotype.Component;
import ru.akvine.zond.enums.ErrorLevel;
import ru.akvine.zond.enums.ErrorType;
import ru.akvine.zond.models.SourceFile;
import ru.akvine.zond.models.Violation;
import ru.akvine.zond.rules.AbstractTaintRule;
import ru.akvine.zond.rules.RuleCodes;
import ru.akvine.zond.rules.support.LocalTypes;
import ru.akvine.zond.rules.support.Nodes;
import ru.akvine.zond.rules.support.StringLiterals;
import ru.akvine.zond.rules.support.Taint;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;

@Component
public class CheckStatementInsteadOfPreparedRule extends AbstractTaintRule {
    private static final String STATEMENT = "Statement";
    private static final String CREATE_STATEMENT = "createStatement";
    private static final Set<String> EXECUTE_METHODS = Set.of("executeQuery", "executeUpdate", "execute", "addBatch");

    @Override
    public String code() {
        return RuleCodes.CHECK_STATEMENT_INSTEAD_OF_PREPARED_RULE_CODE;
    }

    @Override
    public String description() {
        return "Сканирует код и ищет выполнение через Statement запроса, в текст которого попадают данные извне";
    }

    @Override
    protected List<Violation> check(SourceFile sourceFile, Taint taint) {
        List<Violation> violations = new ArrayList<>();
        for (MethodCallExpr call : sourceFile.unit().findAll(MethodCallExpr.class)) {
            boolean isExecution = EXECUTE_METHODS.contains(call.getNameAsString()) && !call.getArguments().isEmpty()
                    && call.getScope().filter(this::isStatement).isPresent();
            if (!isExecution || StringLiterals.textOf(Nodes.unwrap(call.getArgument(0))).isPresent()) {
                continue;
            }
            // Statement сам по себе не уязвимость: запрос из константы, из настроек или собранный в коде
            // нарушителю недоступен. Опасен только текст, в который попадают данные клиента
            Optional<Taint.Source> source = taint.findSource(call.getArgument(0));
            source.ifPresent(found -> violations.add(violation(sourceFile, call,
                    "'" + call.getScope().get() + "." + call.getNameAsString() + "(...)' выполняет через Statement"
                            + " запрос, в текст которого попадают данные извне (" + found.describe() + "): тот, кто ими"
                            + " управляет, может дописать к запросу свой SQL; используйте PreparedStatement"
                            + " с параметрами")));
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

    // Переменная типа Statement либо connection.createStatement().execute(...)
    private boolean isStatement(Expression scope) {
        Expression value = Nodes.unwrap(scope);
        if (value.isMethodCallExpr()) {
            return CREATE_STATEMENT.equals(value.asMethodCallExpr().getNameAsString());
        }
        return LocalTypes.typeOf(value).filter(STATEMENT::equals).isPresent();
    }
}
