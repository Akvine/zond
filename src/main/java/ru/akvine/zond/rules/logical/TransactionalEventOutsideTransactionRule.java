package ru.akvine.zond.rules.logical;

import com.github.javaparser.ast.Node;
import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.expr.AnnotationExpr;
import com.github.javaparser.ast.expr.ClassExpr;
import com.github.javaparser.ast.expr.Expression;
import com.github.javaparser.ast.expr.MethodCallExpr;
import org.springframework.stereotype.Component;
import ru.akvine.zond.config.RuleSettings;
import ru.akvine.zond.enums.ErrorLevel;
import ru.akvine.zond.enums.ErrorType;
import ru.akvine.zond.models.RuleParameter;
import ru.akvine.zond.models.SourceFile;
import ru.akvine.zond.models.Violation;
import ru.akvine.zond.rules.AbstractRule;
import ru.akvine.zond.rules.ProjectRule;
import ru.akvine.zond.rules.RuleCodes;
import ru.akvine.zond.rules.support.Annotations;
import ru.akvine.zond.rules.support.CallGraph;
import ru.akvine.zond.rules.support.LocalTypes;
import ru.akvine.zond.rules.support.MethodCalls;
import ru.akvine.zond.rules.support.Nodes;
import ru.akvine.zond.rules.support.ProjectClasses;
import ru.akvine.zond.rules.support.Queries;
import ru.akvine.zond.rules.support.TransactionalAnnotations;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Событие для @TransactionalEventListener опубликовано там, где транзакции нет. Такой слушатель ждет
 * коммита, а без транзакции ждать нечего - Spring молча отбрасывает событие, и слушатель не вызывается.
 */
@Component
public class TransactionalEventOutsideTransactionRule extends AbstractRule implements ProjectRule {
    private static final RuleParameter MAX_CALL_DEPTH = new RuleParameter(
            RuleSettings.MAX_CALL_DEPTH, 3,
            "На сколько вызовов вверх от места публикации искать транзакционный метод; 0 - только в самом методе");
    private static final String LISTENER = "TransactionalEventListener";
    private static final String FALLBACK = "fallbackExecution";
    private static final String TRUE = "true";
    private static final String PUBLISH = "publishEvent";
    // Слушатель любых событий: какое именно ему предназначено, по типу не узнать
    private static final Set<String> ANY_EVENT = Set.of("Object", "ApplicationEvent", "PayloadApplicationEvent");
    private static final Set<String> NO_TRANSACTION = Set.of("NOT_SUPPORTED", "NEVER");
    // transactionTemplate.execute(status -> ...): транзакция открыта вручную
    private static final Set<String> TEMPLATE_METHODS = Set.of("execute", "executeWithoutResult");
    private static final Pattern TEMPLATE = Pattern.compile("(?i).*transaction.*");

    /**
     * @param event  тип события, которое слушатель принимает
     * @param method сам слушатель
     */
    private record Listener(String event, MethodDeclaration method) {
    }

    @Override
    public String code() {
        return RuleCodes.TRANSACTIONAL_EVENT_OUTSIDE_TRANSACTION_RULE_CODE;
    }

    @Override
    public String description() {
        return "Сканирует код и ищет события для @TransactionalEventListener, которые публикуются вне транзакции";
    }

    @Override
    public List<RuleParameter> parameters() {
        return List.of(MAX_CALL_DEPTH);
    }

