package ru.akvine.zond.rules;

import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.stmt.ReturnStmt;
import org.springframework.stereotype.Component;
import ru.akvine.zond.enums.ErrorLevel;
import ru.akvine.zond.enums.ErrorType;
import ru.akvine.zond.models.SourceFile;
import ru.akvine.zond.models.Violation;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

@Component
public class CheckNullInsteadOfEmptyCollectionRule extends AbstractRule {
    private static final Set<String> COLLECTION_TYPES = Set.of(
            "List", "Set", "Map", "Collection", "Iterable", "Queue", "Deque", "SortedSet", "SortedMap", "Stream");
    private static final String ARRAY_SUFFIX = "[]";
    private static final String NULLABLE = "Nullable";

    @Override
    public String code() {
        return RuleCodes.CHECK_NULL_INSTEAD_OF_EMPTY_COLLECTION_RULE_CODE;
    }

    @Override
    public String description() {
        return "Сканирует код и ищет return null в методах, которые возвращают коллекцию или массив";
    }

    @Override
    public List<Violation> check(SourceFile sourceFile) {
        List<Violation> violations = new ArrayList<>();
        for (MethodDeclaration method : sourceFile.unit().findAll(MethodDeclaration.class)) {
            String type = LocalTypes.typeName(method.getType());
            boolean returnsCollection = COLLECTION_TYPES.contains(type) || type.endsWith(ARRAY_SUFFIX);
            if (!returnsCollection || Annotations.has(method, NULLABLE) || TestClasses.isInside(method)) {
                continue;
            }
            for (ReturnStmt returned : method.findAll(ReturnStmt.class)) {
                boolean returnsNull = returned.getExpression().filter(value -> value.isNullLiteralExpr()).isPresent();
                if (returnsNull && !Nodes.isInNestedScope(returned, method)) {
                    violations.add(violation(sourceFile, returned,
                            "Метод '" + method.getNameAsString() + "' возвращает null вместо пустого значения:"
                                    + " каждый, кто его вызывает, обязан проверять результат, и первый забывший"
                                    + " получит NullPointerException; возвращайте пустую коллекцию"));
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
        return ErrorType.LOGICAL;
    }
}
