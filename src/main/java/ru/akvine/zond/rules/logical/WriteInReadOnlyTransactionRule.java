package ru.akvine.zond.rules.logical;

import com.github.javaparser.ast.Node;
import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration;
import com.github.javaparser.ast.body.FieldDeclaration;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.body.Parameter;
import com.github.javaparser.ast.body.VariableDeclarator;
import com.github.javaparser.ast.expr.Expression;
import com.github.javaparser.ast.expr.MethodCallExpr;
import com.github.javaparser.ast.nodeTypes.NodeWithSimpleName;
import com.github.javaparser.ast.type.Type;
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
import ru.akvine.zond.rules.support.CallChains;
import ru.akvine.zond.rules.support.CallGraph;
import ru.akvine.zond.rules.support.TransactionalAnnotations;
import ru.akvine.zond.rules.support.Types;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;

@Component
public class WriteInReadOnlyTransactionRule extends AbstractRule implements ProjectRule {
    private static final RuleParameter MAX_CALL_DEPTH = new RuleParameter(
            "max-call-depth", 3, "На сколько вызовов вглубь от readOnly-метода искать запись; 0 - не искать");
    private static final String ASYNC = "Async";

    // save, saveAll, deleteById, updateStatus, insert, removeAll, persist, merge и т.п.
    private static final List<String> WRITE_METHOD_PREFIXES =
            List.of("save", "delete", "update", "insert", "remove", "persist", "merge");

    // У коллекций и строк тоже есть remove / merge / insert / delete, но к БД они отношения не имеют
    private static final Set<String> NOT_STORAGE_TYPES = Set.of("String", "StringBuilder", "StringBuffer");
    private static final List<String> NOT_STORAGE_TYPE_SUFFIXES =
            List.of("List", "Set", "Map", "Queue", "Deque", "Collection");

    @Override
    public List<RuleParameter> parameters() {
        return List.of(MAX_CALL_DEPTH);
    }

    @Override
    public String code() {
        return RuleCodes.WRITE_IN_READ_ONLY_TRANSACTION_RULE_CODE;
    }

    @Override
    public String description() {
        return "Сканирует код и ищет вызовы save / delete / update в методах с @Transactional(readOnly = true)"
                + " и в методах, которые они вызывают";
    }

