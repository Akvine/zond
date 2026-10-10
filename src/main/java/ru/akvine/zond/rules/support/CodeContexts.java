package ru.akvine.zond.rules.support;

import com.github.javaparser.ast.Node;
import com.github.javaparser.ast.body.BodyDeclaration;
import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration;
import com.github.javaparser.ast.body.ConstructorDeclaration;
import com.github.javaparser.ast.body.EnumDeclaration;
import com.github.javaparser.ast.body.FieldDeclaration;
import com.github.javaparser.ast.body.InitializerDeclaration;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.body.TypeDeclaration;
import com.github.javaparser.ast.expr.AssignExpr;
import com.github.javaparser.ast.expr.ConditionalExpr;
import com.github.javaparser.ast.expr.LambdaExpr;
import com.github.javaparser.ast.expr.MethodCallExpr;
import com.github.javaparser.ast.expr.ObjectCreationExpr;
import com.github.javaparser.ast.expr.UnaryExpr;
import com.github.javaparser.ast.nodeTypes.NodeWithArguments;
import com.github.javaparser.ast.stmt.BlockStmt;
import com.github.javaparser.ast.stmt.CatchClause;
import com.github.javaparser.ast.stmt.DoStmt;
import com.github.javaparser.ast.stmt.ForStmt;
import com.github.javaparser.ast.stmt.IfStmt;
import com.github.javaparser.ast.stmt.Statement;
import com.github.javaparser.ast.stmt.SwitchEntry;
import com.github.javaparser.ast.stmt.ThrowStmt;
import com.github.javaparser.ast.stmt.TryStmt;
import com.github.javaparser.ast.stmt.WhileStmt;
import lombok.experimental.UtilityClass;

import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Обстоятельства, в которых выполняется код: запуск приложения, обработка ошибки, цикл повторных попыток,
 * граница задачи. Одно и то же действие в них значит разное: запись в лог в цикле - поток записей, а в catch
 * внутри цикла - сообщение об ошибке; падение на повторе ключа в обычном методе - авария, а при запуске -
 * вовремя найденная ошибка настройки. Правила спрашивают об этом здесь, чтобы отвечать одинаково.
 */
@UtilityClass
public class CodeContexts {
    // Методы, которые контейнер вызывает один раз, пока приложение запускается
    private static final Set<String> STARTUP_ANNOTATIONS = Set.of("PostConstruct", "Bean", "Autowired", "Inject");
    private static final String EVENT_LISTENER = "EventListener";
    private static final Pattern STARTUP_EVENT = Pattern.compile(
            "Application(Ready|Started|Starting|Prepared|EnvironmentPrepared)Event|Context(Refreshed|Started)Event");
    private static final String AFTER_PROPERTIES_SET = "afterPropertiesSet";
    private static final String ON_APPLICATION_EVENT = "onApplicationEvent";
    private static final String RUN = "run";
    private static final Set<String> RUNNER_INTERFACES = Set.of("CommandLineRunner", "ApplicationRunner");

    // Счетчик или предел цикла назван попыткой: attempt, retry, retries, maxTries.
    // "tries" берется только отдельным словом: иначе подошли бы entries и countries
    private static final Pattern RETRY_NAME = Pattern.compile("(?i:attempt|retry|retries)|(?<![a-zA-Z])tries|Tries");

    // Переменная, которой листают страницы: offset, page, pageNumber, cursor, lastId
    private static final Pattern PAGING_NAME = Pattern.compile("(?i)offset|page|cursor|lastId|chunk");

    // Метод, с которого начинается самостоятельная задача: выше него исключение ловить уже некому
    private static final Set<String> BOUNDARY_ANNOTATIONS = Set.of(
            "Scheduled", "Schedules", "Async", "EventListener", "TransactionalEventListener", "KafkaListener",
            "RabbitListener", "JmsListener", "SqsListener", "StreamListener", "ExceptionHandler", "PreDestroy");
    private static final Set<String> BOUNDARY_METHODS =
            Set.of("main", "doFilter", "doFilterInternal", "onMessage", "executeInternal");
    // run() и call() - вход в задачу, только когда метод реализует Runnable, Callable и подобные
    private static final Set<String> TASK_METHODS = Set.of("run", "call");
    private static final Set<String> TASK_TYPES = Set.of(
            "Runnable", "Callable", "Thread", "TimerTask", "CommandLineRunner", "ApplicationRunner", "Job", "Tasklet");
    // Вызовы, которые отдают лямбду на выполнение другому потоку
    private static final Set<String> EXECUTOR_METHODS = Set.of(
            "submit", "execute", "schedule", "scheduleAtFixedRate", "scheduleWithFixedDelay", "runAsync",
            "supplyAsync", "invokeLater", "start");
    private static final String THREAD = "Thread";
    private static final String FUNCTIONAL_INTERFACE = "FunctionalInterface";

