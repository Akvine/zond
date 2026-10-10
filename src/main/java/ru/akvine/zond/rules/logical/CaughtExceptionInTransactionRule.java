package ru.akvine.zond.rules.logical;

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
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * Исключение из метода, который участвует в общей транзакции, поймано и проглочено. Вызванный метод, выходя
 * с исключением, уже пометил транзакцию к откату, и коммит внешнего метода закончится UnexpectedRollbackException.
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
                if (isTransactional(method)) {
                    check(sourceFile, method, graph, classes, violations);
                }
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
            SourceFile sourceFile, MethodDeclaration method, CallGraph graph, ProjectClasses classes,
            List<Violation> violations) {
        for (TryStmt statement : method.findAll(TryStmt.class)) {
            // try внутри лямбды или анонимного класса выполняется уже не в этом методе
            if (Nodes.isInNestedScope(statement, method)) {
                continue;
            }
            Optional<String> participant = statement.getTryBlock().findAll(MethodCallExpr.class).stream()
                    .filter(call -> !Nodes.isInNestedScope(call, statement))
                    .filter(call -> joinsTransaction(call, method, graph))
                    .map(this::describe)
                    .findFirst();
            if (participant.isEmpty()) {
                continue;
            }
            for (CatchClause clause : statement.getCatchClauses()) {
                if (catchesUnchecked(clause, classes) && !Messaging.rethrows(clause) && !marksRollback(clause)) {
                    violations.add(violation(sourceFile, clause,
                            "Исключение из '" + participant.get() + "' перехвачено в @Transactional-методе '"
                                    + method.getNameAsString() + "', и работа продолжается: вызванный метод"
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
