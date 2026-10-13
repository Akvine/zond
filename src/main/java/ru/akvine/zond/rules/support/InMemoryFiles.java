package ru.akvine.zond.rules.support;

import com.github.javaparser.ast.Node;
import com.github.javaparser.ast.body.VariableDeclarator;
import com.github.javaparser.ast.expr.Expression;
import com.github.javaparser.ast.expr.FieldAccessExpr;
import com.github.javaparser.ast.expr.LambdaExpr;
import com.github.javaparser.ast.expr.MethodCallExpr;
import com.github.javaparser.ast.expr.NameExpr;
import com.github.javaparser.ast.expr.ObjectCreationExpr;
import com.github.javaparser.ast.stmt.DoStmt;
import com.github.javaparser.ast.stmt.ForEachStmt;
import com.github.javaparser.ast.stmt.ForStmt;
import com.github.javaparser.ast.stmt.WhileStmt;
import lombok.experimental.UtilityClass;

import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Файл, собранный или прочитанный в память целиком: буферы, в которых он копится, и признаки того,
 * что массив байт - это файл, а не короткое значение
 */
@UtilityClass
public class InMemoryFiles {
    public final static String BYTE_ARRAY = "byte[]";

    private final static Set<String> BYTE_BUFFERS = Set.of(
            "ByteArrayOutputStream", "FastByteArrayOutputStream", "UnsynchronizedByteArrayOutputStream");
    private final static Set<String> TEXT_BUFFERS =
            Set.of("StringBuilder", "StringBuffer", "StringWriter", "StringJoiner", "CharArrayWriter");

    // Вызовы, которые читают накопленное, а не пишут в буфер
    private final static Set<String> READS = Set.of(
            "toByteArray", "toString", "size", "length", "close", "flush", "reset", "toCharArray", "getBuffer",
            "isEmpty", "toInputStream", "writeTo", "charAt", "substring", "indexOf", "hashCode", "equals");

    // Значение одного из этих типов - не обертка над буфером, а то, что из него получили
    private final static Set<String> VALUE_TYPES =
            Set.of(BYTE_ARRAY, "String", "int", "long", "boolean", "Integer", "Long", "Boolean");
    private final static Set<String> SINGLE_BYTE_TYPES = Set.of("int", "byte", "char", "Integer", "Byte", "Character");
    private final static Set<String> PRIMITIVE_ARRAYS = Set.of(BYTE_ARRAY, "char[]", "int[]", "short[]", "long[]");
    private final static Set<String> CHARACTER_STREAMS = Set.of("chars", "codePoints");

    private final static Pattern WRITE_NAME = Pattern.compile("^(write|print|append|put|add)");
    private final static String WRITE = "write";
    private final static String READ = "read";
    private final static String LENGTH = "length";

    private final static Pattern FILE_WORD = Pattern.compile(
            "(?i)(file|report|document|attachment|archive|export|excel|xlsx|xls|csv|pdf|zip|dump|backup|upload"
                    + "|workbook|invoice|statement|image|photo|picture|scan)");

    public boolean isByteBuffer(VariableDeclarator variable) {
        return isBuffer(variable, BYTE_BUFFERS);
    }

    public boolean isTextBuffer(VariableDeclarator variable) {
        return isBuffer(variable, TEXT_BUFFERS);
    }

    /**
     * @return true, если выражение - локальная переменная или параметр с типом буфера байт
     */
    public boolean isByteBuffer(Expression expression) {
        return LocalTypes.typeOf(Nodes.unwrap(expression)).filter(BYTE_BUFFERS::contains).isPresent();
    }

    public boolean isByteArray(Expression expression) {
        return LocalTypes.typeOf(Nodes.unwrap(expression)).filter(BYTE_ARRAY::equals).isPresent();
    }

    /**
     * @return true, если в тексте есть слово, которым называют файл: report, attachment, xlsx...
     */
    public boolean hasFileWord(String text) {
        return FILE_WORD.matcher(text).find();
    }

    /**
     * @return имена переменных, запись в которые попадает в буфер: сам буфер и обертки над ним
     * ({@code new PrintWriter(out)}, {@code new CSVPrinter(writer, format)}, {@code new ZipOutputStream(out)})
     */
    public Set<String> writers(Node callable, String buffer) {
        Set<String> writers = new HashSet<>(Set.of(buffer));
        boolean grown = true;
        while (grown) {
            grown = false;
            for (VariableDeclarator variable : callable.findAll(VariableDeclarator.class)) {
                if (!writers.contains(variable.getNameAsString()) && wraps(variable, writers)) {
                    writers.add(variable.getNameAsString());
                    grown = true;
                }
            }
        }
        return writers;
    }

    /**
     * Ищет запись в буфер, которая повторяется по числу данных: строка файла на каждую запись выборки.
     * Не считаются: запись по одному байту или символу (это кодирование значения, а не файл), цикл
     * повторных попыток и буфер, созданный заново на каждой итерации.
     *
     * @param buffer  объявление буфера
     * @param writers имена буфера и его оберток, см. {@link #writers}
     */
    public Optional<MethodCallExpr> repeatedWrite(Node callable, VariableDeclarator buffer, Set<String> writers) {
        List<MethodCallExpr> writes = callable.findAll(MethodCallExpr.class).stream()
                .filter(call -> writesTo(call, buffer.getNameAsString(), writers))
                .filter(call -> isAccumulating(call, buffer))
                .toList();
        // zip.setLevel(...) в цикле тоже вызов на обертке, но показать лучше саму запись: zip.write(...)
        return writes.stream()
                .filter(call -> WRITE_NAME.matcher(call.getNameAsString()).find())
                .findFirst()
                .or(() -> writes.stream().findFirst());
    }

