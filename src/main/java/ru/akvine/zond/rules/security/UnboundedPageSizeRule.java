package ru.akvine.zond.rules.security;

import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.Node;
import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.body.Parameter;
import com.github.javaparser.ast.expr.AssignExpr;
import com.github.javaparser.ast.expr.BinaryExpr;
import com.github.javaparser.ast.expr.Expression;
import com.github.javaparser.ast.expr.MethodCallExpr;
import org.springframework.stereotype.Component;
import ru.akvine.zond.enums.ErrorLevel;
import ru.akvine.zond.enums.ErrorType;
import ru.akvine.zond.models.SourceFile;
import ru.akvine.zond.models.Violation;
import ru.akvine.zond.rules.AbstractRule;
import ru.akvine.zond.rules.ProjectRule;
import ru.akvine.zond.rules.RuleCodes;
import ru.akvine.zond.rules.support.Annotations;
import ru.akvine.zond.rules.support.CallGraph;
import ru.akvine.zond.rules.support.Handlers;
import ru.akvine.zond.rules.support.LocalTypes;
import ru.akvine.zond.rules.support.Nodes;
import ru.akvine.zond.rules.support.ProjectClasses;

import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Размер страницы, который задает клиент. Pageable в параметре обработчика Spring ограничивает сам,
 * а PageRequest.of(page, size), собранный вручную, примет любое число.
 */
@Component
public class UnboundedPageSizeRule extends AbstractRule implements ProjectRule {
    private static final Set<String> PAGE_TYPES = Set.of("PageRequest", "Pageable");
    private static final String OF = "of";
    private static final String OF_SIZE = "ofSize";
    private static final int SIZE_ARGUMENT = 1;
    private static final Set<String> BOUNDS = Set.of("Max", "Range");
    private static final Set<String> CLAMP_METHODS = Set.of("min", "clamp");
    private static final Set<BinaryExpr.Operator> COMPARISONS = Set.of(
            BinaryExpr.Operator.GREATER, BinaryExpr.Operator.GREATER_EQUALS, BinaryExpr.Operator.LESS,
            BinaryExpr.Operator.LESS_EQUALS);
    private static final Pattern GETTER = Pattern.compile("^get([A-Z].*)$");

    @Override
    public String code() {
        return RuleCodes.UNBOUNDED_PAGE_SIZE_RULE_CODE;
    }

    @Override
    public String description() {
        return "Сканирует код и ищет размер страницы, который приходит из запроса и ничем не ограничен";
    }

