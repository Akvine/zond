package ru.akvine.zond.rules.performance;

import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration;
import com.github.javaparser.ast.body.FieldDeclaration;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.body.TypeDeclaration;
import com.github.javaparser.ast.expr.AnnotationExpr;
import com.github.javaparser.ast.expr.AssignExpr;
import com.github.javaparser.ast.expr.Expression;
import com.github.javaparser.ast.expr.MethodCallExpr;
import com.github.javaparser.ast.expr.NameExpr;
import com.github.javaparser.ast.expr.ObjectCreationExpr;
import com.github.javaparser.ast.expr.StringLiteralExpr;
import com.github.javaparser.ast.expr.UnaryExpr;
import com.github.javaparser.ast.stmt.BlockStmt;
import org.springframework.stereotype.Component;
import ru.akvine.zond.enums.Confidence;
import ru.akvine.zond.enums.ErrorLevel;
import ru.akvine.zond.enums.ErrorType;
import ru.akvine.zond.models.RuleParameter;
import ru.akvine.zond.models.SourceFile;
import ru.akvine.zond.models.Violation;
import ru.akvine.zond.rules.AbstractRule;
import ru.akvine.zond.rules.ProjectRule;
import ru.akvine.zond.rules.RuleCodes;
import ru.akvine.zond.rules.support.Annotations;
import ru.akvine.zond.rules.support.CallGraph;
import ru.akvine.zond.rules.support.LocalTypes;
import ru.akvine.zond.rules.support.MethodCalls;
import ru.akvine.zond.rules.support.Repositories;
import ru.akvine.zond.rules.support.TestClasses;
import ru.akvine.zond.rules.support.TransactionalAnnotations;
import ru.akvine.zond.rules.support.Types;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * Транзакция, которая только читает, но объявлена без readOnly = true. Hibernate хранит снимок каждой
 * загруженной сущности, чтобы перед коммитом найти изменения, и сбрасывает контекст; с readOnly снимков нет,
 * а запросы может обслужить реплика.
 * <p>
 * Ошибиться в сторону readOnly нельзя: запись в такой транзакции не сохранится. Поэтому находка есть, только
 * если про каждый вызов в методе и в методах, которые он вызывает, известно, что он ничего не меняет.
 */
@Component
public class MissingReadOnlyTransactionRule extends AbstractRule implements ProjectRule {
    private static final RuleParameter MAX_CALL_DEPTH = new RuleParameter(
            "max-call-depth", 3, "На сколько вызовов вглубь проверять, что метод только читает");

    // findById, getAllByStatus, countByOwner, existsByEmail, searchActive, streamAll
    private static final Pattern READ_METHOD = Pattern.compile(
            "^(find|get|read|query|count|exists|search|load|stream|list|fetch|select)([A-Z].*)?$");
    // findByIdForUpdate, lockById: SELECT ... FOR UPDATE в readOnly-транзакции база отклонит
    private static final Pattern LOCKING_READ = Pattern.compile(".*(ForUpdate|Lock|ForShare).*|^lock.*");
    // save, delete, update, insert, remove, persist, merge, flush, publishEvent, send, execute
    private static final Pattern WRITE_METHOD = Pattern.compile(
            "^(save|delete|update|insert|remove|persist|merge|flush|refresh|lock|publish|send|execute|create"
                    + "|add|put|set|mark|apply|process|handle|register|increment|decrement|clear|reset)([A-Z].*)?$");
    private static final Pattern SETTER = Pattern.compile("^set[A-Z].*");
    // Чтение и вычисление, а не изменение: order.getItems(), order.isPaid(), order.toDto(), order.equals(other)
    private static final Pattern QUERY_METHOD = Pattern.compile(
            "^(get|is|has|to|as|equals|hashCode|compare|contains|size|stream|length|count|find|matches|format)([A-Z].*)?$");
    // DELETE ... RETURNING через queryForList - запись, хоть и вызов "query"
    private static final Pattern SQL_WRITE = Pattern.compile(
            "(?i)\\b(delete\\s+from|insert\\s+into|merge\\s+into|truncate)\\b|\\bupdate\\s+\\w+\\s+set\\b");
    // SELECT ... FOR UPDATE, FOR SHARE, LOCK IN SHARE MODE, WITH (UPDLOCK)
    private static final Pattern SQL_LOCK = Pattern.compile(
            "(?i)\\bfor\\s+(update|share|no\\s+key\\s+update)\\b|lock\\s+in\\s+share\\s+mode|updlock");
    // Транзакция нужна, чтобы запрос ушел на ведущую базу: readOnly отправит его на реплику
    private static final Pattern ROUTING_COMMENT = Pattern.compile(
            "(?i)\\b(master|primary|slave|replica|read-?write)\\b|主库|从库|ведущ|реплик");
    private static final Set<String> ENTITY_MANAGER_READS = Set.of(
            "find", "createQuery", "createNamedQuery", "getReference", "getCriteriaBuilder", "contains");
    private static final Pattern ENTITY_MANAGER = Pattern.compile(".*(entityManager|session)$", Pattern.CASE_INSENSITIVE);
    private static final Pattern JDBC_TEMPLATE = Pattern.compile(".*jdbcTemplate$", Pattern.CASE_INSENSITIVE);
    private static final Set<String> JDK_RECEIVER_SUFFIXES = Set.of(
            "List", "Set", "Map", "Collection", "Queue", "Deque", "String", "StringBuilder", "Optional", "Stream");
    // Аннотации метода репозитория, после которых он перестает быть чтением
    private static final Set<String> WRITING_QUERY_ANNOTATIONS = Set.of("Modifying", "Lock");
    private static final String ASYNC = "Async";
    // У jakarta.transaction.Transactional нет readOnly: предложить нечего
    private static final Set<String> JTA_TRANSACTIONAL = Set.of(
            "jakarta.transaction.Transactional", "javax.transaction.Transactional");
    private static final String READ_ONLY = "readOnly";
    private static final String MAPSTRUCT_PACKAGE = "org.mapstruct";
    private static final String MAPPER = "Mapper";
    private static final Set<String> MYBATIS_BASE_MAPPERS = Set.of("BaseMapper", "Mapper");

