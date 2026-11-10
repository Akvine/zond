package ru.akvine.zond.rules.logical;

import com.github.javaparser.ast.Node;
import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.body.Parameter;
import com.github.javaparser.ast.body.VariableDeclarator;
import com.github.javaparser.ast.expr.Expression;
import com.github.javaparser.ast.expr.MethodCallExpr;
import org.springframework.stereotype.Component;
import ru.akvine.zond.enums.Confidence;
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
import ru.akvine.zond.rules.support.Queries;
import ru.akvine.zond.rules.support.StreamChains;
import ru.akvine.zond.rules.support.StringLiterals;
import ru.akvine.zond.rules.support.TestClasses;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Постраничная выдача без сортировки. Порядок строк без ORDER BY база не гарантирует: он зависит от плана
 * запроса и меняется между вызовами. Страницы при этом нарезаются из разных порядков - одна и та же запись
 * попадает на две страницы, а другая не попадает ни на одну.
 */
@Component
public class PaginationWithoutSortRule extends AbstractRule implements ProjectRule {
    private static final String PAGE_REQUEST = "PageRequest";
    private static final String OF = "of";
    private static final String WITH_PAGE = "withPage";
    private static final String OF_SIZE = "ofSize";
    private static final String FIRST_PAGE = "0";
    private static final int PAGE_AND_SIZE = 2;
    private static final String UNSORTED = "unsorted";
    private static final String ORDER_BY_IN_NAME = "OrderBy";
    private static final Pattern ORDER_BY = Pattern.compile("\\border\\s+by\\b", Pattern.CASE_INSENSITIVE);
    // ... LIMIT ? OFFSET ?, OFFSET 20 ROWS, MySQL LIMIT ?, ?
    private static final Pattern SQL_OFFSET = Pattern.compile(
            "\\boffset\\s+[?:\\d]|\\blimit\\s+[?:]\\w*\\s*,\\s*[?:]", Pattern.CASE_INSENSITIVE);
    private static final Pattern SELECT = Pattern.compile("\\bselect\\b.*\\bfrom\\b", Pattern.CASE_INSENSITIVE | Pattern.DOTALL);

    private static final Set<String> CONTROLLERS = Set.of("RestController", "Controller");
    private static final Set<String> MAPPINGS = Set.of(
            "RequestMapping", "GetMapping", "PostMapping", "PutMapping", "PatchMapping", "DeleteMapping");
    private static final String PAGEABLE = "Pageable";
    private static final String SORT_DEFAULT = "SortDefault";
    private static final String PAGEABLE_DEFAULT = "PageableDefault";
    private static final String SORT_MEMBER = "sort";
    private static final String CONSEQUENCE = ": порядок строк без ORDER BY база не гарантирует, он зависит от плана"
            + " запроса и меняется от вызова к вызову. Страницы нарезаются из разных порядков - одна запись попадает"
            + " на две страницы, а другая не попадает ни на одну; ";

    @Override
    public String code() {
        return RuleCodes.PAGINATION_WITHOUT_SORT_RULE_CODE;
    }

    @Override
    public String description() {
        return "Сканирует проект и ищет постраничную выдачу без сортировки: PageRequest.of(page, size) без Sort,"
                + " OFFSET без ORDER BY и Pageable в обработчике без сортировки по умолчанию";
    }

