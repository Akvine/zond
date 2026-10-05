package ru.akvine.zond.rules.resources;

import com.github.javaparser.ast.Node;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.body.VariableDeclarator;
import com.github.javaparser.ast.expr.BinaryExpr;
import com.github.javaparser.ast.expr.Expression;
import com.github.javaparser.ast.expr.MethodCallExpr;
import com.github.javaparser.ast.expr.NameExpr;
import com.github.javaparser.ast.stmt.ReturnStmt;
import com.github.javaparser.ast.stmt.TryStmt;
import org.springframework.stereotype.Component;
import ru.akvine.zond.enums.ErrorLevel;
import ru.akvine.zond.enums.ErrorType;
import ru.akvine.zond.models.RuleParameter;
import ru.akvine.zond.models.SourceFile;
import ru.akvine.zond.models.Violation;
import ru.akvine.zond.rules.AbstractRule;
import ru.akvine.zond.rules.ProjectRule;
import ru.akvine.zond.rules.RuleCodes;
import ru.akvine.zond.rules.support.CallGraph;
import ru.akvine.zond.rules.support.LocalTypes;
import ru.akvine.zond.rules.support.Loggers;
import ru.akvine.zond.rules.support.MethodCalls;
import ru.akvine.zond.rules.support.Nodes;
import ru.akvine.zond.rules.support.Resources;

import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.LinkedHashSet;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Ресурс считается незакрытым, если его путь по программе нигде не заканчивается закрытием и не уходит туда,
 * где его могут закрыть. Путь прослеживается и через методы проекта: ресурс, отданный методу, который его
 * тоже не закрывает, остается открытым; метод, который возвращает открытый ресурс, сам открывает его
 * для вызывающего.
 */
@Component
public class CheckUnclosedResourceRule extends AbstractRule implements ProjectRule {
    // Методы, которые сейчас разбираются на пути вглубь. Без этого метод, вызывающий сам себя (или два метода,
    // вызывающих друг друга), обходился бы заново на каждом уровне, и время росло бы как степень глубины
    private final Set<Node> tracing = Collections.newSetFromMap(new IdentityHashMap<>());

    // Уже посчитанное за это сканирование: до одного метода доходят разными путями, а ответ один.
    // Ключ - номер параметра и оставшаяся глубина
    private final Map<MethodDeclaration, Map<String, Fate>> knownFates = new IdentityHashMap<>();
    private final Map<MethodDeclaration, Map<Integer, Boolean>> knownOpeners = new IdentityHashMap<>();

    private static final RuleParameter MAX_CALL_DEPTH = new RuleParameter(
            "max-call-depth", 3, "На сколько вызовов вглубь прослеживать ресурс; 0 - только в самом методе");

    private static final String CLOSE = "close";
    private static final String FILES = "Files";
    private static final Set<String> FILES_STREAM_METHODS = Set.of("lines", "list", "walk", "find");

    // Завершающие операции стрима: после них закрыть стрим уже некому
    private static final Set<String> TERMINAL_OPERATIONS = Set.of(
            "collect", "forEach", "forEachOrdered", "count", "toList", "toArray", "reduce", "anyMatch", "allMatch",
            "noneMatch", "findFirst", "findAny", "min", "max", "sum");

    // Методы, которые только смотрят на объект и владеть им не начинают
    private static final Set<String> OBSERVING_METHODS =
            Set.of("requireNonNull", "nonNull", "isNull", "valueOf", "println", "print", "hashCode", "identityHashCode");

    /**
     * Что стало с ресурсом в методе
     *
     * @param closed  вызван close()
     * @param escaped ушел туда, где его могут закрыть: возвращен, сохранен, обернут, отдан неизвестному коду
     * @param passed  методы проекта, которым он был отдан и которые его тоже не закрыли
     */
    private record Fate(boolean closed, boolean escaped, Set<String> passed) {

        private boolean isLeaked() {
            return !closed && !escaped;
        }
    }

    @Override
    public List<RuleParameter> parameters() {
        return List.of(MAX_CALL_DEPTH);
    }

    @Override
    public String code() {
        return RuleCodes.CHECK_UNCLOSED_RESOURCE_RULE_CODE;
    }

    @Override
    public String description() {
        return "Сканирует код и ищет ресурсы (потоки, соединения, стримы файлов), которые открыты и не закрыты";
    }