    /**
     * @return true, если узел выполняется один раз при запуске приложения или загрузке класса: статическая
     * инициализация, создание бина-одиночки, {@code @PostConstruct}, {@code @Bean} и методы, вызываемые только из них
     */
    public boolean isStartup(Node node) {
        Optional<TypeDeclaration<?>> type = enclosingType(node);
        if (type.isEmpty()) {
            return false;
        }
        Optional<Node> callable = Nodes.enclosingCallable(node).filter(type.get()::isAncestorOf);
        if (callable.isEmpty()) {
            // Значение поля: статическое вычисляется при загрузке класса, обычное - при создании объекта
            return node.findAncestor(FieldDeclaration.class)
                    .filter(field -> field.isStatic() || isCreatedAtStartup(type.get()))
                    .isPresent();
        }
        if (callable.get() instanceof InitializerDeclaration initializer) {
            return initializer.isStatic() || isCreatedAtStartup(type.get());
        }
        if (callable.get() instanceof ConstructorDeclaration) {
            return isCreatedAtStartup(type.get());
        }
        return type.get() instanceof ClassOrInterfaceDeclaration declaration
                && startupMethods(declaration).contains(callable.get());
    }

    /**
     * @return true для метода, который контейнер вызывает при запуске, и для закрытого метода, вызываемого
     * только из таких
     */
    public boolean isStartupMethod(MethodDeclaration method) {
        return method.getParentNode()
                .filter(parent -> parent instanceof ClassOrInterfaceDeclaration)
                .map(parent -> (ClassOrInterfaceDeclaration) parent)
                .filter(type -> startupMethods(type).contains(method))
                .isPresent();
    }

    /**
     * @return методы класса, которые выполняются при запуске: помеченные для контейнера и закрытые методы,
     * которые вызываются только из них, из конструктора бина или из блока инициализации
     */
    public Set<MethodDeclaration> startupMethods(ClassOrInterfaceDeclaration type) {
        Set<MethodDeclaration> startup = new HashSet<>();
        for (MethodDeclaration method : type.getMethods()) {
            if (isStartupEntry(method, type)) {
                startup.add(method);
            }
        }
        boolean added = true;
        while (added) {
            added = false;
            for (MethodDeclaration method : type.getMethods()) {
                if (method.isPrivate() && !startup.contains(method) && isCalledOnlyAtStartup(method, type, startup)) {
                    startup.add(method);
                    added = true;
                }
            }
        }
        return startup;
    }

    /**
     * @return true, если узел стоит в catch: это путь ошибки, а не обычная работа
     */
    public boolean isInCatch(Node node) {
        return findWithinCallable(node, CatchClause.class).isPresent();
    }

    /**
     * @return true, если узел выполняется не на каждой итерации, а при условии: внутри if, catch, switch
     * или условного выражения, которые лежат в теле цикла
     */
    public boolean isConditionalInIteration(Node node) {
        Optional<Node> iteration = Loops.enclosingIteration(node);
        if (iteration.isEmpty()) {
            return false;
        }
        Node current = node.getParentNode().orElse(null);
        while (current != null && current != iteration.get()) {
            if (current instanceof IfStmt || current instanceof CatchClause || current instanceof SwitchEntry
                    || current instanceof ConditionalExpr) {
                return true;
            }
            current = current.getParentNode().orElse(null);
        }
        return false;
    }

