package ru.akvine.zond.rules.logical;

import com.github.javaparser.ast.Node;
import com.github.javaparser.ast.expr.Expression;
import com.github.javaparser.ast.expr.MethodCallExpr;
import org.springframework.stereotype.Component;
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

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;

@Component
public class MutableMapKeyRule extends AbstractRule implements ProjectRule {
    private static final String PUT = "put";
    private static final String ADD = "add";
    private static final Set<String> MAP_TYPES = Set.of(
            "Map", "HashMap", "LinkedHashMap", "ConcurrentHashMap", "TreeMap", "SortedMap", "ConcurrentMap");
    private static final Set<String> SET_TYPES = Set.of("Set", "HashSet", "LinkedHashSet", "TreeSet", "SortedSet");
    private static final Pattern SETTER = Pattern.compile("set[A-Z].*");
    private static final String HASH_CODE = "hashCode";
    // Lombok строит hashCode по полям класса
    private static final Set<String> LOMBOK_HASH_CODE = Set.of("Data", "EqualsAndHashCode", "Value");

    @Override
    public String code() {
        return RuleCodes.MUTABLE_MAP_KEY_RULE_CODE;
    }

    @Override
    public String description() {
        return "Сканирует код и ищет объект, который изменяют после того, как положили ключом в Map или в Set";
    }

    @Override
    public List<Violation> checkProject(List<SourceFile> sourceFiles) {
        ProjectClasses classes = ProjectClasses.of(sourceFiles);
        return sourceFiles.stream().flatMap(sourceFile -> check(sourceFile, classes).stream()).toList();
    }

    @Override
    public ErrorLevel errorLevel() {
        return ErrorLevel.MAJOR;
    }

    @Override
    public ErrorType errorType() {
        return ErrorType.LOGICAL;
    }

    private List<Violation> check(SourceFile sourceFile, ProjectClasses classes) {
        List<Violation> violations = new ArrayList<>();
        Set<Node> reported = new HashSet<>();
        for (MethodCallExpr call : sourceFile.unit().findAll(MethodCallExpr.class)) {
            Optional<String> key = keyOf(call).filter(name -> hashDependsOnFields(call, classes));
            Optional<Node> callable = Nodes.enclosingCallable(call);
            if (key.isEmpty() || callable.isEmpty()) {
                continue;
            }
            for (MethodCallExpr later : callable.get().findAll(MethodCallExpr.class)) {
                boolean changesKey = SETTER.matcher(later.getNameAsString()).matches()
                        && later.getScope().filter(scope -> isName(scope, key.get())).isPresent();
                if (changesKey && isAfter(later, call) && reported.add(later)) {
                    violations.add(violation(sourceFile, later,
                            "Объект '" + key.get() + "' изменяется после того, как стал ключом (строка "
                                    + call.getBegin().map(position -> position.line).orElse(0) + "): его hashCode"
                                    + " меняется, и запись в коллекции перестает находиться - get вернет null,"
                                    + " contains - false; заполняйте объект до вставки либо делайте ключ неизменяемым"));
                }
            }
        }
        return violations;
    }

    // Без своего hashCode объект ищется по ссылке, и изменение полей ему не мешает
    private boolean hashDependsOnFields(MethodCallExpr call, ProjectClasses classes) {
        return LocalTypes.findDeclaration(call.getArgument(0))
                .flatMap(LocalTypes::declaredType)
                .flatMap(classes::find)
                .filter(type -> !type.getMethodsByName(HASH_CODE).isEmpty() || Annotations.hasAny(type, LOMBOK_HASH_CODE))
                .isPresent();
    }

    // map.put(key, value) либо set.add(key), где key - переменная
    private Optional<String> keyOf(MethodCallExpr call) {
        boolean intoMap = PUT.equals(call.getNameAsString()) && call.getArguments().size() == 2 && isOneOf(call, MAP_TYPES);
        boolean intoSet = ADD.equals(call.getNameAsString()) && call.getArguments().size() == 1 && isOneOf(call, SET_TYPES);
        if (!intoMap && !intoSet) {
            return Optional.empty();
        }
        Expression key = Nodes.unwrap(call.getArgument(0));
        return key.isNameExpr() ? Optional.of(key.asNameExpr().getNameAsString()) : Optional.empty();
    }

    private boolean isOneOf(MethodCallExpr call, Set<String> types) {
        return call.getScope().flatMap(LocalTypes::typeOf).filter(types::contains).isPresent();
    }

    private boolean isName(Expression expression, String name) {
        Expression value = Nodes.unwrap(expression);
        return value.isNameExpr() && value.asNameExpr().getNameAsString().equals(name);
    }

    private boolean isAfter(Node later, Node earlier) {
        return later.getBegin().isPresent() && earlier.getEnd().isPresent()
                && later.getBegin().get().isAfter(earlier.getEnd().get());
    }
}
