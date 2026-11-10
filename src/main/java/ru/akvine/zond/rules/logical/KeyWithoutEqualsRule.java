package ru.akvine.zond.rules.logical;

import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration;
import com.github.javaparser.ast.expr.Expression;
import com.github.javaparser.ast.expr.MethodCallExpr;
import com.github.javaparser.ast.type.ClassOrInterfaceType;
import com.github.javaparser.ast.type.Type;
import org.springframework.stereotype.Component;
import ru.akvine.zond.enums.Confidence;
import ru.akvine.zond.enums.ErrorLevel;
import ru.akvine.zond.enums.ErrorType;
import ru.akvine.zond.models.SourceFile;
import ru.akvine.zond.models.Violation;
import ru.akvine.zond.rules.AbstractRule;
import ru.akvine.zond.rules.ProjectRule;
import ru.akvine.zond.rules.RuleCodes;
import ru.akvine.zond.rules.support.Annotations;
import ru.akvine.zond.rules.support.LocalTypes;
import ru.akvine.zond.rules.support.Nodes;
import ru.akvine.zond.rules.support.ProjectClasses;
import ru.akvine.zond.rules.support.TestClasses;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Ключ хеш-таблицы без сравнения по содержимому. У массива и у класса без своих equals и hashCode оба метода
 * работают по ссылке: два ключа с одинаковыми данными - разные ключи. get с новым объектом ничего не находит,
 * put каждый раз добавляет запись, и карта растет.
 */
@Component
public class KeyWithoutEqualsRule extends AbstractRule implements ProjectRule {
    private static final Set<String> MAP_TYPES = Set.of(
            "Map", "HashMap", "LinkedHashMap", "ConcurrentHashMap", "ConcurrentMap", "Hashtable", "WeakHashMap");
    private static final Set<String> SET_TYPES = Set.of("Set", "HashSet", "LinkedHashSet");
    private static final Set<String> EQUALITY_METHODS = Set.of("equals", "hashCode");
    private static final Set<String> LOMBOK_EQUALITY = Set.of("Data", "Value", "EqualsAndHashCode");
    // Вызовы, первым аргументом которых идет ключ
    private static final Set<String> KEY_CALLS = Set.of(
            "get", "getOrDefault", "containsKey", "contains", "remove", "put", "putIfAbsent", "computeIfAbsent",
            "computeIfPresent", "compute", "merge", "add");

    @Override
    public String code() {
        return RuleCodes.KEY_WITHOUT_EQUALS_RULE_CODE;
    }

    @Override
    public String description() {
        return "Сканирует проект и ищет ключи HashMap и элементы HashSet без сравнения по содержимому: массивы"
                + " и свои классы без equals и hashCode, которые создают заново для поиска";
    }

