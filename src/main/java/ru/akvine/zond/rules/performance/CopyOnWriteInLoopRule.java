package ru.akvine.zond.rules.performance;

import com.github.javaparser.ast.Node;
import com.github.javaparser.ast.expr.Expression;
import com.github.javaparser.ast.expr.MethodCallExpr;
import org.springframework.stereotype.Component;
import ru.akvine.zond.enums.ErrorLevel;
import ru.akvine.zond.enums.ErrorType;
import ru.akvine.zond.models.SourceFile;
import ru.akvine.zond.models.Violation;
import ru.akvine.zond.rules.AbstractRule;
import ru.akvine.zond.rules.RuleCodes;
import ru.akvine.zond.rules.support.CodeContexts;
import ru.akvine.zond.rules.support.LocalTypes;
import ru.akvine.zond.rules.support.Loops;
import ru.akvine.zond.rules.support.Nodes;
import ru.akvine.zond.rules.support.TestClasses;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * Запись в CopyOnWriteArrayList в цикле: на каждое add() список копирует весь свой массив, и цикл из n
 * добавлений обходится в n² копирований. Элементы собирают в обычный список и добавляют одним addAll().
 */
@Component
public class CopyOnWriteInLoopRule extends AbstractRule {
    private static final Set<String> COPY_ON_WRITE = Set.of("CopyOnWriteArrayList", "CopyOnWriteArraySet");
    private static final Set<String> WRITES = Set.of("add", "remove", "set", "addIfAbsent");

    @Override
    public String code() {
        return RuleCodes.COPY_ON_WRITE_IN_LOOP_RULE_CODE;
    }

    @Override
    public String description() {
        return "Сканирует код и ищет запись в CopyOnWriteArrayList и CopyOnWriteArraySet в цикле: каждая запись"
                + " копирует весь массив";
    }

    @Override
    public List<Violation> check(SourceFile sourceFile) {
        List<Violation> violations = new ArrayList<>();
        Set<String> reported = new HashSet<>();
        for (MethodCallExpr call : sourceFile.unit().findAll(MethodCallExpr.class)) {
            if (!WRITES.contains(call.getNameAsString()) || call.getScope().isEmpty()
                    || !CodeContexts.isRepeatedOverData(call) || TestClasses.isInside(call)
                    || !isCopyOnWrite(call.getScope().get())) {
                continue;
            }
            Optional<Node> loop = Loops.enclosingIteration(call);
            if (reported.add(loop.map(System::identityHashCode).orElse(0) + ":" + call.getScope().get())) {
                violations.add(violation(sourceFile, call,
                        "'" + Nodes.text(call) + "' в цикле: коллекция с копированием при записи на каждый вызов"
                                + " копирует весь свой массив, и n записей обходятся в n² копирований; соберите"
                                + " элементы в обычный список и добавьте их одним addAll()"));
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
        return ErrorType.PERFORMANCE;
    }

    // Тип переменной либо то, чем она создана: List<Listener> listeners = new CopyOnWriteArrayList<>()
    private boolean isCopyOnWrite(Expression scope) {
        return LocalTypes.typeOf(scope).filter(COPY_ON_WRITE::contains).isPresent()
                || LocalTypes.findInitializer(scope)
                .map(Nodes::unwrap)
                .filter(Expression::isObjectCreationExpr)
                .filter(creation -> COPY_ON_WRITE.contains(creation.asObjectCreationExpr().getType().getNameAsString()))
                .isPresent();
    }
}