    /**
     * Цикл повторных попыток: одно действие повторяют, пока не получится. Число его итераций - это число
     * попыток, а не объем данных, поэтому запрос или запись в лог внутри него не умножаются на число элементов.
     */
    public boolean isRetryLoop(Node iteration) {
        String header;
        Statement body;
        if (iteration instanceof ForStmt loop) {
            header = loop.getInitialization().toString() + loop.getCompare().map(Node::toString).orElse("")
                    + loop.getUpdate().toString();
            body = loop.getBody();
        } else if (iteration instanceof WhileStmt loop) {
            header = loop.getCondition().toString();
            body = loop.getBody();
        } else if (iteration instanceof DoStmt loop) {
            header = loop.getCondition().toString();
            body = loop.getBody();
        } else {
            // for-each и лямбда стрима перебирают данные
            return false;
        }
        return RETRY_NAME.matcher(header).find() || repeatsUntilSuccess(body);
    }

    /**
     * Постраничное чтение: цикл идет по страницам, сдвигая смещение, номер страницы или курсор. Запрос на каждую
     * страницу - то, ради чего такой цикл пишут, а не запрос на каждый элемент.
     */
    public boolean isPagingLoop(Node iteration) {
        if (!(iteration instanceof ForStmt || iteration instanceof WhileStmt || iteration instanceof DoStmt)) {
            return false;
        }
        boolean assigned = iteration.findAll(AssignExpr.class).stream()
                .anyMatch(assignment -> PAGING_NAME.matcher(assignment.getTarget().toString()).find());
        return assigned || iteration.findAll(UnaryExpr.class).stream()
                .filter(unary -> unary.getOperator() != UnaryExpr.Operator.LOGICAL_COMPLEMENT
                        && unary.getOperator() != UnaryExpr.Operator.MINUS)
                .anyMatch(unary -> PAGING_NAME.matcher(unary.getExpression().toString()).find());
    }

    /**
     * @return true, если узел повторяется по числу элементов данных: он лежит в цикле или в поэлементной
     * лямбде, и это не цикл повторных попыток
     */
    public boolean isRepeatedOverData(Node node) {
        Optional<Node> iteration = Loops.enclosingIteration(node);
        while (iteration.isPresent()) {
            if (!isRetryLoop(iteration.get()) && !isPagingLoop(iteration.get())) {
                return true;
            }
            iteration = Loops.enclosingIteration(iteration.get());
        }
        return false;
    }

    /**
     * Граница задачи: выше этого catch исключение ловить некому либо незачем. Это метод, с которого задача
     * начинается ({@code @Scheduled}, слушатель, run() потока), лямбда, отданная исполнителю, и обработка одного
     * элемента в цикле, где ошибка одного не должна останавливать остальные, и уборка в блоке finally.
     */
    public boolean isTaskBoundary(CatchClause clause) {
        Optional<TryStmt> tryStatement = clause.getParentNode()
                .filter(parent -> parent instanceof TryStmt)
                .map(parent -> (TryStmt) parent);
        if (tryStatement.isEmpty()) {
            return false;
        }
        if (isItemBoundary(tryStatement.get()) || isInExecutorLambda(clause) || isCleanup(tryStatement.get())) {
            return true;
        }
        return Nodes.enclosingCallable(clause)
                .filter(callable -> callable instanceof MethodDeclaration)
                .map(callable -> (MethodDeclaration) callable)
                .filter(CodeContexts::isTaskEntry)
                .isPresent();
    }

    /**
     * @return true, если catch передает пойманное исключение дальше - в лог, обработчику, в результат:
     * ошибка не проглочена. Обращение только к его тексту (e.getMessage()) не считается: стек при этом теряется
     */
    public boolean passesExceptionOn(CatchClause clause) {
        String exception = clause.getParameter().getNameAsString();
        return clause.getBody().findAll(Node.class).stream()
                .filter(node -> node instanceof NodeWithArguments<?>)
                .map(node -> (NodeWithArguments<?>) node)
                .flatMap(call -> call.getArguments().stream())
                .map(Nodes::unwrap)
                .anyMatch(argument -> argument.isNameExpr() && argument.asNameExpr().getNameAsString().equals(exception));
    }

    /**
     * @return true, если catch записывает в лог само пойманное исключение: его стек уже сохранен
     */
    public boolean logsException(CatchClause clause) {
        String exception = clause.getParameter().getNameAsString();
        return clause.getBody().findAll(MethodCallExpr.class).stream()
                .filter(Loggers::isLogCall)
                .flatMap(call -> call.getArguments().stream())
                .map(Nodes::unwrap)
                .anyMatch(argument -> argument.isNameExpr() && argument.asNameExpr().getNameAsString().equals(exception));
    }

