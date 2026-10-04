package ru.akvine.zond.rules;

import com.github.javaparser.ast.Node;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.body.VariableDeclarator;
import com.github.javaparser.ast.expr.Expression;
import com.github.javaparser.ast.expr.MethodCallExpr;
import org.springframework.stereotype.Component;
import ru.akvine.zond.enums.ErrorLevel;
import ru.akvine.zond.enums.ErrorType;
import ru.akvine.zond.models.SourceFile;
import ru.akvine.zond.models.Violation;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;

@Component
public class CheckReferenceOutsideTransactionRule extends AbstractRule {
    private static final Set<String> REFERENCE_METHODS = Set.of("getReferenceById", "getOne", "getById", "getReference");

    // Идентификатор хранится в самой ссылке - для него обращение к БД не нужно
    private static final Set<String> SAFE_METHODS = Set.of("getId", "getClass", "equals", "hashCode");

    @Override
    public String code() {
        return RuleCodes.CHECK_REFERENCE_OUTSIDE_TRANSACTION_RULE_CODE;
    }

    @Override
    public String description() {
        return "Сканирует код и ищет обращение к полям ссылки из getReferenceById / getOne вне транзакции";
    }

    @Override
    public List<Violation> check(SourceFile sourceFile) {
        List<Violation> violations = new ArrayList<>();
        for (VariableDeclarator variable : sourceFile.unit().findAll(VariableDeclarator.class)) {
            Optional<MethodCallExpr> source = variable.getInitializer()
                    .map(Nodes::unwrap)
                    .filter(Expression::isMethodCallExpr)
                    .map(Expression::asMethodCallExpr)
                    .filter(call -> REFERENCE_METHODS.contains(call.getNameAsString()));
            Optional<Node> callable = Nodes.enclosingCallable(variable);
            if (source.isEmpty() || callable.isEmpty() || isTransactional(callable.get())
                    || TestClasses.isInside(variable)) {
                continue;
            }

            String reference = variable.getNameAsString();
            callable.get().findAll(MethodCallExpr.class).stream()
                    .filter(call -> call.getScope().filter(scope -> scope.toString().equals(reference)).isPresent())
                    .filter(call -> !SAFE_METHODS.contains(call.getNameAsString()))
                    .findFirst()
                    .ifPresent(call -> violations.add(violation(sourceFile, call,
                            "'" + call + "': '" + reference + "' получена через " + source.get().getNameAsString()
                                    + "() - это ссылка без данных, а транзакции в методе нет: при обращении"
                                    + " к полю будет LazyInitializationException; загружайте через findById()"
                                    + " либо работайте в @Transactional")));
        }
        return violations;
    }

    @Override
    public ErrorLevel errorLevel() {
        return ErrorLevel.MAJOR;
    }

    @Override
    public ErrorType errorType() {
        return ErrorType.LOGICAL;
    }

    private boolean isTransactional(Node callable) {
        return callable instanceof MethodDeclaration method
                && TransactionalAnnotations.findEffective(method).isPresent();
    }
}
