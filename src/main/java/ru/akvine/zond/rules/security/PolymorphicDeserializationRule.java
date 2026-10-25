package ru.akvine.zond.rules.security;

import com.github.javaparser.ast.Node;
import com.github.javaparser.ast.body.FieldDeclaration;
import com.github.javaparser.ast.body.VariableDeclarator;
import com.github.javaparser.ast.expr.AnnotationExpr;
import com.github.javaparser.ast.expr.Expression;
import com.github.javaparser.ast.expr.MemberValuePair;
import com.github.javaparser.ast.expr.MethodCallExpr;
import com.github.javaparser.ast.expr.ObjectCreationExpr;
import org.springframework.stereotype.Component;
import ru.akvine.zond.enums.Confidence;
import ru.akvine.zond.enums.ErrorLevel;
import ru.akvine.zond.enums.ErrorType;
import ru.akvine.zond.models.SourceFile;
import ru.akvine.zond.models.Violation;
import ru.akvine.zond.rules.AbstractRule;
import ru.akvine.zond.rules.RuleCodes;
import ru.akvine.zond.rules.support.LocalTypes;
import ru.akvine.zond.rules.support.TestClasses;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Десериализация, при которой класс объекта выбирает присланный текст: Jackson с enableDefaultTyping или
 * activateDefaultTyping(LaissezFaireSubTypeValidator), @JsonTypeInfo(use = Id.CLASS) у поля типа Object,
 * new Yaml().load(...) в SnakeYAML 1.x, XStream без списка разрешенных классов. Клиент присылает имя класса
 * из библиотек приложения ("гаджет"), и при создании объекта выполняется его код
 */
@Component
public class PolymorphicDeserializationRule extends AbstractRule {
    private static final String ENABLE_DEFAULT_TYPING = "enableDefaultTyping";
    private static final Set<String> ACTIVATE_DEFAULT_TYPING =
            Set.of("activateDefaultTyping", "activateDefaultTypingAsProperty");
    // Проверка подтипов, которая пропускает любой класс
    private static final Pattern PERMISSIVE_VALIDATOR = Pattern.compile(
            "LaissezFaireSubTypeValidator|allowIf(Base|Sub)Type\\(\\s*Object\\.class\\s*\\)");
    private static final String JSON_TYPE_INFO = "JsonTypeInfo";
    private static final String USE = "use";
    private static final Pattern CLASS_ID = Pattern.compile(".*\\bId\\.(MINIMAL_)?CLASS$");
    // Под такой тип подходит любой класс из библиотек
    private static final Set<String> OPEN_TYPES = Set.of("Object", "Serializable", "Comparable", "Cloneable");
    private static final String YAML = "Yaml";
    private static final Set<String> SAFE_YAML_CONSTRUCTORS = Set.of("SafeConstructor", "LoaderOptions");
    private static final Set<String> YAML_LOADING = Set.of("load", "loadAs", "loadAll");
    private static final String XSTREAM = "XStream";
    private static final Set<String> XSTREAM_PERMISSIONS = Set.of(
            "allowTypes", "allowTypesByWildcard", "allowTypesByRegExp", "allowTypeHierarchy", "addPermission",
            "setupDefaultSecurity", "denyTypes");

    @Override
    public String code() {
        return RuleCodes.POLYMORPHIC_DESERIALIZATION_RULE_CODE;
    }

    @Override
    public String description() {
        return "Сканирует код и ищет десериализацию, где класс объекта задает присланный текст: default typing"
                + " в Jackson, @JsonTypeInfo(use = Id.CLASS) у Object, SnakeYAML без SafeConstructor, XStream";
    }

