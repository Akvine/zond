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
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;

@Component
public class CheckLazyAccessOutsideTransactionRule extends AbstractRule implements ProjectRule {
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
    public List<Violation> checkProject(List<SourceFile> sourceFiles) {
        CallGraph graph = CallGraph.of(sourceFiles);
        List<Violation> violations = new ArrayList<>();
        for (SourceFile sourceFile : sourceFiles) {
            List<Violation> found = check(sourceFile, graph);
            found.sort(Comparator.comparingInt(Violation::line));
            violations.addAll(found);
        }
        return violations;
    }

    private List<Violation> check(SourceFile sourceFile, CallGraph graph) {
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
                for (Node access : findRelationAccesses(method, entity)) {
                    violations.add(report(sourceFile, access, describe(access), entity));
                }

                // Сущность отдана другому методу, и к связи обращается уже он - тоже без транзакции
                for (MethodCallExpr call : method.findAll(MethodCallExpr.class)) {
                    findAccessInCallee(call, entity, graph).ifPresent(access -> violations.add(report(
                            sourceFile, call, access + " в методе '" + call.getNameAsString() + "'", entity)));
                }
            }
        }
        return violations;
    }

    // order.getItems().size() и for (Item item : order.getItems())
    private List<Node> findRelationAccesses(Node body, String entity) {
        List<Node> accesses = new ArrayList<>();
        for (MethodCallExpr call : body.findAll(MethodCallExpr.class)) {
            if (COLLECTION_METHODS.contains(call.getNameAsString())
                    && call.getScope().filter(scope -> isRelationGetter(scope, entity)).isPresent()) {
                accesses.add(call);
            }
        }
        for (ForEachStmt loop : body.findAll(ForEachStmt.class)) {
            if (isRelationGetter(loop.getIterable(), entity)) {
                accesses.add(loop);
            }
        }
        return accesses;
    }

    private String describe(Node access) {
        return access instanceof ForEachStmt loop
                ? loop.getIterable().toString()
                : ((MethodCallExpr) access).getScope().get().toString();
    }

    /**
     * @return обращение к связи в методе проекта, которому сущность передана аргументом
     */
    private Optional<String> findAccessInCallee(MethodCallExpr call, String entity, CallGraph graph) {
        int index = -1;
        for (int position = 0; position < call.getArguments().size(); position++) {
            if (call.getArgument(position).toString().equals(entity)) {
                index = position;
            }
        }
        if (index < 0) {
            return Optional.empty();
        }
        for (MethodDeclaration target : graph.targetsOf(call)) {
            // В транзакционном методе сущность можно присоединить к сессии заново - о нем не судим
            if (index >= target.getParameters().size() || isTransactional(target)) {
                continue;
            }
            String parameter = target.getParameter(index).getNameAsString();
            Optional<String> access = findRelationAccesses(target, parameter).stream().map(this::describe).findFirst();
            if (access.isPresent()) {
                return access;
            }
        }
        return Optional.empty();
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
