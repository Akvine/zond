package ru.akvine.zond.rules;

import com.github.javaparser.ast.Node;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.expr.Expression;
import com.github.javaparser.ast.expr.MethodCallExpr;
import com.github.javaparser.ast.stmt.ReturnStmt;
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
public class CheckOptionalMisuseRule extends AbstractRule {
    private static final String OPTIONAL = "Optional";
    private static final String GET = "get";
    private static final Set<String> PRESENCE_CHECKS = Set.of("isPresent", "isEmpty");

    // Методы, которые возвращают Optional: вызов get() сразу после них ничем не защищен
    private static final Set<String> OPTIONAL_SOURCES = Set.of("findFirst", "findAny", "findById", "ofNullable");

    @Override
    public String code() {
        return RuleCodes.CHECK_OPTIONAL_MISUSE_RULE_CODE;
    }

    @Override
    public String description() {
        return "Сканирует код и ищет Optional.get() без проверки и return null из методов, возвращающих Optional";
    }

    @Override
    public List<Violation> check(SourceFile sourceFile) {
        List<Violation> violations = new ArrayList<>();

        for (MethodDeclaration method : sourceFile.unit().findAll(MethodDeclaration.class)) {
            if (!OPTIONAL.equals(LocalTypes.typeName(method.getType()))) {
                continue;
            }
            for (ReturnStmt returnStmt : method.findAll(ReturnStmt.class)) {
                boolean returnsNull = returnStmt.getExpression().filter(Expression::isNullLiteralExpr).isPresent();
                if (returnsNull && !Nodes.isInNestedScope(returnStmt, method)) {
                    violations.add(violation(sourceFile, returnStmt,
                            "Метод '" + method.getNameAsString() + "' возвращает Optional, но отдает null:"
                                    + " вызывающий код получит NullPointerException; верните Optional.empty()"));
                }
            }
        }

        for (MethodCallExpr call : sourceFile.unit().findAll(MethodCallExpr.class)) {
            if (!GET.equals(call.getNameAsString()) || !call.getArguments().isEmpty() || call.getScope().isEmpty()) {
                continue;
            }

            Expression scope = Nodes.unwrap(call.getScope().get());
            if (isUncheckedSource(scope) || isUncheckedVariable(scope, call)) {
                violations.add(violation(sourceFile, call,
                        "Optional.get() без проверки в '" + call + "': на пустом Optional будет"
                                + " NoSuchElementException; используйте orElseThrow(), orElse() или ifPresent()"));
            }
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

    // stream.findFirst().get(), repository.findById(id).get()
    private boolean isUncheckedSource(Expression scope) {
        return scope.isMethodCallExpr() && OPTIONAL_SOURCES.contains(scope.asMethodCallExpr().getNameAsString());
    }

    // Переменная типа Optional, для которой в методе нигде нет isPresent() / isEmpty(),
    // в том числе через цепочку: value.filter(...).isPresent()
    private boolean isUncheckedVariable(Expression scope, MethodCallExpr call) {
        if (LocalTypes.typeOf(scope).filter(OPTIONAL::equals).isEmpty()) {
            return false;
        }

        Optional<Node> callable = Nodes.enclosingCallable(call);
        String variable = scope.toString();
        return callable.isPresent() && callable.get().findAll(MethodCallExpr.class).stream()
                .filter(check -> PRESENCE_CHECKS.contains(check.getNameAsString()))
                .noneMatch(check -> check.getScope()
                        .map(checked -> Nodes.unwrap(checked).toString())
                        .filter(checked -> checked.equals(variable) || checked.startsWith(variable + "."))
                        .isPresent());
    }
}
