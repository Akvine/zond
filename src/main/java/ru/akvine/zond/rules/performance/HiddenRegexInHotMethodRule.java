package ru.akvine.zond.rules.performance;

import com.github.javaparser.ast.Node;
import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.expr.MethodCallExpr;
import com.github.javaparser.ast.expr.MethodReferenceExpr;
import com.github.javaparser.ast.stmt.Statement;
import org.springframework.stereotype.Component;
import ru.akvine.zond.enums.Confidence;
import ru.akvine.zond.enums.ErrorLevel;
import ru.akvine.zond.enums.ErrorType;
import ru.akvine.zond.models.SourceFile;
import ru.akvine.zond.models.Violation;
import ru.akvine.zond.rules.AbstractRule;
import ru.akvine.zond.rules.ProjectRule;
import ru.akvine.zond.rules.RuleCodes;
import ru.akvine.zond.rules.support.CallGraph;
import ru.akvine.zond.rules.support.CodeContexts;
import ru.akvine.zond.rules.support.Loops;
import ru.akvine.zond.rules.support.MethodReferences;
import ru.akvine.zond.rules.support.ProjectClasses;
import ru.akvine.zond.rules.support.RegexCalls;
import ru.akvine.zond.rules.support.TestClasses;

import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * {@code value.replaceAll("\\s+", " ")}, {@code code.matches("[A-Z]{3}")} и {@code row.split("\\s+")} компилируют шаблон при
 * каждом вызове. Пока метод вызывают изредка, это мелочь; когда его вызывают на каждом элементе - из цикла или
 * из стрима, - шаблон разбирается столько раз, сколько элементов. Сам цикл с таким вызовом показывает jr:239,
 * здесь - метод, который вызывают из цикла.
 */
@Component
public class HiddenRegexInHotMethodRule extends AbstractRule implements ProjectRule {
    private static final String FIELD_INITIALIZER = "инициализация поля";

    // Откуда метод вызывают на каждом элементе; exact - цель вызова определена однозначно
    private record HotSite(String place, boolean exact) {
    }

    @Override
    public String code() {
        return RuleCodes.HIDDEN_REGEX_IN_HOT_METHOD_RULE_CODE;
    }

    @Override
    public String description() {
        return "Сканирует код и ищет String.matches, replaceAll, replaceFirst и split с постоянным шаблоном в методах,"
                + " которые вызываются на каждом элементе цикла или стрима";
    }

    @Override
    public List<Violation> checkProject(List<SourceFile> sourceFiles) {
        CallGraph graph = CallGraph.of(sourceFiles);
        Map<MethodDeclaration, HotSite> referenced = referencedPerElement(sourceFiles);
        List<Violation> violations = new ArrayList<>();
        for (SourceFile sourceFile : sourceFiles) {
            // name.replaceAll(...).replaceAll(...) - одна находка на выражение, а не на каждое звено
            // Узлы сравниваются по содержимому, а одинаковые выражения в разных методах - разные находки,
            // поэтому таблица различает операторы по ссылке
            Map<Node, List<MethodCallExpr>> byStatement = new IdentityHashMap<>();
            List<List<MethodCallExpr>> groups = new ArrayList<>();
            for (MethodCallExpr call : sourceFile.unit().findAll(MethodCallExpr.class)) {
                // Шаблон прямо в цикле - находка jr:239
                if (RegexCalls.compilesConstantRegex(call) && !Loops.isRepeated(call) && !CodeContexts.isStartup(call)
                        && !TestClasses.isInside(call)) {
                    Node statement = call.findAncestor(Statement.class).map(Node.class::cast).orElse(call);
                    byStatement.computeIfAbsent(statement, key -> {
                        List<MethodCallExpr> group = new ArrayList<>();
                        groups.add(group);
                        return group;
                    }).add(call);
                }
            }
            for (List<MethodCallExpr> calls : groups) {
                calls.stream().findFirst()
                        .flatMap(first -> first.findAncestor(MethodDeclaration.class))
                        .flatMap(method -> findHotSite(method, graph, referenced)
                                .map(site -> report(sourceFile, calls, method, site)))
                        .ifPresent(violations::add);
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
        return ErrorType.PERFORMANCE;
    }

    private Violation report(SourceFile sourceFile, List<MethodCallExpr> calls, MethodDeclaration method,
                             HotSite site) {
        // В цепочке первым найден внешний вызов, а читают ее с внутреннего
        MethodCallExpr call = calls.get(calls.size() - 1);
        String others = calls.size() > 1 ? " и еще " + (calls.size() - 1) + " в этом выражении" : "";
        boolean plainText = calls.stream().allMatch(RegexCalls::replacesPlainText);
        Violation violation = violation(sourceFile, call.getName(),
                "'" + call.getNameAsString() + "(" + call.getArgument(0)
                        + (call.getArguments().size() > 1 ? ", ..." : "") + ")'" + others
                        + ": регулярное выражение компилируется при каждом вызове, а метод '"
                        + method.getNameAsString() + "' вызывается на каждом элементе (" + site.place() + "), и шаблон"
                        + " разбирается столько раз, сколько элементов; " + (plainText
                        ? "здесь ищется обычный текст - замените вызов на replace(...), ему регулярные выражения"
                        + " не нужны"
                        : "вынесите Pattern.compile(...) в private static final и используйте его matcher(...)"
                        + " или split(...)"));
        return site.exact() ? violation : violation.withConfidence(Confidence.PROBABLE);
    }

    // items.stream().map(this::normalize), names.forEach(Cleaner::clean)
    private Map<MethodDeclaration, HotSite> referencedPerElement(List<SourceFile> sourceFiles) {
        ProjectClasses classes = ProjectClasses.of(sourceFiles);
        Map<MethodDeclaration, HotSite> referenced = new IdentityHashMap<>();
        for (SourceFile sourceFile : sourceFiles) {
            for (MethodReferenceExpr reference : sourceFile.unit().findAll(MethodReferenceExpr.class)) {
                if (MethodReferences.perElementOperation(reference).isEmpty() || CodeContexts.isStartup(reference)
                        || TestClasses.isInside(reference)) {
                    continue;
                }
                List<MethodDeclaration> targets = MethodReferences.targets(reference, classes);
                for (MethodDeclaration target : targets) {
                    referenced.putIfAbsent(target, new HotSite(
                            reference.findAncestor(MethodDeclaration.class).map(this::place).orElse(FIELD_INITIALIZER),
                            targets.size() == 1));
                }
            }
        }
        return referenced;
    }

    private Optional<HotSite> findHotSite(MethodDeclaration method, CallGraph graph,
                                          Map<MethodDeclaration, HotSite> referenced) {
        Optional<HotSite> fromLoop = graph.callsTo(method).stream()
                .filter(call -> call.caller() != method && CodeContexts.isRepeatedOverData(call.site())
                        && !CodeContexts.isStartup(call.site()) && !TestClasses.isInside(call.site()))
                .findFirst()
                .map(call -> new HotSite(place(call.caller()), graph.isExact(call.site())));
        return fromLoop.isPresent() ? fromLoop : Optional.ofNullable(referenced.get(method));
    }

    private String place(MethodDeclaration caller) {
        return caller.findAncestor(ClassOrInterfaceDeclaration.class)
                .map(type -> type.getNameAsString() + ".")
                .orElse("") + caller.getNameAsString();
    }
}