    /**
     * @return true, если catch выбрасывает пойманное исключение дальше - само либо причиной нового:
     * стек уходит вместе с ним и не теряется
     */
    public boolean rethrowsWithCause(CatchClause clause) {
        String exception = clause.getParameter().getNameAsString();
        return clause.getBody().findAll(ThrowStmt.class).stream()
                .map(ThrowStmt::getExpression)
                .map(Nodes::unwrap)
                .anyMatch(thrown -> thrown.isNameExpr() && thrown.asNameExpr().getNameAsString().equals(exception)
                        || thrown.isObjectCreationExpr() && thrown.asObjectCreationExpr().getArguments().stream()
                        .map(Nodes::unwrap)
                        .anyMatch(argument -> argument.isNameExpr()
                                && argument.asNameExpr().getNameAsString().equals(exception)));
    }

    /**
     * @return true для метода функционального интерфейса: его сигнатура подстраивается под лямбды, которые
     * в него передадут
     */
    public boolean isFunctionalInterfaceMethod(MethodDeclaration method) {
        return method.getParentNode()
                .filter(parent -> parent instanceof ClassOrInterfaceDeclaration)
                .map(parent -> (ClassOrInterfaceDeclaration) parent)
                .filter(ClassOrInterfaceDeclaration::isInterface)
                .filter(type -> Annotations.has(type, FUNCTIONAL_INTERFACE) || type.getMethods().stream()
                        .filter(candidate -> candidate.getBody().isEmpty() && !candidate.isStatic())
                        .count() == 1)
                .isPresent() && method.getBody().isEmpty();
    }

    private boolean isStartupEntry(MethodDeclaration method, ClassOrInterfaceDeclaration type) {
        if (Annotations.hasAny(method, STARTUP_ANNOTATIONS) || AFTER_PROPERTIES_SET.equals(method.getNameAsString())) {
            return true;
        }
        // Слушатель считается запуском, только если слушает событие запуска: остальные события приходят во время работы
        boolean listensToStartup = method.getParameters().stream()
                .anyMatch(parameter -> STARTUP_EVENT.matcher(parameter.getType().asString()).find())
                || Annotations.find(method, EVENT_LISTENER)
                .filter(annotation -> STARTUP_EVENT.matcher(annotation.toString()).find())
                .isPresent();
        if (listensToStartup && (Annotations.has(method, EVENT_LISTENER) || ON_APPLICATION_EVENT.equals(method.getNameAsString()))) {
            return true;
        }
        return RUN.equals(method.getNameAsString()) && type.getImplementedTypes().stream()
                .anyMatch(implemented -> RUNNER_INTERFACES.contains(implemented.getNameAsString()));
    }

    private boolean isCalledOnlyAtStartup(MethodDeclaration method, ClassOrInterfaceDeclaration type, Set<MethodDeclaration> startup) {
        List<MethodCallExpr> calls = type.findAll(MethodCallExpr.class).stream()
                .filter(call -> call.getNameAsString().equals(method.getNameAsString()))
                .filter(call -> call.getScope().filter(scope -> !scope.isThisExpr()).isEmpty())
                .toList();
        boolean createdAtStartup = isCreatedAtStartup(type);
        return !calls.isEmpty() && calls.stream().allMatch(call -> Nodes.enclosingCallable(call)
                .filter(callable -> callable instanceof MethodDeclaration ? startup.contains(callable) : createdAtStartup)
                .isPresent());
    }

    // Бин-одиночку контейнер создает при запуске; константы перечисления - при загрузке класса
    private boolean isCreatedAtStartup(TypeDeclaration<?> type) {
        return type instanceof EnumDeclaration
                || type instanceof ClassOrInterfaceDeclaration declaration && BeanScopes.isSingletonBean(declaration);
    }

    private Optional<TypeDeclaration<?>> enclosingType(Node node) {
        Node current = node.getParentNode().orElse(null);
        while (current != null) {
            if (current instanceof TypeDeclaration<?> type) {
                return Optional.of(type);
            }
            current = current.getParentNode().orElse(null);
        }
        return Optional.empty();
    }