    @Override
    public List<Violation> checkProject(List<SourceFile> sourceFiles) {
        ProjectClasses classes = ProjectClasses.of(sourceFiles);
        List<Violation> violations = new ArrayList<>();
        for (SourceFile sourceFile : sourceFiles) {
            for (MethodCallExpr call : sourceFile.unit().findAll(MethodCallExpr.class)) {
                if (isUnsortedPage(call) && !TestClasses.isInside(call) && !isSortedByQuery(call, classes)) {
                    violations.add(violation(sourceFile, call,
                            "'" + Nodes.text(call) + "' - страница без сортировки" + CONSEQUENCE
                                    + "задайте порядок по уникальному полю: PageRequest.of(page, size, Sort.by(\"id\"))"));
                }
            }
            for (StringLiterals.LiteralText literal : StringLiterals.findComplete(sourceFile.unit())) {
                String text = literal.text();
                boolean unsorted = SQL_OFFSET.matcher(text).find() && SELECT.matcher(text).find()
                        && !ORDER_BY.matcher(text).find();
                if (unsorted && !TestClasses.isInside(literal.node())) {
                    violations.add(violation(sourceFile, literal.node(),
                            "Запрос с OFFSET без ORDER BY" + CONSEQUENCE
                                    + "добавьте ORDER BY по уникальной колонке либо по набору колонок, который"
                                    + " однозначно задает порядок").withConfidence(Confidence.CONFIRMED));
                }
            }
            for (Parameter parameter : sourceFile.unit().findAll(Parameter.class)) {
                if (isUnsortedHandlerPage(parameter)) {
                    violations.add(violation(sourceFile, parameter,
                            "Параметр '" + parameter.getNameAsString() + "' обработчика запроса без сортировки по"
                                    + " умолчанию: если клиент не передал sort, запрос уйдет без ORDER BY" + CONSEQUENCE
                                    + "задайте порядок: @PageableDefault(sort = \"id\") либо @SortDefault")
                            .withLevel(ErrorLevel.MINOR).withConfidence(Confidence.SUSPICION));
                }
            }
        }
        violations.sort(Comparator.comparing((Violation found) -> found.file().toString()).thenComparingInt(Violation::line));
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

    // PageRequest.of(page, size), PageRequest.of(page, size, Sort.unsorted()), Pageable.ofSize(size).withPage(page)
    private boolean isUnsortedPage(MethodCallExpr call) {
        String name = call.getNameAsString();
        if (WITH_PAGE.equals(name) && call.getArguments().size() == 1) {
            boolean plain = StreamChains.callsBefore(call).stream().anyMatch(step -> OF_SIZE.equals(step.getNameAsString()));
            return plain && !isFirstPage(call.getArgument(0));
        }
        boolean pageRequest = OF.equals(name) && call.getScope().filter(scope -> PAGE_REQUEST.equals(scope.toString())).isPresent();
        if (!pageRequest || call.getArguments().size() < PAGE_AND_SIZE || isFirstPage(call.getArgument(0))) {
            return false;
        }
        // Первая страница без сортировки - это просто "любые N строк", а не постраничная выдача
        return call.getArguments().size() == PAGE_AND_SIZE || call.getArgument(PAGE_AND_SIZE).toString().contains(UNSORTED);
    }

    private boolean isFirstPage(Expression page) {
        return FIRST_PAGE.equals(Nodes.unwrap(page).toString());
    }

    /**
     * Порядок задан самим запросом: страницу передают только методам с OrderBy в имени либо с ORDER BY в @Query
     */
    private boolean isSortedByQuery(MethodCallExpr page, ProjectClasses classes) {
        List<MethodCallExpr> consumers = new ArrayList<>();
        Optional<Node> parent = page.getParentNode();
        if (parent.filter(found -> found instanceof MethodCallExpr).isPresent()) {
            consumers.add((MethodCallExpr) parent.get());
        } else if (parent.filter(found -> found instanceof VariableDeclarator).isPresent()) {
            String variable = ((VariableDeclarator) parent.get()).getNameAsString();
            Nodes.enclosingCallable(page).ifPresent(callable -> callable.findAll(MethodCallExpr.class).stream()
                    .filter(call -> call.getArguments().stream().anyMatch(argument -> argument.toString().equals(variable)))
                    .forEach(consumers::add));
        }
        return !consumers.isEmpty() && consumers.stream().allMatch(consumer -> isSorted(consumer, classes));
    }

    private boolean isSorted(MethodCallExpr query, ProjectClasses classes) {
        if (query.getNameAsString().contains(ORDER_BY_IN_NAME)) {
            return true;
        }
        Optional<ClassOrInterfaceDeclaration> repository = query.getScope().flatMap(LocalTypes::typeOf).flatMap(classes::find);
        return repository.stream()
                .flatMap(type -> type.getMethodsByName(query.getNameAsString()).stream())
                .anyMatch(this::hasOrderBy);
    }

    private boolean hasOrderBy(MethodDeclaration method) {
        return Queries.find(method).flatMap(Queries::text).filter(text -> ORDER_BY.matcher(text).find()).isPresent();
    }

    // Pageable в методе контроллера без @SortDefault и без @PageableDefault(sort = ...)
    private boolean isUnsortedHandlerPage(Parameter parameter) {
        if (!PAGEABLE.equals(LocalTypes.typeName(parameter.getType())) || Annotations.has(parameter, SORT_DEFAULT)) {
            return false;
        }
        boolean sortedByDefault = Annotations.find(parameter, PAGEABLE_DEFAULT)
                .flatMap(annotation -> Queries.member(annotation, SORT_MEMBER))
                .isPresent();
        Optional<MethodDeclaration> method = parameter.getParentNode()
                .filter(parent -> parent instanceof MethodDeclaration)
                .map(parent -> (MethodDeclaration) parent);
        boolean handler = method.filter(found -> Annotations.hasAny(found, MAPPINGS)).isPresent()
                && method.flatMap(found -> found.findAncestor(ClassOrInterfaceDeclaration.class))
                .filter(type -> Annotations.hasAny(type, CONTROLLERS))
                .isPresent();
        return handler && !sortedByDefault;
    }
}
