package ru.akvine.zond.rules;

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
import ru.akvine.zond.models.SourceFile;
import ru.akvine.zond.models.Violation;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;

@Component
public class CheckWriteInReadOnlyTransactionRule implements Rule {
    // save, saveAll, deleteById, updateStatus, insert, removeAll, persist, merge и т.п.
    private static final List<String> WRITE_METHOD_PREFIXES =
            List.of("save", "delete", "update", "insert", "remove", "persist", "merge");

    // У коллекций и строк тоже есть remove / merge / insert / delete, но к БД они отношения не имеют
    private static final Set<String> NOT_STORAGE_TYPES = Set.of("String", "StringBuilder", "StringBuffer");
    private static final List<String> NOT_STORAGE_TYPE_SUFFIXES =
            List.of("List", "Set", "Map", "Queue", "Deque", "Collection");

    @Override
    public String name() {
        return getClass().getSimpleName();
    }

    @Override
    public String code() {
        return RuleCodes.CHECK_WRITE_IN_READ_ONLY_TRANSACTION_RULE_CODE;
    }

    @Override
    public String description() {
        return "Сканирует код и ищет вызовы save / delete / update в методах с @Transactional(readOnly = true)";
    }

    @Override
    public boolean enabled() {
        return true;
    }

    @Override
    public List<Violation> check(SourceFile sourceFile) {
        List<Violation> violations = new ArrayList<>();
        for (ClassOrInterfaceDeclaration type : sourceFile.unit().findAll(ClassOrInterfaceDeclaration.class)) {
            if (type.isInterface()) {
                continue;
            }

            Map<String, String> fieldTypes = findFieldTypes(type);
            for (MethodDeclaration method : type.getMethods()) {
                // Приватные не учитываем: на них @Transactional не работает в принципе, это ловит отдельное правило
                if (method.isPrivate() || !isReadOnly(method)) {
                    continue;
                }

                Set<String> localNames = findLocalNames(method);
                for (MethodCallExpr call : method.findAll(MethodCallExpr.class)) {
                    if (!isWriteMethod(call.getNameAsString())) {
                        continue;
                    }
                    findStorageField(call, fieldTypes, localNames).ifPresent(field -> violations.add(new Violation(
                            errorLevel(),
                            errorType(),
                            code(),
                            name(),
                            sourceFile.path(),
                            call.getBegin().map(position -> position.line).orElse(0),
                            "Вызов '" + field + "." + call.getNameAsString() + "' в методе '"
                                    + method.getNameAsString() + "' с @Transactional(readOnly = true):"
                                    + " изменения могут не сохраниться или будут отклонены базой данных")));
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

    private boolean isReadOnly(MethodDeclaration method) {
        return TransactionalAnnotations.findEffective(method)
                .filter(TransactionalAnnotations::isReadOnly)
                .isPresent();
    }

    // save, saveAll - да; saved, updatedAt - нет
    private boolean isWriteMethod(String methodName) {
        return WRITE_METHOD_PREFIXES.stream().anyMatch(prefix -> methodName.startsWith(prefix)
                && (methodName.length() == prefix.length() || Character.isUpperCase(methodName.charAt(prefix.length()))));
    }

    /**
     * Типы не разрешаем, поэтому записью считаем только вызов на поле класса (репозиторий, DAO, другой сервис).
     *
     * @return имя поля, на котором вызван метод
     */
    private Optional<String> findStorageField(
            MethodCallExpr call, Map<String, String> fieldTypes, Set<String> localNames) {
        return call.getScope()
                .flatMap(scope -> fieldName(scope, localNames))
                .filter(fieldTypes::containsKey)
                .filter(field -> isStorageType(fieldTypes.get(field)));
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
}
