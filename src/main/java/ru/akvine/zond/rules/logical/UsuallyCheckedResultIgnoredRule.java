package ru.akvine.zond.rules.logical;

import com.github.javaparser.ast.Node;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.body.TypeDeclaration;
import com.github.javaparser.ast.body.VariableDeclarator;
import com.github.javaparser.ast.expr.AssignExpr;
import com.github.javaparser.ast.expr.Expression;
import com.github.javaparser.ast.expr.LambdaExpr;
import com.github.javaparser.ast.expr.MethodCallExpr;
import com.github.javaparser.ast.expr.SwitchExpr;
import com.github.javaparser.ast.nodeTypes.NodeWithTypeParameters;
import com.github.javaparser.ast.stmt.ExpressionStmt;
import com.github.javaparser.ast.stmt.ReturnStmt;
import com.github.javaparser.ast.stmt.SwitchEntry;
import org.springframework.stereotype.Component;
import ru.akvine.zond.enums.ErrorLevel;
import ru.akvine.zond.enums.ErrorType;
import ru.akvine.zond.models.SourceFile;
import ru.akvine.zond.models.Violation;
import ru.akvine.zond.rules.AbstractRule;
import ru.akvine.zond.rules.ProjectRule;
import ru.akvine.zond.rules.RuleCodes;
import ru.akvine.zond.rules.support.CallGraph;
import ru.akvine.zond.rules.support.LocalTypes;
import ru.akvine.zond.rules.support.SideEffects;
import ru.akvine.zond.rules.support.TestClasses;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Результат метода, который в проекте почти всегда проверяют, в этом месте отброшен. Если validator.check(order),
 * Context.hasPrivilege(...) или matcher.find() в 9 из 10 мест кладут в переменную или проверяют в if, то десятое
 * место, где результат не нужен, скорее всего ошибка: забытая проверка, а не задумка.
 * <p>
 * Так ищут ошибки как отклонение от поведения самого проекта: правила о конкретных методах знать не нужно.
 * Методы своего класса сопоставляются по графу вызовов, методы библиотек - по типу объекта, имени и числу
 * аргументов. Не проверяются:
 * <ul>
 *     <li>методы, ради действия которых результат обычно отбрасывают: add, put, remove, save, send, submit;</li>
 *     <li>чтение со сдвигом позиции: in.readShort(), parser.next(), buffer.getInt() пропускают данные;</li>
 *     <li>проверки, которые бросают исключение: checkNotNull(...), requireNonNull(...), validate(...);</li>
 *     <li>построители: query.active(), builder.name(...) возвращают тот же объект;</li>
 *     <li>методы, о которых уже сообщает jr:180 (File.delete(), tryLock()).</li>
 * </ul>
 */
@Component
public class UsuallyCheckedResultIgnoredRule extends AbstractRule implements ProjectRule {
    // Соглашение видно, когда вызовов хотя бы столько и результат проверяют в такой доле из них
    private static final int MIN_CALLS = 5;
    private static final double CHECKED_SHARE = 0.8;
    // Результат этих методов - побочная информация: что было в карте, добавилось ли в множество, сколько строк
    // изменено, что лежало в счетчике до изменения
    private static final Set<String> ACTION_METHODS = Set.of(
            "insert", "push", "pop", "compute", "computeIfAbsent", "computeIfPresent", "merge", "replace", "retainAll",
            "execute", "submit", "schedule", "apply", "accept", "run", "call", "get", "join", "await", "countDown",
            "release", "close", "flush", "print", "println", "printf", "format", "update", "delete", "publish",
            "build", "start", "complete", "cancel", "createDirectories", "createFile", "copy", "move", "exchange",
            "from", "executeUpdate", "executeLargeUpdate", "executeBatch", "compareAndSet", "weakCompareAndSet",
            "getAndIncrement", "getAndDecrement", "getAndAdd", "getAndSet", "getAndUpdate", "getAndAccumulate",
            "incrementAndGet", "decrementAndGet", "addAndGet", "updateAndGet", "accumulateAndGet");
    // То же по началу имени: addListener, removeFromList, saveOrUpdate, postForEntity, readShort, nextToken
    private static final List<String> ACTION_PREFIXES = List.of(
            "add", "remove", "put", "set", "save", "send", "write", "post", "append", "poll", "offer", "register",
            "unregister", "read", "next", "skip");
    // checkNotNull(value), requireResource(id): бросают исключение, а результат - то же значение для удобства.
    // Проверка своего класса, которая возвращает boolean, ничего не бросает - ее результат забывать нельзя
    private static final List<String> CHECK_PREFIXES = List.of(
            "check", "require", "validate", "verify", "ensure", "assert");
    private static final List<String> GETTER_PREFIXES = List.of("get", "is", "has");
    // ByteBuffer, ByteBuf: get*() сдвигает позицию, остальное возвращает сам буфер
    private static final Pattern BUFFER_TYPE = Pattern.compile(".*(Buffer|Buf)$");
    // О них сообщает jr:180
    private static final Set<String> REPORTED_ELSEWHERE = Set.of(
            "mkdir", "mkdirs", "createNewFile", "renameTo", "setReadable", "setWritable", "setExecutable",
            "setLastModified", "tryLock", "tryAcquire", "awaitTermination");

