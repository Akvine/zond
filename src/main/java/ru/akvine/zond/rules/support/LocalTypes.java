package ru.akvine.zond.rules.support;

import com.github.javaparser.ast.Node;
import com.github.javaparser.ast.body.FieldDeclaration;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.body.Parameter;
import com.github.javaparser.ast.body.TypeDeclaration;
import com.github.javaparser.ast.body.VariableDeclarator;
import com.github.javaparser.ast.expr.AssignExpr;
import com.github.javaparser.ast.expr.Expression;
import com.github.javaparser.ast.stmt.ExpressionStmt;
import com.github.javaparser.ast.stmt.ReturnStmt;
import com.github.javaparser.ast.type.Type;
import lombok.experimental.UtilityClass;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.function.Supplier;
import java.util.stream.Collectors;

/**
 * Типы выражений. Сначала быстрый путь - то, что видно из объявлений в этом же файле (локальные переменные,
 * параметры, поля) и из литералов; если так тип не определить, спрашиваем решатель типов ({@link Types}).
 */
@UtilityClass
public class LocalTypes {

    /**
     * Выражение, для которого известен тип места назначения: инициализатор, правая часть присваивания, return
     */
    public record TypedExpression(String targetType, Expression expression) {
    }

    /**
     * @return простое имя типа выражения (String, int, BigDecimal) либо пусто, если тип определить не удалось
     */
    public Optional<String> typeOf(Expression expression) {
        Expression value = Nodes.unwrap(expression);
        return declaredTypeOf(value)
                .or(() -> Types.simpleName(value))
                .or(() -> Lombok.typeName(value));
    }

    /**
     * Является ли объект одним из перечисленных библиотечных типов (Lock, ExecutorService) либо их наследником.
     *
     * @param byName запасная проверка по имени объекта - на случай, когда тип определить не удалось
     */
    public boolean isAnyOf(Expression expression, Set<String> typeNames, Supplier<Boolean> byName) {
        Expression value = Nodes.unwrap(expression);
        Optional<String> type = typeOf(value);
        if (type.filter(typeNames::contains).isPresent()) {
            return true;
        }
        // Тип назван иначе, но может оказаться наследником: class OrderLock extends ReentrantLock
        return Types.isKindOf(value, typeNames).orElseGet(() -> type.isEmpty() && byName.get());
    }

    // Тип по литералу или объявлению в этом же файле
    private Optional<String> declaredTypeOf(Expression value) {
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
    public Optional<Node> findDeclaration(Node usage, String name) {
        Optional<Node> callable = Nodes.enclosingCallable(usage);
        if (callable.isPresent()) {
            List<Node> candidates = new ArrayList<>();
            callable.get().findAll(Parameter.class).stream()
                    .filter(parameter -> parameter.getNameAsString().equals(name))
                    .forEach(candidates::add);
            callable.get().findAll(VariableDeclarator.class).stream()
                    .filter(variable -> variable.getNameAsString().equals(name))
                    .forEach(candidates::add);

            // Одно имя может быть объявлено в методе несколько раз (в разных циклах, лямбдах):
            // берем объявление из ближайшей области видимости, в которой находится использование
            Optional<Node> nearest = candidates.stream()
                    .filter(candidate -> scopeOf(candidate).filter(scope -> scope.isAncestorOf(usage)).isPresent())
                    .max(Comparator.comparingInt(candidate -> depth(scopeOf(candidate).get())));
            if (nearest.isPresent()) {
                return nearest;
            }

            if (!candidates.isEmpty()) {
                // Область видимости определить не удалось: при разных типах не гадаем, какое объявление нужное
                Set<Optional<String>> types = candidates.stream()
                        .map(LocalTypes::declaredType)
                        .collect(Collectors.toSet());
                return types.size() == 1 ? Optional.of(candidates.get(0)) : Optional.empty();
            }
        }
        return findField(usage, name).map(variable -> variable);
    }

    // Параметр виден в своем методе или лямбде, локальная переменная - в блоке, цикле или try, где объявлена
    private Optional<Node> scopeOf(Node declaration) {
        if (declaration instanceof Parameter) {
            return declaration.getParentNode();
        }
        return declaration.getParentNode()
                .flatMap(Node::getParentNode)
                .flatMap(holder -> holder instanceof ExpressionStmt ? holder.getParentNode() : Optional.of(holder));
    }

    private int depth(Node node) {
        int depth = 0;
        for (Node current = node; current.getParentNode().isPresent(); current = current.getParentNode().get()) {
            depth++;
        }
        return depth;
    }

    /**
     * @return объявление переменной, на которую ссылается выражение: x либо this.x
     */
    public Optional<Node> findDeclaration(Expression expression) {
        Expression value = Nodes.unwrap(expression);
        if (value.isNameExpr()) {
            return findDeclaration(value, value.asNameExpr().getNameAsString());
        }
        if (value.isFieldAccessExpr() && value.asFieldAccessExpr().getScope().isThisExpr()) {
            return findField(value, value.asFieldAccessExpr().getNameAsString()).map(variable -> variable);
        }
        return Optional.empty();
    }

    /**
     * @return выражение, которым инициализирована переменная: для List<X> list = new LinkedList<>() это new LinkedList<>()
     */
    public Optional<Expression> findInitializer(Expression expression) {
        return findDeclaration(expression)
                .filter(declaration -> declaration instanceof VariableDeclarator)
                .flatMap(declaration -> ((VariableDeclarator) declaration).getInitializer());
    }

    /**
     * @return поле с таким именем в классе, где находится узел, либо во внешних классах
     */
    public Optional<VariableDeclarator> findField(Node from, String name) {
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

    public Optional<String> declaredType(Node declaration) {
        if (declaration instanceof Parameter parameter) {
            return parameter.getType().isUnknownType()
                    ? Optional.empty()
                    : Optional.of(typeName(parameter.getType()));
        }
        if (declaration instanceof VariableDeclarator variable) {
            // var x = new Foo() -> Foo; так же и val из Lombok
            if (variable.getType().isVarType() || Lombok.isVal(variable.getType())) {
                return variable.getInitializer()
                        .filter(initializer -> !initializer.isNameExpr())
                        .flatMap(LocalTypes::typeOf);
            }
            return Optional.of(typeName(variable.getType()));
        }
        return Optional.empty();
    }

    // java.util.Map<String, String> -> Map
    public String typeName(Type type) {
        return type.isClassOrInterfaceType() ? type.asClassOrInterfaceType().getNameAsString() : type.asString();
    }

    public List<TypedExpression> findTypedExpressions(Node root) {
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
