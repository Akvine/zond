package ru.akvine.zond.rules.logical;

import com.github.javaparser.ast.Node;
import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.body.VariableDeclarator;
import com.github.javaparser.ast.expr.AssignExpr;
import com.github.javaparser.ast.expr.Expression;
import com.github.javaparser.ast.expr.MethodCallExpr;
import com.github.javaparser.ast.expr.NameExpr;
import com.github.javaparser.ast.stmt.ExpressionStmt;
import com.github.javaparser.ast.stmt.ReturnStmt;
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
import ru.akvine.zond.rules.support.CallGraph;
import ru.akvine.zond.rules.support.LocalTypes;
import ru.akvine.zond.rules.support.Loggers;
import ru.akvine.zond.rules.support.MethodCalls;
import ru.akvine.zond.rules.support.Nodes;
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
import java.util.stream.Collectors;

/**
 * Сущность прочитали из репозитория и изменили в методе без транзакции, а сохранить забыли. Отслеживает изменения
 * и сам записывает их в базу только контекст транзакции; без нее сущность после чтения уже отсоединена,
 * и изменение остается в памяти.
 */
@Component
public class EntityChangeWithoutSaveRule extends AbstractRule implements ProjectRule {
    private static final RuleParameter MAX_CALL_DEPTH = new RuleParameter(
            "max-call-depth", 3,
            "На сколько вызовов вверх искать транзакцию, в которой выполняется метод; 0 - смотреть только сам метод");
    private static final String ENTITY = "Entity";
    private static final Set<String> ENTITY_MANAGER_READS = Set.of("find", "getReference");
    private static final Pattern PERSISTENCE_CONTEXT = Pattern.compile("(?i).*(entitymanager|session)$|^em$");
    // order.validate(), order.checkOwner(user): вызов без результата, который запись не меняет
    private static final Pattern CHECK_NAME = Pattern.compile("^(validate|check|assert|verify|ensure|require).*");
    // transactionTemplate.execute(status -> ...): транзакция открыта вручную
    private static final Pattern TRANSACTION_WRAPPER = Pattern.compile("(?i).*(transaction|tx).*");
    // Преобразование в ответ сущность никуда не сохраняет: mapper.toDto(order), OrderDto.from(order)
    private static final Pattern CONVERTER = Pattern.compile("(?i).*(mapper|converter|assembler|transformer|presenter)$");
    private static final Pattern CONVERSION = Pattern.compile("^(to|map|convert|from|of|as)([A-Z].*)?$");

    @Override
    public String code() {
        return RuleCodes.ENTITY_CHANGE_WITHOUT_SAVE_RULE_CODE;
    }

    @Override
    public String description() {
        return "Сканирует код и ищет изменение сущности, прочитанной из репозитория, в методе без @Transactional"
                + " и без вызова save()";
    }

    @Override
    public List<RuleParameter> parameters() {
        return List.of(MAX_CALL_DEPTH);
    }

