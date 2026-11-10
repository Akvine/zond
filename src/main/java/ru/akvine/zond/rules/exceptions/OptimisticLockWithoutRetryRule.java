package ru.akvine.zond.rules.exceptions;

import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.body.VariableDeclarator;
import com.github.javaparser.ast.expr.AnnotationExpr;
import com.github.javaparser.ast.expr.Expression;
import com.github.javaparser.ast.expr.MethodCallExpr;
import com.github.javaparser.ast.stmt.ExpressionStmt;
import org.springframework.stereotype.Component;
import ru.akvine.zond.enums.ErrorLevel;
import ru.akvine.zond.enums.ErrorType;
import ru.akvine.zond.models.RuleParameter;
import ru.akvine.zond.models.SourceFile;
import ru.akvine.zond.models.Violation;
import ru.akvine.zond.rules.AbstractRule;
import ru.akvine.zond.rules.ProjectRule;
import ru.akvine.zond.rules.RuleCodes;
import ru.akvine.zond.rules.support.Annotations;
import ru.akvine.zond.rules.support.FailureHandling;
import ru.akvine.zond.rules.support.LocalTypes;
import ru.akvine.zond.rules.support.Loggers;
import ru.akvine.zond.rules.support.Nodes;
import ru.akvine.zond.rules.support.PessimisticLocks;
import ru.akvine.zond.rules.support.ProjectClasses;
import ru.akvine.zond.rules.support.Repositories;
import ru.akvine.zond.rules.support.TestClasses;
import ru.akvine.zond.rules.support.TransactionalAnnotations;

import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Сущность с @Version читают, меняют и сохраняют, а к отказу оптимистической блокировки никто не готов.
 * Версия для того и нужна, чтобы второе из двух одновременных изменений закончилось исключением; дальше
 * операцию нужно повторить на свежих данных либо ответить клиенту, что запись уже изменили.
 */
@Component
public class OptimisticLockWithoutRetryRule extends AbstractRule implements ProjectRule {
    private static final RuleParameter MAX_CALL_DEPTH = new RuleParameter(
            "max-call-depth", 3,
            "На сколько вызовов вверх от записи искать перехват исключения или повтор; 0 - только в самом методе");
    private static final String VERSION = "Version";

    private static final Set<String> SAVE_METHODS = Set.of("save", "saveAndFlush", "merge", "update");
    private static final Set<String> DELETE_METHODS = Set.of("delete", "remove");
    private static final Set<String> ENTITY_MANAGER_READS = Set.of("find", "getReference");
    private static final Pattern PERSISTENCE_CONTEXT = Pattern.compile("(?i).*(entitymanager|session)$|^em$");
    // Чтение с блокировкой строки: findByIdForUpdate, findWithLockById - изменить запись между чтением
    // и сохранением никто не успеет
    private static final Pattern LOCKING_NAME = Pattern.compile("(?i).*(forupdate|lock).*");
    // order.validate(), order.checkOwner(user): вызов без результата, который запись не меняет
    private static final Pattern CHECK_NAME = Pattern.compile("^(validate|check|assert|verify|ensure|require).*");

    /**
     * Что делают с прочитанной сущностью в методе
     *
     * @param changed первая команда, которая меняет сущность: order.setStatus(...), order.cancel()
     * @param passed  сущность отдали другому методу: mapper.update(order, request)
     * @param saved   явное сохранение: repository.save(order)
     * @param deleted удаление: repository.delete(order)
     */
    private record Usage(
            Optional<MethodCallExpr> changed, Optional<MethodCallExpr> passed, Optional<MethodCallExpr> saved,
            Optional<MethodCallExpr> deleted) {
    }

    @Override
    public String code() {
        return RuleCodes.OPTIMISTIC_LOCK_WITHOUT_RETRY_RULE_CODE;
    }

    @Override
    public String description() {
        return "Сканирует код и ищет изменение сущности с @Version без повтора операции и без перехвата"
                + " OptimisticLockException";
    }

    @Override
    public List<RuleParameter> parameters() {
        return List.of(MAX_CALL_DEPTH);
    }

