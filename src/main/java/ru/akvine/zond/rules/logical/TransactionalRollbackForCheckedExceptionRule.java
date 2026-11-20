package ru.akvine.zond.rules.logical;

import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.expr.MethodCallExpr;
import com.github.javaparser.ast.nodeTypes.NodeWithSimpleName;
import com.github.javaparser.ast.type.ReferenceType;
import org.springframework.stereotype.Component;
import ru.akvine.zond.enums.Confidence;
import ru.akvine.zond.enums.ErrorLevel;
import ru.akvine.zond.enums.ErrorType;
import ru.akvine.zond.models.SourceFile;
import ru.akvine.zond.models.Violation;
import ru.akvine.zond.rules.Rule;
import ru.akvine.zond.rules.RuleCodes;
import ru.akvine.zond.rules.support.Annotations;
import ru.akvine.zond.rules.support.ExceptionKinds;
import ru.akvine.zond.rules.support.LocalTypes;
import ru.akvine.zond.rules.support.TransactionalAnnotations;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

@Component
public class TransactionalRollbackForCheckedExceptionRule implements Rule {
    private static final String SNEAKY_THROWS = "SneakyThrows";
    // Методы этих интерфейсов проверяемых исключений не бросают: @SneakyThrows над runnable.run() ничего не прячет
    private static final Set<String> SAFE_FUNCTIONAL_TYPES = Set.of(
            "Runnable", "Supplier", "Consumer", "BiConsumer", "Function", "BiFunction", "Predicate", "BiPredicate",
            "UnaryOperator", "BinaryOperator", "BooleanSupplier", "IntSupplier", "LongSupplier", "DoubleSupplier");
    private static final String UNKNOWN_NOTE = ". Класс исключения в проекте и библиотеках найти не удалось: если это"
            + " наследник RuntimeException, транзакция откатится и находка ложная, поэтому это подозрение";

    @Override
    public String name() {
        return getClass().getSimpleName();
    }

    @Override
    public String code() {
        return RuleCodes.TRANSACTIONAL_ROLLBACK_FOR_CHECKED_EXCEPTION_RULE_CODE;
    }

    @Override
    public String description() {
        return "Сканирует код и ищет @Transactional без rollbackFor над методами, которые бросают checked-исключения,"
                + " в том числе скрытые за @SneakyThrows";
    }

    @Override
    public boolean enabled() {
        return true;
    }

    @Override
    public List<Violation> check(SourceFile sourceFile) {
        List<Violation> violations = new ArrayList<>();
        for (MethodDeclaration method : sourceFile.findAll(MethodDeclaration.class)) {
            // Приватные не учитываем: на них @Transactional не работает в принципе, это ловит отдельное правило
            if (method.isPrivate()) {
                continue;
            }

            // Проверяемые наверняка и те, о которых ничего не известно: вторые сами по себе дают только подозрение
            List<String> checkedExceptions = findExceptions(method, Set.of(ExceptionKinds.Kind.CHECKED));
            List<String> unknownExceptions = findExceptions(method, Set.of(ExceptionKinds.Kind.UNKNOWN));
            // Lombok пробрасывает проверяемое исключение мимо компилятора: в сигнатуре его нет, но оно есть
            boolean sneaky = Annotations.has(method, SNEAKY_THROWS) && !callsOnlySafeFunctions(method);
            if (checkedExceptions.isEmpty() && unknownExceptions.isEmpty() && sneaky) {
                TransactionalAnnotations.findEffective(method)
                        .filter(annotation -> !TransactionalAnnotations.hasRollbackRule(annotation))
                        .ifPresent(annotation -> violations.add(violation(sourceFile, method,
                                "@SneakyThrows на @Transactional-методе '" + method.getNameAsString()
                                        + "': Lombok пробрасывает проверяемое исключение без throws в сигнатуре,"
                                        + " а на проверяемых исключениях Spring транзакцию не откатывает - она будет"
                                        + " зафиксирована с частично выполненной работой; задайте"
                                        + " rollbackFor = Exception.class либо оборачивайте исключение в непроверяемое")));
            }
            if (checkedExceptions.isEmpty() && unknownExceptions.isEmpty()) {
                continue;
            }

            boolean certain = !checkedExceptions.isEmpty();
            List<String> thrown = certain
                    ? findExceptions(method, Set.of(ExceptionKinds.Kind.CHECKED, ExceptionKinds.Kind.UNKNOWN))
                    : unknownExceptions;
            TransactionalAnnotations.findEffective(method)
                    .filter(annotation -> !TransactionalAnnotations.hasRollbackRule(annotation))
                    .ifPresent(annotation -> {
                        Violation violation = violation(sourceFile, method,
                                "@Transactional без rollbackFor над методом '" + method.getNameAsString()
                                        + "', который бросает checked-исключение " + String.join(", ", thrown)
                                        + ": транзакция не откатится, а будет зафиксирована"
                                        + (certain ? "" : UNKNOWN_NOTE));
                        violations.add(certain ? violation : violation.withConfidence(Confidence.SUSPICION));
                    });
        }
        return violations;
    }

    @Override
    public ErrorLevel errorLevel() {
        return ErrorLevel.CRITICAL;
    }

    @Override
    public ErrorType errorType() {
        return ErrorType.LOGICAL;
    }

    private Violation violation(SourceFile sourceFile, MethodDeclaration method, String message) {
        return new Violation(errorLevel(), errorType(), code(), name(), sourceFile.path(),
                method.getBegin().map(position -> position.line).orElse(0), message);
    }

    private List<String> findExceptions(MethodDeclaration method, Set<ExceptionKinds.Kind> kinds) {
        // throws E, где E - параметр типа метода: чем он окажется, неизвестно
        Set<String> typeParameters = method.getTypeParameters().stream()
                .map(NodeWithSimpleName::getNameAsString)
                .collect(Collectors.toSet());

        return method.getThrownExceptions().stream()
                .filter(exception -> kinds.contains(ExceptionKinds.of(exception)))
                .map(this::simpleName)
                .filter(exception -> !typeParameters.contains(exception))
                .toList();
    }

    // execute(Runnable task) { task.run(); }: все вызовы в теле - методы функциональных интерфейсов из параметров
    private boolean callsOnlySafeFunctions(MethodDeclaration method) {
        Set<String> safeParameters = method.getParameters().stream()
                .filter(parameter -> SAFE_FUNCTIONAL_TYPES.contains(LocalTypes.typeName(parameter.getType())))
                .map(parameter -> parameter.getNameAsString())
                .collect(Collectors.toSet());
        List<MethodCallExpr> calls = method.findAll(MethodCallExpr.class);
        return !safeParameters.isEmpty() && !calls.isEmpty() && calls.stream()
                .allMatch(call -> call.getScope()
                        .filter(scope -> scope.isNameExpr()
                                && safeParameters.contains(scope.asNameExpr().getNameAsString()))
                        .isPresent());
    }

    // java.io.IOException -> IOException
    private String simpleName(ReferenceType type) {
        return type.isClassOrInterfaceType() ? type.asClassOrInterfaceType().getNameAsString() : type.asString();
    }
}
