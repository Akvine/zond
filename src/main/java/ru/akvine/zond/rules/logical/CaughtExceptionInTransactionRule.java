package ru.akvine.zond.rules.logical;

import com.github.javaparser.ast.Node;
import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.expr.AnnotationExpr;
import com.github.javaparser.ast.expr.MethodCallExpr;
import com.github.javaparser.ast.stmt.CatchClause;
import com.github.javaparser.ast.stmt.TryStmt;
import com.github.javaparser.ast.type.Type;
import org.springframework.stereotype.Component;
import ru.akvine.zond.enums.ErrorLevel;
import ru.akvine.zond.enums.ErrorType;
import ru.akvine.zond.models.SourceFile;
import ru.akvine.zond.models.Violation;
import ru.akvine.zond.rules.AbstractRule;
import ru.akvine.zond.rules.ProjectRule;
import ru.akvine.zond.rules.RuleCodes;
import ru.akvine.zond.rules.support.CallGraph;
import ru.akvine.zond.rules.support.LocalTypes;
import ru.akvine.zond.rules.support.Messaging;
import ru.akvine.zond.rules.support.MethodCalls;
import ru.akvine.zond.rules.support.Nodes;
import ru.akvine.zond.rules.support.ProjectClasses;
import ru.akvine.zond.rules.support.Repositories;
import ru.akvine.zond.rules.support.TransactionalAnnotations;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * Исключение из метода, который участвует в общей транзакции, поймано и проглочено. Вызванный метод, выходя
 * с исключением, уже пометил транзакцию к откату, и коммит внешнего метода закончится UnexpectedRollbackException.
 * <p>
 * Транзакция при этом одна на всю цепочку вызовов внутри бина: свой метод, вызванный напрямую, выполняется
 * в ней же. Поэтому вызов участника ищется и в своих методах, а перехват - и в закрытых методах, которые
 * вызываются только из транзакционных.
 */
@Component
public class CaughtExceptionInTransactionRule extends AbstractRule implements ProjectRule {
    private static final Set<String> BROAD = Set.of("Exception", "RuntimeException", "Throwable");
    // Непроверяемые исключения, которые чаще всего ловят вокруг работы с данными
    private static final Set<String> KNOWN_UNCHECKED = Set.of(
            "DataAccessException", "DataIntegrityViolationException", "DuplicateKeyException",
            "EmptyResultDataAccessException", "OptimisticLockingFailureException",
            "ObjectOptimisticLockingFailureException", "PersistenceException", "EntityNotFoundException",
            "ConstraintViolationException", "NoSuchElementException", "IllegalArgumentException",
            "IllegalStateException", "NullPointerException");
    private static final String RUNTIME_EXCEPTION = "RuntimeException";

    // Вызовы репозитория, которые обращаются к базе сразу и потому бросают исключение на месте;
    // обычный save() откладывает запрос до коммита
    private static final Set<String> IMMEDIATE_REPOSITORY_METHODS = Set.of(
            "saveAndFlush", "saveAllAndFlush", "flush", "deleteById", "deleteAllById");
    private static final String NO_ROLLBACK = "noRollbackFor";
    private static final String ROLLBACK_ONLY = "setRollbackOnly";
    // place() -> chargeQuietly() -> paymentService.charge(): дальше своих методов цепочка обычно не уходит
    private static final int MAX_OWN_CALL_DEPTH = 2;

    /**
     * Вызов, исключение из которого помечает общую транзакцию к откату
     *
     * @param call сам вызов: paymentService.charge
     * @param via  свой метод, через который до него дошли, если вызов стоит не в самом try
     */
    private record Participant(String call, Optional<String> via) {

        String describe() {
            return "'" + call + "'" + via.map(method -> " (через свой метод '" + method + "')").orElse("");
        }
    }

    @Override
    public String code() {
        return RuleCodes.CAUGHT_EXCEPTION_IN_TRANSACTION_RULE_CODE;
    }

    @Override
    public String description() {
        return "Сканирует код и ищет в @Transactional-методах перехват исключения из другого транзакционного метода без проброса";
    }

