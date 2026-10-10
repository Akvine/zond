package ru.akvine.zond.rules.concurrency;

import com.github.javaparser.ast.Node;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.expr.AnnotationExpr;
import com.github.javaparser.ast.expr.Expression;
import com.github.javaparser.ast.expr.MethodCallExpr;
import com.github.javaparser.ast.stmt.SynchronizedStmt;
import org.springframework.stereotype.Component;
import ru.akvine.zond.enums.ErrorLevel;
import ru.akvine.zond.enums.ErrorType;
import ru.akvine.zond.models.SourceFile;
import ru.akvine.zond.models.Violation;
import ru.akvine.zond.rules.AbstractRule;
import ru.akvine.zond.rules.RuleCodes;
import ru.akvine.zond.rules.support.Jmix;
import ru.akvine.zond.rules.support.MethodCalls;
import ru.akvine.zond.rules.support.Nodes;
import ru.akvine.zond.rules.support.Repositories;
import ru.akvine.zond.rules.support.TransactionalAnnotations;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Блокировка, взятая и отпущенная внутри @Transactional-метода. Транзакцию коммитит прокси уже после выхода
 * из метода, то есть после unlock: следующий поток получает блокировку и читает из базы данные, какими они
 * были до изменения. Блокировка есть, а защиты нет.
 */
@Component
public class LockReleasedBeforeCommitRule extends AbstractRule {
    private static final Set<String> LOCK_METHODS = Set.of("lock", "tryLock", "lockInterruptibly");
    private static final String UNLOCK = "unlock";
    // Режимы, при которых транзакции у метода нет вовсе
    private static final Set<String> NO_TRANSACTION = Set.of("NOT_SUPPORTED", "NEVER");
    // Код, который выполняется уже после коммита: там блокировку отпускать и нужно
    private static final Pattern AFTER_COMMIT = Pattern.compile("(?i).*after(commit|completion).*|registerSynchronization");

    private static final Pattern ENTITY_MANAGER = Pattern.compile("(?i).*entitymanager$|^em$");
    private static final Pattern JDBC_TEMPLATE = Pattern.compile("(?i).*jdbc(template|operations)$");
    private static final String FIX = "; берите блокировку снаружи транзакции - в методе без @Transactional, который"
            + " вызывает транзакционный метод другого бина, - либо отпускайте ее после коммита"
            + " (TransactionSynchronization.afterCompletion)";

    @Override
    public String code() {
        return RuleCodes.LOCK_RELEASED_BEFORE_COMMIT_RULE_CODE;
    }

    @Override
    public String description() {
        return "Сканирует код и ищет блокировку, которая берется и отпускается внутри @Transactional-метода, до коммита";
    }

    @Override
    public List<Violation> check(SourceFile sourceFile) {
        List<Violation> violations = new ArrayList<>();
        for (MethodDeclaration method : sourceFile.unit().findAll(MethodDeclaration.class)) {
            if (writesInTransaction(method)) {
                check(sourceFile, method, violations);
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
        return ErrorType.CONCURRENCY;
    }

    private void check(SourceFile sourceFile, MethodDeclaration method, List<Violation> violations) {
        if (method.isSynchronized()) {
            violations.add(violation(sourceFile, method.getName(),
                    "Метод '" + method.getNameAsString() + "' одновременно synchronized и @Transactional: монитор"
                            + " отпускается при выходе из метода, а транзакцию прокси коммитит уже после этого -"
                            + " следующий поток войдет в метод и прочитает из базы данные до изменения" + FIX));
            return;
        }
        checkBlocks(sourceFile, method, violations);
        checkLocks(sourceFile, method, violations);
    }

    // Блок synchronized внутри транзакции: считаем только тот, что охраняет работу с базой
    private void checkBlocks(SourceFile sourceFile, MethodDeclaration method, List<Violation> violations) {
        String name = method.getNameAsString();
        for (SynchronizedStmt block : method.findAll(SynchronizedStmt.class)) {
            if (!Nodes.isInNestedScope(block, method) && accessesDatabase(block)) {
                violations.add(violation(sourceFile, block,
                        "Блок synchronized с обращением к базе внутри @Transactional-метода '" + name + "': блок"
                                + " заканчивается раньше, чем транзакция закоммитится, - следующий поток войдет"
                                + " в него и прочитает из базы данные до изменения" + FIX));
            }
        }
    }

    private void checkLocks(SourceFile sourceFile, MethodDeclaration method, List<Violation> violations) {
        String name = method.getNameAsString();
        Set<String> reported = new LinkedHashSet<>();
        for (MethodCallExpr lock : method.findAll(MethodCallExpr.class)) {
            Optional<String> owner = lock.getScope().map(Expression::toString);
            boolean taken = LOCK_METHODS.contains(lock.getNameAsString()) && owner.isPresent()
                    && !Nodes.isInNestedScope(lock, method);
            // Пара lock - unlock на одном объекте: у блокировки записи в базе (entityManager.lock) unlock нет
            if (taken && isReleasedBeforeCommit(method, owner.get()) && reported.add(owner.get())) {
                violations.add(violation(sourceFile, lock.getName(),
                        "Блокировка '" + owner.get() + "' берется и отпускается внутри @Transactional-метода '" + name
                                + "': unlock выполнится раньше, чем транзакция закоммитится (коммит делает прокси"
                                + " после выхода из метода), - следующий поток получит блокировку и прочитает"
                                + " из базы данные до изменения" + FIX));
            }
        }
    }

    // Транзакция только на чтение ничего не записывает: защищать блокировкой в ней нечего
    private boolean writesInTransaction(MethodDeclaration method) {
        if (method.isPrivate() || method.getBody().isEmpty()) {
            return false;
        }
        Optional<AnnotationExpr> transaction = TransactionalAnnotations.findEffective(method);
        return transaction.isPresent()
                && !TransactionalAnnotations.isReadOnly(transaction.get())
                && TransactionalAnnotations.findOwnBehaviorPropagation(transaction.get())
                .filter(NO_TRANSACTION::contains)
                .isEmpty();
    }

    private boolean isReleasedBeforeCommit(MethodDeclaration method, String owner) {
        return method.findAll(MethodCallExpr.class).stream()
                .filter(call -> UNLOCK.equals(call.getNameAsString()))
                .filter(call -> call.getScope().filter(scope -> scope.toString().equals(owner)).isPresent())
                .anyMatch(call -> !runsAfterCommit(call, method));
    }

    private boolean runsAfterCommit(MethodCallExpr unlock, MethodDeclaration method) {
        Node current = unlock.getParentNode().orElse(null);
        while (current != null && current != method) {
            if (current instanceof MethodDeclaration nested && AFTER_COMMIT.matcher(nested.getNameAsString()).matches()) {
                return true;
            }
            if (current instanceof MethodCallExpr outer && AFTER_COMMIT.matcher(outer.getNameAsString()).matches()) {
                return true;
            }
            current = current.getParentNode().orElse(null);
        }
        return false;
    }

    private boolean accessesDatabase(Node node) {
        return node.findAll(MethodCallExpr.class).stream().anyMatch(call -> {
            Optional<String> receiver = call.getScope().map(MethodCalls::receiverName);
            return Repositories.isRepositoryCall(call)
                    || receiver.filter(name -> ENTITY_MANAGER.matcher(name).matches()).isPresent()
                    || receiver.filter(name -> JDBC_TEMPLATE.matcher(name).matches()).isPresent()
                    || call.getScope().filter(Jmix::isDataManager).isPresent();
        });
    }
}
