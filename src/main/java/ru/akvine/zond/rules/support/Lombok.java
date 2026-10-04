package ru.akvine.zond.rules.support;

import com.github.javaparser.ast.Node;
import com.github.javaparser.ast.body.BodyDeclaration;
import com.github.javaparser.ast.body.FieldDeclaration;
import com.github.javaparser.ast.body.TypeDeclaration;
import com.github.javaparser.ast.body.VariableDeclarator;
import com.github.javaparser.ast.expr.AnnotationExpr;
import com.github.javaparser.ast.expr.Expression;
import com.github.javaparser.ast.expr.MethodCallExpr;
import com.github.javaparser.ast.expr.VariableDeclarationExpr;
import com.github.javaparser.ast.nodeTypes.NodeWithAnnotations;
import com.github.javaparser.ast.type.Type;
import com.github.javaparser.resolution.types.ResolvedType;
import lombok.experimental.UtilityClass;

import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Код, который Lombok дописывает при компиляции и которого поэтому нет в исходниках: геттеры и сеттеры,
 * builder, методы with..., поле логгера. Решатель типов о нем не знает - здесь он восстанавливается по аннотациям.
 */
@UtilityClass
public class Lombok {
    private static final String GETTER = "Getter";
    private static final String SETTER = "Setter";
    private static final String WITH = "With";
    private static final String ACCESSORS = "Accessors";
    private static final String CLEANUP = "Cleanup";
    private static final String VAL = "val";

    // На классе создают геттеры для всех полей; @Getter - еще и на отдельном поле
    private static final Set<String> GETTER_ANNOTATIONS = Set.of(GETTER, "Data", "Value");
    private static final Set<String> SETTER_ANNOTATIONS = Set.of(SETTER, "Data");
    private static final Set<String> BUILDER_ANNOTATIONS = Set.of("Builder", "SuperBuilder");

    // Аннотация -> простое имя типа поля log, которое она создает
    private static final Map<String, String> LOGGER_TYPES = Map.of(
            "Slf4j", "Logger",
            "XSlf4j", "XLogger",
            "Log4j", "Logger",
            "Log4j2", "Logger",
            "Log", "Logger",
            "JBossLog", "Logger",
            "Flogger", "FluentLogger",
            "CommonsLog", "Log",
            "CustomLog", "Logger");
    private static final String LOGGER_FIELD = "log";

    private static final String GET_PREFIX = "get";
    private static final String IS_PREFIX = "is";
    private static final String SET_PREFIX = "set";
    private static final String WITH_PREFIX = "with";
    private static final String BUILDER = "builder";
    private static final String TO_BUILDER = "toBuilder";
    private static final String BUILD = "build";

    private static final String FLUENT = "fluent";
    private static final String CHAIN = "chain";
    private static final String TRUE = "true";

    /**
     * @return тип вызова метода, который создаст Lombok: user.getName(), user.setName("x") с chain,
     * user.withName("x"), User.builder().name("x").build(); пусто, если это не такой вызов
     */
    public Optional<ResolvedType> resolve(Expression expression) {
        if (!expression.isMethodCallExpr()) {
            return Optional.empty();
        }
        MethodCallExpr call = expression.asMethodCallExpr();
        if (BUILD.equals(call.getNameAsString()) && call.getArguments().isEmpty()) {
            return builtType(call);
        }

        Optional<TypeDeclaration<?>> owner = ownerOf(call);
        if (owner.isEmpty()) {
            return Optional.empty();
        }
        return switch (call.getArguments().size()) {
            case 0 -> getterType(call, owner.get());
            case 1 -> isChainedSetter(call, owner.get()) || isWith(call, owner.get())
                    ? call.getScope().flatMap(Types::resolve)
                    : Optional.empty();
            default -> Optional.empty();
        };
    }

