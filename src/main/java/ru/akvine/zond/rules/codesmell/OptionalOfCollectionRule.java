package ru.akvine.zond.rules.codesmell;

import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.type.ClassOrInterfaceType;
import com.github.javaparser.ast.type.Type;
import org.springframework.stereotype.Component;
import ru.akvine.zond.enums.ErrorLevel;
import ru.akvine.zond.enums.ErrorType;
import ru.akvine.zond.models.SourceFile;
import ru.akvine.zond.models.Violation;
import ru.akvine.zond.rules.AbstractRule;
import ru.akvine.zond.rules.RuleCodes;
import ru.akvine.zond.rules.support.Annotations;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * Optional вокруг коллекции в типе результата: {@code Optional<List<T>>}. У такого результата три состояния - пусто,
 * пустая коллекция и коллекция с данными, - и вызывающий код проверяет два уровня. "Ничего нет" у коллекции
 * уже есть: это пустая коллекция.
 */
@Component
public class OptionalOfCollectionRule extends AbstractRule {
    private static final String OPTIONAL = "Optional";
    private static final String OVERRIDE = "Override";
    private static final Set<String> CONTAINERS = Set.of(
            "List", "Set", "Collection", "Map", "Iterable", "Queue", "Deque", "SortedSet", "SortedMap",
            "NavigableSet", "NavigableMap", "ArrayList", "LinkedList", "HashSet", "LinkedHashSet", "TreeSet",
            "HashMap", "LinkedHashMap", "TreeMap", "Stream", "Page", "Slice");

    @Override
    public String code() {
        return RuleCodes.OPTIONAL_OF_COLLECTION_RULE_CODE;
    }

    @Override
    public String description() {
        return "Сканирует код и ищет методы, которые возвращают Optional с коллекцией или массивом внутри";
    }

    @Override
    public List<Violation> check(SourceFile sourceFile) {
        List<Violation> violations = new ArrayList<>();
        for (MethodDeclaration method : sourceFile.unit().findAll(MethodDeclaration.class)) {
            // Сигнатуру переопределенного метода задает предок: менять ее нужно там
            if (Annotations.has(method, OVERRIDE)) {
                continue;
            }
            wrappedContainer(method.getType()).ifPresent(inner -> violations.add(violation(sourceFile, method.getType(),
                    "Метод '" + method.getNameAsString() + "' возвращает " + method.getType() + ": у результата три"
                            + " состояния - пусто, " + emptyName(inner) + " и данные, и вызывающему коду приходится"
                            + " проверять оба уровня; \"ничего нет\" здесь уже выражает " + emptyName(inner)
                            + " - возвращайте " + inner + " без Optional")));
        }
        return violations;
    }

    @Override
    public ErrorLevel errorLevel() {
        return ErrorLevel.MINOR;
    }

    @Override
    public ErrorType errorType() {
        return ErrorType.CODE_SMELL;
    }

    // Optional<List<Order>> -> List<Order>; Optional<byte[]> -> byte[]
    private Optional<Type> wrappedContainer(Type type) {
        if (!type.isClassOrInterfaceType() || !OPTIONAL.equals(type.asClassOrInterfaceType().getNameAsString())) {
            return Optional.empty();
        }
        return type.asClassOrInterfaceType().getTypeArguments()
                .filter(arguments -> arguments.size() == 1)
                .map(arguments -> arguments.get(0))
                .filter(inner -> inner.isArrayType() || inner.isClassOrInterfaceType()
                        && CONTAINERS.contains(((ClassOrInterfaceType) inner).getNameAsString()));
    }

    private String emptyName(Type inner) {
        return inner.isArrayType() ? "пустой массив" : "пустая коллекция";
    }
}
