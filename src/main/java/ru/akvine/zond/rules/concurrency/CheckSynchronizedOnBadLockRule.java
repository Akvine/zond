package ru.akvine.zond.rules.concurrency;

import com.github.javaparser.ast.body.FieldDeclaration;
import com.github.javaparser.ast.body.VariableDeclarator;
import com.github.javaparser.ast.expr.Expression;
import com.github.javaparser.ast.stmt.SynchronizedStmt;
import org.springframework.stereotype.Component;
import ru.akvine.zond.enums.ErrorLevel;
import ru.akvine.zond.enums.ErrorType;
import ru.akvine.zond.models.SourceFile;
import ru.akvine.zond.models.Violation;
import ru.akvine.zond.rules.AbstractRule;
import ru.akvine.zond.rules.RuleCodes;
import ru.akvine.zond.rules.support.LocalTypes;
import ru.akvine.zond.rules.support.Nodes;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;

@Component
public class CheckSynchronizedOnBadLockRule extends AbstractRule {
    // Объекты этих типов JVM кэширует и разделяет между несвязанными частями программы
    private static final Set<String> SHARED_TYPES = Set.of(
            "String", "Integer", "Long", "Short", "Byte", "Character", "Boolean");

    @Override
    public String code() {
        return RuleCodes.CHECK_SYNCHRONIZED_ON_BAD_LOCK_RULE_CODE;
    }

    @Override
    public String description() {
        return "Сканирует код и ищет synchronized по ненадежному объекту: строке, обертке, не-final полю";
    }

    @Override
    public List<Violation> check(SourceFile sourceFile) {
        List<Violation> violations = new ArrayList<>();
        for (SynchronizedStmt block : sourceFile.unit().findAll(SynchronizedStmt.class)) {
            describeProblem(Nodes.unwrap(block.getExpression())).ifPresent(problem -> violations.add(violation(
                    sourceFile, block,
                    "synchronized (" + block.getExpression() + "): " + problem
                            + "; используйте отдельное поле private final Object lock = new Object()")));
        }
        return violations;
    }

    @Override
    public ErrorLevel errorLevel() {
        return ErrorLevel.MAJOR;
    }

    @Override
    public ErrorType errorType() {
        return ErrorType.CONCURRENCY;
    }

    private Optional<String> describeProblem(Expression lock) {
        if (lock.isStringLiteralExpr()) {
            return Optional.of("строковый литерал один на всю JVM, тот же замок может захватить посторонний код");
        }

        Optional<String> type = LocalTypes.typeOf(lock);
        if (type.filter(SHARED_TYPES::contains).isPresent()) {
            return Optional.of("объекты типа " + type.get() + " кэшируются и разделяются, тот же замок может"
                    + " захватить посторонний код");
        }

        // Поле можно переприсвоить - тогда потоки синхронизируются по разным объектам
        boolean isMutableField = LocalTypes.findDeclaration(lock)
                .filter(declaration -> declaration instanceof VariableDeclarator)
                .flatMap(declaration -> declaration.getParentNode())
                .filter(parent -> parent instanceof FieldDeclaration field && !field.isFinal())
                .isPresent();
        return isMutableField
                ? Optional.of("поле не final, после его замены потоки будут синхронизироваться по разным объектам")
                : Optional.empty();
    }
}