    /**
     * Все вызовы одного метода: сколько используют результат и сколько из них продолжают цепочку вызовов
     */
    private static final class Usage {
        private final List<MethodCallExpr> ignored = new ArrayList<>();
        private int total;
        private int used;
        private int chained;
    }

    @Override
    public String code() {
        return RuleCodes.USUALLY_CHECKED_RESULT_IGNORED_RULE_CODE;
    }

    @Override
    public String description() {
        return "Сканирует проект и ищет вызовы, результат которых отброшен, хотя в остальных местах проекта его проверяют";
    }

    @Override
    public List<Violation> checkProject(List<SourceFile> sourceFiles) {
        CallGraph graph = CallGraph.of(sourceFiles);
        SideEffects effects = SideEffects.of(sourceFiles);
        // Узлы JavaParser равны по тексту: одинаковые вызовы и методы из разных файлов различаются только по ссылке
        Map<MethodDeclaration, Usage> ownUsages = new IdentityHashMap<>();
        Map<String, Usage> libraryUsages = new HashMap<>();
        Map<MethodCallExpr, SourceFile> files = new IdentityHashMap<>();
        for (SourceFile sourceFile : sourceFiles) {
            for (MethodCallExpr call : sourceFile.unit().findAll(MethodCallExpr.class)) {
                String method = call.getNameAsString();
                if (ACTION_METHODS.contains(method) || REPORTED_ELSEWHERE.contains(method)
                        || startsWithWord(method, ACTION_PREFIXES) || TestClasses.isInside(call)) {
                    continue;
                }
                Optional<Usage> usage = usageOf(call, graph, effects, ownUsages, libraryUsages);
                if (usage.isEmpty()) {
                    continue;
                }
                usage.get().total++;
                if (isIgnored(call)) {
                    usage.get().ignored.add(call);
                    files.put(call, sourceFile);
                } else {
                    usage.get().used++;
                    if (isChained(call)) {
                        usage.get().chained++;
                    }
                }
            }
        }
        List<Violation> violations = new ArrayList<>();
        List<Usage> usages = new ArrayList<>(ownUsages.values());
        usages.addAll(libraryUsages.values());
        for (Usage usage : usages) {
            boolean convention = usage.total >= MIN_CALLS && usage.used >= CHECKED_SHARE * usage.total;
            // query.active().list(): результат берут, чтобы продолжить цепочку - это построитель, а не проверка
            boolean fluent = usage.chained * 2 >= usage.used;
            if (!convention || fluent) {
                continue;
            }
            for (MethodCallExpr call : usage.ignored) {
                violations.add(violation(files.get(call), call,
                        "Результат '" + call + "' отброшен, хотя в остальных местах проекта его используют ("
                                + usage.used + " из " + usage.total + " вызовов): скорее всего здесь забыли"
                                + " проверку; проверьте результат либо оставьте комментарий, почему он не нужен"));
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
        return ErrorType.LOGICAL;
    }

    // Метод проекта - сам метод; метод библиотеки - тип объекта, имя и число аргументов. Тип неизвестен - не судим
    private Optional<Usage> usageOf(MethodCallExpr call, CallGraph graph, SideEffects effects,
                                    Map<MethodDeclaration, Usage> ownUsages, Map<String, Usage> libraryUsages) {
        boolean checking = startsWithWord(call.getNameAsString(), CHECK_PREFIXES);
        // user.getRoles() отдельной строкой подгружает ленивую коллекцию Hibernate
        boolean getter = call.getArguments().isEmpty() && startsWithWord(call.getNameAsString(), GETTER_PREFIXES);
        if (getter) {
            return Optional.empty();
        }
        List<MethodDeclaration> targets = graph.targetsOf(call);
        if (targets.size() == 1) {
            MethodDeclaration target = targets.get(0);
            // Метод без побочных эффектов отдельной строкой ничего не делает: об этом сообщает jr:455
            boolean skipped = target.getType().isVoidType() || returnsOwnType(target) || isLazyGetter(target)
                    || checking && !isBoolean(target) || effects.isPure(target);
            return skipped ? Optional.empty() : Optional.of(ownUsages.computeIfAbsent(target, found -> new Usage()));
        }
        if (!targets.isEmpty() || call.getScope().isEmpty() || checking) {
            return Optional.empty();
        }
        return LocalTypes.typeOf(call.getScope().get())
                .filter(type -> !BUFFER_TYPE.matcher(type).matches())
                .map(type -> libraryUsages.computeIfAbsent(
                        type + "." + call.getNameAsString() + "/" + call.getArguments().size(), found -> new Usage()));
    }

    // addListener, readShort, но не address и readiness
    private boolean startsWithWord(String method, List<String> prefixes) {
        return prefixes.stream().anyMatch(prefix -> method.startsWith(prefix)
                && (method.length() == prefix.length() || Character.isUpperCase(method.charAt(prefix.length()))));
    }

    private boolean isBoolean(MethodDeclaration method) {
        String type = method.getType().asString();
        return type.equals("boolean") || type.equals("Boolean");
    }

    // builder.name(...) возвращает тот же построитель, AbstractQuery<T>.orderBy(...) - параметр типа T
    private boolean returnsOwnType(MethodDeclaration method) {
        String returned = method.getType().asString();
        return method.findAncestor(TypeDeclaration.class)
                .map(type -> type.getNameAsString().equals(returned)
                        || type instanceof NodeWithTypeParameters<?> generic && generic.getTypeParameters().stream()
                        .anyMatch(parameter -> parameter.getNameAsString().equals(returned)))
                .orElse(false);
    }

    // if (conn == null) { conn = open(); } return conn; - getSqlConnection() отдельной строкой вызывают ради заполнения поля
    private boolean isLazyGetter(MethodDeclaration method) {
        Set<String> locals = new HashSet<>();
        method.getParameters().forEach(parameter -> locals.add(parameter.getNameAsString()));
        method.findAll(VariableDeclarator.class).forEach(variable -> locals.add(variable.getNameAsString()));
        Set<String> assigned = new HashSet<>();
        method.findAll(AssignExpr.class).forEach(assignment -> assigned.add(fieldName(assignment.getTarget())));
        List<ReturnStmt> returns = method.findAll(ReturnStmt.class);
        return !returns.isEmpty() && returns.stream().allMatch(statement -> statement.getExpression()
                .map(this::fieldName)
                .filter(name -> !locals.contains(name) && assigned.contains(name))
                .isPresent());
    }

    // conn и this.conn - одно поле
    private String fieldName(Expression expression) {
        return expression.isFieldAccessExpr() && expression.asFieldAccessExpr().getScope().isThisExpr()
                ? expression.asFieldAccessExpr().getNameAsString()
                : expression.toString();
    }

    // Вызов - целый оператор: результат никуда не идет. Тело лямбды-выражения и ветка case X -> value(...) выражения
    // switch - это возвращаемое значение
    private boolean isIgnored(MethodCallExpr call) {
        Node parent = call.getParentNode().orElse(null);
        if (!(parent instanceof ExpressionStmt statement)) {
            return false;
        }
        Node owner = statement.getParentNode().orElse(null);
        boolean switchValue = owner instanceof SwitchEntry entry
                && entry.getParentNode().filter(SwitchExpr.class::isInstance).isPresent();
        return !(owner instanceof LambdaExpr) && !switchValue;
    }

    private boolean isChained(MethodCallExpr call) {
        return call.getParentNode()
                .filter(parent -> parent instanceof MethodCallExpr next && next.getScope().filter(scope -> scope == call).isPresent())
                .isPresent();
    }
}