    @Override
    public List<Violation> checkProject(List<SourceFile> sourceFiles) {
        ProjectClasses classes = ProjectClasses.of(sourceFiles);
        List<Violation> violations = new ArrayList<>();
        // Класс-ключ -> первое место, где он использован ключом
        Map<ClassOrInterfaceDeclaration, String> usages = new LinkedHashMap<>();
        for (SourceFile sourceFile : sourceFiles) {
            Set<Integer> reportedLines = new HashSet<>();
            for (ClassOrInterfaceType type : sourceFile.unit().findAll(ClassOrInterfaceType.class)) {
                Optional<Type> key = keyType(type);
                if (key.isEmpty() || TestClasses.isInside(type)) {
                    continue;
                }
                String place = sourceFile.path().getFileName() + ":" + type.getBegin().map(position -> position.line).orElse(0);
                if (key.get().isArrayType() && reportedLines.add(type.getBegin().map(position -> position.line).orElse(0))) {
                    violations.add(violation(sourceFile, type,
                            "Массив в роли ключа: '" + Nodes.text(type) + "'. hashCode и equals у массива работают по"
                                    + " ссылке, поэтому два массива с одинаковым содержимым - разные ключи: get и"
                                    + " contains с новым массивом ничего не находят, а put добавляет вторую запись;"
                                    + " оберните содержимое в неизменяемый тип: String, List.of(...), ByteBuffer.wrap(...)"
                                    + " либо record со своими equals и hashCode").withConfidence(Confidence.CONFIRMED));
                }
                if (key.get().isClassOrInterfaceType()) {
                    classes.find(LocalTypes.typeName(key.get()))
                            .ifPresent(found -> usages.putIfAbsent(found, Nodes.text(type) + " в " + place));
                }
            }
        }

        Map<String, String> lookups = lookupsWithNewKey(sourceFiles);
        usages.forEach((key, usage) -> {
            String lookup = lookups.get(key.getNameAsString());
            if (lookup != null && comparesByReference(key, classes)) {
                violations.add(violation(classes.fileOf(key), key.getName(),
                        "Класс '" + key.getNameAsString() + "' служит ключом хеш-таблицы (" + usage + "), и для"
                                + " обращения к ней ключ создают заново (" + lookup + "), но equals и hashCode у класса"
                                + " не переопределены: ключи сравниваются по ссылке, новый объект не равен ни одному"
                                + " из лежащих в таблице - get и contains ничего не находят, а put каждый раз добавляет"
                                + " запись, и таблица растет; сделайте ключ record либо добавьте equals и hashCode"));
            }
        });
        return violations;
    }

    @Override
    public ErrorLevel errorLevel() {
        return ErrorLevel.MAJOR;
    }

    @Override
    public ErrorType errorType() {
        return ErrorType.LOGICAL;
    }

    // Map<Key, V>, Set<Key>: тип ключа
    private Optional<Type> keyType(ClassOrInterfaceType type) {
        String container = type.getNameAsString();
        if (!MAP_TYPES.contains(container) && !SET_TYPES.contains(container)) {
            return Optional.empty();
        }
        return type.getTypeArguments().filter(arguments -> !arguments.isEmpty()).map(arguments -> arguments.get(0));
    }

    /**
     * @return имя класса -> место, где его объект создают прямо в обращении к таблице: routes.get(new RouteKey(...))
     */
    private Map<String, String> lookupsWithNewKey(List<SourceFile> sourceFiles) {
        Map<String, String> lookups = new LinkedHashMap<>();
        for (SourceFile sourceFile : sourceFiles) {
            for (MethodCallExpr call : sourceFile.unit().findAll(MethodCallExpr.class)) {
                if (!KEY_CALLS.contains(call.getNameAsString()) || call.getArguments().isEmpty() || call.getScope().isEmpty()) {
                    continue;
                }
                Expression key = Nodes.unwrap(call.getArgument(0));
                if (key.isObjectCreationExpr() && key.asObjectCreationExpr().getAnonymousClassBody().isEmpty()) {
                    lookups.putIfAbsent(key.asObjectCreationExpr().getType().getNameAsString(),
                            Nodes.text(call.getScope().get()) + "." + call.getNameAsString() + "(new "
                                    + key.asObjectCreationExpr().getType().getNameAsString() + "(...)) в "
                                    + sourceFile.path().getFileName() + ":"
                                    + call.getBegin().map(position -> position.line).orElse(0));
                }
            }
        }
        return lookups;
    }

    // Ни сам класс, ни его предки из проекта equals и hashCode не определяют; о предке вне проекта судить нельзя
    private boolean comparesByReference(ClassOrInterfaceDeclaration key, ProjectClasses classes) {
        if (key.isInterface() || key.isAbstract() || classes.hasUnknownAncestor(key)) {
            return false;
        }
        Optional<ClassOrInterfaceDeclaration> current = Optional.of(key);
        while (current.isPresent()) {
            boolean defined = Annotations.hasAny(current.get(), LOMBOK_EQUALITY)
                    || current.get().getMethods().stream().anyMatch(method -> EQUALITY_METHODS.contains(method.getNameAsString()));
            if (defined) {
                return false;
            }
            current = classes.parent(current.get());
        }
        return true;
    }
}
