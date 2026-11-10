package ru.akvine.zond.rules.security;

import com.github.javaparser.ast.Node;
import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.body.Parameter;
import com.github.javaparser.ast.body.VariableDeclarator;
import com.github.javaparser.ast.expr.BinaryExpr;
import com.github.javaparser.ast.expr.ClassExpr;
import com.github.javaparser.ast.expr.ConditionalExpr;
import com.github.javaparser.ast.expr.Expression;
import com.github.javaparser.ast.expr.InstanceOfExpr;
import com.github.javaparser.ast.expr.MethodCallExpr;
import com.github.javaparser.ast.expr.NameExpr;
import com.github.javaparser.ast.expr.ObjectCreationExpr;
import com.github.javaparser.ast.stmt.CatchClause;
import com.github.javaparser.ast.stmt.IfStmt;
import com.github.javaparser.ast.stmt.ReturnStmt;
import com.github.javaparser.ast.stmt.SwitchStmt;
import com.github.javaparser.ast.type.Type;
import org.springframework.stereotype.Component;
import ru.akvine.zond.enums.ErrorLevel;
import ru.akvine.zond.enums.ErrorType;
import ru.akvine.zond.models.SourceFile;
import ru.akvine.zond.models.Violation;
import ru.akvine.zond.rules.AbstractRule;
import ru.akvine.zond.rules.RuleCodes;
import ru.akvine.zond.rules.support.Annotations;
import ru.akvine.zond.rules.support.LocalTypes;
import ru.akvine.zond.rules.support.Loggers;
import ru.akvine.zond.rules.support.Nodes;
import ru.akvine.zond.rules.support.Types;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Исключение базы данных, подробности которого уходят клиенту. В его тексте - SQL-запрос, имена таблиц, колонок
 * и ограничений, адрес сервера: по ним видно устройство системы. Проверяются обработчики
 * ({@code @ExceptionHandler}, {@code ExceptionMapper}) таких исключений и {@code catch} в контроллерах.
 * Обработчики общих исключений (Exception, RuntimeException) проверяет отдельное правило.
 */
@Component
public class DatabaseExceptionToClientRule extends AbstractRule {
    private static final String EXCEPTION_HANDLER = "ExceptionHandler";
    private static final String EXCEPTION_MAPPER = "ExceptionMapper";
    private static final String TO_RESPONSE = "toResponse";
    private static final Set<String> CONTROLLERS = Set.of("RestController", "Controller");
    private static final Set<String> BROAD_EXCEPTIONS = Set.of("Exception", "Throwable", "RuntimeException");

    // Spring, JPA, Hibernate, драйверы. Исключения "запись не найдена" сюда не входят: их текст обычно пишет
    // само приложение, и он предназначен клиенту
    private static final Set<String> DATABASE_EXCEPTIONS = Set.of(
            "DataAccessException", "DataIntegrityViolationException", "DuplicateKeyException", "BadSqlGrammarException",
            "UncategorizedSQLException", "InvalidDataAccessResourceUsageException", "InvalidDataAccessApiUsageException",
            "DataAccessResourceFailureException", "CannotGetJdbcConnectionException", "QueryTimeoutException",
            "TransientDataAccessException", "NonTransientDataAccessException", "RecoverableDataAccessException",
            "ConcurrencyFailureException", "PessimisticLockingFailureException", "CannotAcquireLockException",
            "DeadlockLoserDataAccessException", "JpaSystemException", "TransactionSystemException",
            "PersistenceException", "HibernateException", "JDBCException", "GenericJDBCException",
            "JDBCConnectionException", "LockAcquisitionException", "PessimisticLockException", "LockTimeoutException",
            "MongoException", "MongoWriteException", "R2dbcException");
    // SQLException, PSQLException, SQLGrammarException, JdbcSQLSyntaxErrorException, свой OrderDataAccessException
    private static final Pattern DATABASE_NAME = Pattern.compile(".*(SQL|Sql|JDBC|Jdbc)\\w*Exception$|.*DataAccessException$");
    private static final Set<String> DATABASE_ROOTS = Set.of("SQLException", "DataAccessException", "JDBCException");
    // Одно имя у двух разных исключений: Hibernate сообщает о нарушении ограничения базы, Bean Validation -
    // о неверных данных запроса, и его текст клиенту как раз нужен
    private static final String CONSTRAINT_VIOLATION = "ConstraintViolationException";
    private static final String HIBERNATE_PACKAGE = "org.hibernate";