    @Override
    public List<Violation> checkProject(List<SourceFile> sourceFiles) {
        List<Listener> listeners = listenersOf(sourceFiles);
        List<Violation> violations = new ArrayList<>();
        if (listeners.isEmpty()) {
            return violations;
        }
        ProjectClasses classes = ProjectClasses.of(sourceFiles);
        CallGraph graph = CallGraph.of(sourceFiles);
        for (SourceFile sourceFile : sourceFiles) {
            for (MethodCallExpr publish : sourceFile.unit().findAll(MethodCallExpr.class)) {
                if (!PUBLISH.equals(publish.getNameAsString()) || publish.getArguments().size() != 1) {
                    continue;
                }
                Optional<Listener> listener = eventType(publish.getArgument(0))
                        .flatMap(event -> listeners.stream().filter(found -> accepts(found, event, classes)).findFirst());
                if (listener.isPresent() && !isInTransaction(publish, graph)) {
                    violations.add(violation(sourceFile, publish.getName(), message(publish, listener.get())));
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

    private String message(MethodCallExpr publish, Listener listener) {
        String owner = listener.method().findAncestor(ClassOrInterfaceDeclaration.class)
                .map(type -> type.getNameAsString() + ".")
                .orElse("");
        return "Событие " + listener.event() + " публикуется вне транзакции ('" + publish + "'), а слушатель '"
                + owner + listener.method().getNameAsString() + "' помечен @TransactionalEventListener: без активной"
                + " транзакции такой слушатель не вызывается вовсе, и ошибки при этом нет; публикуйте событие"
                + " внутри @Transactional-метода либо задайте слушателю fallbackExecution = true";
    }

    // Слушатель с fallbackExecution = true вызывается и без транзакции
    private List<Listener> listenersOf(List<SourceFile> sourceFiles) {
        List<Listener> listeners = new ArrayList<>();
        for (SourceFile sourceFile : sourceFiles) {
            for (MethodDeclaration method : sourceFile.unit().findAll(MethodDeclaration.class)) {
                Optional<AnnotationExpr> annotation = Annotations.find(method, LISTENER)
                        .filter(found -> Queries.member(found, FALLBACK).filter(value -> TRUE.equals(value.toString())).isEmpty());
                annotation.ifPresent(found -> eventsOf(method, found).stream()
                        .filter(event -> !ANY_EVENT.contains(event))
                        .forEach(event -> listeners.add(new Listener(event, method))));
            }
        }
        return listeners;
    }

    // Тип события - параметр слушателя; у слушателя без параметров он назван в аннотации
    private List<String> eventsOf(MethodDeclaration method, AnnotationExpr annotation) {
        if (!method.getParameters().isEmpty()) {
            return List.of(LocalTypes.typeName(method.getParameter(0).getType()));
        }
        return annotation.findAll(ClassExpr.class).stream().map(type -> LocalTypes.typeName(type.getType())).toList();
    }

    // publishEvent(new OrderPaid(order)), publishEvent(event)
    private Optional<String> eventType(Expression argument) {
        Expression value = Nodes.unwrap(argument);
        return value.isObjectCreationExpr()
                ? Optional.of(value.asObjectCreationExpr().getType().getNameAsString())
                : LocalTypes.typeOf(value);
    }

    // Слушатель принимает событие своего типа и его наследников
    private boolean accepts(Listener listener, String event, ProjectClasses classes) {
        Set<ClassOrInterfaceDeclaration> seen = new HashSet<>();
        Optional<ClassOrInterfaceDeclaration> current = classes.find(event);
        if (listener.event().equals(event)) {
            return true;
        }
        while (current.isPresent() && seen.add(current.get())) {
            boolean inherits = current.get().getExtendedTypes().stream()
                    .anyMatch(parent -> parent.getNameAsString().equals(listener.event()))
                    || current.get().getImplementedTypes().stream()
                    .anyMatch(parent -> parent.getNameAsString().equals(listener.event()));
            if (inherits) {
                return true;
            }
            current = classes.parent(current.get());
        }
        return false;
    }

    // Транзакция есть, если ее открыл сам метод, один из тех, кто его вызывает, либо она открыта вручную
    private boolean isInTransaction(MethodCallExpr publish, CallGraph graph) {
        boolean byTemplate = publish.findAncestor(MethodCallExpr.class, outer ->
                TEMPLATE_METHODS.contains(outer.getNameAsString())
                        && outer.getScope().filter(scope -> TEMPLATE.matcher(MethodCalls.receiverName(scope)).matches()).isPresent())
                .isPresent();
        Optional<Node> callable = Nodes.enclosingCallable(publish);
        // Вне метода (инициализатор поля) о транзакции судить не по чему
        if (byTemplate || callable.isEmpty() || !(callable.get() instanceof MethodDeclaration method)) {
            return true;
        }
        Set<MethodDeclaration> reached = Collections.newSetFromMap(new IdentityHashMap<>());
        reached.add(method);
        List<MethodDeclaration> level = List.of(method);
        for (int step = 0; step <= value(MAX_CALL_DEPTH) && !level.isEmpty(); step++) {
            List<MethodDeclaration> callers = new ArrayList<>();
            for (MethodDeclaration current : level) {
                if (opensTransaction(current)) {
                    return true;
                }
                graph.callsTo(current).stream()
                        .map(CallGraph.Call::caller)
                        .filter(reached::add)
                        .forEach(callers::add);
            }
            level = callers;
        }
        return false;
    }

    // На закрытом методе @Transactional не действует; NOT_SUPPORTED и NEVER транзакцию не открывают
    private boolean opensTransaction(MethodDeclaration method) {
        return !method.isPrivate() && TransactionalAnnotations.findEffective(method)
                .filter(annotation -> TransactionalAnnotations.findOwnBehaviorPropagation(annotation)
                        .filter(NO_TRANSACTION::contains)
                        .isEmpty())
                .isPresent();
    }
}
