package ru.akvine.zond.rules.performance;

import com.github.javaparser.ast.Node;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.body.TypeDeclaration;
import com.github.javaparser.ast.expr.AssignExpr;
import com.github.javaparser.ast.expr.Expression;
import com.github.javaparser.ast.expr.LambdaExpr;
import com.github.javaparser.ast.expr.MethodCallExpr;
import com.github.javaparser.ast.expr.NameExpr;
import com.github.javaparser.ast.stmt.BlockStmt;
import com.github.javaparser.ast.stmt.Statement;
import org.springframework.stereotype.Component;
import ru.akvine.zond.enums.Confidence;
import ru.akvine.zond.enums.ErrorLevel;
import ru.akvine.zond.enums.ErrorType;
import ru.akvine.zond.models.SourceFile;
import ru.akvine.zond.models.TestPaths;
import ru.akvine.zond.models.Violation;
import ru.akvine.zond.rules.AbstractRule;
import ru.akvine.zond.rules.ProjectRule;
import ru.akvine.zond.rules.RuleCodes;
import ru.akvine.zond.rules.support.Annotations;
import ru.akvine.zond.rules.support.CallGraph;
import ru.akvine.zond.rules.support.JdbcBatches;
import ru.akvine.zond.rules.support.LocalTypes;
import ru.akvine.zond.rules.support.Loops;
import ru.akvine.zond.rules.support.MethodCalls;
import ru.akvine.zond.rules.support.Nodes;
import ru.akvine.zond.rules.support.Repositories;
import ru.akvine.zond.rules.support.TestClasses;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;
import java.util.regex.Pattern;

/**
 * Метод вызывает один за другим два метода, и каждый из них внутри выполняет один и тот же запрос с теми же
 * аргументами: {@code findValue(key)} читает имя таблицы по ключу, и следом {@code prepare(key)} читает его еще раз.
 * По одному методу повтор не виден - запросы стоят в разных методах и классах. Повтор внутри одного метода
 * показывает jr:512.
 */
@Component
public class RepeatedQueryAcrossCallsRule extends AbstractRule implements ProjectRule {
    private static final int CALL_DEPTH = 4;

    private static final Pattern READ = Pattern.compile(
            "(find|get|read|query|search|load|fetch|select|count|exists)\\w*");
    // getNextNumber(), findForUpdate(id), getReferenceById(id): ответ меняется либо запроса нет вовсе
    private static final Pattern CHANGING = Pattern.compile(
            "(?i)next|increment|generate|lock|forUpdate|random|reference");
    private static final String JDBC_QUERY_PREFIX = "query";
    private static final Set<String> JDBC_WRITES = Set.of("update", "batchUpdate", "execute");
    private static final Set<String> PLAIN_WRITES = Set.of(
            "persist", "merge", "remove", "executeUpdate", "executeBatch", "flush");
    // В транзакции повторный поиск по ключу Hibernate отдаст из контекста без запроса
    private static final Set<String> BY_KEY = Set.of("findById", "getById", "existsById");
    private static final String CACHEABLE = "Cacheable";

    // Обозначения аргумента запроса: параметр метода по номеру, переменная по имени, постоянное значение по тексту
    private static final String PARAMETER = "#";
    private static final String FIXED = "=";
    private static final Pattern GETTER = Pattern.compile("(get|is)[A-Z]\\w*");

    /**
     * @param id    по чему запросы считаются одним и тем же
     * @param title как назвать запрос в сообщении
     * @param name  имя метода запроса
     */
    private record Query(String id, String title, String name) {
    }

    /**
     * Запрос вместе с тем, откуда берутся все его аргументы
     */
    private record Use(Query query, List<String> arguments) {
        private String key() {
            return query.id() + "(" + String.join(", ", arguments) + ")";
        }

        private List<String> variables() {
            return arguments.stream().filter(argument -> !argument.startsWith(FIXED)).toList();
        }
    }