    @Override
    public List<Violation> checkProject(List<SourceFile> sourceFiles) {
        FailureHandling handling = FailureHandling.of(sourceFiles);
        // Обработчик на все приложение отвечает клиенту осмысленно (409 Conflict), где бы запись ни сорвалась
        if (handling.hasGlobalHandler(FailureHandling.OPTIMISTIC_FAILURES)) {
            return List.of();
        }
        ProjectClasses classes = ProjectClasses.of(sourceFiles);
        Set<ClassOrInterfaceDeclaration> versioned = Collections.newSetFromMap(new IdentityHashMap<>());
        classes.all().stream().filter(type -> isVersioned(type, classes)).forEach(versioned::add);
        if (versioned.isEmpty()) {
            return List.of();
        }

        PessimisticLocks locks = PessimisticLocks.of(classes);
        List<Violation> violations = new ArrayList<>();
        for (SourceFile sourceFile : sourceFiles) {
            for (MethodDeclaration method : sourceFile.unit().findAll(MethodDeclaration.class)) {
                if (method.getBody().isEmpty() || TestClasses.isInside(method) || isReadOnly(method)) {
                    continue;
                }
                // Одна находка на метод: повтор нужен ему целиком, сколько бы сущностей он ни менял
                for (VariableDeclarator variable : method.findAll(VariableDeclarator.class)) {
                    Optional<Violation> found = readEntity(variable, classes, versioned, locks)
                            .flatMap(entity -> findWrite(method, variable)
                                    .filter(write -> handling.escapes(
                                            write, FailureHandling.OPTIMISTIC_FAILURES, value(MAX_CALL_DEPTH)))
                                    .map(write -> violation(sourceFile, write, message(entity, method, write))));
                    if (found.isPresent()) {
                        violations.add(found.get());
                        break;
                    }
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
        return ErrorType.EXCEPTION;
    }

    // @Version в самом классе либо в предке из проекта. О предке из библиотеки ничего не известно: без поля
    // с версией на виду правило молчит
    private boolean isVersioned(ClassOrInterfaceDeclaration type, ProjectClasses classes) {
        if (type.isInterface()) {
            return false;
        }
        Set<ClassOrInterfaceDeclaration> seen = Collections.newSetFromMap(new IdentityHashMap<>());
        Optional<ClassOrInterfaceDeclaration> current = Optional.of(type);
        while (current.isPresent() && seen.add(current.get())) {
            boolean version = current.get().getFields().stream().anyMatch(field -> Annotations.has(field, VERSION))
                    || current.get().getMethods().stream().anyMatch(method -> Annotations.has(method, VERSION));
            if (version) {
                return true;
            }
            current = classes.parent(current.get());
        }
        return false;
    }

    private boolean isReadOnly(MethodDeclaration method) {
        return TransactionalAnnotations.findEffective(method).filter(TransactionalAnnotations::isReadOnly).isPresent();
    }

    /**
     * @return сущность с версией, если переменная - запись, прочитанная из базы обычным запросом:
     * {@code Order order = orderRepository.findById(id).orElseThrow()}
     */
    private Optional<ClassOrInterfaceDeclaration> readEntity(
            VariableDeclarator variable, ProjectClasses classes, Set<ClassOrInterfaceDeclaration> versioned,
            PessimisticLocks locks) {
        if (variable.getInitializer().isEmpty()) {
            return Optional.empty();
        }
        // Тип проверяем первым: он известен без разбора вызовов, а переменных в проекте много
        Optional<ClassOrInterfaceDeclaration> entity = LocalTypes.declaredType(variable)
                .flatMap(classes::find)
                .filter(versioned::contains);
        if (entity.isEmpty()) {
            return Optional.empty();
        }
        Optional<MethodCallExpr> read = variable.getInitializer().get().findAll(MethodCallExpr.class).stream()
                .filter(call -> Repositories.isRepositoryCall(call) || isEntityManagerRead(call))
                .findFirst();
        boolean locked = read.filter(call -> LOCKING_NAME.matcher(call.getNameAsString()).matches()
                || locks.describe(call).isPresent()).isPresent();
        return read.isPresent() && !locked ? entity : Optional.empty();
    }

    private boolean isEntityManagerRead(MethodCallExpr call) {
        return ENTITY_MANAGER_READS.contains(call.getNameAsString())
                && call.getScope().filter(scope -> PERSISTENCE_CONTEXT.matcher(scope.toString()).matches()).isPresent();
    }

    /**
     * @return вызов, на котором изменение уходит в базу. Удаление - само удаление; измененная сущность - ее
     * сохранение, а в транзакции, где сохранять явно не нужно, - само изменение. Сущность, которую только
     * прочитали, записью не считается
     */
    private Optional<MethodCallExpr> findWrite(MethodDeclaration method, VariableDeclarator variable) {
        Usage usage = usageOf(method, variable);
        if (usage.deleted().isPresent()) {
            return usage.deleted();
        }
        boolean touched = usage.changed().isPresent() || usage.passed().isPresent();
        if (touched && usage.saved().isPresent()) {
            return usage.saved();
        }
        boolean transactional = !method.isPrivate() && TransactionalAnnotations.findEffective(method).isPresent();
        return transactional ? usage.changed() : Optional.empty();
    }

    private Usage usageOf(MethodDeclaration method, VariableDeclarator variable) {
        Optional<MethodCallExpr> changed = Optional.empty();
        Optional<MethodCallExpr> passed = Optional.empty();
        Optional<MethodCallExpr> saved = Optional.empty();
        Optional<MethodCallExpr> deleted = Optional.empty();
        for (MethodCallExpr call : method.findAll(MethodCallExpr.class)) {
            boolean statement = call.getParentNode().filter(parent -> parent instanceof ExpressionStmt).isPresent();
            boolean onEntity = call.getScope().filter(scope -> refersTo(scope, variable)).isPresent();
            boolean withEntity = call.getArguments().stream().anyMatch(argument -> refersTo(argument, variable));
            if (onEntity && statement && !CHECK_NAME.matcher(call.getNameAsString()).matches()
                    && changed.isEmpty()) {
                // order.setStatus(...), order.cancel(): вызов ради изменения, результат не нужен
                changed = Optional.of(call);
            } else if (withEntity && isStorage(call) && SAVE_METHODS.contains(call.getNameAsString())
                    && saved.isEmpty()) {
                saved = Optional.of(call);
            } else if (withEntity && isStorage(call) && DELETE_METHODS.contains(call.getNameAsString())
                    && deleted.isEmpty()) {
                deleted = Optional.of(call);
            } else if (withEntity && statement && !Loggers.isLogCall(call) && passed.isEmpty()) {
                passed = Optional.of(call);
            }
        }
        return new Usage(changed, passed, saved, deleted);
    }

    // orderRepository.save(order), entityManager.merge(order), session.update(order); mapper.update(order, request)
    // и list.remove(order) в базу ничего не пишут
    private boolean isStorage(MethodCallExpr call) {
        return Repositories.isRepositoryCall(call)
                || call.getScope().filter(scope -> PERSISTENCE_CONTEXT.matcher(scope.toString()).matches()).isPresent();
    }

    // Имя может быть объявлено в методе не раз: сверяем само объявление, а не имя
    private boolean refersTo(Expression expression, VariableDeclarator variable) {
        Expression value = Nodes.unwrap(expression);
        return value.isNameExpr()
                && value.asNameExpr().getNameAsString().equals(variable.getNameAsString())
                && LocalTypes.findDeclaration(value).filter(declaration -> declaration == variable).isPresent();
    }

    private String message(ClassOrInterfaceDeclaration entity, MethodDeclaration method, MethodCallExpr write) {
        boolean transactional = TransactionalAnnotations.findEffective(method).map(AnnotationExpr::getNameAsString).isPresent();
        return "Сущность '" + entity.getNameAsString() + "' с @Version меняется в методе '" + method.getNameAsString()
                + "' (" + describe(write) + "), а к отказу оптимистической блокировки никто не готов: если запись"
                + " успел изменить другой запрос, " + (transactional ? "коммит транзакции" : "сохранение")
                + " закончится OptimisticLockException (в Spring - ObjectOptimisticLockingFailureException), и"
                + " запрос завершится ошибкой 500; повторите операцию на свежих данных (@Retryable, RetryTemplate"
                + " вокруг вызова метода), перехватите исключение у вызывающего либо заведите для него"
                + " @ExceptionHandler";
    }

    private String describe(MethodCallExpr call) {
        return call.getScope().map(scope -> Nodes.unwrap(scope) + ".").orElse("") + call.getNameAsString() + "(...)";
    }
}
