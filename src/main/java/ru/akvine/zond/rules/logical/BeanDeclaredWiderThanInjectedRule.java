package ru.akvine.zond.rules.logical;

import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.expr.Expression;
import com.github.javaparser.ast.expr.LambdaExpr;
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
import ru.akvine.zond.rules.support.SpringBeans;
import ru.akvine.zond.rules.support.TestClasses;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;

/**
 * Бин создается {@code @Bean}-методом, который объявлен с типом интерфейса или предка, а внедряют его по самому
 * классу. Пока бин не создан, Spring знает о нем только объявленный тип метода: компонент, который создается
 * раньше и требует класс, подходящего бина не находит, и контекст не поднимается. Работает такое внедрение лишь
 * тогда, когда бин успели создать раньше.
 * <p>
 * Раньше создаются компоненты, найденные сканированием (@Service, @RestController): их Spring регистрирует до
 * {@code @Bean}-методов. О получателе, который сам создан {@code @Bean}-методом, не судим: порядок там другой
 */
@Component
public class BeanDeclaredWiderThanInjectedRule extends AbstractRule implements ProjectRule {
    private static final String BEAN = "Bean";
    private static final Set<String> STEREOTYPES = Set.of(
            "Component", "Service", "Repository", "Controller", "RestController", "Configuration");
    private static final int MAX_NAMED = 3;

    @Override
    public String code() {
        return RuleCodes.BEAN_DECLARED_WIDER_THAN_INJECTED_RULE_CODE;
    }

    @Override
    public String description() {
        return "Сканирует проект и ищет бин, который объявлен @Bean-методом с типом интерфейса, а внедряется по"
                + " своему классу";
    }

    @Override
    public List<Violation> checkProject(List<SourceFile> sourceFiles) {
        ProjectClasses classes = ProjectClasses.of(sourceFiles);
        List<MethodDeclaration> beanMethods = new ArrayList<>();
        for (SourceFile sourceFile : sourceFiles) {
            sourceFile.findAll(MethodDeclaration.class).stream()
                    .filter(method -> Annotations.has(method, BEAN) && !TestClasses.isInside(method))
                    .forEach(beanMethods::add);
        }
        // Типы, под которыми бины объявлены: по ним Spring находит бин и до его создания
        Set<String> declared = new HashSet<>();
        beanMethods.forEach(method -> declared.add(LocalTypes.typeName(method.getType())));
        // Классы, которые создают сами @Bean-методы: такой получатель создается не раньше, а в свой черед - после
        // методов, объявленных выше него, и нужный бин к этому времени обычно уже есть
        Set<String> createdByMethods = new HashSet<>(declared);
        beanMethods.forEach(method -> createdClass(method).ifPresent(createdByMethods::add));

        List<Violation> violations = new ArrayList<>();
        for (MethodDeclaration method : beanMethods) {
            Optional<String> created = createdClass(method)
                    .filter(type -> !type.equals(LocalTypes.typeName(method.getType())) && !declared.contains(type))
                    // Класс со своей аннотацией Spring создает и сам - под собственным типом
                    .filter(type -> classes.find(type)
                            .filter(found -> !Annotations.hasAny(found, STEREOTYPES))
                            .isPresent());
            if (created.isEmpty()) {
                continue;
            }
            Set<String> consumers = eagerConsumers(created.get(), classes);
            consumers.removeAll(createdByMethods);
            if (consumers.isEmpty()) {
                continue;
            }
            SourceFile sourceFile = sourceFiles.stream()
                    .filter(file -> method.findCompilationUnit().filter(unit -> unit == file.unit()).isPresent())
                    .findFirst()
                    .orElseThrow();
            violations.add(violation(sourceFile, method,
                    "Бин '" + method.getNameAsString() + "' объявлен с типом " + LocalTypes.typeName(method.getType())
                            + ", а создается как " + created.get() + ", и по этому классу его внедряют в "
                            + String.join(", ", consumers.stream().limit(MAX_NAMED).toList())
                            + ": пока бин не создан, Spring знает только объявленный тип метода, и компонент,"
                            + " который создается раньше, подходящего бина не находит - контекст не поднимется"
                            + " (NoSuchBeanDefinitionException); объявите метод с типом " + created.get()));
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

    // Класс, который метод создает во всех своих return: new TelegramProductionBot(...) сразу или через переменную
    private Optional<String> createdClass(MethodDeclaration method) {
        Set<String> created = new HashSet<>();
        for (ReturnStmt statement : method.findAll(ReturnStmt.class)) {
            // return внутри лямбды относится к ней, а не к методу
            if (statement.findAncestor(MethodDeclaration.class).filter(owner -> owner == method).isEmpty()
                    || statement.findAncestor(LambdaExpr.class).isPresent()) {
                continue;
            }
            Optional<Expression> value = statement.getExpression().map(Nodes::unwrap);
            if (value.filter(Expression::isNameExpr).isPresent()) {
                value = LocalTypes.findInitializer(value.get()).map(Nodes::unwrap);
            }
            if (value.filter(Expression::isObjectCreationExpr).isEmpty()) {
                return Optional.empty();
            }
            created.add(value.get().asObjectCreationExpr().getType().getNameAsString());
        }
        return created.size() == 1 ? Optional.of(created.iterator().next()) : Optional.empty();
    }

    // Компоненты, которым бин нужен сразу при создании: @Lazy и ObjectProvider получают его позже, когда он уже есть
    private Set<String> eagerConsumers(String type, ProjectClasses classes) {
        Set<String> consumers = new TreeSet<>();
        for (ClassOrInterfaceDeclaration bean : classes.all()) {
            if (bean.isInterface() || !Annotations.hasAny(bean, STEREOTYPES) || TestClasses.isInside(bean)) {
                continue;
            }
            boolean needs = SpringBeans.findDependencies(bean).stream()
                    .anyMatch(dependency -> !dependency.deferred() && dependency.type().equals(type));
            if (needs) {
                consumers.add(bean.getNameAsString());
            }
        }
        return consumers;
    }
}