    /**
     * Что вызов читает из базы
     *
     * @param uses     запросы по ключу "запрос + аргументы"
     * @param indirect true, если запросы выполняет вызванный метод проекта, а не сам вызов
     */
    private record Reads(MethodCallExpr site, Map<String, Use> uses, boolean indirect) {
    }

    // Что методы читают по своим параметрам и пишут ли они в базу: считается один раз на метод
    private final class Summaries {
        private final CallGraph graph;
        private final Map<MethodDeclaration, List<Use>> reads = new IdentityHashMap<>();
        private final Map<MethodDeclaration, Boolean> writes = new IdentityHashMap<>();

        private Summaries(CallGraph graph) {
            this.graph = graph;
        }

        private List<MethodDeclaration> bodies(MethodCallExpr call) {
            return graph.targetsOf(call).stream().filter(target -> target.getBody().isPresent()).toList();
        }

        /**
         * @return запросы метода, у которых каждый аргумент - параметр метода как есть либо постоянное значение
         */
        private List<Use> readsOf(MethodDeclaration method, int depth) {
            List<Use> known = reads.get(method);
            if (known != null) {
                return known;
            }
            List<Use> result = new ArrayList<>();
            // Запись до обхода: метод, который вызывает сам себя, второй раз не разбирается
            reads.put(method, result);
            // Ответ метода с @Cacheable приходит из кеша, а не из базы
            if (Annotations.has(method, CACHEABLE)) {
                return result;
            }
            Map<String, Integer> parameters = new HashMap<>();
            for (int index = 0; index < method.getParameters().size(); index++) {
                String name = method.getParameter(index).getNameAsString();
                if (!isAssigned(method, name)) {
                    parameters.put(name, index);
                }
            }
            if (parameters.isEmpty()) {
                return result;
            }
            Function<Expression, Optional<String>> naming = argument -> {
                Expression value = Nodes.unwrap(argument);
                if (value.isNameExpr() && parameters.containsKey(value.asNameExpr().getNameAsString())) {
                    return Optional.of(PARAMETER + parameters.get(value.asNameExpr().getNameAsString()));
                }
                return fixedValue(value);
            };
            for (MethodCallExpr call : method.findAll(MethodCallExpr.class)) {
                for (Use use : usesAt(call, depth, naming)) {
                    if (use.arguments().stream().anyMatch(argument -> argument.startsWith(PARAMETER))) {
                        result.add(use);
                    }
                }
            }
            return result;
        }

        /**
         * @param naming как обозначить аргумент в месте вызова; пусто - значение по коду не назвать
         * @return запросы, которые выполняет вызов, с аргументами в обозначениях вызывающего кода
         */
        private List<Use> usesAt(MethodCallExpr call, int depth, Function<Expression, Optional<String>> naming) {
            List<MethodDeclaration> targets = bodies(call);
            if (targets.isEmpty()) {
                return directQuery(call)
                        .flatMap(query -> name(queryArguments(call), naming).map(names -> new Use(query, names)))
                        .map(List::of)
                        .orElse(List.of());
            }
            // Несколько реализаций: какая из них выполнится, по коду не видно
            if (targets.size() != 1 || depth == 0
                    || targets.get(0).getParameters().size() != call.getArguments().size()) {
                return List.of();
            }
            List<Use> uses = new ArrayList<>();
            for (Use inner : readsOf(targets.get(0), depth - 1)) {
                List<String> names = new ArrayList<>();
                for (String argument : inner.arguments()) {
                    Optional<String> name = argument.startsWith(PARAMETER)
                            ? naming.apply(call.getArgument(Integer.parseInt(argument.substring(PARAMETER.length()))))
                            : Optional.of(argument);
                    if (name.isEmpty()) {
                        names = null;
                        break;
                    }
                    names.add(name.get());
                }
                if (names != null) {
                    uses.add(new Use(inner.query(), names));
                }
            }
            return uses;
        }