    /**
     * @return простое имя типа для того, что решателю не найти даже с подсказкой: поле log от @Slf4j
     * имеет тип из библиотеки логирования, которой среди исходников нет
     */
    public Optional<String> typeName(Expression expression) {
        boolean isLogger = expression.isNameExpr() && LOGGER_FIELD.equals(expression.asNameExpr().getNameAsString())
                || expression.isFieldAccessExpr()
                && expression.asFieldAccessExpr().getScope().isThisExpr()
                && LOGGER_FIELD.equals(expression.asFieldAccessExpr().getNameAsString());
        if (!isLogger) {
            return Optional.empty();
        }

        Node current = expression.getParentNode().orElse(null);
        while (current != null) {
            if (current instanceof TypeDeclaration<?> type) {
                Optional<String> logger = type.getAnnotations().stream()
                        .map(Lombok::nameOf)
                        .filter(LOGGER_TYPES::containsKey)
                        .map(LOGGER_TYPES::get)
                        .findFirst();
                if (logger.isPresent()) {
                    return logger;
                }
            }
            current = current.getParentNode().orElse(null);
        }
        return Optional.empty();
    }

    /**
     * @return true для val из Lombok: тип такой переменной берется из инициализатора, как у var
     */
    public boolean isVal(Type type) {
        return type.isClassOrInterfaceType() && VAL.equals(type.asClassOrInterfaceType().getNameAsString());
    }

    /**
     * @return true, если Lombok создаст для поля сеттер: @Setter либо @Data на классе или @Setter на поле
     */
    public boolean hasSetter(TypeDeclaration<?> owner, FieldDeclaration field) {
        return !field.isStatic()
                && !field.isFinal()
                && (hasAny(owner, SETTER_ANNOTATIONS) || has(field, SETTER));
    }

    /**
     * @return true для переменной с @Cleanup: Lombok сам закроет ее в конце блока
     */
    public boolean isCleanedUp(VariableDeclarator variable) {
        return variable.getParentNode()
                .filter(parent -> parent instanceof VariableDeclarationExpr)
                .map(parent -> (VariableDeclarationExpr) parent)
                .filter(declaration -> has(declaration, CLEANUP))
                .isPresent();
    }

    // Класс, у которого вызван метод: тип получателя, а для вызова без получателя и this.x() - свой класс
    private Optional<TypeDeclaration<?>> ownerOf(MethodCallExpr call) {
        Optional<Expression> scope = call.getScope().map(Nodes::unwrap);
        if (scope.isPresent() && !scope.get().isThisExpr()) {
            return Types.projectType(scope.get());
        }

        Node current = call.getParentNode().orElse(null);
        while (current != null) {
            if (current instanceof TypeDeclaration<?> type) {
                return Optional.of(type);
            }
            current = current.getParentNode().orElse(null);
        }
        return Optional.empty();
    }

    // getName() и isActive() - по полю name / active; при @Accessors(fluent = true) геттер назван как поле: name()
    private Optional<ResolvedType> getterType(MethodCallExpr call, TypeDeclaration<?> owner) {
        String method = call.getNameAsString();
        Optional<FieldDeclaration> field = Optional.empty();
        if (method.startsWith(GET_PREFIX)) {
            field = findField(owner, propertyName(method, GET_PREFIX));
        } else if (method.startsWith(IS_PREFIX)) {
            // boolean active -> isActive(); boolean isActive -> тоже isActive()
            field = findField(owner, propertyName(method, IS_PREFIX)).or(() -> findField(owner, method));
        }
        if (field.isEmpty()) {
            field = findField(owner, method).filter(candidate -> isFluent(owner, candidate));
        }

        return field
                .filter(candidate -> !candidate.isStatic())
                .filter(candidate -> hasAny(owner, GETTER_ANNOTATIONS) || has(candidate, GETTER))
                .flatMap(candidate -> Types.resolve(candidate.getVariable(0).getType()));
    }

    // Сеттер возвращает сам объект при @Accessors(chain = true) и при fluent, где он назван как поле: name("x")
    private boolean isChainedSetter(MethodCallExpr call, TypeDeclaration<?> owner) {
        String method = call.getNameAsString();
        Optional<FieldDeclaration> named = method.startsWith(SET_PREFIX)
                ? findField(owner, propertyName(method, SET_PREFIX))
                        .filter(field -> isChain(owner, field) || isFluent(owner, field))
                : Optional.empty();
        Optional<FieldDeclaration> fluent = findField(owner, method).filter(field -> isFluent(owner, field));
        return named.or(() -> fluent).filter(field -> hasSetter(owner, field)).isPresent();
    }