    @Override
    public List<RuleParameter> parameters() {
        return List.of(MAX_CALL_DEPTH);
    }

    @Override
    public String code() {
        return RuleCodes.MISSING_READ_ONLY_TRANSACTION_RULE_CODE;
    }

    @Override
    public String description() {
        return "Сканирует код и ищет методы с @Transactional без readOnly = true, которые только читают данные";
    }

    @Override
    public List<Violation> checkProject(List<SourceFile> sourceFiles) {
        CallGraph graph = CallGraph.of(sourceFiles);
        List<Violation> violations = new ArrayList<>();
        for (SourceFile sourceFile : sourceFiles) {
            boolean jta = sourceFile.unit().getImports().stream()
                    .anyMatch(declaration -> JTA_TRANSACTIONAL.contains(declaration.getNameAsString()));
            if (jta) {
                continue;
            }
            for (MethodDeclaration method : sourceFile.unit().findAll(MethodDeclaration.class)) {
                Optional<AnnotationExpr> transactional = writableTransaction(method);
                if (transactional.isEmpty()) {
                    continue;
                }
                // findByIdForUpdate сам блокирует строки; комментарий о ведущей базе - транзакция нужна, чтобы
                // запрос ушел на нее, а readOnly отправит его на реплику
                if (LOCKING_READ.matcher(method.getNameAsString()).matches()
                        || WRITE_METHOD.matcher(method.getNameAsString()).matches() || mentionsRouting(method)) {
                    continue;
                }
                Reads reads = new Reads();
                analyze(method, graph, 0, new HashSet<>(), reads);
                if (!reads.unsafe && reads.first != null) {
                    violations.add(violation(sourceFile, method.getName(),
                            "Метод '" + method.getNameAsString() + "' только читает данные ('" + reads.first
                                    + "'), но транзакция у него не readOnly: Hibernate хранит снимок каждой"
                                    + " загруженной сущности, чтобы перед коммитом искать изменения; поставьте"
                                    + " @Transactional(readOnly = true) - снимков не будет, а запросы сможет"
                                    + " обслужить реплика").withConfidence(reads.guessed ? Confidence.SUSPICION : Confidence.PROBABLE));
                }
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

    /**
     * Что известно о методе: первое найденное чтение, нашлось ли что-то, что может писать или чего не разобрать,
     * и угадан ли хоть один вызов по имени, а не найден по типам
     */
    private static final class Reads {
        private String first;
        private boolean unsafe;
        private boolean guessed;
    }

    // Обычная транзакция открывается этим методом: с readOnly = false, с собственным режимом
    // (REQUIRES_NEW) и на приватном методе (там @Transactional не работает) ничего не предлагаем
    private Optional<AnnotationExpr> writableTransaction(MethodDeclaration method) {
        if (method.isPrivate() || method.getBody().isEmpty() || TestClasses.isInside(method)
                || method.getParentNode().filter(parent -> parent instanceof ClassOrInterfaceDeclaration type
                && !type.isInterface()).isEmpty()) {
            return Optional.empty();
        }
        return TransactionalAnnotations.findEffective(method)
                .filter(annotation -> !TransactionalAnnotations.isReadOnly(annotation))
                .filter(annotation -> !mentionsReadOnly(annotation))
                .filter(annotation -> TransactionalAnnotations.findOwnBehaviorPropagation(annotation).isEmpty());
    }

    // readOnly = false записано явно: решение принято
    private boolean mentionsReadOnly(AnnotationExpr annotation) {
        return annotation.isNormalAnnotationExpr() && annotation.asNormalAnnotationExpr().getPairs().stream()
                .anyMatch(pair -> READ_ONLY.equals(pair.getNameAsString()));
    }

    private void analyze(MethodDeclaration method, CallGraph graph, int depth, Set<MethodDeclaration> visited,
                         Reads reads) {
        if (!visited.add(method) || method.getBody().isEmpty()) {
            return;
        }
        BlockStmt body = method.getBody().get();
        // SELECT ... FOR UPDATE в readOnly-транзакции база отклонит; this.stock -= quantity в методе сущности
        // сохранится при коммите без save
        if (body.findAll(StringLiteralExpr.class).stream().anyMatch(literal -> SQL_LOCK.matcher(literal.getValue()).find()
                || SQL_WRITE.matcher(literal.getValue()).find())
                || writesField(body)) {
            reads.unsafe = true;
            return;
        }
        for (MethodCallExpr call : body.findAll(MethodCallExpr.class)) {
            if (reads.unsafe) {
                return;
            }
            inspect(call, graph, depth, visited, reads);
        }
    }

    private void inspect(MethodCallExpr call, CallGraph graph, int depth, Set<MethodDeclaration> visited, Reads reads) {
        String name = call.getNameAsString();
        // order.setStatus(...): сущность из контекста сохранится при коммите сама, без save
        if (SETTER.matcher(name).matches() && call.getArguments().size() == 1 && !isOnNewObject(call)) {
            reads.unsafe = true;
            return;
        }
        Optional<Expression> scope = call.getScope();
        if (scope.isPresent() && isDataAccessObject(call, scope.get())) {
            boolean read = isReadAccess(call, scope.get())
                    && graph.targetsOf(call).stream().noneMatch(target -> Annotations.hasAny(target, WRITING_QUERY_ANNOTATIONS));
            if (!read) {
                reads.unsafe = true;
                return;
            }
            if (reads.first == null) {
                reads.first = MethodCalls.receiverName(scope.get()) + "." + name;
            }
            return;
        }

        List<MethodDeclaration> targets = graph.targetsOf(call);
        if (!targets.isEmpty()) {
            for (MethodDeclaration target : targets) {
                // @Async уходит в другой поток и в другую транзакцию; собственный режим (REQUIRES_NEW) - тоже,
                // но тогда метод сам решает, писать ли: не разбираем
                if (Annotations.has(target, ASYNC)) {
                    continue;
                }
                if (depth >= value(MAX_CALL_DEPTH) || TransactionalAnnotations.find(target)
                        .flatMap(TransactionalAnnotations::findOwnBehaviorPropagation).isPresent()) {
                    reads.unsafe = true;
                    return;
                }
                reads.guessed |= !graph.isExact(call);
                analyze(target, graph, depth + 1, visited, reads);
            }
            return;
        }
        // Метод другого бина, которого не нашли в проекте: что он делает, неизвестно. product.take(quantity) у
        // сущности из контекста меняет ее, даже если это не сеттер
        if (WRITE_METHOD.matcher(name).matches() && scope.filter(this::isJdkReceiver).isEmpty()
                || scope.filter(this::isUnknownBean).isPresent()
                || scope.filter(this::isLocalObject).isPresent() && !QUERY_METHOD.matcher(name).matches()
                && scope.filter(this::isJdkReceiver).isEmpty()) {
            reads.unsafe = true;
        }
    }

    // Переменная или параметр метода, а не поле класса
    private boolean isLocalObject(Expression scope) {
        return scope instanceof NameExpr && LocalTypes.findDeclaration(scope)
                .filter(declaration -> !(declaration.getParentNode().orElse(null) instanceof FieldDeclaration))
                .isPresent();
    }

    // this.stock = ..., stock -= quantity, other.count++: запись в поле объекта
    private boolean writesField(BlockStmt body) {
        Stream<Expression> targets = Stream.concat(
                body.findAll(AssignExpr.class).stream().map(AssignExpr::getTarget),
                body.findAll(UnaryExpr.class).stream()
                        .filter(unary -> unary.getOperator().name().endsWith("INCREMENT")
                                || unary.getOperator().name().endsWith("DECREMENT"))
                        .map(UnaryExpr::getExpression));
        return targets.anyMatch(target -> target.isFieldAccessExpr() || target.isNameExpr() && LocalTypes.findDeclaration(target)
                .filter(declaration -> declaration.getParentNode().orElse(null) instanceof FieldDeclaration)
                .isPresent());
    }

    // Javadoc и комментарии в теле: "routed to the master db", "читаем с основной базы"
    private boolean mentionsRouting(MethodDeclaration method) {
        String comments = Stream.concat(method.getComment().stream(), method.getAllContainedComments().stream())
                .map(comment -> comment.getContent())
                .reduce("", String::concat);
        return ROUTING_COMMENT.matcher(comments).find();
    }

    // Вызов прямо на объекте доступа: repository.findById(id), а не orElseThrow() на его результате
    private boolean isDataAccessObject(MethodCallExpr call, Expression scope) {
        if (!scope.isNameExpr() && !(scope.isFieldAccessExpr() && scope.asFieldAccessExpr().getScope().isThisExpr())) {
            return false;
        }
        String receiver = MethodCalls.receiverName(scope);
        return Repositories.isRepositoryCall(call)
                || isMyBatisMapper(scope)
                || ENTITY_MANAGER.matcher(receiver).matches()
                || JDBC_TEMPLATE.matcher(receiver).matches();
    }

    private boolean isReadAccess(MethodCallExpr call, Expression scope) {
        String name = call.getNameAsString();
        String receiver = MethodCalls.receiverName(scope);
        if (ENTITY_MANAGER.matcher(receiver).matches()) {
            return ENTITY_MANAGER_READS.contains(name);
        }
        return READ_METHOD.matcher(name).matches() && !LOCKING_READ.matcher(name).matches();
    }

    // new OrderDto() и builder: свой объект, а не сущность из контекста
    private boolean isOnNewObject(MethodCallExpr call) {
        return call.getScope()
                .flatMap(LocalTypes::findInitializer)
                .filter(initializer -> initializer instanceof ObjectCreationExpr
                        || initializer.isMethodCallExpr() && initializer.toString().contains("builder"))
                .isPresent()
                || call.getScope().filter(scope -> scope instanceof ObjectCreationExpr).isPresent();
    }

    // list.remove(x), builder.append(...): коллекция или строка, а не хранилище
    private boolean isJdkReceiver(Expression scope) {
        if (Types.isJdkType(scope)) {
            return true;
        }
        return LocalTypes.typeOf(scope)
                .filter(type -> JDK_RECEIVER_SUFFIXES.stream().anyMatch(type::endsWith))
                .isPresent();
    }

    // Поле класса с типом проекта, вызов на котором граф не разрешил: другой сервис, о котором ничего не знаем.
    // Маппер MapStruct только перекладывает поля - реализацию создает библиотека, а в базу он не ходит
    private boolean isUnknownBean(Expression scope) {
        if (!(scope instanceof NameExpr name) || LocalTypes.findField(scope, name.getNameAsString()).isEmpty()) {
            return false;
        }
        return Types.projectType(scope).filter(type -> !isMapStructMapper(type)).isPresent();
    }

    private boolean isMapStructMapper(TypeDeclaration<?> type) {
        return type.findCompilationUnit()
                .filter(unit -> unit.getImports().stream()
                        .anyMatch(declaration -> declaration.getNameAsString().startsWith(MAPSTRUCT_PACKAGE)))
                .isPresent();
    }

    // Маппер MyBatis (@Mapper, BaseMapper) - это доступ к БД: selectById читает, updateById пишет
    private boolean isMyBatisMapper(Expression scope) {
        return Types.projectType(scope)
                .filter(type -> !isMapStructMapper(type))
                .filter(type -> Annotations.has(type, MAPPER) || type instanceof ClassOrInterfaceDeclaration declaration
                        && declaration.getExtendedTypes().stream()
                        .anyMatch(extended -> MYBATIS_BASE_MAPPERS.contains(extended.getNameAsString())))
                .isPresent();
    }
}
