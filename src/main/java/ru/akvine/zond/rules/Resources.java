package ru.akvine.zond.rules;

import com.github.javaparser.ast.Node;
import com.github.javaparser.ast.body.VariableDeclarator;
import com.github.javaparser.ast.expr.Expression;
import com.github.javaparser.ast.expr.MethodCallExpr;
import com.github.javaparser.ast.expr.VariableDeclarationExpr;
import com.github.javaparser.ast.stmt.ExpressionStmt;
import lombok.experimental.UtilityClass;

import java.util.Set;

/**
 * Ресурсы, которые нужно закрывать. Типы не разрешаем, поэтому узнаем их по известным именам классов и методов.
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

    /**
     * @return true, если выражение открывает ресурс: new FileInputStream(...), Files.lines(...), getConnection()
     */
    boolean opens(Expression initializer) {
        Expression value = Nodes.unwrap(initializer);
        if (value.isObjectCreationExpr()) {
            String type = value.asObjectCreationExpr().getType().getNameAsString();
            // new Scanner(System.in): стандартный ввод закрывать не нужно
            return RESOURCE_TYPES.contains(type)
                    && !value.toString().contains(SYSTEM_IN)
                    && !isOwnClassNamedScanner(value, type);
        }
        if (value.isMethodCallExpr()) {
            MethodCallExpr call = value.asMethodCallExpr();
            boolean filesResource = FILES_RESOURCE_METHODS.contains(call.getNameAsString())
                    && MethodCalls.isCallOn(call, FILES, call.getNameAsString());
            return filesResource || (call.getScope().isPresent() && RESOURCE_METHODS.contains(call.getNameAsString()));
        }
        return false;
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
     * @return true для обычной локальной переменной: не поле, не ресурс в try (...) и не счетчик цикла
     */
    boolean isLocalVariable(VariableDeclarator variable) {
        return variable.getParentNode()
                .filter(parent -> parent instanceof VariableDeclarationExpr)
                .flatMap(Node::getParentNode)
                .filter(parent -> parent instanceof ExpressionStmt)
                .isPresent();
    }
}
