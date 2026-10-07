package ru.akvine.zond.rules.logical;

import com.github.javaparser.ast.Node;
import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration;
import com.github.javaparser.ast.body.FieldDeclaration;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.body.VariableDeclarator;
import com.github.javaparser.ast.expr.Expression;
import com.github.javaparser.ast.expr.MethodCallExpr;
import com.github.javaparser.ast.expr.ObjectCreationExpr;
import com.github.javaparser.ast.stmt.ReturnStmt;
import org.springframework.stereotype.Component;
import ru.akvine.zond.enums.ErrorLevel;
import ru.akvine.zond.enums.ErrorType;
import ru.akvine.zond.models.SourceFile;
import ru.akvine.zond.models.Violation;
import ru.akvine.zond.rules.AbstractRule;
import ru.akvine.zond.rules.ProjectRule;
import ru.akvine.zond.rules.RuleCodes;
import ru.akvine.zond.rules.support.Annotations;
import ru.akvine.zond.rules.support.LocalTypes;
import ru.akvine.zond.rules.support.Nodes;
import ru.akvine.zond.rules.support.ProjectClasses;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

@Component
public class MapperMissesFieldRule extends AbstractRule implements ProjectRule {
    private static final String SET_PREFIX = "set";
    private static final String BUILDER = "builder";
    private static final String BUILD = "build";
    private static final Set<String> LOMBOK_SETTERS = Set.of("Data", "Setter");
    private static final Set<String> LOMBOK_BUILDERS = Set.of("Builder", "SuperBuilder");
    // Меньше двух заполненных полей - это не маппер, а создание объекта по ходу дела
    private static final int MIN_FILLED = 2;

    @Override
    public String code() {
        return RuleCodes.MAPPER_MISSES_FIELD_RULE_CODE;
    }

    @Override
    public String description() {
        return "Сканирует проект и ищет ручные мапперы, которые не заполняют поле результата, хотя в исходном объекте оно есть";
    }

    @Override
    public List<Violation> checkProject(List<SourceFile> sourceFiles) {
        ProjectClasses classes = ProjectClasses.of(sourceFiles);
        List<Violation> violations = new ArrayList<>();
        for (SourceFile sourceFile : sourceFiles) {
            for (MethodDeclaration method : sourceFile.unit().findAll(MethodDeclaration.class)) {
                Set<String> available = sourceProperties(method, classes);
                if (available.isEmpty()) {
                    continue;
                }
                for (Filling filling : fillings(method, classes)) {
                    List<String> missed = filling.settable().stream()
                            .filter(name -> !filling.filled().contains(name) && available.contains(name))
                            .toList();
                    // Заполнена меньшая часть полей - объект собирают частично намеренно
                    if (filling.filled().size() < MIN_FILLED || missed.isEmpty() || missed.size() > filling.filled().size()) {
                        continue;
                    }
                    violations.add(violation(sourceFile, filling.place(),
                            "Метод '" + method.getNameAsString() + "' собирает '" + filling.type() + "', но не заполняет "
                                    + (missed.size() == 1 ? "поле '" : "поля '") + String.join("', '", missed)
                                    + "', хотя в исходном объекте оно есть: значение потеряется при преобразовании."
                                    + " Если это намеренно - скройте находку комментарием zond:ignore"));
                }
            }
        }
        return violations;
    }

    @Override
    public ErrorLevel errorLevel() {
        return ErrorLevel.MINOR;
    }

    @Override
    public ErrorType errorType() {
        return ErrorType.LOGICAL;
    }

    /**
     * Место, где метод собирает объект
     *
     * @param settable поля, которые можно заполнить
     * @param filled   поля, которые метод заполнил
     */
    private record Filling(Node place, String type, Set<String> settable, Set<String> filled) {
    }

    // Свойства параметров метода - классов проекта: то, откуда маппер берет значения
    private Set<String> sourceProperties(MethodDeclaration method, ProjectClasses classes) {
        Set<String> properties = new LinkedHashSet<>();
        method.getParameters().forEach(parameter -> classes.find(LocalTypes.typeName(parameter.getType()))
                .filter(type -> !type.isInterface())
                .ifPresent(type -> properties.addAll(classes.properties(type).types().keySet())));
        return properties;
    }

