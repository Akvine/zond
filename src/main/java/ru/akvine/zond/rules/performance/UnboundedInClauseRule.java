package ru.akvine.zond.rules.performance;

import com.github.javaparser.ast.Node;
import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.body.Parameter;
import com.github.javaparser.ast.body.VariableDeclarator;
import com.github.javaparser.ast.expr.Expression;
import com.github.javaparser.ast.expr.MethodCallExpr;
import com.github.javaparser.ast.expr.NameExpr;
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
import ru.akvine.zond.rules.support.Repositories;
import ru.akvine.zond.rules.support.TestClasses;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Список неограниченной длины в условии IN. У Oracle в IN не больше 1000 значений, у PostgreSQL и SQL Server
 * ограничено число параметров запроса, а Hibernate хранит отдельный план запроса на каждую длину списка.
 * Пока данных мало, запрос работает; с ростом таблицы он начинает падать.
 */
@Component
public class UnboundedInClauseRule extends AbstractRule implements ProjectRule {
    private static final Set<String> BY_ID_METHODS = Set.of(
            "findAllById", "deleteAllById", "deleteAllByIdInBatch", "existsAllById");
    // findByStatusIn, deleteByOwnerIdNotIn, findByIdInAndActiveTrue
    private static final Pattern DERIVED_IN = Pattern.compile(
            "^(find|read|get|query|search|stream|count|exists|delete|remove)\\w*By\\w*[a-z0-9](Not)?In"
                    + "($|And\\p{Upper}.*|Or\\p{Upper}.*|OrderBy.*)");
    // ... where o.id in (:ids), in :ids, in (?1)
    private static final Pattern QUERY_IN = Pattern.compile("\\bin\\s*\\(?\\s*[:?]", Pattern.CASE_INSENSITIVE);
    // Чтение из репозитория без постраничного предела
    private static final Pattern READ = Pattern.compile("^(find|read|get|query|search|load|list)\\w*");
    private static final Set<String> PAGE_TYPES = Set.of("Pageable", "PageRequest", "Limit");
    private static final Set<String> COLLECTIONS = Set.of("List", "Set", "Collection", "Iterable", "ArrayList", "HashSet");
    // Список делят на части: длина каждой известна
    private static final Set<String> PARTITIONING = Set.of(
            "partition", "chunk", "chunked", "batch", "batched", "windowed", "subList", "split");
    private static final Set<String> REQUEST_DATA = Set.of("RequestBody", "RequestParam");
    private static final Set<String> MAPPINGS = Set.of(
            "RequestMapping", "GetMapping", "PostMapping", "PutMapping", "PatchMapping", "DeleteMapping");
    private static final String SIZE = "Size";

    @Override
    public String code() {
        return RuleCodes.UNBOUNDED_IN_CLAUSE_RULE_CODE;
    }

    @Override
    public String description() {
        return "Сканирует проект и ищет запросы с IN, в которые передают список неограниченной длины: из"
                + " результата другого запроса либо из тела HTTP-запроса";
    }

    // Список заведомо может вырасти, но насколько - по коду не узнать
    @Override
    public Confidence confidence() {
        return Confidence.SUSPICION;
    }

