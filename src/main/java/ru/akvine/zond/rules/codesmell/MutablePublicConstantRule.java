package ru.akvine.zond.rules.codesmell;

import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration;
import com.github.javaparser.ast.body.FieldDeclaration;
import com.github.javaparser.ast.body.VariableDeclarator;
import com.github.javaparser.ast.expr.Expression;
import org.springframework.stereotype.Component;
import ru.akvine.zond.enums.ErrorLevel;
import ru.akvine.zond.enums.ErrorType;
import ru.akvine.zond.models.SourceFile;
import ru.akvine.zond.models.Violation;
import ru.akvine.zond.rules.AbstractRule;
import ru.akvine.zond.rules.RuleCodes;
import ru.akvine.zond.rules.support.MethodCalls;
import ru.akvine.zond.rules.support.Nodes;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;

@Component
public class MutablePublicConstantRule extends AbstractRule {
    private static final Set<String> MUTABLE_TYPES = Set.of(
            "ArrayList", "LinkedList", "HashMap", "LinkedHashMap", "TreeMap", "HashSet", "LinkedHashSet", "TreeSet",
            "EnumMap", "ArrayDeque", "ConcurrentHashMap", "CopyOnWriteArrayList");
    private static final String ARRAYS = "Arrays";
    private static final String AS_LIST = "asList";

    // new String[0] и {} - пустой массив, менять в нем нечего
    private static final String EMPTY_ARRAY = "{}";
    private static final String ZERO_LENGTH = "[0]";

    @Override
    public String code() {
        return RuleCodes.MUTABLE_PUBLIC_CONSTANT_RULE_CODE;
    }

    @Override
    public String description() {
        return "Сканирует код и ищет public static final коллекции и массивы, содержимое которых можно изменить";
    }

    @Override
    public List<Violation> check(SourceFile sourceFile) {
        List<Violation> violations = new ArrayList<>();
        for (FieldDeclaration field : sourceFile.unit().findAll(FieldDeclaration.class)) {
            if (!isPublicConstant(field)) {
                continue;
            }
            for (VariableDeclarator variable : field.getVariables()) {
                describeMutable(variable).ifPresent(kind -> violations.add(violation(sourceFile, variable,
                        "Константа '" + variable.getNameAsString() + "' - " + kind + ": final запрещает только"
                                + " заменить ссылку, а содержимое может изменить любой код, и изменение увидят"
                                + " все; используйте List.of / Set.of / Map.of либо Collections.unmodifiable...")));
            }
        }
        return violations;
    }

    @Override
    public ErrorLevel errorLevel() {
        return ErrorLevel.MAJOR;
    }

    @Override
    public ErrorType errorType() {
        return ErrorType.CODE_SMELL;
    }

    // В интерфейсе любое поле - public static final
    private boolean isPublicConstant(FieldDeclaration field) {
        boolean inInterface = field.getParentNode()
                .filter(parent -> parent instanceof ClassOrInterfaceDeclaration type && type.isInterface())
                .isPresent();
        return inInterface || field.isPublic() && field.isStatic() && field.isFinal();
    }

    private Optional<String> describeMutable(VariableDeclarator variable) {
        Optional<Expression> initializer = variable.getInitializer().map(Nodes::unwrap);
        if (variable.getType().isArrayType()) {
            String text = initializer.map(Expression::toString).orElse(EMPTY_ARRAY);
            boolean isEmpty = text.replace(" ", "").equals(EMPTY_ARRAY) || text.endsWith(ZERO_LENGTH);
            return isEmpty ? Optional.empty() : Optional.of("массив");
        }
        if (initializer.isEmpty()) {
            return Optional.empty();
        }
        Expression value = initializer.get();
        boolean isMutableCollection = value.isObjectCreationExpr()
                && MUTABLE_TYPES.contains(value.asObjectCreationExpr().getType().getNameAsString());
        // Arrays.asList не дает добавлять элементы, но заменить существующие через set(...) позволяет
        boolean isArrayView = value.isMethodCallExpr() && MethodCalls.isCallOn(value.asMethodCallExpr(), ARRAYS, AS_LIST);
        return isMutableCollection || isArrayView ? Optional.of("изменяемая коллекция") : Optional.empty();
    }
}