    private static final Set<String> DETAIL_METHODS = Set.of(
            "getMessage", "getLocalizedMessage", "toString", "getCause", "getRootCause", "getMostSpecificCause",
            "getStackTrace", "getSQL", "getSql", "getSQLState", "getSQLException", "getNextException",
            "getConstraintName", "getServerErrorMessage");
    // ExceptionUtils.getRootCauseMessage(e), NestedExceptionUtils.getMostSpecificCause(e), String.valueOf(e)
    private static final Set<String> DETAIL_HELPERS = Set.of(
            "getRootCauseMessage", "getRootCause", "getMostSpecificCause", "getStackTrace", "getMessage", "valueOf");
    // По тексту исключения выбирают ответ, а не отдают его: e.getMessage().contains("uk_users_email")
    private static final Set<String> CHECK_METHODS = Set.of(
            "contains", "containsIgnoreCase", "equals", "equalsIgnoreCase", "startsWith", "endsWith", "matches",
            "isEmpty", "isBlank", "indexOf", "nonNull", "isNull", "hasText");
    // Само исключение отдано в тело ответа: сериализатор выпишет и текст, и причину, и стек
    private static final Set<String> BODY_METHODS = Set.of("body", "ok", "entity");
    // Куда в catch контроллера нужно попасть тексту, чтобы уйти клиенту
    private static final Set<String> RESPONSE_METHODS = Set.of(
            "body", "ok", "entity", "sendError", "write", "print", "println", "addAttribute", "setDetail");
    private static final Set<String> RESPONSE_TYPES = Set.of("ResponseEntity", "ResponseStatusException", "ModelAndView");

    @Override
    public String code() {
        return RuleCodes.DATABASE_EXCEPTION_TO_CLIENT_RULE_CODE;
    }

    @Override
    public String description() {
        return "Сканирует код и ищет обработчики исключений базы данных (SQLException, DataAccessException,"
                + " PersistenceException), которые отдают клиенту текст исключения";
    }