    @Override
    public List<Violation> checkProject(List<SourceFile> sourceFiles) {
        ProjectClasses classes = ProjectClasses.of(sourceFiles);
        List<Violation> violations = new ArrayList<>();
        for (SourceFile sourceFile : sourceFiles) {
            for (MethodCallExpr call : sourceFile.unit().findAll(MethodCallExpr.class)) {
                if (call.getArguments().isEmpty() || !isInQuery(call, classes) || TestClasses.isInside(call)
                        || isPartitioned(call)) {
                    continue;
                }
                call.getArguments().stream()
                        .map(argument -> describeUnbounded(argument).map(reason -> new String[] {Nodes.text(argument), reason}))
                        .flatMap(Optional::stream)
                        .findFirst()
                        .ifPresent(found -> violations.add(violation(sourceFile, call,
                                "Список для IN в '" + call.getScope().map(scope -> Nodes.text(scope) + ".").orElse("")
                                        + call.getNameAsString() + "(...)' ничем не ограничен: " + found[1]
                                        + ". У Oracle в IN не больше 1000 значений (ORA-01795), у PostgreSQL и"
                                        + " SQL Server ограничено число параметров запроса, а Hibernate хранит"
                                        + " отдельный план на каждую длину списка; делите список на части по"
                                        + " 500-1000 значений (Lists.partition) либо замените IN соединением с"
                                        + " подзапросом")));
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

    // findAllById(ids), findByStatusIn(statuses) либо метод с @Query, где есть in (:ids)
    private boolean isInQuery(MethodCallExpr call, ProjectClasses classes) {
        if (!Repositories.isRepositoryCall(call)) {
            return false;
        }
        String name = call.getNameAsString();
        if (BY_ID_METHODS.contains(name) || DERIVED_IN.matcher(name).matches()) {
            return true;
        }
        Optional<ClassOrInterfaceDeclaration> repository = call.getScope().flatMap(LocalTypes::typeOf).flatMap(classes::find);
        return repository.stream()
                .flatMap(type -> type.getMethodsByName(name).stream())
                .anyMatch(method -> Queries.find(method).flatMap(Queries::text)
                        .filter(text -> QUERY_IN.matcher(text).find()).isPresent());
    }

    /**
     * @return откуда взялся список, если его длину ничто не ограничивает
     */
    private Optional<String> describeUnbounded(Expression argument) {
        for (NameExpr name : names(argument)) {
            Optional<Node> declaration = LocalTypes.findDeclaration(name);
            if (declaration.filter(found -> found instanceof Parameter).isPresent()) {
                Parameter parameter = (Parameter) declaration.get();
                if (isRequestCollection(parameter)) {
                    return Optional.of("'" + name.getNameAsString() + "' приходит из HTTP-запроса, и его длину"
                            + " задает клиент");
                }
            }
            Optional<Expression> source = declaration.filter(found -> found instanceof VariableDeclarator)
                    .flatMap(found -> ((VariableDeclarator) found).getInitializer());
            Optional<MethodCallExpr> read = source.flatMap(this::unpagedRead);
            if (read.isPresent()) {
                return Optional.of("'" + name.getNameAsString() + "' собран из результата '"
                        + read.get().getScope().map(scope -> Nodes.text(scope) + ".").orElse("") + read.get().getNameAsString()
                        + "(...)' - в нем столько значений, сколько строк вернул тот запрос");
            }
        }
        return Optional.empty();
    }

    // Сам аргумент либо переменные, из которых он собран: orders.stream().map(Order::getId).toList()
    private List<NameExpr> names(Expression argument) {
        Expression value = Nodes.unwrap(argument);
        return value.isNameExpr() ? List.of(value.asNameExpr()) : value.findAll(NameExpr.class);
    }

    /**
     * @return чтение из репозитория без постраничного предела, из которого получено значение, - напрямую либо
     * через одну промежуточную переменную: ids = orders.stream()..., orders = repository.findByStatus(...)
     */
    private Optional<MethodCallExpr> unpagedRead(Expression value) {
        Optional<MethodCallExpr> direct = value.findAll(MethodCallExpr.class).stream().filter(this::isUnpagedRead).findFirst();
        if (direct.isPresent()) {
            return direct;
        }
        return value.findAll(NameExpr.class).stream()
                .map(LocalTypes::findInitializer)
                .flatMap(Optional::stream)
                .flatMap(initializer -> initializer.findAll(MethodCallExpr.class).stream())
                .filter(this::isUnpagedRead)
                .findFirst();
    }

    private boolean isUnpagedRead(MethodCallExpr call) {
        boolean paged = call.getArguments().stream().anyMatch(argument -> argument.toString().contains("PageRequest")
                || LocalTypes.typeOf(argument).filter(PAGE_TYPES::contains).isPresent());
        return Repositories.isRepositoryCall(call) && READ.matcher(call.getNameAsString()).matches()
                && !call.getNameAsString().endsWith("ById") && !paged;
    }

    // @RequestBody List<Long> ids, @RequestParam Set<String> codes без @Size
    private boolean isRequestCollection(Parameter parameter) {
        boolean handler = parameter.getParentNode()
                .filter(parent -> parent instanceof MethodDeclaration method && Annotations.hasAny(method, MAPPINGS))
                .isPresent();
        return COLLECTIONS.contains(LocalTypes.typeName(parameter.getType())) && !Annotations.has(parameter, SIZE)
                && (Annotations.hasAny(parameter, REQUEST_DATA) || handler);
    }

    // Lists.partition(ids, 500), ids.subList(from, to): запрос получает часть известной длины
    private boolean isPartitioned(MethodCallExpr call) {
        return Nodes.enclosingCallable(call).stream()
                .flatMap(callable -> callable.findAll(MethodCallExpr.class).stream())
                .anyMatch(step -> PARTITIONING.contains(step.getNameAsString()));
    }
}
