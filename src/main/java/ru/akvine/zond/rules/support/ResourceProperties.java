package ru.akvine.zond.rules.support;

import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.Node;
import com.github.javaparser.ast.body.VariableDeclarator;
import com.github.javaparser.ast.expr.AssignExpr;
import com.github.javaparser.ast.expr.Expression;
import com.github.javaparser.ast.expr.MethodCallExpr;
import com.github.javaparser.ast.expr.StringLiteralExpr;
import lombok.experimental.UtilityClass;

import java.io.IOException;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Properties;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Значение, которое код берет из своего файла настроек:
 * <pre>
 * props.load(getClass().getClassLoader().getResourceAsStream("crypto.properties"));
 * String algorithm = props.getProperty("hashAlg", "SHA-256");
 * MessageDigest.getInstance(algorithm);
 * </pre>
 * Файл ищется среди ресурсов модуля, в котором лежит исходник. Ключа в файле нет - действует значение по
 * умолчанию из вызова; самого файла нет - значение неизвестно
 */
@UtilityClass
public class ResourceProperties {
    private static final String GET_PROPERTY = "getProperty";
    private static final String LOAD = "load";
    private static final String EXTENSION = ".properties";
    private static final List<String> RESOURCE_DIRECTORIES = List.of("src/main/resources", "src/test/resources", "resources", "");
    // На сколько каталогов вверх от исходника искать каталог ресурсов модуля
    private static final int MAX_LEVELS = 12;
    private static final Map<Path, Optional<Properties>> FILES = new ConcurrentHashMap<>();

    /**
     * @return текст выражения: литерал, переменная, которой он присвоен один раз, или настройка из файла
     */
    public Optional<String> textOf(Expression expression) {
        Expression value = Nodes.unwrap(expression);
        Optional<String> literal = StringLiterals.textOf(value);
        if (literal.isPresent()) {
            return literal;
        }
        if (value.isNameExpr()) {
            return LocalTypes.findDeclaration(value)
                    .filter(VariableDeclarator.class::isInstance)
                    .map(VariableDeclarator.class::cast)
                    .filter(variable -> !isReassigned(variable))
                    .flatMap(VariableDeclarator::getInitializer)
                    .flatMap(initializer -> StringLiterals.textOf(initializer).or(() -> propertyValue(initializer)));
        }
        return propertyValue(value);
    }

    // props.getProperty("key") или props.getProperty("key", "default")
    private Optional<String> propertyValue(Expression expression) {
        Expression value = Nodes.unwrap(expression);
        if (!(value instanceof MethodCallExpr call) || !GET_PROPERTY.equals(call.getNameAsString())
                || call.getArguments().isEmpty() || call.getArguments().size() > 2
                || call.getScope().filter(Expression::isNameExpr).isEmpty()) {
            return Optional.empty();
        }
        Optional<String> key = StringLiterals.textOf(call.getArgument(0));
        Optional<String> fallback = call.getArguments().size() == 2 ? StringLiterals.textOf(call.getArgument(1)) : Optional.empty();
        String holder = call.getScope().get().asNameExpr().getNameAsString();
        Optional<Properties> file = loadedFile(call, holder);
        if (key.isEmpty() || file.isEmpty()) {
            return Optional.empty();
        }
        return Optional.ofNullable(file.get().getProperty(key.get())).or(() -> fallback);
    }

    // props.load(...getResourceAsStream("benchmark.properties")) в том же методе
    private Optional<Properties> loadedFile(MethodCallExpr read, String holder) {
        Optional<Node> callable = Nodes.enclosingCallable(read);
        Optional<Path> source = read.findCompilationUnit().flatMap(CompilationUnit::getStorage)
                .map(storage -> storage.getPath().toAbsolutePath());
        if (callable.isEmpty() || source.isEmpty()) {
            return Optional.empty();
        }
        return callable.get().findAll(MethodCallExpr.class).stream()
                .filter(call -> LOAD.equals(call.getNameAsString()) && call.getArguments().size() == 1)
                .filter(call -> call.getScope().filter(scope -> scope.isNameExpr()
                        && scope.asNameExpr().getNameAsString().equals(holder)).isPresent())
                .flatMap(call -> call.getArgument(0).findAll(StringLiteralExpr.class).stream())
                .map(StringLiteralExpr::asString)
                .filter(name -> name.endsWith(EXTENSION))
                .findFirst()
                .flatMap(name -> find(source.get().getParent(), name.startsWith("/") ? name.substring(1) : name))
                .flatMap(path -> FILES.computeIfAbsent(path, ResourceProperties::read));
    }

    private Optional<Path> find(Path directory, String name) {
        Path current = directory;
        for (int level = 0; current != null && level < MAX_LEVELS; level++) {
            for (String resources : RESOURCE_DIRECTORIES) {
                Path candidate = (resources.isEmpty() ? current : current.resolve(resources)).resolve(name);
                if (Files.isRegularFile(candidate)) {
                    return Optional.of(candidate);
                }
            }
            current = current.getParent();
        }
        return Optional.empty();
    }

    private Optional<Properties> read(Path path) {
        Properties properties = new Properties();
        try (Reader reader = Files.newBufferedReader(path, StandardCharsets.UTF_8)) {
            properties.load(reader);
            return Optional.of(properties);
        } catch (IOException | IllegalArgumentException e) {
            return Optional.empty();
        }
    }

    private boolean isReassigned(VariableDeclarator variable) {
        return Nodes.enclosingCallable(variable).stream()
                .flatMap(callable -> callable.findAll(AssignExpr.class).stream())
                .anyMatch(assignment -> assignment.getTarget().isNameExpr()
                        && assignment.getTarget().asNameExpr().getNameAsString().equals(variable.getNameAsString()));
    }
}
