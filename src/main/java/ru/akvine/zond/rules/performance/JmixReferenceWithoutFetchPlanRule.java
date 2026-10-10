package ru.akvine.zond.rules.performance;

import com.github.javaparser.ast.Node;
import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration;
import com.github.javaparser.ast.body.VariableDeclarator;
import com.github.javaparser.ast.expr.Expression;
import com.github.javaparser.ast.expr.LambdaExpr;
import com.github.javaparser.ast.expr.MethodCallExpr;
import com.github.javaparser.ast.stmt.ForEachStmt;
import org.springframework.stereotype.Component;
import ru.akvine.zond.enums.ErrorLevel;
import ru.akvine.zond.enums.ErrorType;
import ru.akvine.zond.models.SourceFile;
import ru.akvine.zond.models.Violation;
import ru.akvine.zond.rules.AbstractRule;
import ru.akvine.zond.rules.ProjectRule;
import ru.akvine.zond.rules.RuleCodes;
import ru.akvine.zond.rules.support.Annotations;
import ru.akvine.zond.rules.support.Jmix;
import ru.akvine.zond.rules.support.LocalTypes;
import ru.akvine.zond.rules.support.Nodes;
import ru.akvine.zond.rules.support.ProjectClasses;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * Список сущностей загружен без плана выборки, а в цикле у каждой читают связь. Jmix дозагружает связь
 * по требованию - отдельным запросом на каждый элемент.
 */
@Component
public class JmixReferenceWithoutFetchPlanRule extends AbstractRule implements ProjectRule {
    private static final String LIST = "list";
    private static final Set<String> FETCH_PLAN_METHODS = Set.of("fetchPlan", "fetchPlanProperties");
    private static final Set<String> REFERENCE_ANNOTATIONS = Set.of(
            "ManyToOne", "OneToOne", "OneToMany", "ManyToMany", "Composition");
    // items.forEach(item -> ...), items.stream().map(item -> ...)
    private static final Set<String> PER_ELEMENT_METHODS = Set.of("forEach", "map", "filter", "flatMap", "peek", "anyMatch", "allMatch");
    private static final String GET = "get";

    @Override
    public String code() {
        return RuleCodes.JMIX_REFERENCE_WITHOUT_FETCH_PLAN_RULE_CODE;
    }

    @Override
    public String description() {
        return "Сканирует код и ищет чтение связей в цикле у сущностей, загруженных через DataManager без плана выборки";
    }

    @Override
    public List<Violation> checkProject(List<SourceFile> sourceFiles) {
        ProjectClasses classes = ProjectClasses.of(sourceFiles);
        List<Violation> violations = new ArrayList<>();
        for (SourceFile sourceFile : sourceFiles) {
            for (MethodCallExpr load : sourceFile.unit().findAll(MethodCallExpr.class)) {
                Optional<List<MethodCallExpr>> chain = Jmix.loadChain(load).filter(calls -> LIST.equals(load.getNameAsString()));
                boolean planned = chain.isEmpty() || chain.get().stream()
                        .anyMatch(call -> FETCH_PLAN_METHODS.contains(call.getNameAsString()));
                if (planned) {
                    continue;
                }
                Set<String> references = referencesOf(chain.get().get(chain.get().size() - 1), classes);
                if (references.isEmpty()) {
                    continue;
                }
                for (Element element : elementsOf(load)) {
                    firstReference(element, references).ifPresent(access -> violations.add(violation(sourceFile, access,
                            "Связь '" + property(access) + "' читается в цикле у сущностей, загруженных без плана"
                                    + " выборки: для каждого элемента Jmix выполнит отдельный запрос (N+1);"
                                    + " добавьте в загрузку fetchPlan со связью '" + property(access) + "'")));
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
        return ErrorType.PERFORMANCE;
    }

    /**
     * @param name имя переменной, в которой на каждом шаге лежит очередная сущность
     * @param body код, который выполняется для каждой сущности
     */
    private record Element(String name, Node body) {
    }

    // dataManager.load(Order.class) -> поля-связи сущности Order
    private Set<String> referencesOf(MethodCallExpr root, ProjectClasses classes) {
        Set<String> references = new HashSet<>();
        if (root.getArguments().isEmpty() || !root.getArgument(0).isClassExpr()) {
            return references;
        }
        Optional<ClassOrInterfaceDeclaration> entity = classes.find(
                LocalTypes.typeName(root.getArgument(0).asClassExpr().getType()));
        entity.ifPresent(found -> found.getFields().stream()
                .filter(field -> Annotations.hasAny(field, REFERENCE_ANNOTATIONS))
                .flatMap(field -> field.getVariables().stream())
                .forEach(variable -> references.add(variable.getNameAsString())));
        return references;
    }

    // Где перебирают загруженный список: for по переменной с ним либо поэлементная лямбда
    private List<Element> elementsOf(MethodCallExpr load) {
        List<Element> elements = new ArrayList<>();
        Optional<Node> parent = load.getParentNode();
        Optional<Node> callable = Nodes.enclosingCallable(load);
        if (parent.isEmpty() || callable.isEmpty()) {
            return elements;
        }
        if (parent.get() instanceof ForEachStmt loop && loop.getIterable() == load) {
            elements.add(new Element(loop.getVariableDeclarator().getNameAsString(), loop.getBody()));
            return elements;
        }
        if (!(parent.get() instanceof VariableDeclarator variable)) {
            return elements;
        }
        String list = variable.getNameAsString();
        for (ForEachStmt loop : callable.get().findAll(ForEachStmt.class)) {
            if (loop.getIterable().toString().equals(list)) {
                elements.add(new Element(loop.getVariableDeclarator().getNameAsString(), loop.getBody()));
            }
        }
        for (MethodCallExpr call : callable.get().findAll(MethodCallExpr.class)) {
            boolean onList = PER_ELEMENT_METHODS.contains(call.getNameAsString()) && rootOf(call).toString().equals(list);
            if (onList && call.getArguments().size() == 1 && call.getArgument(0).isLambdaExpr()) {
                LambdaExpr lambda = call.getArgument(0).asLambdaExpr();
                if (lambda.getParameters().size() == 1) {
                    elements.add(new Element(lambda.getParameter(0).getNameAsString(), lambda.getBody()));
                }
            }
        }
        return elements;
    }

    // order.getCustomer() - чтение связи; сам по себе вызов уже обращается к базе
    private Optional<MethodCallExpr> firstReference(Element element, Set<String> references) {
        return element.body().findAll(MethodCallExpr.class).stream()
                .filter(call -> call.getArguments().isEmpty() && call.getNameAsString().startsWith(GET))
                .filter(call -> call.getScope().filter(scope -> scope.toString().equals(element.name())).isPresent())
                .filter(call -> references.contains(property(call)))
                .findFirst();
    }

    private String property(MethodCallExpr getter) {
        String name = getter.getNameAsString().substring(GET.length());
        return name.isEmpty() ? name : Character.toLowerCase(name.charAt(0)) + name.substring(1);
    }

    // items.stream().map(...) -> items
    private Expression rootOf(MethodCallExpr call) {
        Expression current = call;
        while (current.isMethodCallExpr() && current.asMethodCallExpr().getScope().isPresent()) {
            current = Nodes.unwrap(current.asMethodCallExpr().getScope().get());
        }
        return current;
    }
}
