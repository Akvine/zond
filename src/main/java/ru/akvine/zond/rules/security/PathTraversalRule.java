package ru.akvine.zond.rules.security;

import com.github.javaparser.ast.Node;
import com.github.javaparser.ast.expr.Expression;
import com.github.javaparser.ast.expr.MethodCallExpr;
import org.springframework.stereotype.Component;
import ru.akvine.zond.enums.ErrorLevel;
import ru.akvine.zond.enums.ErrorType;
import ru.akvine.zond.models.SourceFile;
import ru.akvine.zond.models.Violation;
import ru.akvine.zond.rules.AbstractTaintRule;
import ru.akvine.zond.rules.RuleCodes;
import ru.akvine.zond.rules.support.LocalTypes;
import ru.akvine.zond.rules.support.MethodCalls;
import ru.akvine.zond.rules.support.Nodes;
import ru.akvine.zond.rules.support.Taint;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;

@Component
public class PathTraversalRule extends AbstractTaintRule {
    private static final Set<String> FILE_TYPES =
            Set.of("File", "FileInputStream", "FileOutputStream", "FileReader", "FileWriter", "RandomAccessFile");
    private static final Set<String> PATH_FACTORIES = Set.of("Paths", "Path");
    private static final Set<String> PATH_FACTORY_METHODS = Set.of("get", "of");
    private static final String RESOLVE = "resolve";
    private static final Set<String> NAME_ONLY_METHODS = Set.of("getName", "getFileName");

    // Проверки, после которых путь не может выйти за пределы каталога
    private static final Set<String> SANITIZERS =
            Set.of("normalize", "cleanPath", "getCanonicalPath", "getCanonicalFile", "toRealPath", "startsWith");

    @Override
    public String code() {
        return RuleCodes.PATH_TRAVERSAL_RULE_CODE;
    }

    @Override
    public String description() {
        return "Сканирует код и ищет пути к файлам, собранные из параметров запроса без проверки";
    }

    @Override
    protected List<Violation> check(SourceFile sourceFile, Taint taint) {
        List<Violation> violations = new ArrayList<>();
        for (Expression expression : sourceFile.unit().findAll(Expression.class)) {
            List<Expression> arguments = pathArguments(expression);
            if (arguments.isEmpty() || isSanitized(expression) || isNameOnly(expression)) {
                continue;
            }
            arguments.stream()
                    .map(taint::findSource)
                    .flatMap(Optional::stream)
                    .findFirst()
                    .ifPresent(input -> violations.add(violation(sourceFile, expression,
                            "Путь к файлу строится из данных запроса '" + input + "' без проверки: значение вида"
                                    + " ../../etc/passwd выведет за пределы каталога; приведите путь через"
                                    + " normalize() и проверьте, что он начинается с разрешенного каталога")));
        }
        return violations;
    }

    @Override
    public ErrorLevel errorLevel() {
        return ErrorLevel.CRITICAL;
    }

    @Override
    public ErrorType errorType() {
        return ErrorType.SECURITY;
    }

    // new File(...), Paths.get(...), Path.of(...), base.resolve(...)
    private List<Expression> pathArguments(Expression expression) {
        if (expression.isObjectCreationExpr()
                && FILE_TYPES.contains(expression.asObjectCreationExpr().getType().getNameAsString())) {
            return expression.asObjectCreationExpr().getArguments();
        }
        if (!expression.isMethodCallExpr()) {
            return List.of();
        }

        MethodCallExpr call = expression.asMethodCallExpr();
        boolean isFactory = PATH_FACTORY_METHODS.contains(call.getNameAsString())
                && call.getScope().map(MethodCalls::receiverName).filter(PATH_FACTORIES::contains).isPresent();
        // base.resolve(name) у пути; SchemaType.resolve(type, name) у класса и свой resolve(name) - другие методы
        boolean isResolve = RESOLVE.equals(call.getNameAsString())
                && call.getScope().filter(scope -> !isClassReference(scope)).isPresent();
        return isFactory || isResolve ? call.getArguments() : List.of();
    }

    private boolean isClassReference(Expression scope) {
        return scope.isNameExpr() && Character.isUpperCase(scope.asNameExpr().getNameAsString().charAt(0))
                && LocalTypes.findDeclaration(scope).isEmpty();
    }

    // new File(path).getName(), Paths.get(path).getFileName(): от пути берут только имя файла без каталогов
    private boolean isNameOnly(Expression path) {
        return path.getParentNode()
                .filter(parent -> parent instanceof MethodCallExpr call
                        && call.getScope().filter(scope -> Nodes.unwrap(scope) == path || scope == path).isPresent()
                        && NAME_ONLY_METHODS.contains(call.getNameAsString()))
                .isPresent();
    }

    // Где-либо в методе путь нормализуют или сверяют с базовым каталогом
    private boolean isSanitized(Node node) {
        return Nodes.enclosingCallable(node)
                .filter(callable -> callable.findAll(MethodCallExpr.class).stream()
                        .anyMatch(call -> SANITIZERS.contains(call.getNameAsString())))
                .isPresent();
    }
}
