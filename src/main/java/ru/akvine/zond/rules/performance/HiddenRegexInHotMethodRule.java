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
import ru.akvine.zond.rules.support.LocalTypes;
import ru.akvine.zond.rules.support.Loops;
import ru.akvine.zond.rules.support.MethodReferences;
import ru.akvine.zond.rules.support.Nodes;
import ru.akvine.zond.rules.support.ProjectClasses;
import ru.akvine.zond.rules.support.TestClasses;

import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * {@code value.replaceAll("\\s+", " ")} и {@code code.matches("[A-Z]{3}")} внутри метода компилируют шаблон при
 * каждом вызове. Пока метод вызывают изредка, это мелочь; когда его вызывают на каждом элементе - из цикла или
 * из стрима, - шаблон разбирается столько раз, сколько элементов. Сам цикл с таким вызовом показывает jr:239,
 * здесь - метод, который вызывают из цикла.
 */
@Component
public class HiddenRegexInHotMethodRule extends AbstractRule implements ProjectRule {
    private static final Set<String> REPLACING = Set.of("replaceAll", "replaceFirst");
    private static final String MATCHES = "matches";
    private static final String MATCHER = "matcher";
    private static final String MATCHER_TYPE = "Matcher";
    private static final String FIELD_INITIALIZER = "инициализация поля";
    private static final Pattern REGEX_SYNTAX = Pattern.compile("[\\\\\\[\\](){}.*+?^$|]");

    // Откуда метод вызывают на каждом элементе; exact - цель вызова определена однозначно
    private record HotSite(String place, boolean exact) {
    }

    @Override
    public String code() {
        return RuleCodes.HIDDEN_REGEX_IN_HOT_METHOD_RULE_CODE;
    }

    @Override
    public String description() {
        return "Сканирует код и ищет String.matches, replaceAll и replaceFirst с постоянным шаблоном в методах,"
                + " которые вызываются на каждом элементе цикла или стрима";
    }

    @Override
    public List<Violation> checkProject(List<SourceFile> sourceFiles) {
        CallGraph graph = CallGraph.of(sourceFiles);
        Map<MethodDeclaration, HotSite> referenced = referencedPerElement(sourceFiles);
        List<Violation> violations = new ArrayList<>();
        for (SourceFile sourceFile : sourceFiles) {
            // name.replaceAll(...).replaceAll(...) - одна находка на выражение, а не на каждое звено
            Map<Node, List<MethodCallExpr>> byStatement = new LinkedHashMap<>();
            for (MethodCallExpr call : sourceFile.unit().findAll(MethodCallExpr.class)) {
                // Шаблон прямо в цикле - находка jr:239
                if (compilesConstantRegex(call) && !Loops.isRepeated(call) && !CodeContexts.isStartup(call)
                        && !TestClasses.isInside(call)) {
                    byStatement.computeIfAbsent(call.findAncestor(Statement.class).map(Node.class::cast).orElse(call),
                            key -> new ArrayList<>()).add(call);
                }
            }
            for (List<MethodCallExpr> calls : byStatement.values()) {
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
        boolean plainText = calls.stream().noneMatch(this::needsRegex);
        Violation violation = violation(sourceFile, call.getName(),
                "'" + call.getNameAsString() + "(" + call.getArgument(0)
                        + (call.getArguments().size() > 1 ? ", ..." : "") + ")'" + others
                        + ": регулярное выражение компилируется при каждом вызове, а метод '"
                        + method.getNameAsString() + "' вызывается на каждом элементе (" + site.place() + "), и шаблон"
                        + " разбирается столько раз, сколько элементов; " + (plainText
                        ? "здесь ищется обычный текст - замените вызов на replace(...), ему регулярные выражения"
                        + " не нужны"
                        : "вынесите Pattern.compile(...) в private static final и используйте matcher(...)"));
        return site.exact() ? violation : violation.withConfidence(Confidence.PROBABLE);
    }

    // replaceAll(" ", "") ищет обычный текст, replaceAll("\\s+", " ") - шаблон
    private boolean needsRegex(MethodCallExpr call) {
        return MATCHES.equals(call.getNameAsString())
                || REGEX_SYNTAX.matcher(call.getArgument(0).asStringLiteralExpr().asString()).find();
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

    private boolean compilesConstantRegex(MethodCallExpr call) {
        if (call.getArguments().isEmpty() || !call.getArgument(0).isStringLiteralExpr() || call.getScope().isEmpty()) {
            return false;
        }
        boolean regexMethod = REPLACING.contains(call.getNameAsString())
                || MATCHES.equals(call.getNameAsString()) && call.getArguments().size() == 1;
        return regexMethod && !isOnMatcher(call);
    }

    // SPACES.matcher(text).replaceAll(" "): шаблон уже скомпилирован, а строка в аргументе - замена
    private boolean isOnMatcher(MethodCallExpr call) {
        return call.getScope()
                .map(Nodes::unwrap)
                .filter(scope -> scope.isMethodCallExpr() && MATCHER.equals(scope.asMethodCallExpr().getNameAsString())
                        || LocalTypes.typeOf(scope).filter(MATCHER_TYPE::equals).isPresent())
                .isPresent();
    }
}
