package ru.akvine.zond.rules.exceptions;

import com.github.javaparser.ast.Node;
import com.github.javaparser.ast.body.BodyDeclaration;
import com.github.javaparser.ast.body.EnumDeclaration;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.expr.AnnotationExpr;
import com.github.javaparser.ast.expr.ClassExpr;
import com.github.javaparser.ast.expr.Expression;
import com.github.javaparser.ast.expr.MethodCallExpr;
import com.github.javaparser.ast.expr.NameExpr;
import com.github.javaparser.ast.stmt.TryStmt;
import com.github.javaparser.ast.type.Type;
import org.springframework.stereotype.Component;
import ru.akvine.zond.enums.ErrorLevel;
import ru.akvine.zond.enums.ErrorType;
import ru.akvine.zond.models.SourceFile;
import ru.akvine.zond.models.Violation;
import ru.akvine.zond.rules.AbstractTaintRule;
import ru.akvine.zond.rules.RuleCodes;
import ru.akvine.zond.rules.support.Annotations;
import ru.akvine.zond.rules.support.CallGraph;
import ru.akvine.zond.rules.support.LocalTypes;
import ru.akvine.zond.rules.support.MethodCalls;
import ru.akvine.zond.rules.support.Nodes;
import ru.akvine.zond.rules.support.ProjectClasses;
import ru.akvine.zond.rules.support.Taint;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * Строку из запроса превращают в перечисление, UUID или дату и не ждут, что она окажется не той.
 * Числа проверяет отдельное правило; здесь - остальные разборы, которые бросают исключение на плохом вводе.
 */
@Component
public class UnhandledRequestValueParseRule extends AbstractTaintRule {
    private static final String VALUE_OF = "valueOf";
    private static final String ENUM = "Enum";
    private static final String UUID = "UUID";
    private static final String FROM_STRING = "fromString";
    private static final String PARSE = "parse";
    private static final Set<String> TEMPORAL_TYPES = Set.of(
            "LocalDate", "LocalDateTime", "LocalTime", "Instant", "OffsetDateTime", "ZonedDateTime", "OffsetTime",
            "YearMonth", "Year", "MonthDay", "Duration", "Period");

    private static final String ILLEGAL_ARGUMENT = "IllegalArgumentException";
    private static final String DATE_TIME_PARSE = "DateTimeParseException";
    private static final Set<String> ANY_HANDLERS = Set.of("RuntimeException", "Exception", "Throwable");
    private static final Set<String> TEMPORAL_HANDLERS = Set.of(DATE_TIME_PARSE, "DateTimeException");
    private static final String EXCEPTION_HANDLER = "ExceptionHandler";

    // Значение проверили до разбора: EnumUtils.isValidEnum(...), перебор Status.values()
    private static final String VALUES = "values";
    private static final Set<String> ENUM_CHECKS = Set.of("isValidEnum", "getEnum", VALUES);

    // Проверка значения до разбора: validator.validate(request), verifyRequest(request), Assert.isTrue(...)
    private static final Pattern VALIDATION = Pattern.compile("(?i)^(valid|verif|check|assert|ensure|require).*");
    private static final Pattern VALIDATOR = Pattern.compile("(?i).*(validator|verifier|checker)$");
    // @Pattern на поле запроса: значение проверено по шаблону еще до входа в обработчик
    private static final String PATTERN_ANNOTATION = "Pattern";
    private static final Pattern GETTER = Pattern.compile("^get([A-Z].*)$");
    private static final Set<String> NULL_CHECKS = Set.of("requireNonNull", "checkNotNull", "requireNonNullElse");
    private static final int VALIDATION_DEPTH = 3;

    // Перечисления проекта и исключения, которые общий обработчик ошибок уже превращает в понятный ответ
    private Set<String> enums = Set.of();
    private Set<String> answered = Set.of();
    private CallGraph graph;
    private ProjectClasses classes;

    /**
     * @param text      строка, которую разбирают
     * @param exception исключение, которое разбор бросает на неподходящей строке
     */
    private record Parsing(Expression text, String exception) {
    }

    @Override
    public String code() {
        return RuleCodes.UNHANDLED_REQUEST_VALUE_PARSE_RULE_CODE;
    }

    @Override
    public String description() {
        return "Сканирует код и ищет разбор перечисления, UUID или даты из данных запроса без обработки ошибки";
    }