    @Override
    public List<Violation> checkProject(List<SourceFile> sourceFiles) {
        knownFates.clear();
        knownOpeners.clear();
        CallGraph graph = CallGraph.of(sourceFiles);
        List<Violation> violations = new ArrayList<>();
        for (SourceFile sourceFile : sourceFiles) {
            check(sourceFile, graph, violations);
        }
        return violations;
    }

    @Override
    public ErrorLevel errorLevel() {
        return ErrorLevel.CRITICAL;
    }

    @Override
    public ErrorType errorType() {
        return ErrorType.RESOURCE;
    }

    private void check(SourceFile sourceFile, CallGraph graph, List<Violation> violations) {
        for (VariableDeclarator variable : sourceFile.unit().findAll(VariableDeclarator.class)) {
            if (!Resources.isLocalVariable(variable) || variable.getInitializer().isEmpty()) {
                continue;
            }
            Optional<String> origin = describeOpening(variable.getInitializer().get(), graph);
            Optional<Node> callable = Nodes.enclosingCallable(variable);
            if (origin.isEmpty() || callable.isEmpty()) {
                continue;
            }

            Fate fate = fateOf(variable.getNameAsString(), callable.get(), graph, value(MAX_CALL_DEPTH));
            if (fate.isLeaked()) {
                violations.add(violation(sourceFile, variable,
                        "Ресурс '" + variable.getNameAsString() + "'" + origin.get() + " открыт и не закрыт"
                                + describePassing(fate) + ": используйте try-with-resources"));
            }
        }

        for (MethodCallExpr call : sourceFile.unit().findAll(MethodCallExpr.class)) {
            if (isFilesStream(call) && isConsumedWithoutClosing(call)) {
                violations.add(violation(sourceFile, call,
                        "Стрим Files." + call.getNameAsString() + "(...) держит открытый файл и не закрыт:"
                                + " используйте try-with-resources"));
            }
        }
    }

    /**
     * @return пусто, если выражение не открывает ресурс; иначе пояснение для сообщения: пустая строка
     * для new FileReader(...) и подобных либо указание на метод проекта, который вернул открытый ресурс
     */
    private Optional<String> describeOpening(Expression initializer, CallGraph graph) {
        if (Resources.opens(initializer)) {
            return Optional.of("");
        }

        Expression value = Nodes.unwrap(initializer);
        if (!value.isMethodCallExpr()) {
            return Optional.empty();
        }
        MethodCallExpr call = value.asMethodCallExpr();
        boolean opensInside = graph.targetsOf(call).stream()
                .anyMatch(target -> returnsOpenedResource(target, graph, value(MAX_CALL_DEPTH)));
        return opensInside
                ? Optional.of(", полученный из '" + call.getNameAsString() + "(...)',")
                : Optional.empty();
    }

    // return new FileReader(...); либо return reader; где reader открыт в этом же методе; либо return open(...);
    private boolean returnsOpenedResource(MethodDeclaration method, CallGraph graph, int depth) {
        if (depth <= 0 || method.getBody().isEmpty() || !tracing.add(method)) {
            return false;
        }
        try {
            Map<Integer, Boolean> known = knownOpeners.computeIfAbsent(method, key -> new HashMap<>());
            Boolean result = known.get(depth);
            if (result == null) {
                result = returnsOpened(method, graph, depth);
                known.put(depth, result);
            }
            return result;
        } finally {
            tracing.remove(method);
        }
    }

    private boolean returnsOpened(MethodDeclaration method, CallGraph graph, int depth) {
        for (ReturnStmt returned : method.getBody().get().findAll(ReturnStmt.class)) {
            if (returned.getExpression().isEmpty() || Nodes.isInNestedScope(returned, method)) {
                continue;
            }
            Expression value = Nodes.unwrap(returned.getExpression().get());
            Expression source = LocalTypes.findInitializer(value).map(Nodes::unwrap).orElse(value);
            if (Resources.opens(source)) {
                return true;
            }
            boolean delegates = source.isMethodCallExpr() && graph.targetsOf(source.asMethodCallExpr()).stream()
                    .anyMatch(target -> target != method && returnsOpenedResource(target, graph, depth - 1));
            if (delegates) {
                return true;
            }
        }
        return false;
    }