        private boolean writesIn(MethodDeclaration method, int depth) {
            Boolean known = writes.get(method);
            if (known != null) {
                return known;
            }
            writes.put(method, false);
            boolean result = false;
            for (MethodCallExpr call : method.findAll(MethodCallExpr.class)) {
                if (isWrite(call) || depth > 0 && bodies(call).stream().anyMatch(target -> writesIn(target, depth - 1))) {
                    result = true;
                    break;
                }
            }
            writes.put(method, result);
            return result;
        }

        private boolean writes(MethodCallExpr call) {
            return isWrite(call) || bodies(call).stream().anyMatch(target -> writesIn(target, CALL_DEPTH));
        }
    }

    @Override
    public String code() {
        return RuleCodes.REPEATED_QUERY_ACROSS_CALLS_RULE_CODE;
    }

    @Override
    public String description() {
        return "Сканирует код и ищет два вызова подряд, внутри которых выполняется один и тот же запрос с теми же"
                + " аргументами: значение читается из базы повторно в разных методах";
    }

    @Override
    public List<Violation> checkProject(List<SourceFile> sourceFiles) {
        Summaries summaries = new Summaries(CallGraph.of(sourceFiles));
        List<Violation> violations = new ArrayList<>();
        for (SourceFile sourceFile : sourceFiles) {
            if (TestPaths.isTest(sourceFile.path())) {
                continue;
            }
            for (MethodDeclaration method : sourceFile.unit().findAll(MethodDeclaration.class)) {
                if (method.getBody().isPresent() && !TestClasses.isInside(method)) {
                    check(sourceFile, method, summaries, violations);
                }
            }
        }
        violations.sort((left, right) -> left.file().equals(right.file())
                ? Integer.compare(left.line(), right.line())
                : left.file().compareTo(right.file()));
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

    private void check(SourceFile sourceFile, MethodDeclaration method, Summaries summaries,
                       List<Violation> violations) {
        List<Reads> sites = new ArrayList<>();
        for (MethodCallExpr call : method.findAll(MethodCallExpr.class)) {
            if (call.findAncestor(MethodDeclaration.class).filter(owner -> owner == method).isEmpty()) {
                continue;
            }
            Map<String, Use> uses = new LinkedHashMap<>();
            for (Use use : summaries.usesAt(call, CALL_DEPTH, this::variableOrFixedValue)) {
                // Запрос из одних постоянных значений от переменных метода не зависит - сравнивать нечего
                if (!use.variables().isEmpty()) {
                    uses.putIfAbsent(use.key(), use);
                }
            }
            if (!uses.isEmpty()) {
                sites.add(new Reads(call, uses, !summaries.bodies(call).isEmpty()));
            }
        }
        Set<MethodCallExpr> reported = Collections.newSetFromMap(new IdentityHashMap<>());
        for (int later = 1; later < sites.size(); later++) {
            for (int earlier = 0; earlier < later; earlier++) {
                Reads first = sites.get(earlier);
                Reads second = sites.get(later);
                // Два запроса прямо в методе показывает jr:512
                if (!first.indirect() && !second.indirect() || reported.contains(second.site())) {
                    continue;
                }
                Optional<Use> repeat = second.uses().entrySet().stream()
                        .filter(entry -> first.uses().containsKey(entry.getKey()))
                        .map(Map.Entry::getValue)
                        .findFirst();
                if (repeat.isEmpty() || !runOneAfterAnother(first.site(), second.site())
                        || repeat.get().variables().stream().anyMatch(variable -> isAssigned(method, root(variable)))
                        || changesBetween(method, first.site(), second.site(), summaries)) {
                    continue;
                }
                reported.add(second.site());
                Query query = repeat.get().query();
                List<String> variables = repeat.get().variables();
                boolean exact = (!first.indirect() || summaries.graph.isExact(first.site()))
                        && (!second.indirect() || summaries.graph.isExact(second.site()));
                Violation violation = violation(sourceFile, second.site(),
                        "'" + title(second.site()) + "' выполняет запрос '" + query.title() + "' "
                                + (variables.size() == 1 ? "с тем же '" : "с теми же '")
                                + String.join("', '", variables) + "', что уже выполнен в '" + title(first.site())
                                + "' (строка " + line(first.site()) + "), а данные между вызовами не менялись:"
                                + " это лишний запрос к базе; получите значение один раз и передайте его в оба"
                                + " метода")
                        .withLevel(BY_KEY.contains(query.name()) ? ErrorLevel.INFO : errorLevel());
                // Цель вызова угадана по именам: тот ли метод выполнится, анализ не уверен
                violations.add(exact ? violation : violation.withConfidence(Confidence.SUSPICION));
            }
        }
    }

    // Аргументы, от которых зависит ответ: у JdbcTemplate первый аргумент - текст запроса, он уже часть самого запроса
    private List<Expression> queryArguments(MethodCallExpr call) {
        boolean isJdbc = call.getScope().filter(JdbcBatches::isJdbcTemplate).isPresent();
        return isJdbc
                ? call.getArguments().subList(1, call.getArguments().size())
                : call.getArguments();
    }

    private Optional<List<String>> name(List<Expression> arguments, Function<Expression, Optional<String>> naming) {
        List<String> names = new ArrayList<>();
        for (Expression argument : arguments) {
            Optional<String> name = naming.apply(argument);
            if (name.isEmpty()) {
                return Optional.empty();
            }
            names.add(name.get());
        }
        return Optional.of(names);
    }

    // В проверяемом методе аргумент - переменная, цепочка геттеров от нее либо постоянное значение
    private Optional<String> variableOrFixedValue(Expression argument) {
        Expression value = Nodes.unwrap(argument);
        Expression root = value;
        while (root.isMethodCallExpr() && root.asMethodCallExpr().getArguments().isEmpty()
                && GETTER.matcher(root.asMethodCallExpr().getNameAsString()).matches()
                && root.asMethodCallExpr().getScope().isPresent()) {
            root = Nodes.unwrap(root.asMethodCallExpr().getScope().get());
        }
        if (root.isNameExpr() && LocalTypes.isLocal(root, root.asNameExpr().getNameAsString())) {
            return Optional.of(value.toString());
        }
        return fixedValue(value);
    }

    // Литерал, константа, X.class, ссылка на метод, поле объекта: значение, которое от вызова к вызову не меняется
    private Optional<String> fixedValue(Expression value) {
        if (value.isLambdaExpr()) {
            return Optional.of(FIXED + "lambda");
        }
        boolean usesLocals = value.findAll(NameExpr.class).stream()
                .anyMatch(name -> LocalTypes.isLocal(name, name.getNameAsString()));
        return usesLocals || !value.findAll(MethodCallExpr.class).stream().allMatch(this::isFactory)
                ? Optional.empty()
                : Optional.of(FIXED + value);
    }

    // PageRequest.of(0, 10), Sort.by("name"): вызов у класса, а не у объекта с состоянием
    private boolean isFactory(MethodCallExpr call) {
        return call.getScope()
                .map(Nodes::unwrap)
                .filter(scope -> scope.isNameExpr() && Character.isUpperCase(scope.asNameExpr().getNameAsString().charAt(0))
                        || scope.isMethodCallExpr())
                .isPresent();
    }

    // order.getClient().getId() -> order
    private String root(String variable) {
        int dot = variable.indexOf('.');
        return dot < 0 ? variable : variable.substring(0, dot);
    }

    // Оба вызова - операторы одного блока вне лямбд: выполняются один за другим, а не в разных ветках
    private boolean runOneAfterAnother(MethodCallExpr first, MethodCallExpr second) {
        Optional<Statement> firstStatement = first.findAncestor(Statement.class);
        Optional<Statement> secondStatement = second.findAncestor(Statement.class);
        if (firstStatement.isEmpty() || secondStatement.isEmpty() || firstStatement.get() == secondStatement.get()) {
            return false;
        }
        Node firstBlock = firstStatement.get().getParentNode().orElse(null);
        return firstBlock instanceof BlockStmt && firstBlock == secondStatement.get().getParentNode().orElse(null)
                && first.findAncestor(LambdaExpr.class).isEmpty() && second.findAncestor(LambdaExpr.class).isEmpty()
                && Loops.enclosingIteration(first).orElse(null) == Loops.enclosingIteration(second).orElse(null)
                // Вызов в условии или в теле вложенного оператора выполняется не всегда
                && isOwnExpression(first, firstStatement.get()) && isOwnExpression(second, secondStatement.get());
    }

    // Оператор-выражение, объявление переменной или return: вызов выполняется всякий раз, когда выполняется оператор
    private boolean isOwnExpression(MethodCallExpr call, Statement statement) {
        return statement.isExpressionStmt() || statement.isReturnStmt()
                || statement.isIfStmt() && Nodes.isInside(call, statement.asIfStmt().getCondition());
    }

    // Первый вызов или что-то между вызовами пишет в базу - второй запрос может вернуть другое
    private boolean changesBetween(MethodDeclaration method, MethodCallExpr first, MethodCallExpr second,
                                   Summaries summaries) {
        if (summaries.writes(first)) {
            return true;
        }
        int from = position(first);
        int to = position(second);
        return method.findAll(MethodCallExpr.class).stream()
                .filter(call -> call != first && call != second && position(call) > from && position(call) < to)
                .anyMatch(summaries::writes);
    }

    private boolean isAssigned(MethodDeclaration method, String name) {
        return method.findAll(AssignExpr.class).stream()
                .anyMatch(assignment -> assignment.getTarget().isNameExpr()
                        && assignment.getTarget().asNameExpr().getNameAsString().equals(name));
    }

    // userRepository.findByEmail(email) у интерфейса Spring Data либо jdbcTemplate.queryForObject(sql, ..., key)
    private Optional<Query> directQuery(MethodCallExpr call) {
        String name = call.getNameAsString();
        if (call.getScope().isEmpty()) {
            return Optional.empty();
        }
        Expression scope = call.getScope().get();
        if (JdbcBatches.isJdbcTemplate(scope) && name.startsWith(JDBC_QUERY_PREFIX) && !call.getArguments().isEmpty()) {
            String owner = call.findAncestor(MethodDeclaration.class).map(this::qualifiedName).orElse("");
            return Optional.of(new Query(owner + "#" + call.getArgument(0), owner, name));
        }
        if (Repositories.isRepositoryCall(call) && READ.matcher(name).matches() && !CHANGING.matcher(name).find()) {
            String receiver = LocalTypes.typeOf(scope).orElseGet(() -> MethodCalls.receiverName(scope));
            return Optional.of(new Query(receiver + "." + name, receiver + "." + name, name));
        }
        return Optional.empty();
    }

    private boolean isWrite(MethodCallExpr call) {
        String name = call.getNameAsString();
        if (call.getScope().isEmpty()) {
            return false;
        }
        if (PLAIN_WRITES.contains(name)) {
            return true;
        }
        if (JdbcBatches.isJdbcTemplate(call.getScope().get())) {
            return JDBC_WRITES.contains(name);
        }
        return Repositories.isRepositoryCall(call) && !READ.matcher(name).matches();
    }

    private String qualifiedName(MethodDeclaration method) {
        return method.findAncestor(TypeDeclaration.class)
                .map(type -> ((TypeDeclaration<?>) type).getNameAsString() + ".")
                .orElse("") + method.getNameAsString();
    }

    private String title(MethodCallExpr call) {
        return call.getScope().map(scope -> scope + ".").orElse("") + call.getNameAsString() + "(...)";
    }

    private int line(Node node) {
        return node.getBegin().map(position -> position.line).orElse(0);
    }

    private int position(Node node) {
        return node.getBegin().map(position -> position.line * 10_000 + position.column).orElse(0);
    }
}