    @Override
    public List<Violation> checkProject(List<SourceFile> sourceFiles) {
        Set<String> declared = new HashSet<>();
        Set<String> handled = new HashSet<>();
        for (SourceFile sourceFile : sourceFiles) {
            sourceFile.unit().findAll(EnumDeclaration.class).forEach(found -> declared.add(found.getNameAsString()));
            sourceFile.unit().findAll(AnnotationExpr.class).stream()
                    .filter(annotation -> EXCEPTION_HANDLER.equals(annotation.getName().getIdentifier()))
                    .forEach(annotation -> handled.addAll(handledBy(annotation)));
        }
        enums = declared;
        answered = handled;
        graph = CallGraph.of(sourceFiles);
        classes = ProjectClasses.of(sourceFiles);
        return super.checkProject(sourceFiles);
    }

    @Override
    protected List<Violation> check(SourceFile sourceFile, Taint taint) {
        List<Violation> violations = new ArrayList<>();
        for (MethodCallExpr call : sourceFile.unit().findAll(MethodCallExpr.class)) {
            Optional<Parsing> parsing = parsingOf(call);
            if (parsing.isEmpty() || parsing.get().text().isStringLiteralExpr() || isExpected(call, parsing.get())) {
                continue;
            }
            taint.findSource(parsing.get().text()).ifPresent(source -> violations.add(violation(sourceFile, call,
                    "'" + call + "' разбирает значение из данных клиента '" + source + "' без обработки ошибки:"
                            + " на неподходящей строке метод бросит " + parsing.get().exception() + ", и клиент"
                            + " получит ошибку сервера (500) вместо ошибки запроса (400); проверьте значение"
                            + " заранее либо перехватите исключение и верните понятный ответ")));
        }
        return violations;
    }

    @Override
    public ErrorLevel errorLevel() {
        return ErrorLevel.MAJOR;
    }

    @Override
    public ErrorType errorType() {
        return ErrorType.EXCEPTION;
    }

    // Status.valueOf(text), Enum.valueOf(Status.class, text), UUID.fromString(text), LocalDate.parse(text)
    private Optional<Parsing> parsingOf(MethodCallExpr call) {
        if (call.getScope().isEmpty() || call.getArguments().isEmpty()) {
            return Optional.empty();
        }
        String owner = MethodCalls.receiverName(call.getScope().get());
        String method = call.getNameAsString();
        int arguments = call.getArguments().size();
        if (VALUE_OF.equals(method) && arguments == 1 && enums.contains(owner)) {
            return Optional.of(new Parsing(call.getArgument(0), ILLEGAL_ARGUMENT));
        }
        if (VALUE_OF.equals(method) && arguments == 2 && ENUM.equals(owner)) {
            return Optional.of(new Parsing(call.getArgument(1), ILLEGAL_ARGUMENT));
        }
        if (MethodCalls.isCallOn(call, UUID, FROM_STRING)) {
            return Optional.of(new Parsing(call.getArgument(0), ILLEGAL_ARGUMENT));
        }
        return PARSE.equals(method) && TEMPORAL_TYPES.contains(owner)
                ? Optional.of(new Parsing(call.getArgument(0), DATE_TIME_PARSE))
                : Optional.empty();
    }

    // Ошибку уже ждут: общий обработчик отвечает на это исключение, вызов стоит в try с подходящим catch
    // либо значение проверено до разбора
    private boolean isExpected(MethodCallExpr call, Parsing parsing) {
        Set<String> handlers = new HashSet<>(ANY_HANDLERS);
        if (DATE_TIME_PARSE.equals(parsing.exception())) {
            handlers.addAll(TEMPORAL_HANDLERS);
        } else {
            handlers.add(ILLEGAL_ARGUMENT);
        }
        boolean answeredByAdvice = answered.stream()
                .anyMatch(exception -> handlers.contains(exception) && !ANY_HANDLERS.contains(exception));
        return answeredByAdvice || isCaught(call, handlers) || isCheckedBefore(call)
                || isCheckedByPattern(parsing.text()) || isValidatedOnEveryPath(call, rootsOf(List.of(parsing.text())));
    }

    // request.getPhone(), где на поле phone класса запроса стоит @Pattern
    private boolean isCheckedByPattern(Expression text) {
        Expression value = Nodes.unwrap(text);
        if (!value.isMethodCallExpr() || value.asMethodCallExpr().getScope().isEmpty()) {
            return false;
        }
        MethodCallExpr getter = value.asMethodCallExpr();
        Matcher name = GETTER.matcher(getter.getNameAsString());
        if (!name.matches()) {
            return false;
        }
        String property = Character.toLowerCase(name.group(1).charAt(0)) + name.group(1).substring(1);
        return LocalTypes.typeOf(getter.getScope().get())
                .flatMap(classes::find)
                .flatMap(type -> type.getFieldByName(property))
                .filter(field -> Annotations.has(field, PATTERN_ANNOTATION))
                .isPresent();
    }