    @Override
    public List<Violation> check(SourceFile sourceFile) {
        List<Violation> violations = new ArrayList<>();
        Set<Integer> reportedLines = new HashSet<>();
        for (MethodDeclaration method : sourceFile.unit().findAll(MethodDeclaration.class)) {
            if (method.getBody().isEmpty()) {
                continue;
            }
            for (Parameter exception : handledExceptions(method)) {
                String type = LocalTypes.typeName(exception.getType());
                for (Node leak : findLeaks(method.getBody().get(), exception.getNameAsString(), false)) {
                    if (reportedLines.add(line(leak))) {
                        violations.add(violation(sourceFile, leak, message(leak, "обработчике исключения базы данных", type)));
                    }
                }
            }
        }
        for (CatchClause clause : sourceFile.unit().findAll(CatchClause.class)) {
            Optional<String> type = databaseType(clause);
            if (type.isEmpty() || !isInController(clause)) {
                continue;
            }
            for (Node leak : findLeaks(clause.getBody(), clause.getParameter().getNameAsString(), true)) {
                if (reportedLines.add(line(leak))) {
                    violations.add(violation(sourceFile, leak, message(leak, "catch контроллера", type.get())));
                }
            }
        }
        violations.sort((left, right) -> Integer.compare(left.line(), right.line()));
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

    private String message(Node leak, String place, String type) {
        return "'" + leak + "' в " + place + " (" + type + ") уходит в ответ клиенту: в тексте такого исключения -"
                + " SQL-запрос, имена таблиц, колонок и ограничений, адрес сервера базы, по ним видно устройство"
                + " системы; запишите исключение в лог, а клиенту верните общий текст и идентификатор ошибки";
    }

    /**
     * @return параметры-исключения базы данных, если метод - обработчик: {@code @ExceptionHandler} либо
     * {@code toResponse} у {@code ExceptionMapper}. Параметр общего типа (Exception) не возвращается:
     * такой обработчик проверяет правило об общих исключениях
     */
    private List<Parameter> handledExceptions(MethodDeclaration method) {
        boolean listed = Annotations.find(method, EXCEPTION_HANDLER)
                .map(annotation -> annotation.findAll(ClassExpr.class).stream()
                        .anyMatch(type -> isDatabase(type.getType())))
                .orElse(false);
        boolean handler = Annotations.has(method, EXCEPTION_HANDLER) || isMapper(method);
        if (!handler) {
            return List.of();
        }
        return method.getParameters().stream()
                .filter(parameter -> !BROAD_EXCEPTIONS.contains(LocalTypes.typeName(parameter.getType())))
                // @ExceptionHandler(SQLException.class) Object handle(NestedRuntimeException e): тип берем из аннотации
                .filter(parameter -> isDatabase(parameter.getType())
                        || listed && LocalTypes.typeName(parameter.getType()).endsWith("Exception"))
                .toList();
    }

    // class DbMapper implements ExceptionMapper<SQLException> { public Response toResponse(SQLException e) }
    private boolean isMapper(MethodDeclaration method) {
        return TO_RESPONSE.equals(method.getNameAsString())
                && method.findAncestor(ClassOrInterfaceDeclaration.class)
                .filter(owner -> owner.getImplementedTypes().stream()
                        .anyMatch(implemented -> EXCEPTION_MAPPER.equals(implemented.getNameAsString())))
                .isPresent();
    }

    private Optional<String> databaseType(CatchClause clause) {
        Type type = clause.getParameter().getType();
        List<? extends Type> types = type.isUnionType() ? type.asUnionType().getElements() : List.of(type);
        return types.stream().filter(this::isDatabase).map(LocalTypes::typeName).findFirst();
    }

    private boolean isInController(Node node) {
        return node.findAncestor(ClassOrInterfaceDeclaration.class)
                .filter(owner -> Annotations.hasAny(owner, CONTROLLERS))
                .isPresent();
    }

    private boolean isDatabase(Type type) {
        String name = LocalTypes.typeName(type);
        if (CONSTRAINT_VIOLATION.equals(name)) {
            return isFromHibernate(type);
        }
        if (DATABASE_EXCEPTIONS.contains(name) || DATABASE_NAME.matcher(name).matches()) {
            return true;
        }
        // Свое исключение проекта - наследник исключения базы: class StorageException extends DataAccessException
        return !BROAD_EXCEPTIONS.contains(name) && Types.isKindOf(type, DATABASE_ROOTS).orElse(false);
    }

    // Полное имя в коде либо импорт из org.hibernate
    private boolean isFromHibernate(Type type) {
        if (type.isClassOrInterfaceType() && type.asClassOrInterfaceType().getScope()
                .filter(scope -> scope.asString().startsWith(HIBERNATE_PACKAGE)).isPresent()) {
            return true;
        }
        return type.findCompilationUnit()
                .filter(unit -> unit.getImports().stream()
                        .map(declaration -> declaration.getNameAsString())
                        .anyMatch(name -> name.startsWith(HIBERNATE_PACKAGE) && name.endsWith(CONSTRAINT_VIOLATION)))
                .isPresent();
    }

    /**
     * @param responseOnly считать только то, что явно уходит в ответ: return, тело ответа, sendError.
     *                     В обработчике исключения в ответ уходит все, что не лог и не проверка
     * @return места, где подробности исключения покидают приложение
     */
    private List<Node> findLeaks(Node body, String exception, boolean responseOnly) {
        List<Node> leaks = new ArrayList<>();
        for (MethodCallExpr call : body.findAll(MethodCallExpr.class)) {
            if (readsDetails(call, exception) && leaves(call, body, responseOnly)) {
                leaks.add(chainTop(call));
            }
            // ResponseEntity.status(500).body(e): исключение целиком в теле ответа
            boolean wholeException = BODY_METHODS.contains(call.getNameAsString())
                    && call.getArguments().stream().anyMatch(argument -> isName(argument, exception));
            if (wholeException) {
                leaks.add(call);
            }
        }
        // "Ошибка базы: " + e
        for (BinaryExpr concatenation : body.findAll(BinaryExpr.class)) {
            boolean joins = concatenation.getOperator() == BinaryExpr.Operator.PLUS
                    && (isName(concatenation.getLeft(), exception) || isName(concatenation.getRight(), exception));
            if (joins && leaves(concatenation, body, responseOnly)) {
                leaks.add(concatenation);
            }
        }
        for (ReturnStmt exit : body.findAll(ReturnStmt.class)) {
            if (exit.getExpression().filter(value -> isName(value, exception)).isPresent()) {
                leaks.add(exit);
            }
        }
        return leaks;
    }

    // e.getMessage(), e.getMostSpecificCause() либо ExceptionUtils.getRootCauseMessage(e)
    private boolean readsDetails(MethodCallExpr call, String exception) {
        boolean ownDetail = DETAIL_METHODS.contains(call.getNameAsString())
                && call.getScope().filter(scope -> isName(scope, exception)).isPresent();
        boolean helper = DETAIL_HELPERS.contains(call.getNameAsString())
                && call.getScope().filter(scope -> !isName(scope, exception)).isPresent()
                && call.getArguments().stream().anyMatch(argument -> isName(argument, exception));
        return ownDetail || helper;
    }

    // e.getMostSpecificCause().getMessage() - в находке показываем цепочку целиком
    private Expression chainTop(MethodCallExpr call) {
        Expression top = call;
        while (top.getParentNode().filter(parent -> parent instanceof MethodCallExpr).isPresent()) {
            MethodCallExpr parent = (MethodCallExpr) top.getParentNode().get();
            Expression link = top;
            if (parent.getScope().filter(scope -> scope == link).isEmpty()) {
                break;
            }
            top = parent;
        }
        return top;
    }

    private boolean isName(Expression expression, String name) {
        Expression value = Nodes.unwrap(expression);
        return value.isNameExpr() && value.asNameExpr().getNameAsString().equals(name);
    }

    /**
     * Текст исключения покидает приложение, если его не только пишут в лог и не только проверяют. Текст,
     * положенный в переменную, прослеживается до ее использований
     */
    private boolean leaves(Expression detail, Node body, boolean responseOnly) {
        Node top = detail;
        Node current = detail.getParentNode().orElse(null);
        while (current != null && current != body) {
            // e.getMessage().contains("duplicate"): цепочка заканчивается проверкой
            if (current instanceof MethodCallExpr call) {
                if (Loggers.isLogCall(call) || CHECK_METHODS.contains(call.getNameAsString())) {
                    return false;
                }
                if (responseOnly && RESPONSE_METHODS.contains(call.getNameAsString())) {
                    return true;
                }
            }
            if (responseOnly && current instanceof ObjectCreationExpr creation
                    && RESPONSE_TYPES.contains(creation.getType().getNameAsString())) {
                return true;
            }
            if (isCondition(current, top)) {
                return false;
            }
            if (current instanceof VariableDeclarator variable) {
                return variableLeaves(variable, body, responseOnly);
            }
            if (current instanceof ReturnStmt) {
                return true;
            }
            top = current;
            current = current.getParentNode().orElse(null);
        }
        return !responseOnly;
    }

    // if (e.getSQLState() == null), e.getCause() instanceof PSQLException, switch (e.getErrorCode())
    private boolean isCondition(Node parent, Node child) {
        if (parent instanceof IfStmt statement) {
            return statement.getCondition() == child;
        }
        if (parent instanceof ConditionalExpr conditional) {
            return conditional.getCondition() == child;
        }
        if (parent instanceof SwitchStmt statement) {
            return statement.getSelector() == child;
        }
        if (parent instanceof BinaryExpr binary) {
            return binary.getOperator() == BinaryExpr.Operator.EQUALS || binary.getOperator() == BinaryExpr.Operator.NOT_EQUALS;
        }
        return parent instanceof InstanceOfExpr;
    }

    // String message = e.getMessage(); log.error(message); - текст остался в приложении
    private boolean variableLeaves(VariableDeclarator variable, Node body, boolean responseOnly) {
        return body.findAll(NameExpr.class).stream()
                .filter(usage -> usage.getNameAsString().equals(variable.getNameAsString()))
                .filter(usage -> LocalTypes.findDeclaration(usage).filter(declaration -> declaration == variable).isPresent())
                .anyMatch(usage -> leaves(usage, body, responseOnly));
    }

    private int line(Node node) {
        return node.getBegin().map(position -> position.line).orElse(0);
    }
}