    // while (true) { try { ...; return result; } catch (...) { подождать } }: выход из цикла - последней строкой try
    private boolean repeatsUntilSuccess(Statement body) {
        if (!body.isBlockStmt()) {
            return false;
        }
        return body.asBlockStmt().getStatements().stream()
                .filter(Statement::isTryStmt)
                .map(Statement::asTryStmt)
                .anyMatch(tryStatement -> !tryStatement.getCatchClauses().isEmpty()
                        && last(tryStatement.getTryBlock())
                        .filter(last -> last.isReturnStmt() || last.isBreakStmt())
                        .isPresent()
                        && tryStatement.getCatchClauses().stream().anyMatch(clause -> !endsWithThrow(clause.getBody())));
    }

    private boolean endsWithThrow(BlockStmt block) {
        return last(block).filter(last -> last instanceof ThrowStmt).isPresent();
    }

    private Optional<Statement> last(BlockStmt block) {
        return block.getStatements().isEmpty()
                ? Optional.empty()
                : Optional.of(block.getStatement(block.getStatements().size() - 1));
    }

    // try стоит прямо в теле цикла или поэлементной лямбды: каждый элемент обрабатывается под своей защитой
    private boolean isItemBoundary(TryStmt tryStatement) {
        Optional<Node> holder = tryStatement.getParentNode()
                .filter(parent -> parent instanceof BlockStmt)
                .flatMap(Node::getParentNode);
        return holder.isPresent()
                && Loops.enclosingIteration(tryStatement).filter(iteration -> iteration == holder.get()).isPresent();
    }

    // finally { try { lock.unlock(); } catch (Exception e) { ... } }: сбой уборки не должен заслонить
    // исходное исключение, поэтому ловят все
    private boolean isCleanup(TryStmt tryStatement) {
        Optional<Node> block = tryStatement.getParentNode().filter(parent -> parent instanceof BlockStmt);
        return block.flatMap(Node::getParentNode)
                .filter(parent -> parent instanceof TryStmt outer
                        && outer.getFinallyBlock().filter(finallyBlock -> finallyBlock == block.get()).isPresent())
                .isPresent();
    }

    // executor.submit(() -> { try { ... } catch (Exception e) { ... } }), new Thread(() -> ...)
    private boolean isInExecutorLambda(Node node) {
        return findWithinCallable(node, LambdaExpr.class)
                .flatMap(Node::getParentNode)
                .filter(parent -> parent instanceof MethodCallExpr call && EXECUTOR_METHODS.contains(call.getNameAsString())
                        || parent instanceof ObjectCreationExpr creation && THREAD.equals(creation.getType().getNameAsString()))
                .isPresent();
    }

    private boolean isTaskEntry(MethodDeclaration method) {
        String name = method.getNameAsString();
        if (Annotations.hasAny(method, BOUNDARY_ANNOTATIONS) || BOUNDARY_METHODS.contains(name)) {
            return true;
        }
        if (!TASK_METHODS.contains(name)) {
            return false;
        }
        boolean implementsTask = method.getParentNode()
                .filter(parent -> parent instanceof ClassOrInterfaceDeclaration)
                .map(parent -> (ClassOrInterfaceDeclaration) parent)
                .filter(type -> type.getImplementedTypes().stream().anyMatch(implemented -> TASK_TYPES.contains(implemented.getNameAsString()))
                        || type.getExtendedTypes().stream().anyMatch(extended -> TASK_TYPES.contains(extended.getNameAsString())))
                .isPresent();
        // Анонимный new Runnable() { @Override public void run() {...} }
        boolean anonymousTask = method.getParentNode()
                .filter(parent -> parent instanceof ObjectCreationExpr creation && TASK_TYPES.contains(creation.getType().getNameAsString()))
                .isPresent();
        return implementsTask || anonymousTask;
    }

    // Ближайший предок нужного вида в пределах текущего метода
    private <T extends Node> Optional<T> findWithinCallable(Node node, Class<T> kind) {
        Node current = node.getParentNode().orElse(null);
        while (current != null && !(current instanceof BodyDeclaration<?>)) {
            if (kind.isInstance(current)) {
                return Optional.of(kind.cast(current));
            }
            current = current.getParentNode().orElse(null);
        }
        return Optional.empty();
    }
}
