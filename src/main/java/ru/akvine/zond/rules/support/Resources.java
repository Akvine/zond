package ru.akvine.zond.rules.support;

import com.github.javaparser.ast.Node;
import com.github.javaparser.ast.body.VariableDeclarator;
import com.github.javaparser.ast.expr.Expression;
import com.github.javaparser.ast.expr.MethodCallExpr;
import com.github.javaparser.ast.expr.ObjectCreationExpr;
import com.github.javaparser.ast.expr.VariableDeclarationExpr;
import com.github.javaparser.ast.stmt.ExpressionStmt;
import com.github.javaparser.ast.stmt.TryStmt;
import com.github.javaparser.ast.type.ClassOrInterfaceType;
import lombok.experimental.UtilityClass;

import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Ресурсы, которые нужно закрывать: известные классы, их наследники и методы, которые открывают ресурс.
 * Где тип разрешить не удалось, судим по именам.
 */
@UtilityClass
public class Resources {
    private final static String FILES = "Files";
    private final static String SYSTEM_IN = "System.in";
    private final static String SCANNER = "Scanner";
    private final static String JAVA_UTIL_SCANNER = "java.util.Scanner";
    private final static String JAVA_UTIL = "java.util";

    private final static Set<String> RESOURCE_TYPES = Set.of(
            "FileInputStream", "FileOutputStream", "FileReader", "FileWriter", "RandomAccessFile",
            "BufferedReader", "BufferedWriter", "BufferedInputStream", "BufferedOutputStream",
            "InputStreamReader", "OutputStreamWriter", "PrintWriter", "PrintStream", "Scanner",
            "DataInputStream", "DataOutputStream", "ObjectInputStream", "ObjectOutputStream",
            "ZipInputStream", "ZipOutputStream", "GZIPInputStream", "GZIPOutputStream",
            "Socket", "ServerSocket");

    private final static Set<String> FILES_RESOURCE_METHODS = Set.of(
            "lines", "list", "walk", "find",
            "newInputStream", "newOutputStream", "newBufferedReader", "newBufferedWriter", "newDirectoryStream");

    private final static Set<String> RESOURCE_METHODS = Set.of(
            "getConnection", "prepareStatement", "prepareCall", "createStatement", "executeQuery", "openStream");

    private final static Set<String> JDBC_OWNED_METHODS = Set.of(
            "executeQuery", "getResultSet", "getGeneratedKeys", "prepareStatement", "prepareCall", "createStatement");
    private final static Set<String> OWNER_STREAM_METHODS = Set.of(
            "getInputStream", "getOutputStream", "getReader", "getWriter", "getErrorStream");
    private final static Set<String> SERVLET_TYPES = Set.of(
            "HttpServletRequest", "HttpServletResponse", "ServletRequest", "ServletResponse",
            "ContentCachingRequestWrapper", "ContentCachingResponseWrapper", "HttpServletRequestWrapper",
            "HttpServletResponseWrapper");
    private final static Pattern SERVLET_NAME = Pattern.compile("(?i)(http)?(servlet)?(request|response|req|resp)");
    private final static Set<String> CLOSEABLE = Set.of("AutoCloseable");
    private final static Set<String> STANDARD_STREAMS = Set.of("System.out", "System.err", "System.in");
    private final static Set<String> IN_MEMORY_STREAMS = Set.of(
            "ByteArrayOutputStream", "ByteArrayInputStream", "StringWriter", "StringReader", "CharArrayWriter",
            "CharArrayReader");

    /**
     * @return true, если выражение открывает ресурс: new FileInputStream(...), Files.lines(...), getConnection()
     */
    /**
     * new DataOutputStream(new ByteArrayOutputStream()), new PrintWriter(System.out): обертка над буфером в памяти
     * не держит ни файла, ни сокета, а System.out закрывать нельзя - закрывать тут нечего
     */
    private boolean wrapsNothingToClose(ObjectCreationExpr wrapper) {
        if (wrapper.getArguments().isEmpty()) {
            return false;
        }
        Expression inner = Nodes.unwrap(wrapper.getArgument(0));
        if (inner.isNameExpr()) {
            inner = LocalTypes.findInitializer(inner).map(Nodes::unwrap).orElse(inner);
        }
        if (inner.isFieldAccessExpr() && STANDARD_STREAMS.contains(inner.toString())) {
            return true;
        }
        if (inner.isMethodCallExpr() && isStreamOfOwner(inner.asMethodCallExpr())) {
            return true;
        }
        if (!inner.isObjectCreationExpr()) {
            return false;
        }
        return IN_MEMORY_STREAMS.contains(inner.asObjectCreationExpr().getType().getNameAsString())
                || wrapsNothingToClose(inner.asObjectCreationExpr());
    }

