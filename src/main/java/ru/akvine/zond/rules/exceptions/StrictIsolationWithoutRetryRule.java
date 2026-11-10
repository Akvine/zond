package ru.akvine.zond.rules.exceptions;

import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.expr.AnnotationExpr;
import com.github.javaparser.ast.expr.Expression;
import com.github.javaparser.ast.expr.MemberValuePair;
import org.springframework.stereotype.Component;
import ru.akvine.zond.enums.ErrorLevel;
import ru.akvine.zond.enums.ErrorType;
import ru.akvine.zond.models.RuleParameter;
import ru.akvine.zond.models.SourceFile;
import ru.akvine.zond.models.Violation;
import ru.akvine.zond.rules.AbstractRule;
import ru.akvine.zond.rules.ProjectRule;
import ru.akvine.zond.rules.RuleCodes;
import ru.akvine.zond.rules.support.FailureHandling;
import ru.akvine.zond.rules.support.TestClasses;
import ru.akvine.zond.rules.support.TransactionalAnnotations;

import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Транзакция с изоляцией REPEATABLE_READ или SERIALIZABLE, которую никто не повторяет. При таких уровнях база
 * сама откатывает одну из транзакций, работающих с одними строками: ошибка сериализации, взаимная блокировка.
 * Это штатный исход, и рассчитан он на повтор транзакции целиком.
 */
@Component
public class StrictIsolationWithoutRetryRule extends AbstractRule implements ProjectRule {
    private static final RuleParameter MAX_CALL_DEPTH = new RuleParameter(
            "max-call-depth", 3,
            "На сколько вызовов вверх от транзакционного метода искать перехват исключения или повтор;"
                    + " 0 - только на самом методе");
    private static final String ISOLATION = "isolation";
    private static final Set<String> STRICT_LEVELS = Set.of("REPEATABLE_READ", "SERIALIZABLE");
    // В сообщении о классе называем несколько методов, остальные - числом
    private static final int MAX_NAMES = 3;

    @Override
    public String code() {
        return RuleCodes.STRICT_ISOLATION_WITHOUT_RETRY_RULE_CODE;
    }

    @Override
    public String description() {
        return "Сканирует код и ищет @Transactional с изоляцией REPEATABLE_READ или SERIALIZABLE без повтора"
                + " транзакции при ошибке сериализации";
    }

    @Override
    public List<RuleParameter> parameters() {
        return List.of(MAX_CALL_DEPTH);
    }

    @Override
    public List<Violation> checkProject(List<SourceFile> sourceFiles) {
        FailureHandling handling = FailureHandling.of(sourceFiles);
        if (handling.hasGlobalHandler(FailureHandling.LOCK_FAILURES)) {
            return List.of();
        }
        List<Violation> violations = new ArrayList<>();
        for (SourceFile sourceFile : sourceFiles) {
            // Аннотация на классе действует на все его открытые методы: о ней сообщаем один раз
            Map<AnnotationExpr, List<MethodDeclaration>> unprotected = new IdentityHashMap<>();
            List<AnnotationExpr> order = new ArrayList<>();
            for (MethodDeclaration method : sourceFile.unit().findAll(MethodDeclaration.class)) {
                Optional<AnnotationExpr> transaction = strictTransaction(method);
                if (transaction.isEmpty() || TestClasses.isInside(method)
                        || !handling.escapesFrom(method, FailureHandling.LOCK_FAILURES, value(MAX_CALL_DEPTH))) {
                    continue;
                }
                if (!unprotected.containsKey(transaction.get())) {
                    order.add(transaction.get());
                }
                unprotected.computeIfAbsent(transaction.get(), annotation -> new ArrayList<>()).add(method);
            }
            for (AnnotationExpr transaction : order) {
                violations.add(violation(sourceFile, transaction, message(transaction, unprotected.get(transaction))));
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
        return ErrorType.EXCEPTION;
    }

    // На приватных методах @Transactional не действует; транзакция только для чтения ничего не меняет,
    // и откатывать ее базе незачем
    private Optional<AnnotationExpr> strictTransaction(MethodDeclaration method) {
        if (method.isPrivate() || method.getBody().isEmpty()) {
            return Optional.empty();
        }
        return TransactionalAnnotations.findEffective(method)
                .filter(annotation -> !TransactionalAnnotations.isReadOnly(annotation))
                .filter(annotation -> isolationOf(annotation).isPresent());
    }

    // isolation = Isolation.SERIALIZABLE -> SERIALIZABLE
    private Optional<String> isolationOf(AnnotationExpr annotation) {
        if (!annotation.isNormalAnnotationExpr()) {
            return Optional.empty();
        }
        return annotation.asNormalAnnotationExpr().getPairs().stream()
                .filter(pair -> ISOLATION.equals(pair.getNameAsString()))
                .map(MemberValuePair::getValue)
                .map(this::lastIdentifier)
                .filter(STRICT_LEVELS::contains)
                .findFirst();
    }

    private String lastIdentifier(Expression value) {
        if (value.isFieldAccessExpr()) {
            return value.asFieldAccessExpr().getNameAsString();
        }
        return value.isNameExpr() ? value.asNameExpr().getNameAsString() : "";
    }

    private String message(AnnotationExpr transaction, List<MethodDeclaration> methods) {
        String level = isolationOf(transaction).orElse("");
        boolean onClass = transaction.getParentNode().filter(parent -> parent instanceof ClassOrInterfaceDeclaration).isPresent();
        String names = methods.stream()
                .limit(MAX_NAMES)
                .map(method -> "'" + method.getNameAsString() + "'")
                .collect(Collectors.joining(", "));
        String rest = methods.size() > MAX_NAMES ? " и еще " + (methods.size() - MAX_NAMES) : "";
        String subject = onClass
                ? "Транзакции класса идут с изоляцией " + level + ", и методы " + names + rest + " никто не повторяет"
                : "Транзакция метода " + names + " идет с изоляцией " + level + ", и ее никто не повторяет";
        return subject + ": при одновременной работе с одними строками база сама откатывает одну из транзакций -"
                + " ошибка сериализации (could not serialize access) либо взаимная блокировка, в Spring это"
                + " CannotAcquireLockException / CannotSerializeTransactionException, - и запрос завершится"
                + " ошибкой 500; повторяйте транзакцию целиком (@Retryable, RetryTemplate), перехватите"
                + " исключение у вызывающего либо заведите для него @ExceptionHandler";
    }
}