    @Override
    public List<Violation> checkProject(List<SourceFile> sourceFiles) {
        CallGraph graph = CallGraph.of(sourceFiles);
        List<Violation> violations = new ArrayList<>();
        for (SourceFile sourceFile : sourceFiles) {
            for (MethodDeclaration method : sourceFile.unit().findAll(MethodDeclaration.class)) {
                if (isReadOnlyEntry(method)) {
                    check(sourceFile, method, graph, violations);
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

    private void check(SourceFile sourceFile, MethodDeclaration method, CallGraph graph, List<Violation> violations) {
        Set<Integer> reportedLines = new HashSet<>();
        for (MethodCallExpr call : method.findAll(MethodCallExpr.class)) {
            findWrite(call)
                    .filter(write -> reportedLines.add(line(call)))
                    .ifPresent(write -> violations.add(violation(sourceFile, call,
                            "Вызов '" + write + "' в методе '" + method.getNameAsString() + "'"
                                    + " с @Transactional(readOnly = true):"
                                    + " изменения могут не сохраниться или будут отклонены базой данных")));
        }

        // Запись спрятана в вызванном методе: он выполняется в той же readOnly-транзакции
        for (CallGraph.Call call : graph.callsFrom(method)) {
            if (call.target() == method) {
                continue;
            }
            CallChains.find(graph, call.target(), value(MAX_CALL_DEPTH), this::hasOwnTransaction, this::findWrite)
                    .filter(found -> reportedLines.add(line(call.site())))
                    .ifPresent(found -> violations.add(violation(sourceFile, call.site(),
                            "Вызов '" + call.site().getNameAsString() + "(...)' в методе '"
                                    + method.getNameAsString() + "' с @Transactional(readOnly = true) приводит"
                                    + " к записи '" + found.operation() + "' (через вызов " + found.chain() + "):"
                                    + " изменения могут не сохраниться или будут отклонены базой данных")
                            .withConfidence(found.confidence(graph.isExact(call.site())))));
        }
    }

    // Приватные не учитываем: на них @Transactional не работает в принципе, это ловит отдельное правило
    private boolean isReadOnlyEntry(MethodDeclaration method) {
        return !method.isPrivate()
                && method.getBody().isPresent()
                && isClassMethod(method)
                && TransactionalAnnotations.findEffective(method)
                .filter(TransactionalAnnotations::isReadOnly)
                .isPresent();
    }

    private boolean isClassMethod(MethodDeclaration method) {
        return method.getParentNode()
                .filter(parent -> parent instanceof ClassOrInterfaceDeclaration type && !type.isInterface())
                .isPresent();
    }

    // Метод с REQUIRES_NEW открывает свою транзакцию, @Async уходит в другой поток - readOnly вызывающего
    // на них не действует; о readOnly-методе правило сообщит отдельно, когда дойдет до него самого.
    // Обычный @Transactional присоединяется к уже открытой readOnly-транзакции и от записи не спасает
    private boolean hasOwnTransaction(MethodDeclaration method) {
        return Annotations.has(method, ASYNC)
                || isReadOnlyEntry(method)
                || TransactionalAnnotations.find(method)
                .flatMap(TransactionalAnnotations::findOwnBehaviorPropagation)
                .isPresent();
    }

    /**
     * Записью считаем только вызов на поле класса (репозиторий, DAO, другой сервис); поле с типом из JDK
     * (коллекция, StringBuilder, AtomicLong) хранилищем не является.
     *
     * @return описание записи вида repository.save, если узел - такой вызов
     */
    private Optional<String> findWrite(Node node) {
        if (!(node instanceof MethodCallExpr call) || !isWriteMethod(call.getNameAsString())) {
            return Optional.empty();
        }

        Optional<ClassOrInterfaceDeclaration> type = enclosing(call, ClassOrInterfaceDeclaration.class);
        Optional<MethodDeclaration> method = enclosing(call, MethodDeclaration.class);
        if (type.isEmpty() || method.isEmpty()) {
            return Optional.empty();
        }

        Map<String, String> fieldTypes = findFieldTypes(type.get());
        Set<String> localNames = findLocalNames(method.get());
        return call.getScope()
                .filter(scope -> !Types.isJdkType(scope))
                .flatMap(scope -> fieldName(scope, localNames))
                .filter(fieldTypes::containsKey)
                .filter(field -> isStorageType(fieldTypes.get(field)))
                .map(field -> field + "." + call.getNameAsString());
    }

    // save, saveAll - да; saved, updatedAt - нет
    private boolean isWriteMethod(String methodName) {
        return WRITE_METHOD_PREFIXES.stream().anyMatch(prefix -> methodName.startsWith(prefix)
                && (methodName.length() == prefix.length() || Character.isUpperCase(methodName.charAt(prefix.length()))));
    }

    // this.x - всегда поле; просто x - поле, только если в методе нет одноименной переменной или параметра
    private Optional<String> fieldName(Expression scope, Set<String> localNames) {
        if (scope.isFieldAccessExpr() && scope.asFieldAccessExpr().getScope().isThisExpr()) {
            return Optional.of(scope.asFieldAccessExpr().getNameAsString());
        }
        if (scope.isNameExpr() && !localNames.contains(scope.asNameExpr().getNameAsString())) {
            return Optional.of(scope.asNameExpr().getNameAsString());
        }
        return Optional.empty();
    }

    private boolean isStorageType(String typeName) {
        return !NOT_STORAGE_TYPES.contains(typeName)
                && NOT_STORAGE_TYPE_SUFFIXES.stream().noneMatch(typeName::endsWith);
    }

    private Map<String, String> findFieldTypes(ClassOrInterfaceDeclaration type) {
        Map<String, String> fieldTypes = new HashMap<>();
        for (FieldDeclaration field : type.getFields()) {
            for (VariableDeclarator variable : field.getVariables()) {
                fieldTypes.put(variable.getNameAsString(), simpleName(variable.getType()));
            }
        }
        return fieldTypes;
    }

    // java.util.Map<String, String> -> Map
    private String simpleName(Type type) {
        return type.isClassOrInterfaceType() ? type.asClassOrInterfaceType().getNameAsString() : type.asString();
    }

    private Set<String> findLocalNames(MethodDeclaration method) {
        Stream<String> variables = method.findAll(VariableDeclarator.class).stream()
                .map(NodeWithSimpleName::getNameAsString);
        Stream<String> parameters = method.findAll(Parameter.class).stream()
                .map(NodeWithSimpleName::getNameAsString);
        return Stream.concat(variables, parameters).collect(Collectors.toSet());
    }

    private <T extends Node> Optional<T> enclosing(Node node, Class<T> type) {
        Node current = node.getParentNode().orElse(null);
        while (current != null) {
            if (type.isInstance(current)) {
                return Optional.of(type.cast(current));
            }
            current = current.getParentNode().orElse(null);
        }
        return Optional.empty();
    }

    private int line(Node node) {
        return node.getBegin().map(position -> position.line).orElse(0);
    }
}