    /**
     * client.getInputStream() при try (Socket client = ...): поток закроется вместе с сокетом, и обертку над ним
     * закрывать незачем. response.getOutputStream() и request.getReader() у сервлета закрывает контейнер
     */
    private boolean isStreamOfOwner(MethodCallExpr stream) {
        if (!OWNER_STREAM_METHODS.contains(stream.getNameAsString()) || stream.getScope().isEmpty()) {
            return false;
        }
        Expression owner = Nodes.unwrap(stream.getScope().get());
        Optional<String> type = LocalTypes.typeOf(owner);
        if (type.isPresent() ? SERVLET_TYPES.contains(type.get()) : SERVLET_NAME.matcher(owner.toString()).matches()) {
            return true;
        }
        return isTryResource(owner, stream);
    }

    // Переменная объявлена в try (...) вокруг этого места: при выходе из блока ее закроют
    private boolean isTryResource(Expression owner, Node usage) {
        if (!owner.isNameExpr()) {
            return false;
        }
        Optional<Node> declaration = LocalTypes.findDeclaration(owner);
        if (declaration.isEmpty() || !(declaration.get() instanceof VariableDeclarator variable)) {
            return false;
        }
        Node holder = variable.getParentNode().flatMap(Node::getParentNode).orElse(null);
        return holder instanceof TryStmt attempt
                && attempt.getResources().stream().anyMatch(resource -> Nodes.isInside(variable, resource))
                && Nodes.isInside(usage, attempt.getTryBlock());
    }

    public boolean opens(Expression initializer) {
        Expression value = Nodes.unwrap(initializer);
        if (value.isObjectCreationExpr()) {
            ClassOrInterfaceType type = value.asObjectCreationExpr().getType();
            String typeName = type.getNameAsString();
            // Собственный класс проекта с таким же именем (свой Scanner) - не ресурс, а наследник ресурса - ресурс
            boolean isResource = Types.isKindOf(type, RESOURCE_TYPES)
                    .orElseGet(() -> RESOURCE_TYPES.contains(typeName) && !isOwnClassNamedScanner(value, typeName));
            // new Scanner(System.in): стандартный ввод закрывать не нужно
            return isResource && !value.toString().contains(SYSTEM_IN) && !wrapsNothingToClose(value.asObjectCreationExpr());
        }
        if (value.isMethodCallExpr()) {
            MethodCallExpr call = value.asMethodCallExpr();
            boolean filesResource = FILES_RESOURCE_METHODS.contains(call.getNameAsString())
                    && MethodCalls.isCallOn(call, FILES, call.getNameAsString());
            return filesResource || opensByMethodName(call);
        }
        return false;
    }

    // dataSource.getConnection(), url.openStream(). Свой метод с таким же именем, который возвращает
    // не закрываемый объект (getConnection() с описанием соединения), ресурсом не считается
    private boolean opensByMethodName(MethodCallExpr call) {
        return call.getScope().isPresent()
                && RESOURCE_METHODS.contains(call.getNameAsString())
                && !isClosedWithOwner(call)
                && Types.isKindOf(call, CLOSEABLE).orElse(true);
    }

    // try (PreparedStatement statement = ...) { ResultSet rows = statement.executeQuery(); }: по правилам JDBC
    // закрытие Statement закрывает его ResultSet, а закрытие Connection - его Statement
    private boolean isClosedWithOwner(MethodCallExpr call) {
        return JDBC_OWNED_METHODS.contains(call.getNameAsString())
                && isTryResource(Nodes.unwrap(call.getScope().get()), call);
    }

    // Scanner - слишком частое имя для своих классов: ресурсом считаем его только при импорте из java.util
    private boolean isOwnClassNamedScanner(Expression creation, String type) {
        if (!SCANNER.equals(type)) {
            return false;
        }
        return creation.findCompilationUnit()
                .map(unit -> unit.getImports().stream()
                        .map(importDeclaration -> importDeclaration.getNameAsString())
                        .noneMatch(name -> name.equals(JAVA_UTIL_SCANNER) || name.equals(JAVA_UTIL)))
                .orElse(false);
    }

    /**
     * @return true для обычной локальной переменной: не поле, не ресурс в try (...), не счетчик цикла
     * и не переменная с @Cleanup, которую Lombok закроет сам
     */
    public boolean isLocalVariable(VariableDeclarator variable) {
        return !Lombok.isCleanedUp(variable) && variable.getParentNode()
                .filter(parent -> parent instanceof VariableDeclarationExpr)
                .flatMap(Node::getParentNode)
                .filter(parent -> parent instanceof ExpressionStmt)
                .isPresent();
    }
}
