package ru.akvine.zond.rules;

import com.github.javaparser.ast.Node;
import com.github.javaparser.ast.body.FieldDeclaration;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.body.Parameter;
import com.github.javaparser.ast.body.TypeDeclaration;
import com.github.javaparser.ast.body.VariableDeclarator;
import com.github.javaparser.ast.expr.AssignExpr;
import com.github.javaparser.ast.expr.Expression;
import com.github.javaparser.ast.stmt.ReturnStmt;
import com.github.javaparser.ast.type.Type;
import lombok.experimental.UtilityClass;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Типы без полноценного разрешения: только то, что видно из объявлений в этом же файле
 * (локальные переменные, параметры, поля) и из литералов.
 */
@UtilityClass
class LocalTypes {

    /**
     * Выражение, для которого известен тип места назначения: инициализатор, правая часть присваивания, return
     */
    record TypedExpression(String targetType, Expression expression) {
    }

    /**
     * @return простое имя типа выражения (String, int, BigDecimal) либо пусто, если по файлу его не определить
     */
    Optional<String> typeOf(Expression expression) {
        Expression value = Nodes.unwrap(expression);
        if (value.isStringLiteralExpr() || value.isTextBlockLiteralExpr()) {
            return Optional.of("String");
        }
        if (value.isIntegerLiteralExpr()) {
            return Optional.of("int");
        }
        if (value.isLongLiteralExpr()) {
            return Optional.of("long");
        }
        if (value.isDoubleLiteralExpr()) {
            boolean isFloat = value.asDoubleLiteralExpr().getValue().toLowerCase().endsWith("f");
            return Optional.of(isFloat ? "float" : "double");
        }
        if (value.isObjectCreationExpr()) {
            return Optional.of(value.asObjectCreationExpr().getType().getNameAsString());
        }
        if (value.isCastExpr()) {
            return Optional.of(typeName(value.asCastExpr().getType()));
        }
        if (value.isNameExpr()) {
            return findDeclaration(value, value.asNameExpr().getNameAsString()).flatMap(LocalTypes::declaredType);
        }
        if (value.isFieldAccessExpr() && value.asFieldAccessExpr().getScope().isThisExpr()) {
            return findField(value, value.asFieldAccessExpr().getNameAsString()).flatMap(LocalTypes::declaredType);
        }
        return Optional.empty();
    }

    /**
     * @return объявление переменной с таким именем: локальная переменная или параметр текущего метода, иначе поле
     */
    Optional<Node> findDeclaration(Node usage, String name) {
        Optional<Node> callable = Nodes.enclosingCallable(usage);
        if (callable.isPresent()) {
            List<Node> candidates = new ArrayList<>();
            callable.get().findAll(Parameter.class).stream()
                    .filter(parameter -> parameter.getNameAsString().equals(name))
                    .forEach(candidates::add);
            callable.get().findAll(VariableDeclarator.class).stream()
                    .filter(variable -> variable.getNameAsString().equals(name))
                    .forEach(candidates::add);

            if (!candidates.isEmpty()) {
                // Одно имя в разных блоках метода с разными типами: какой из них используется, не выясняем
                Set<Optional<String>> types = candidates.stream()
                        .map(LocalTypes::declaredType)
                        .collect(Collectors.toSet());
                return types.size() == 1 ? Optional.of(candidates.get(0)) : Optional.empty();
            }
        }
        return findField(usage, name).map(variable -> variable);
    }

    /**
     * @return поле с таким именем в классе, где находится узел, либо во внешних классах
     */
    Optional<VariableDeclarator> findField(Node from, String name) {
        Node current = from.getParentNode().orElse(null);
        while (current != null) {
            if (current instanceof TypeDeclaration<?> type) {
                for (FieldDeclaration field : type.getFields()) {
                    for (VariableDeclarator variable : field.getVariables()) {
                        if (variable.getNameAsString().equals(name)) {
                            return Optional.of(variable);
                        }
                    }
                }
            }
            current = current.getParentNode().orElse(null);
        }
        return Optional.empty();
    }

    Optional<String> declaredType(Node declaration) {
        if (declaration instanceof Parameter parameter) {
            return parameter.getType().isUnknownType()
                    ? Optional.empty()
                    : Optional.of(typeName(parameter.getType()));
        }
        if (declaration instanceof VariableDeclarator variable) {
            // var x = new Foo() -> Foo
            if (variable.getType().isVarType()) {
                return variable.getInitializer()
                        .filter(initializer -> !initializer.isNameExpr())
                        .flatMap(LocalTypes::typeOf);
            }
            return Optional.of(typeName(variable.getType()));
        }
        return Optional.empty();
    }

    // java.util.Map<String, String> -> Map
    String typeName(Type type) {
        return type.isClassOrInterfaceType() ? type.asClassOrInterfaceType().getNameAsString() : type.asString();
    }

    List<TypedExpression> findTypedExpressions(Node root) {
        List<TypedExpression> expressions = new ArrayList<>();

        for (VariableDeclarator variable : root.findAll(VariableDeclarator.class)) {
            variable.getInitializer().ifPresent(initializer -> declaredType(variable)
                    .ifPresent(type -> expressions.add(new TypedExpression(type, initializer))));
        }

        for (AssignExpr assign : root.findAll(AssignExpr.class)) {
            if (assign.getOperator() == AssignExpr.Operator.ASSIGN) {
                typeOf(assign.getTarget())
                        .ifPresent(type -> expressions.add(new TypedExpression(type, assign.getValue())));
            }
        }

        for (ReturnStmt returnStmt : root.findAll(ReturnStmt.class)) {
            Optional<Node> callable = Nodes.enclosingCallable(returnStmt);
            if (returnStmt.getExpression().isPresent()
                    && callable.isPresent()
                    && callable.get() instanceof MethodDeclaration method
                    && !Nodes.isInNestedScope(returnStmt, method)) {
                expressions.add(new TypedExpression(typeName(method.getType()), returnStmt.getExpression().get()));
            }
        }
        return expressions;
    }
}