    @Override
    public List<Violation> checkProject(List<SourceFile> sourceFiles) {
        ProjectClasses classes = ProjectClasses.of(sourceFiles);
        CallGraph graph = CallGraph.of(sourceFiles);
        Map<CompilationUnit, SourceFile> files = new IdentityHashMap<>();
        sourceFiles.forEach(sourceFile -> files.put(sourceFile.unit(), sourceFile));
        List<Violation> violations = new ArrayList<>();
        for (SourceFile sourceFile : sourceFiles) {
            for (MethodCallExpr page : sourceFile.unit().findAll(MethodCallExpr.class)) {
                Optional<Expression> size = sizeOf(page);
                Optional<MethodDeclaration> method = page.findAncestor(MethodDeclaration.class);
                if (size.isPresent() && method.isPresent()) {
                    check(sourceFile, page, Nodes.unwrap(size.get()), method.get(), classes, graph, files, violations);
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
        return ErrorType.SECURITY;
    }

    private void check(
            SourceFile sourceFile, MethodCallExpr page, Expression size, MethodDeclaration method,
            ProjectClasses classes, CallGraph graph, Map<CompilationUnit, SourceFile> files, List<Violation> violations) {
        // Адрес и параметры запроса могут быть объявлены в интерфейсе, который реализует контроллер
        Optional<Handlers.Handler> found = Handlers.of(method, classes);
        boolean handler = found.isPresent();
        if (size.isNameExpr()) {
            Optional<Parameter> parameter = method.getParameterByName(size.asNameExpr().getNameAsString());
            if (parameter.isEmpty() || isLimited(parameter.get(), method, found)) {
                return;
            }
            if (handler) {
                violations.add(violation(sourceFile, page, message(parameter.get().getNameAsString())));
            } else {
                reportCallers(method, parameter.get(), classes, graph, files, violations);
            }
            return;
        }
        // request.getSize(): размер лежит в объекте запроса, и ограничить его можно на поле
        if (handler && size.isMethodCallExpr() && isUnlimitedProperty(size.asMethodCallExpr(), method, classes)) {
            violations.add(violation(sourceFile, page, message(size.toString())));
        }
    }

    // Страницу собирает сервис, а размер ему передал обработчик прямо из параметра запроса
    private void reportCallers(
            MethodDeclaration service, Parameter size, ProjectClasses classes, CallGraph graph,
            Map<CompilationUnit, SourceFile> files, List<Violation> violations) {
        int index = service.getParameters().indexOf(size);
        for (CallGraph.Call call : graph.callsTo(service)) {
            Optional<Handlers.Handler> caller = Handlers.of(call.caller(), classes);
            if (index < 0 || index >= call.site().getArguments().size() || caller.isEmpty()) {
                continue;
            }
            Expression passed = Nodes.unwrap(call.site().getArgument(index));
            Optional<Parameter> source = passed.isNameExpr()
                    ? call.caller().getParameterByName(passed.asNameExpr().getNameAsString())
                    : Optional.empty();
            Optional<SourceFile> file = call.site().findCompilationUnit().map(files::get);
            if (source.isPresent() && file.isPresent() && !isLimited(source.get(), call.caller(), caller)) {
                violations.add(violation(file.get(), call.site(), message(source.get().getNameAsString())));
            }
        }
    }

    private String message(String size) {
        return "Размер страницы '" + size + "' приходит из запроса и ничем не ограничен: один запрос"
                + " с размером в миллион записей выгрузит в память всю таблицу; ограничьте его сверху"
                + " (@Max на параметре либо Math.min(size, предел))";
    }

    /**
     * @return выражение с размером страницы, если вызов - PageRequest.of(page, size) или Pageable.ofSize(size)
     */
    private Optional<Expression> sizeOf(MethodCallExpr call) {
        boolean onPage = call.getScope().filter(scope -> PAGE_TYPES.contains(scope.toString())).isPresent();
        if (!onPage) {
            return Optional.empty();
        }
        if (OF.equals(call.getNameAsString()) && call.getArguments().size() > SIZE_ARGUMENT) {
            return Optional.of(call.getArgument(SIZE_ARGUMENT));
        }
        return OF_SIZE.equals(call.getNameAsString()) && call.getArguments().size() == 1
                ? Optional.of(call.getArgument(0))
                : Optional.empty();
    }

    private boolean isLimited(Parameter parameter, MethodDeclaration method, Optional<Handlers.Handler> handler) {
        return Annotations.hasAny(parameter, BOUNDS)
                || handler.filter(found -> found.hasAnnotation(parameter, BOUNDS)).isPresent()
                || isChecked(method, parameter.getNameAsString());
    }

    // Math.min(size, 100), if (size > 100), size = ...: значение проверили или заменили
    private boolean isChecked(Node method, String text) {
        boolean clamped = method.findAll(MethodCallExpr.class).stream()
                .filter(call -> CLAMP_METHODS.contains(call.getNameAsString()))
                .anyMatch(call -> call.getArguments().stream().anyMatch(argument -> argument.toString().equals(text)));
        boolean compared = method.findAll(BinaryExpr.class).stream()
                .filter(binary -> COMPARISONS.contains(binary.getOperator()))
                .anyMatch(binary -> binary.getLeft().toString().equals(text) || binary.getRight().toString().equals(text));
        boolean replaced = method.findAll(AssignExpr.class).stream()
                .anyMatch(assignment -> assignment.getTarget().toString().equals(text));
        return clamped || compared || replaced;
    }

    private boolean isUnlimitedProperty(MethodCallExpr getter, MethodDeclaration handler, ProjectClasses classes) {
        Matcher name = GETTER.matcher(getter.getNameAsString());
        Optional<Parameter> owner = getter.getScope()
                .filter(Expression::isNameExpr)
                .flatMap(scope -> handler.getParameterByName(scope.asNameExpr().getNameAsString()));
        if (!name.matches() || owner.isEmpty() || isChecked(handler, getter.toString())) {
            return false;
        }
        String property = Character.toLowerCase(name.group(1).charAt(0)) + name.group(1).substring(1);
        // Класс запроса вне проекта: есть ли на поле ограничение, неизвестно
        Optional<ClassOrInterfaceDeclaration> type = classes.find(LocalTypes.typeName(owner.get().getType()));
        return type.flatMap(found -> found.getFieldByName(property))
                .filter(field -> !Annotations.hasAny(field, BOUNDS))
                .isPresent();
    }
}
