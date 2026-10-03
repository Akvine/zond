package ru.akvine.zond.rules;

import com.github.javaparser.ast.Node;
import com.github.javaparser.ast.body.VariableDeclarator;
import com.github.javaparser.ast.expr.Expression;
import com.github.javaparser.ast.expr.MethodCallExpr;
import com.github.javaparser.ast.expr.VariableDeclarationExpr;
import com.github.javaparser.ast.stmt.ExpressionStmt;
import com.github.javaparser.ast.type.ClassOrInterfaceType;
import lombok.experimental.UtilityClass;

import java.util.Set;

/**
 * Ресурсы, которые нужно закрывать: известные классы, их наследники и методы, которые открывают ресурс.
 * Где тип разрешить не удалось, судим по именам.
 */
@UtilityClass
class Resources {
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

    private final static Set<String> CLOSEABLE = Set.of("AutoCloseable");

    /**
     * @return true, если выражение открывает ресурс: new FileInputStream(...), Files.lines(...), getConnection()
     */
    boolean opens(Expression initializer) {
        Expression value = Nodes.unwrap(initializer);
        if (value.isObjectCreationExpr()) {
            ClassOrInterfaceType type = value.asObjectCreationExpr().getType();
            String typeName = type.getNameAsString();
            // Собственный класс проекта с таким же именем (свой Scanner) - не ресурс, а наследник ресурса - ресурс
            boolean isResource = Types.isKindOf(type, RESOURCE_TYPES)
                    .orElseGet(() -> RESOURCE_TYPES.contains(typeName) && !isOwnClassNamedScanner(value, typeName));
            // new Scanner(System.in): стандартный ввод закрывать не нужно
            return isResource && !value.toString().contains(SYSTEM_IN);
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
                && Types.isKindOf(call, CLOSEABLE).orElse(true);
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
    boolean isLocalVariable(VariableDeclarator variable) {
        return !Lombok.isCleanedUp(variable) && variable.getParentNode()
                .filter(parent -> parent instanceof VariableDeclarationExpr)
                .flatMap(Node::getParentNode)
                .filter(parent -> parent instanceof ExpressionStmt)
                .isPresent();
    }
}
