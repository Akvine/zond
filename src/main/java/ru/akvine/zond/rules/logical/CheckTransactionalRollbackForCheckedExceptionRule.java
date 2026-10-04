package ru.akvine.zond.rules.logical;

import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.nodeTypes.NodeWithSimpleName;
import com.github.javaparser.ast.type.ReferenceType;
import org.springframework.stereotype.Component;
import ru.akvine.zond.enums.ErrorLevel;
import ru.akvine.zond.enums.ErrorType;
import ru.akvine.zond.models.SourceFile;
import ru.akvine.zond.models.Violation;
import ru.akvine.zond.rules.Rule;
import ru.akvine.zond.rules.RuleCodes;
import ru.akvine.zond.rules.support.TransactionalAnnotations;
import ru.akvine.zond.rules.support.Types;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

@Component
public class CheckTransactionalRollbackForCheckedExceptionRule implements Rule {
    // Unchecked-исключение - наследник RuntimeException или Error. Если иерархию разрешить не удалось,
    // узнаем его по имени; все остальное из throws считаем checked
    private static final Set<String> UNCHECKED_ROOTS = Set.of("RuntimeException", "Error");

    private static final Set<String> UNCHECKED_EXCEPTIONS = Set.of(
            "RuntimeException",
            "Error",
            "IllegalArgumentException",
            "IllegalStateException",
            "NullPointerException",
            "UnsupportedOperationException",
            "IndexOutOfBoundsException",
            "ArrayIndexOutOfBoundsException",
            "ClassCastException",
            "ArithmeticException",
            "NumberFormatException",
            "ConcurrentModificationException",
            "NoSuchElementException",
            "UncheckedIOException",
            "DateTimeException",
            "SecurityException",
            "DataAccessException");

    @Override
    public String name() {
        return getClass().getSimpleName();
    }

    @Override
    public String code() {
        return RuleCodes.CHECK_TRANSACTIONAL_ROLLBACK_FOR_CHECKED_EXCEPTION_RULE_CODE;
    }

    @Override
    public String description() {
        return "Сканирует код и ищет @Transactional без rollbackFor над методами, которые бросают checked-исключения";
    }

    @Override
    public boolean enabled() {
        return true;
    }

    @Override
    public List<Violation> check(SourceFile sourceFile) {
        List<Violation> violations = new ArrayList<>();
        for (MethodDeclaration method : sourceFile.unit().findAll(MethodDeclaration.class)) {
            // Приватные не учитываем: на них @Transactional не работает в принципе, это ловит отдельное правило
            if (method.isPrivate()) {
                continue;
            }

            List<String> checkedExceptions = findCheckedExceptions(method);
            if (checkedExceptions.isEmpty()) {
                continue;
            }

            TransactionalAnnotations.findEffective(method)
                    .filter(annotation -> !TransactionalAnnotations.hasRollbackRule(annotation))
                    .ifPresent(annotation -> violations.add(new Violation(
                            errorLevel(),
                            errorType(),
                            code(),
                            name(),
                            sourceFile.path(),
                            method.getBegin().map(position -> position.line).orElse(0),
                            "@Transactional без rollbackFor над методом '" + method.getNameAsString()
                                    + "', который бросает checked-исключение " + String.join(", ", checkedExceptions)
                                    + ": транзакция не откатится, а будет зафиксирована")));
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

    private List<String> findCheckedExceptions(MethodDeclaration method) {
        // throws E, где E - параметр типа метода: чем он окажется, неизвестно
        Set<String> typeParameters = method.getTypeParameters().stream()
                .map(NodeWithSimpleName::getNameAsString)
                .collect(Collectors.toSet());

        return method.getThrownExceptions().stream()
                .filter(exception -> !isUnchecked(exception))
                .map(this::simpleName)
                .filter(exception -> !typeParameters.contains(exception))
                .toList();
    }

    private boolean isUnchecked(ReferenceType exception) {
        return Types.isKindOf(exception, UNCHECKED_ROOTS)
                .orElseGet(() -> UNCHECKED_EXCEPTIONS.contains(simpleName(exception)));
    }

    // java.io.IOException -> IOException
    private String simpleName(ReferenceType type) {
        return type.isClassOrInterfaceType() ? type.asClassOrInterfaceType().getNameAsString() : type.asString();
    }
}