    // user.withName("x") возвращает копию того же типа
    private boolean isWith(MethodCallExpr call, TypeDeclaration<?> owner) {
        String method = call.getNameAsString();
        return method.startsWith(WITH_PREFIX)
                && findField(owner, propertyName(method, WITH_PREFIX))
                .filter(field -> has(owner, WITH) || has(field, WITH))
                .isPresent();
    }

    // User.builder().name("x").build() и user.toBuilder().name("x").build(): идем по цепочке к ее началу
    private Optional<ResolvedType> builtType(MethodCallExpr build) {
        Optional<Expression> current = build.getScope().map(Nodes::unwrap);
        while (current.isPresent() && current.get().isMethodCallExpr()) {
            MethodCallExpr call = current.get().asMethodCallExpr();
            Optional<Expression> scope = call.getScope().map(Nodes::unwrap);
            if (call.getArguments().isEmpty() && scope.isPresent()) {
                if (BUILDER.equals(call.getNameAsString()) && scope.get().isNameExpr()) {
                    return Types.resolveTypeName(scope.get().asNameExpr()).filter(Lombok::hasBuilder);
                }
                if (TO_BUILDER.equals(call.getNameAsString())) {
                    return Types.resolve(scope.get()).filter(Lombok::hasBuilder);
                }
            }
            current = scope;
        }
        return Optional.empty();
    }

    // @Builder стоит на классе либо на его конструкторе или фабричном методе
    private boolean hasBuilder(ResolvedType type) {
        return Types.projectType(type)
                .filter(source -> hasAny(source, BUILDER_ANNOTATIONS) || source.getMembers().stream()
                        .anyMatch(member -> hasBuilderAnnotation(member)))
                .isPresent();
    }

    private boolean hasBuilderAnnotation(BodyDeclaration<?> member) {
        return member.getAnnotations().stream().map(Lombok::nameOf).anyMatch(BUILDER_ANNOTATIONS::contains);
    }

    private Optional<FieldDeclaration> findField(TypeDeclaration<?> owner, String name) {
        return owner.getFields().stream()
                .filter(field -> field.getVariables().stream()
                        .anyMatch(variable -> variable.getNameAsString().equals(name)))
                .findFirst();
    }

    // getName -> name
    private String propertyName(String method, String prefix) {
        if (method.length() == prefix.length()) {
            return "";
        }
        String name = method.substring(prefix.length());
        return Character.toLowerCase(name.charAt(0)) + name.substring(1);
    }

    private boolean isFluent(TypeDeclaration<?> owner, FieldDeclaration field) {
        return hasAccessorsFlag(owner, FLUENT) || hasAccessorsFlag(field, FLUENT);
    }

    private boolean isChain(TypeDeclaration<?> owner, FieldDeclaration field) {
        return hasAccessorsFlag(owner, CHAIN) || hasAccessorsFlag(field, CHAIN);
    }

    // @Accessors(chain = true)
    private boolean hasAccessorsFlag(NodeWithAnnotations<?> node, String flag) {
        return node.getAnnotations().stream()
                .filter(annotation -> ACCESSORS.equals(nameOf(annotation)) && annotation.isNormalAnnotationExpr())
                .flatMap(annotation -> annotation.asNormalAnnotationExpr().getPairs().stream())
                .anyMatch(pair -> flag.equals(pair.getNameAsString()) && TRUE.equals(pair.getValue().toString()));
    }

    private boolean has(NodeWithAnnotations<?> node, String annotation) {
        return node.getAnnotations().stream().map(Lombok::nameOf).anyMatch(annotation::equals);
    }

    private boolean hasAny(NodeWithAnnotations<?> node, Set<String> annotations) {
        return node.getAnnotations().stream().map(Lombok::nameOf).anyMatch(annotations::contains);
    }

    // lombok.Getter -> Getter
    private String nameOf(AnnotationExpr annotation) {
        return annotation.getName().getIdentifier();
    }
}