    // Значение проверено, если проверка стоит перед разбором на каждом пути к нему: в самом методе либо
    // в каждом из тех, кто его вызывает. Один непроверенный путь - и плохая строка дойдет до разбора
    private boolean isValidatedOnEveryPath(MethodCallExpr call, Set<String> roots) {
        Optional<MethodDeclaration> method = call.findAncestor(MethodDeclaration.class);
        Set<MethodDeclaration> visited = Collections.newSetFromMap(new IdentityHashMap<>());
        return method.isPresent() && isValidatedBefore(call, roots, method.get(), VALIDATION_DEPTH, visited);
    }

    private boolean isValidatedBefore(
            MethodCallExpr site, Set<String> roots, MethodDeclaration method, int depth, Set<MethodDeclaration> visited) {
        boolean here = method.findAll(MethodCallExpr.class).stream()
                .filter(other -> isBefore(other, site) && isValidation(other))
                .anyMatch(other -> !Collections.disjoint(rootsOf(other.getArguments()), roots));
        if (here) {
            return true;
        }
        List<CallGraph.Call> callers = graph.callsTo(method);
        // Вызывающих нет - это вход в приложение: проверять значение раньше было некому
        if (depth == 0 || callers.isEmpty() || !visited.add(method)) {
            return false;
        }
        return callers.stream().allMatch(caller -> isValidatedBefore(
                caller.site(), rootsOf(caller.site().getArguments()), caller.caller(), depth - 1, visited));
    }

    private boolean isValidation(MethodCallExpr call) {
        // Проверка на null о содержимом строки ничего не говорит
        if (NULL_CHECKS.contains(call.getNameAsString())) {
            return false;
        }
        return VALIDATION.matcher(call.getNameAsString()).matches()
                || call.getScope().filter(scope -> VALIDATOR.matcher(MethodCalls.receiverName(scope)).matches()).isPresent();
    }

    // request.getGender() -> request: объект, о котором идет речь
    private Set<String> rootsOf(List<? extends Expression> expressions) {
        Set<String> roots = new HashSet<>();
        for (Expression expression : expressions) {
            expression.findAll(NameExpr.class).forEach(name -> roots.add(name.getNameAsString()));
        }
        return roots;
    }

    private boolean isBefore(Node node, Node other) {
        return node.getEnd().isPresent() && other.getBegin().isPresent()
                && node.getEnd().get().isBefore(other.getBegin().get());
    }

    private boolean isCaught(MethodCallExpr call, Set<String> handlers) {
        Node child = call;
        Node current = call.getParentNode().orElse(null);
        while (current != null && !(current instanceof BodyDeclaration<?>)) {
            if (current instanceof TryStmt statement && statement.getTryBlock() == child && catches(statement, handlers)) {
                return true;
            }
            child = current;
            current = current.getParentNode().orElse(null);
        }
        return false;
    }

    private boolean catches(TryStmt statement, Set<String> handlers) {
        return statement.getCatchClauses().stream()
                .map(clause -> clause.getParameter().getType())
                .flatMap(type -> type.isUnionType()
                        ? type.asUnionType().getElements().stream().map(element -> (Type) element)
                        : Stream.of(type))
                .map(LocalTypes::typeName)
                .anyMatch(handlers::contains);
    }

    private boolean isCheckedBefore(MethodCallExpr call) {
        Optional<Node> callable = Nodes.enclosingCallable(call);
        return callable.isPresent() && callable.get().findAll(MethodCallExpr.class).stream()
                .filter(other -> ENUM_CHECKS.contains(other.getNameAsString()))
                // values() есть и у карты: проверкой считается только перебор значений перечисления
                .anyMatch(other -> !VALUES.equals(other.getNameAsString())
                        || other.getScope().filter(scope -> enums.contains(MethodCalls.receiverName(scope))).isPresent());
    }

    // @ExceptionHandler(IllegalArgumentException.class) либо @ExceptionHandler на методе с таким параметром
    private Set<String> handledBy(AnnotationExpr annotation) {
        Set<String> exceptions = new HashSet<>();
        annotation.findAll(ClassExpr.class).forEach(type -> exceptions.add(LocalTypes.typeName(type.getType())));
        if (exceptions.isEmpty()) {
            annotation.getParentNode()
                    .filter(parent -> parent instanceof MethodDeclaration)
                    .map(parent -> (MethodDeclaration) parent)
                    .ifPresent(method -> method.getParameters()
                            .forEach(parameter -> exceptions.add(LocalTypes.typeName(parameter.getType()))));
        }
        return exceptions;
    }
}
