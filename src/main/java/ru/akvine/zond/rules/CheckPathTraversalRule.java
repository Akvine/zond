package ru.akvine.zond.rules;

import com.github.javaparser.ast.Node;
import com.github.javaparser.ast.expr.Expression;
import com.github.javaparser.ast.expr.MethodCallExpr;
import org.springframework.stereotype.Component;
import ru.akvine.zond.enums.ErrorLevel;
import ru.akvine.zond.enums.ErrorType;
import ru.akvine.zond.models.SourceFile;
import ru.akvine.zond.models.Violation;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;

@Component
public class CheckPathTraversalRule extends AbstractTaintRule {
    private static final Set<String> FILE_TYPES =
            Set.of("File", "FileInputStream", "FileOutputStream", "FileReader", "FileWriter", "RandomAccessFile");
    private static final Set<String> PATH_FACTORIES = Set.of("Paths", "Path");
    private static final Set<String> PATH_FACTORY_METHODS = Set.of("get", "of");
    private static final String RESOLVE = "resolve";

    // Проверки, после которых путь не может выйти за пределы каталога
    private static final Set<String> SANITIZERS =
            Set.of("normalize", "cleanPath", "getCanonicalPath", "getCanonicalFile", "toRealPath", "startsWith");

    @Override
    public String code() {
        return RuleCodes.CHECK_PATH_TRAVERSAL_RULE_CODE;
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
            if (arguments.isEmpty() || isSanitized(expression)) {
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
        return isFactory || RESOLVE.equals(call.getNameAsString()) ? call.getArguments() : List.of();
    }

    // Где-либо в методе путь нормализуют или сверяют с базовым каталогом
    private boolean isSanitized(Node node) {
        return Nodes.enclosingCallable(node)
                .filter(callable -> callable.findAll(MethodCallExpr.class).stream()
                        .anyMatch(call -> SANITIZERS.contains(call.getNameAsString())))
                .isPresent();
    }
}