    /**
     * @return true, если цикл переносит данные из потока: в его условии или теле есть {@code read(...)}
     */
    public boolean isStreamCopy(Node loop) {
        if (!(loop instanceof WhileStmt || loop instanceof DoStmt || loop instanceof ForStmt)) {
            return false;
        }
        return loop.findAll(MethodCallExpr.class).stream()
                .anyMatch(call -> READ.equals(call.getNameAsString()) && call.getScope().isPresent());
    }

    private boolean isBuffer(VariableDeclarator variable, Set<String> types) {
        boolean declared = LocalTypes.declaredType(variable).filter(types::contains).isPresent();
        // OutputStream out = new ByteArrayOutputStream()
        boolean created = variable.getInitializer()
                .map(Nodes::unwrap)
                .filter(Expression::isObjectCreationExpr)
                .map(initializer -> initializer.asObjectCreationExpr().getType().getNameAsString())
                .filter(types::contains)
                .isPresent();
        return declared || created;
    }

    private boolean wraps(VariableDeclarator variable, Set<String> writers) {
        if (variable.getInitializer().isEmpty()
                || LocalTypes.declaredType(variable).filter(VALUE_TYPES::contains).isPresent()) {
            return false;
        }
        Expression initializer = Nodes.unwrap(variable.getInitializer().get());
        // OutputStream target = out
        if (initializer.isNameExpr()) {
            return writers.contains(initializer.asNameExpr().getNameAsString());
        }
        return initializer.findAll(NameExpr.class).stream()
                .filter(name -> writers.contains(name.getNameAsString()))
                .anyMatch(InMemoryFiles::isArgument);
    }

    private boolean isArgument(NameExpr name) {
        Node parent = name.getParentNode().orElse(null);
        if (parent instanceof ObjectCreationExpr creation) {
            return creation.getArguments().stream().anyMatch(argument -> argument == name);
        }
        return parent instanceof MethodCallExpr call
                && call.getArguments().stream().anyMatch(argument -> argument == name);
    }

    private boolean writesTo(MethodCallExpr call, String buffer, Set<String> writers) {
        Optional<String> receiver = call.getScope()
                .map(Nodes::unwrap)
                .filter(Expression::isNameExpr)
                .map(scope -> scope.asNameExpr().getNameAsString())
                .filter(writers::contains);
        if (receiver.isPresent()) {
            return !READS.contains(call.getNameAsString())
                    && !(receiver.get().equals(buffer) && isSingleByteWrite(call));
        }
        // writeRow(printer, row): буфер или обертка переданы методу, который в них пишет
        return call.getArguments().stream()
                .map(Nodes::unwrap)
                .filter(Expression::isNameExpr)
                .anyMatch(argument -> writers.contains(argument.asNameExpr().getNameAsString()));
    }

    // out.write(b), out.write(0x1F)
    private boolean isSingleByteWrite(MethodCallExpr call) {
        if (!WRITE.equals(call.getNameAsString()) || call.getArguments().size() != 1) {
            return false;
        }
        Expression argument = Nodes.unwrap(call.getArgument(0));
        return argument.isIntegerLiteralExpr() || argument.isCharLiteralExpr() || argument.isCastExpr()
                || argument.isBinaryExpr()
                || LocalTypes.typeOf(argument).filter(SINGLE_BYTE_TYPES::contains).isPresent();
    }

    private boolean isAccumulating(MethodCallExpr write, VariableDeclarator buffer) {
        Optional<Node> iteration = Loops.enclosingIteration(write);
        while (iteration.isPresent()) {
            Node loop = iteration.get();
            if (Loops.isDeclaredOutside(loop, buffer) && !CodeContexts.isRetryLoop(loop)
                    && !isElementLevel(loop) && !isStreamCopy(loop)) {
                return true;
            }
            iteration = Loops.enclosingIteration(loop);
        }
        return false;
    }

    // Перебор байтов или символов одного значения: hex-представление, экранирование, сборка кадра
    private boolean isElementLevel(Node loop) {
        if (loop instanceof ForEachStmt each) {
            return each.getVariable().getElementType().isPrimitiveType();
        }
        if (loop instanceof ForStmt counted) {
            return counted.getCompare().filter(InMemoryFiles::comparesWithValueLength).isPresent();
        }
        // text.chars().forEach(c -> ...)
        return loop instanceof LambdaExpr lambda && lambda.getParentNode()
                .filter(parent -> parent instanceof MethodCallExpr)
                .map(parent -> (MethodCallExpr) parent)
                .filter(call -> call.findAll(MethodCallExpr.class).stream()
                        .anyMatch(inner -> CHARACTER_STREAMS.contains(inner.getNameAsString())
                                && !lambda.isAncestorOf(inner)))
                .isPresent();
    }

    // i < text.length(), i < bytes.length
    private boolean comparesWithValueLength(Expression compare) {
        boolean stringLength = compare.findAll(MethodCallExpr.class).stream()
                .anyMatch(call -> LENGTH.equals(call.getNameAsString()) && call.getArguments().isEmpty());
        boolean primitiveArrayLength = compare.findAll(FieldAccessExpr.class).stream()
                .filter(access -> LENGTH.equals(access.getNameAsString()))
                .anyMatch(access -> LocalTypes.typeOf(access.getScope()).filter(PRIMITIVE_ARRAYS::contains).isPresent());
        return stringLength || primitiveArrayLength;
    }
}