    @Override
    public List<Violation> check(SourceFile sourceFile) {
        List<Violation> violations = new ArrayList<>();
        for (MethodCallExpr call : sourceFile.unit().findAll(MethodCallExpr.class)) {
            if (TestClasses.isInside(call)) {
                continue;
            }
            String method = call.getNameAsString();
            if (ENABLE_DEFAULT_TYPING.equals(method)
                    || ACTIVATE_DEFAULT_TYPING.contains(method) && usesPermissiveValidator(call)) {
                violations.add(violation(sourceFile, call,
                        "'" + method + "' разрешает присланному JSON выбрать любой класс: клиент передаст имя класса"
                                + " из библиотек приложения, и при создании объекта выполнится его код; перечислите"
                                + " допустимые типы через BasicPolymorphicTypeValidator.allowIfSubType(...)"
                                + " либо обойдитесь без default typing"));
            }
        }
        for (AnnotationExpr annotation : sourceFile.unit().findAll(AnnotationExpr.class)) {
            if (JSON_TYPE_INFO.equals(annotation.getName().getIdentifier()) && usesClassName(annotation)
                    && isOpenField(annotation)) {
                violations.add(violation(sourceFile, annotation,
                        "@JsonTypeInfo(use = Id.CLASS) у поля типа Object: класс значения задает присланный JSON,"
                                + " и клиент выберет любой класс из библиотек приложения; используйте Id.NAME с"
                                + " перечнем подтипов в @JsonSubTypes либо сузьте тип поля"));
            }
        }
        for (ObjectCreationExpr creation : sourceFile.unit().findAll(ObjectCreationExpr.class)) {
            if (TestClasses.isInside(creation)) {
                continue;
            }
            String type = creation.getType().getNameAsString();
            if (YAML.equals(type) && !hasSafeConstructor(creation) && isUsedForLoading(creation)) {
                violations.add(violation(sourceFile, creation,
                        "'" + creation + "' без SafeConstructor: в SnakeYAML 1.x тег !!имя.класса в документе"
                                + " создает объект любого класса и выполняет его код; передайте"
                                + " new SafeConstructor(new LoaderOptions()) либо обновитесь до SnakeYAML 2")
                        .withConfidence(Confidence.SUSPICION));
            } else if (XSTREAM.equals(type) && !limitsXStreamTypes(creation)) {
                violations.add(violation(sourceFile, creation,
                        "XStream без списка разрешенных классов: присланный XML создаст объект любого класса;"
                                + " вызовите allowTypes(...) или allowTypesByWildcard(...) с вашими пакетами")
                        .withConfidence(Confidence.SUSPICION));
            }
        }
        return violations;
    }

    @Override
    public ErrorLevel errorLevel() {
        return ErrorLevel.CRITICAL;
    }

    @Override
    public ErrorType errorType() {
        return ErrorType.SECURITY;
    }

    // activateDefaultTyping(LaissezFaireSubTypeValidator.instance, ...) либо через переменную с таким значением
    private boolean usesPermissiveValidator(MethodCallExpr call) {
        return call.getArguments().stream().anyMatch(argument -> {
            String text = argument.toString();
            String initializer = argument.isNameExpr()
                    ? LocalTypes.findInitializer(argument).map(Expression::toString).orElse("")
                    : "";
            return PERMISSIVE_VALIDATOR.matcher(text).find() || PERMISSIVE_VALIDATOR.matcher(initializer).find();
        });
    }

    private boolean usesClassName(AnnotationExpr annotation) {
        return annotation.findAll(MemberValuePair.class).stream()
                .anyMatch(pair -> USE.equals(pair.getNameAsString())
                        && CLASS_ID.matcher(pair.getValue().toString()).matches());
    }

    // Object payload, List<Object> items, Map<String, Serializable> values
    private boolean isOpenField(AnnotationExpr annotation) {
        Node parent = annotation.getParentNode().orElse(null);
        if (!(parent instanceof FieldDeclaration field)) {
            return false;
        }
        String type = field.getElementType().asString();
        return OPEN_TYPES.stream().anyMatch(open -> type.equals(open) || type.contains("<" + open + ">")
                || type.contains(", " + open + ">") || type.contains("<" + open + ","));
    }

    private boolean hasSafeConstructor(ObjectCreationExpr creation) {
        return creation.getArguments().stream()
                .anyMatch(argument -> SAFE_YAML_CONSTRUCTORS.stream().anyMatch(argument.toString()::contains)
                        || LocalTypes.typeOf(argument).filter(SAFE_YAML_CONSTRUCTORS::contains).isPresent());
    }

    // new Yaml().load(text) либо yaml.load(text) у переменной или поля с этим объектом; dump() только пишет
    private boolean isUsedForLoading(ObjectCreationExpr creation) {
        Node parent = creation.getParentNode().orElse(null);
        if (parent instanceof MethodCallExpr call && call.getScope().filter(scope -> scope == creation).isPresent()) {
            return YAML_LOADING.contains(call.getNameAsString());
        }
        if (!(parent instanceof VariableDeclarator variable)) {
            return false;
        }
        String name = variable.getNameAsString();
        return creation.findCompilationUnit().stream()
                .flatMap(unit -> unit.findAll(MethodCallExpr.class).stream())
                .anyMatch(call -> YAML_LOADING.contains(call.getNameAsString()) && call.getScope()
                        .filter(scope -> scope.toString().equals(name) || scope.toString().equals("this." + name))
                        .isPresent());
    }

    // XStream xstream = new XStream(); xstream.allowTypes(...) где-нибудь в этом же файле
    private boolean limitsXStreamTypes(ObjectCreationExpr creation) {
        return creation.findCompilationUnit().stream()
                .flatMap(unit -> unit.findAll(MethodCallExpr.class).stream())
                .anyMatch(call -> XSTREAM_PERMISSIONS.contains(call.getNameAsString()));
    }
}
