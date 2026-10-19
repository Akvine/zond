package ru.akvine.zond.rules.performance;

import com.github.javaparser.ast.Node;
import com.github.javaparser.ast.expr.MethodCallExpr;
import com.github.javaparser.ast.stmt.ForEachStmt;
import org.springframework.stereotype.Component;
import ru.akvine.zond.enums.ErrorLevel;
import ru.akvine.zond.enums.ErrorType;
import ru.akvine.zond.models.SourceFile;
import ru.akvine.zond.models.Violation;
import ru.akvine.zond.rules.AbstractRule;
import ru.akvine.zond.rules.RuleCodes;
import ru.akvine.zond.rules.support.KeyLookups;
import ru.akvine.zond.rules.support.LocalTypes;
import ru.akvine.zond.rules.support.Loops;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;

@Component
public class StreamInLoopRule extends AbstractRule {
    private static final Set<String> STREAM_METHODS = Set.of("stream", "parallelStream");
    // Type.values(), getEnumConstants(), EnumSet.allOf(...): перебор констант перечисления. Их немного, и с ростом
    // данных их не становится больше. map.values() сюда не подходит - это данные
    private static final Pattern ENUM_SOURCE = Pattern.compile("\\b[A-Z]\\w*\\.values\\(\\)|getEnumConstants\\(\\)|EnumSet\\.allOf");

    @Override
    public String code() {
        return RuleCodes.STREAM_IN_LOOP_RULE_CODE;
    }

    @Override
    public String description() {
        return "Сканирует код и ищет стрим по внешней коллекции, который создается на каждой итерации цикла";
    }

    @Override
    public List<Violation> check(SourceFile sourceFile) {
        List<Violation> violations = new ArrayList<>();
        for (MethodCallExpr call : sourceFile.unit().findAll(MethodCallExpr.class)) {
            if (!STREAM_METHODS.contains(call.getNameAsString())
                    || !call.getArguments().isEmpty()
                    || call.getScope().isEmpty()) {
                continue;
            }

            // Коллекция объявлена до цикла, значит на каждой итерации она обходится заново целиком.
            // Стрим по коллекции, полученной внутри итерации (item.getChildren().stream()), сюда не попадает
            Optional<Node> iteration = Loops.enclosingIteration(call);
            Optional<Node> declaration = LocalTypes.findDeclaration(call.getScope().get());
            // Поиск элемента по ключу показывает LinearSearchInsteadOfMapRule: там совет точнее - собрать Map
            boolean keyLookup = KeyLookups.ofStream(call).filter(KeyLookups::isRepeated).isPresent();
            if (iteration.isPresent()
                    && declaration.isPresent()
                    && !keyLookup
                    && !isOverEnumConstants(iteration.get())
                    && Loops.isDeclaredOutside(iteration.get(), declaration.get())) {
                violations.add(violation(sourceFile, call,
                        "Стрим по '" + call.getScope().get() + "' создается на каждой итерации: коллекция всякий раз"
                                + " обходится заново, вместе с циклом выходит O(N×M); вынесите вычисление из цикла"
                                + " или заранее сложите данные в Map / Set"));
            }
        }
        return violations;
    }

    // for (Type type : Type.values()) либо Arrays.stream(Type.values()).collect(toMap(identity(), type -> ...))
    private boolean isOverEnumConstants(Node iteration) {
        if (iteration instanceof ForEachStmt loop) {
            return ENUM_SOURCE.matcher(loop.getIterable().toString()).find();
        }
        Node current = iteration.getParentNode().orElse(null);
        while (current instanceof MethodCallExpr call) {
            if (call.getScope().filter(scope -> ENUM_SOURCE.matcher(scope.toString()).find()).isPresent()) {
                return true;
            }
            current = call.getParentNode().orElse(null);
        }
        return false;
    }

    @Override
    public ErrorLevel errorLevel() {
        return ErrorLevel.MINOR;
    }

    @Override
    public ErrorType errorType() {
        return ErrorType.PERFORMANCE;
    }
}