    private List<Filling> fillings(MethodDeclaration method, ProjectClasses classes) {
        List<Filling> fillings = new ArrayList<>();
        // Target target = new Target(); target.setA(...); return target;
        for (VariableDeclarator variable : method.findAll(VariableDeclarator.class)) {
            Optional<ClassOrInterfaceDeclaration> type = variable.getInitializer()
                    .filter(Expression::isObjectCreationExpr)
                    .map(Expression::asObjectCreationExpr)
                    .filter(creation -> creation.getArguments().isEmpty() && creation.getAnonymousClassBody().isEmpty())
                    .map(ObjectCreationExpr::getType)
                    .flatMap(created -> classes.find(created.getNameAsString()));
            String name = variable.getNameAsString();
            if (type.isEmpty() || !isReturned(method, name) || isPassedOn(method, name)) {
                continue;
            }
            Set<String> filled = new LinkedHashSet<>();
            for (MethodCallExpr call : method.findAll(MethodCallExpr.class)) {
                boolean setter = call.getNameAsString().startsWith(SET_PREFIX) && call.getNameAsString().length() > 3
                        && call.getScope().filter(scope -> isName(scope, name)).isPresent();
                if (setter) {
                    filled.add(uncapitalize(call.getNameAsString().substring(SET_PREFIX.length())));
                }
            }
            fillings.add(new Filling(variable, type.get().getNameAsString(), settable(type.get(), false), filled));
        }
        // Target.builder().a(...).b(...).build()
        for (MethodCallExpr build : method.findAll(MethodCallExpr.class)) {
            if (!BUILD.equals(build.getNameAsString()) || !build.getArguments().isEmpty()) {
                continue;
            }
            Set<String> filled = new LinkedHashSet<>();
            Optional<Expression> scope = build.getScope();
            while (scope.isPresent() && scope.get().isMethodCallExpr()) {
                MethodCallExpr link = scope.get().asMethodCallExpr();
                if (BUILDER.equals(link.getNameAsString())) {
                    link.getScope()
                            .filter(Expression::isNameExpr)
                            .flatMap(owner -> classes.find(owner.asNameExpr().getNameAsString()))
                            .filter(type -> Annotations.hasAny(type, LOMBOK_BUILDERS))
                            .ifPresent(type -> fillings.add(
                                    new Filling(build, type.getNameAsString(), settable(type, true), filled)));
                    break;
                }
                filled.add(link.getNameAsString());
                scope = link.getScope();
            }
        }
        return fillings;
    }

    // Поля, у которых есть сеттер (свой либо от Lombok); у построителя Lombok - все нестатические поля
    private Set<String> settable(ClassOrInterfaceDeclaration type, boolean builder) {
        Set<String> names = new LinkedHashSet<>();
        boolean lombokSetters = Annotations.hasAny(type, LOMBOK_SETTERS);
        for (FieldDeclaration field : type.getFields()) {
            if (field.isStatic() || !builder && field.isFinal()) {
                continue;
            }
            for (VariableDeclarator variable : field.getVariables()) {
                String name = variable.getNameAsString();
                // Поле с начальным значением у построителя заполнено и без вызова
                boolean hasSetter = builder ? variable.getInitializer().isEmpty()
                        : lombokSetters || Annotations.hasAny(field, LOMBOK_SETTERS)
                        || !type.getMethodsByName(SET_PREFIX + capitalize(name)).isEmpty();
                if (hasSetter) {
                    names.add(name);
                }
            }
        }
        return names;
    }

    private boolean isReturned(MethodDeclaration method, String name) {
        return method.findAll(ReturnStmt.class).stream()
                .anyMatch(statement -> statement.getExpression().filter(value -> isName(value, name)).isPresent());
    }

    // Объект отдан другому методу: поля может заполнить он
    private boolean isPassedOn(MethodDeclaration method, String name) {
        return method.findAll(MethodCallExpr.class).stream()
                .anyMatch(call -> call.getArguments().stream().anyMatch(argument -> isName(argument, name)));
    }

    private boolean isName(Expression expression, String name) {
        Expression value = Nodes.unwrap(expression);
        return value.isNameExpr() && value.asNameExpr().getNameAsString().equals(name);
    }

    private String uncapitalize(String name) {
        return Character.toLowerCase(name.charAt(0)) + name.substring(1);
    }

    private String capitalize(String name) {
        return Character.toUpperCase(name.charAt(0)) + name.substring(1);
    }
}