    /**
     * @param name     имя переменной или параметра с ресурсом
     * @param callable метод, в котором она объявлена
     */
    private Fate fateOf(String name, Node callable, CallGraph graph, int depth) {
        boolean closed = false;
        boolean escaped = false;
        Set<String> passed = new LinkedHashSet<>();

        for (NameExpr usage : callable.findAll(NameExpr.class)) {
            if (!usage.getNameAsString().equals(name)) {
                continue;
            }

            Node parent = usage.getParentNode().orElse(null);
            if (parent instanceof MethodCallExpr call && call.getScope().filter(scope -> scope == usage).isPresent()) {
                // reader.read() ничего не меняет, reader.close() закрывает
                closed |= CLOSE.equals(call.getNameAsString());
            } else if (parent instanceof MethodCallExpr call) {
                Optional<Fate> inCallee = fateInCallee(call, usage, graph, depth);
                if (inCallee.isEmpty()) {
                    escaped |= !isObserved(call);
                } else if (inCallee.get().isLeaked()) {
                    passed.add(call.getNameAsString());
                    passed.addAll(inCallee.get().passed());
                } else {
                    escaped = true;
                }
            } else if (!(parent instanceof BinaryExpr)) {
                // Возвращается, сохраняется, оборачивается или указан в try (resource): закроют там.
                // Сравнение (reader != null) ресурсу ничего не делает
                escaped = true;
            }
        }
        return new Fate(closed, escaped, passed);
    }

    /**
     * @return что стало с ресурсом в методе проекта, которому он передан; пусто, если метод не из проекта
     * либо глубина исчерпана - тогда о судьбе ресурса ничего не известно
     */
    private Optional<Fate> fateInCallee(MethodCallExpr call, NameExpr argument, CallGraph graph, int depth) {
        List<MethodDeclaration> targets = graph.targetsOf(call);
        int index = call.getArguments().indexOf(argument);
        if (depth <= 0 || targets.isEmpty() || index < 0) {
            return Optional.empty();
        }

        boolean closed = false;
        boolean escaped = false;
        Set<String> passed = new LinkedHashSet<>();
        for (MethodDeclaration target : targets) {
            // Аргументов больше, чем параметров (varargs), либо у метода нет тела: судить не можем
            if (index >= target.getParameters().size() || target.getBody().isEmpty()) {
                return Optional.empty();
            }
            // Метод уже разбирается выше по цепочке (рекурсия): о ресурсе в нем ничего нового не узнать
            if (!tracing.add(target)) {
                return Optional.empty();
            }
            try {
                Map<String, Fate> known = knownFates.computeIfAbsent(target, key -> new HashMap<>());
                String key = index + "/" + depth;
                Fate fate = known.get(key);
                if (fate == null) {
                    fate = fateOf(target.getParameter(index).getNameAsString(), target, graph, depth - 1);
                    known.put(key, fate);
                }
                closed |= fate.closed();
                escaped |= fate.escaped();
                passed.addAll(fate.passed());
            } finally {
                tracing.remove(target);
            }
        }
        return Optional.of(new Fate(closed, escaped, passed));
    }

    // log.info("{}", reader), Objects.requireNonNull(reader): метод на ресурс только смотрит
    private boolean isObserved(MethodCallExpr call) {
        return Loggers.isLogCall(call) || OBSERVING_METHODS.contains(call.getNameAsString());
    }

    private String describePassing(Fate fate) {
        return fate.passed().isEmpty()
                ? ""
                : " (передается в " + String.join(", ", fate.passed()) + ", но и там не закрывается)";
    }

    private boolean isFilesStream(MethodCallExpr call) {
        return FILES_STREAM_METHODS.contains(call.getNameAsString())
                && MethodCalls.isCallOn(call, FILES, call.getNameAsString());
    }

    // Files.lines(path).filter(...).collect(...): цепочка закончилась, а стрим так и не закрыли
    private boolean isConsumedWithoutClosing(MethodCallExpr call) {
        MethodCallExpr top = call;
        while (true) {
            if (isTryResource(top)) {
                return false;
            }

            MethodCallExpr current = top;
            Optional<MethodCallExpr> next = top.getParentNode()
                    .filter(parent -> parent instanceof MethodCallExpr)
                    .map(parent -> (MethodCallExpr) parent)
                    .filter(parent -> parent.getScope().filter(scope -> scope == current).isPresent());
            if (next.isEmpty()) {
                break;
            }
            top = next.get();
        }
        return top != call && TERMINAL_OPERATIONS.contains(top.getNameAsString());
    }

    private boolean isTryResource(Expression expression) {
        return expression.getParentNode()
                .filter(parent -> parent instanceof TryStmt)
                .map(parent -> (TryStmt) parent)
                .filter(tryStmt -> tryStmt.getResources().stream().anyMatch(resource -> resource == expression))
                .isPresent();
    }
}