    @Override
    public List<Violation> checkProject(List<SourceFile> sourceFiles) {
        CallGraph graph = CallGraph.of(sourceFiles);
        ProjectClasses classes = ProjectClasses.of(sourceFiles);
        List<Violation> violations = new ArrayList<>();
        for (SourceFile sourceFile : sourceFiles) {
            for (MethodDeclaration method : sourceFile.unit().findAll(MethodDeclaration.class)) {
                transactionOf(method, graph, MAX_OWN_CALL_DEPTH)
                        .ifPresent(transaction -> check(sourceFile, method, transaction, graph, classes, violations));
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

    private void check(
            SourceFile sourceFile, MethodDeclaration method, MethodDeclaration transaction, CallGraph graph,
            ProjectClasses classes, List<Violation> violations) {
        for (TryStmt statement : method.findAll(TryStmt.class)) {
            // try внутри лямбды или анонимного класса выполняется уже не в этом методе
            if (Nodes.isInNestedScope(statement, method)) {
                continue;
            }
            Optional<Participant> participant = statement.getTryBlock().findAll(MethodCallExpr.class).stream()
                    .filter(call -> !Nodes.isInNestedScope(call, statement))
                    .flatMap(call -> participant(call, method, graph, MAX_OWN_CALL_DEPTH, identitySet()).stream())
                    .findFirst();
            if (participant.isEmpty()) {
                continue;
            }
            for (CatchClause clause : statement.getCatchClauses()) {
                if (catchesUnchecked(clause, classes) && !Messaging.rethrows(clause) && !marksRollback(clause)) {
                    // Закрытый метод прокси не видит: он выполняется в транзакции того, кто его вызвал
                    String place = method == transaction
                            ? "@Transactional-методе '" + method.getNameAsString() + "'"
                            : "методе '" + method.getNameAsString() + "', который выполняется в транзакции метода '"
                            + transaction.getNameAsString() + "'";
                    violations.add(violation(sourceFile, clause,
                            "Исключение из " + participant.get().describe() + " перехвачено в " + place
                                    + ", и работа продолжается: вызванный метод"
                                    + " участвует в той же транзакции и, выходя с исключением, уже пометил ее"
                                    + " к откату - коммит закончится UnexpectedRollbackException, и все изменения"
                                    + " метода пропадут; пробросьте исключение, выполняйте вызов в отдельной"
                                    + " транзакции (REQUIRES_NEW) либо задайте для него noRollbackFor"));
                }
            }
        }
    }

    // На приватных методах @Transactional не действует: транзакции там нет
    private boolean isTransactional(MethodDeclaration method) {
        return !method.isPrivate()
                && method.getBody().isPresent()
                && TransactionalAnnotations.findEffective(method).isPresent();
    }

    /**
     * @return транзакционный метод, в транзакции которого выполняется код метода: он сам либо, для закрытого
     * метода, тот, кто его вызывает. Пусто, если хотя бы один вызов закрытого метода идет без транзакции
     */
    private Optional<MethodDeclaration> transactionOf(MethodDeclaration method, CallGraph graph, int depth) {
        if (isTransactional(method)) {
            return Optional.of(method);
        }
        if (!method.isPrivate() || method.getBody().isEmpty() || depth == 0) {
            return Optional.empty();
        }
        Optional<MethodDeclaration> transaction = Optional.empty();
        for (CallGraph.Call call : graph.callsTo(method)) {
            if (call.caller() == method) {
                continue;
            }
            Optional<MethodDeclaration> outer = transactionOf(call.caller(), graph, depth - 1);
            if (outer.isEmpty()) {
                return Optional.empty();
            }
            transaction = transaction.or(() -> outer);
        }
        return transaction;
    }

    /**
     * @return описание вызова, исключение из которого помечает общую транзакцию к откату: сам вызов участника
     * либо вызов своего метода, который до участника доходит. Свой метод выполняется здесь же, мимо прокси,
     * и исключение из него приходит в тот же try
     */
    private Optional<Participant> participant(
            MethodCallExpr call, MethodDeclaration caller, CallGraph graph, int depth, Set<MethodDeclaration> seen) {
        if (joinsTransaction(call, caller, graph)) {
            return Optional.of(new Participant(describe(call), Optional.empty()));
        }
        if (depth == 0) {
            return Optional.empty();
        }
        Optional<ClassOrInterfaceDeclaration> callerType = Messaging.ownerOf(caller);
        for (MethodDeclaration target : graph.targetsOf(call)) {
            boolean own = Messaging.ownerOf(target).filter(type -> callerType.filter(owner -> owner == type).isPresent()).isPresent();
            if (!own || target.getBody().isEmpty() || !seen.add(target)) {
                continue;
            }
            Optional<Participant> inner = target.getBody().get().findAll(MethodCallExpr.class).stream()
                    .filter(nested -> !Nodes.isInNestedScope(nested, target) && !isCaughtInside(nested, target))
                    .flatMap(nested -> participant(nested, target, graph, depth - 1, seen).stream())
                    .findFirst();
            if (inner.isPresent()) {
                return Optional.of(new Participant(inner.get().call(), Optional.of(call.getNameAsString())));
            }
        }
        return Optional.empty();
    }

    // Вызов стоит в try с catch внутри самого метода: до вызывающего его исключение может и не дойти
    private boolean isCaughtInside(MethodCallExpr call, MethodDeclaration method) {
        Node child = call;
        for (Node current = call.getParentNode().orElse(null); current != null && current != method;
             current = current.getParentNode().orElse(null)) {
            if (current instanceof TryStmt statement && statement.getTryBlock() == child
                    && !statement.getCatchClauses().isEmpty()) {
                return true;
            }
            child = current;
        }
        return false;
    }

    private Set<MethodDeclaration> identitySet() {
        return Collections.newSetFromMap(new IdentityHashMap<>());
    }

    private boolean joinsTransaction(MethodCallExpr call, MethodDeclaration caller, CallGraph graph) {
        if (Repositories.isRepositoryCall(call) && IMMEDIATE_REPOSITORY_METHODS.contains(call.getNameAsString())) {
            return true;
        }
        Optional<ClassOrInterfaceDeclaration> callerType = Messaging.ownerOf(caller);
        return graph.targetsOf(call).stream().anyMatch(target -> {
            // Вызов метода своего же класса идет мимо прокси: транзакция о его исключении не узнает
            boolean otherBean = Messaging.ownerOf(target).filter(type -> callerType.filter(own -> own == type).isEmpty()).isPresent();
            Optional<AnnotationExpr> transaction = isTransactional(target)
                    ? TransactionalAnnotations.findEffective(target)
                    : Optional.empty();
            return otherBean && transaction
                    .filter(annotation -> TransactionalAnnotations.findOwnBehaviorPropagation(annotation).isEmpty())
                    .filter(annotation -> !annotation.toString().contains(NO_ROLLBACK))
                    .isPresent();
        });
    }

    private boolean catchesUnchecked(CatchClause clause, ProjectClasses classes) {
        Type type = clause.getParameter().getType();
        List<Type> types = type.isUnionType() ? List.copyOf(type.asUnionType().getElements()) : List.of(type);
        return types.stream().anyMatch(caught -> isUnchecked(caught, classes));
    }

    private boolean isUnchecked(Type caught, ProjectClasses classes) {
        String name = LocalTypes.typeName(caught);
        if (BROAD.contains(name) || KNOWN_UNCHECKED.contains(name)) {
            return true;
        }
        // Свое исключение проекта: непроверяемое, если в его предках есть RuntimeException
        Set<ClassOrInterfaceDeclaration> seen = new HashSet<>();
        Optional<ClassOrInterfaceDeclaration> current = classes.find(name);
        while (current.isPresent() && seen.add(current.get())) {
            boolean runtime = current.get().getExtendedTypes().stream()
                    .map(parent -> parent.getNameAsString())
                    .anyMatch(parent -> RUNTIME_EXCEPTION.equals(parent) || KNOWN_UNCHECKED.contains(parent));
            if (runtime) {
                return true;
            }
            current = classes.parent(current.get());
        }
        return false;
    }

    private boolean marksRollback(CatchClause clause) {
        return clause.findAll(MethodCallExpr.class).stream()
                .anyMatch(call -> ROLLBACK_ONLY.equals(call.getNameAsString()));
    }

    private String describe(MethodCallExpr call) {
        return call.getScope().map(scope -> MethodCalls.receiverName(scope) + ".").orElse("") + call.getNameAsString();
    }
}
