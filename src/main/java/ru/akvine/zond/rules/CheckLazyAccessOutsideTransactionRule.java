package ru.akvine.zond.rules;

import com.github.javaparser.ast.Node;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.body.TypeDeclaration;
import com.github.javaparser.ast.body.VariableDeclarator;
import com.github.javaparser.ast.expr.Expression;
import com.github.javaparser.ast.expr.MethodCallExpr;
import com.github.javaparser.ast.stmt.ForEachStmt;
import org.springframework.stereotype.Component;
import ru.akvine.zond.enums.ErrorLevel;
import ru.akvine.zond.enums.ErrorType;
import ru.akvine.zond.models.SourceFile;
import ru.akvine.zond.models.Violation;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

@Component
public class CheckLazyAccessOutsideTransactionRule extends AbstractRule {
    // getOrders(), getItems()
    private static final Pattern GETTER = Pattern.compile("^get[A-Z].*");

    // Типы не разрешаем: о том, что геттер возвращает коллекцию-связь, судим по тому, что с ней делают
    private static final Set<String> COLLECTION_METHODS =
            Set.of("size", "isEmpty", "stream", "forEach", "iterator", "contains");

    @Override
    public String code() {
        return RuleCodes.CHECK_LAZY_ACCESS_OUTSIDE_TRANSACTION_RULE_CODE;
    }

    @Override
    public String description() {
        return "Сканирует код и ищет обращение к коллекциям-связям сущности, загруженной вне транзакции";
    }

    @Override
    public List<Violation> check(SourceFile sourceFile) {
        List<Violation> violations = new ArrayList<>();
        for (MethodDeclaration method : sourceFile.unit().findAll(MethodDeclaration.class)) {
            if (method.getBody().isEmpty() || isTransactional(method) || TestClasses.isInside(method)) {
                continue;
            }

            for (VariableDeclarator variable : method.findAll(VariableDeclarator.class)) {
                if (variable.getInitializer().filter(this::isLoadedFromRepository).isEmpty()) {
                    continue;
                }
                String entity = variable.getNameAsString();

                // order.getItems().size()
                for (MethodCallExpr call : method.findAll(MethodCallExpr.class)) {
                    if (COLLECTION_METHODS.contains(call.getNameAsString())
                            && call.getScope().filter(scope -> isRelationGetter(scope, entity)).isPresent()) {
                        violations.add(report(sourceFile, call, call.getScope().get().toString(), entity));
                    }
                }

                // for (Item item : order.getItems())
                for (ForEachStmt loop : method.findAll(ForEachStmt.class)) {
                    if (isRelationGetter(loop.getIterable(), entity)) {
                        violations.add(report(sourceFile, loop, loop.getIterable().toString(), entity));
                    }
                }
            }
        }

        violations.sort(Comparator.comparingInt(Violation::line));
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

    private Violation report(SourceFile sourceFile, Node node, String access, String entity) {
        return violation(sourceFile, node,
                "Обращение к связи '" + access + "' вне транзакции: сущность '" + entity + "' получена из"
                        + " репозитория в методе без @Transactional, после возврата из репозитория сессия закрыта -"
                        + " на LAZY-связи будет LazyInitializationException; загрузите связь заранее"
                        + " (JOIN FETCH, @EntityGraph) либо работайте в транзакции");
    }

    // Аннотация на методе либо на классе
    private boolean isTransactional(MethodDeclaration method) {
        if (TransactionalAnnotations.isPresent(method)) {
            return true;
        }
        return method.getParentNode()
                .filter(parent -> parent instanceof TypeDeclaration<?> type && TransactionalAnnotations.isPresent(type))
                .isPresent();
    }

    // repository.findById(id).orElseThrow(), userRepository.getReferenceById(id)
    private boolean isLoadedFromRepository(Expression initializer) {
        Expression value = Nodes.unwrap(initializer);
        if (!value.isMethodCallExpr()) {
            return false;
        }

        List<MethodCallExpr> chain = new ArrayList<>();
        chain.add(value.asMethodCallExpr());
        chain.addAll(StreamChains.callsBefore(value.asMethodCallExpr()));
        return chain.stream().anyMatch(Repositories::isRepositoryCall);
    }

    private boolean isRelationGetter(Expression expression, String entity) {
        Expression value = Nodes.unwrap(expression);
        return value.isMethodCallExpr()
                && GETTER.matcher(value.asMethodCallExpr().getNameAsString()).matches()
                && value.asMethodCallExpr().getArguments().isEmpty()
                && value.asMethodCallExpr().getScope()
                .filter(scope -> scope.isNameExpr() && scope.asNameExpr().getNameAsString().equals(entity))
                .isPresent();
    }
}