    @Override
    public List<Violation> checkProject(List<SourceFile> sourceFiles) {
        ProjectClasses classes = ProjectClasses.of(sourceFiles);
        Set<String> entities = classes.all().stream()
                .filter(type -> Annotations.has(type, ENTITY))
                .map(ClassOrInterfaceDeclaration::getNameAsString)
                .collect(Collectors.toSet());
        if (entities.isEmpty()) {
            return List.of();
        }
        CallGraph graph = CallGraph.of(sourceFiles);
        List<Violation> violations = new ArrayList<>();
        for (SourceFile sourceFile : sourceFiles) {
            for (MethodDeclaration method : sourceFile.unit().findAll(MethodDeclaration.class)) {
                if (method.getBody().isEmpty() || TestClasses.isInside(method)) {
                    continue;
                }
                List<VariableDeclarator> loaded = method.findAll(VariableDeclarator.class).stream()
                        .filter(variable -> isLoadedEntity(variable, entities))
                        .toList();
                // Транзакцию ищем только там, где есть что проверять: это обход графа вызовов
                if (loaded.isEmpty() || runsInTransaction(method, graph, value(MAX_CALL_DEPTH), identitySet())) {
                    continue;
                }
                for (VariableDeclarator variable : loaded) {
                    firstChange(method, variable)
                            .filter(change -> !isInTransactionWrapper(change) && !mayBeSavedElsewhere(method, variable))
                            .ifPresent(change -> violations.add(violation(sourceFile, change, message(method, variable, change))));
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

    // Order order = orderRepository.findById(id).orElseThrow(): тип - сущность проекта, значение - из базы
    private boolean isLoadedEntity(VariableDeclarator variable, Set<String> entities) {
        return variable.getInitializer().isPresent()
                && LocalTypes.declaredType(variable).filter(entities::contains).isPresent()
                && variable.getInitializer().get().findAll(MethodCallExpr.class).stream()
                .anyMatch(call -> Repositories.isRepositoryCall(call) || isEntityManagerRead(call));
    }

    private boolean isEntityManagerRead(MethodCallExpr call) {
        return ENTITY_MANAGER_READS.contains(call.getNameAsString())
                && call.getScope().filter(scope -> PERSISTENCE_CONTEXT.matcher(scope.toString()).matches()).isPresent();
    }

    /**
     * Метод выполняется в транзакции: помечен сам либо его вызывает транзакционный метод. Если хотя бы один
     * путь к методу идет через транзакцию, судить о потере изменений нельзя
     */
    private boolean runsInTransaction(MethodDeclaration method, CallGraph graph, int depth, Set<MethodDeclaration> seen) {
        // На приватных методах @Transactional не действует
        if (!method.isPrivate() && TransactionalAnnotations.findEffective(method).isPresent()) {
            return true;
        }
        if (depth <= 0 || !seen.add(method)) {
            return false;
        }
        return graph.callsTo(method).stream()
                .filter(call -> call.caller() != method)
                .anyMatch(call -> isInTransactionWrapper(call.site())
                        || runsInTransaction(call.caller(), graph, depth - 1, seen));
    }

    private boolean isInTransactionWrapper(Node node) {
        Node child = node;
        for (Node current = node.getParentNode().orElse(null); current != null && !(current instanceof MethodDeclaration);
             current = current.getParentNode().orElse(null)) {
            Node argument = child;
            if (current instanceof MethodCallExpr wrapper
                    && wrapper.getArguments().stream().anyMatch(candidate -> candidate == argument)
                    && wrapper.getScope().filter(scope -> TRANSACTION_WRAPPER.matcher(scope.toString()).matches()).isPresent()) {
                return true;
            }
            child = current;
        }
        return false;
    }

    // order.setStatus(...), order.cancel(): вызов ради изменения, результат не нужен
    private Optional<MethodCallExpr> firstChange(MethodDeclaration method, VariableDeclarator variable) {
        return method.findAll(MethodCallExpr.class).stream()
                .filter(call -> call.getParentNode().filter(parent -> parent instanceof ExpressionStmt).isPresent())
                .filter(call -> call.getScope().filter(scope -> refersTo(scope, variable)).isPresent())
                .filter(call -> !CHECK_NAME.matcher(call.getNameAsString()).matches())
                .findFirst();
    }

    /**
     * Сущность ушла туда, где ее могут сохранить: передана другому методу (repository.save(order),
     * orderService.process(order)), возвращена вызывающему либо куда-то записана. Преобразование в ответ
     * и запись в лог не считаются
     */
    private boolean mayBeSavedElsewhere(MethodDeclaration method, VariableDeclarator variable) {
        for (NameExpr usage : method.findAll(NameExpr.class)) {
            if (!refersTo(usage, variable)) {
                continue;
            }
            Node parent = usage.getParentNode().orElse(null);
            if (parent instanceof ReturnStmt || parent instanceof AssignExpr) {
                return true;
            }
            if (parent instanceof MethodCallExpr call
                    && call.getArguments().stream().anyMatch(argument -> argument == usage)
                    && !Loggers.isLogCall(call) && !isConversion(call)) {
                return true;
            }
        }
        return false;
    }

    private boolean isConversion(MethodCallExpr call) {
        return CONVERSION.matcher(call.getNameAsString()).matches()
                || call.getScope().filter(scope -> CONVERTER.matcher(MethodCalls.receiverName(scope)).matches()).isPresent();
    }

    // Имя может быть объявлено в методе не раз: сверяем само объявление, а не имя
    private boolean refersTo(Expression expression, VariableDeclarator variable) {
        Expression value = Nodes.unwrap(expression);
        return value.isNameExpr()
                && value.asNameExpr().getNameAsString().equals(variable.getNameAsString())
                && LocalTypes.findDeclaration(value).filter(declaration -> declaration == variable).isPresent();
    }

    private Set<MethodDeclaration> identitySet() {
        return Collections.newSetFromMap(new IdentityHashMap<>());
    }

    private String message(MethodDeclaration method, VariableDeclarator variable, MethodCallExpr change) {
        String name = variable.getNameAsString();
        return "Сущность '" + name + "' прочитана из базы и изменена в методе '" + method.getNameAsString()
                + "' без транзакции ('" + name + "." + change.getNameAsString() + "(...)'), а save() не вызван:"
                + " изменения отслеживает и записывает в базу только контекст транзакции, вне ее сущность уже"
                + " отсоединена, и изменение останется в памяти; сохраните ее явно - repository.save(" + name
                + ") - либо пометьте метод @Transactional";
    }
}
