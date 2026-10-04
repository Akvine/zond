package ru.akvine.zond.rules.codesmell;

import com.github.javaparser.ast.Node;
import com.github.javaparser.ast.body.VariableDeclarator;
import com.github.javaparser.ast.expr.Expression;
import com.github.javaparser.ast.expr.MethodReferenceExpr;
import com.github.javaparser.ast.expr.NameExpr;
import org.springframework.stereotype.Component;
import ru.akvine.zond.enums.ErrorLevel;
import ru.akvine.zond.enums.ErrorType;
import ru.akvine.zond.models.SourceFile;
import ru.akvine.zond.models.Violation;
import ru.akvine.zond.rules.AbstractRule;
import ru.akvine.zond.rules.RuleCodes;
import ru.akvine.zond.rules.support.LocalTypes;
import ru.akvine.zond.rules.support.Nodes;
import ru.akvine.zond.rules.support.Resources;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

@Component
public class CheckUnusedLocalVariableRule extends AbstractRule {
    // Имена, которыми переменную намеренно помечают как ненужную
    private static final Set<String> IGNORED_NAMES = Set.of("_", "ignored", "ignore", "unused");

    @Override
    public String code() {
        return RuleCodes.CHECK_UNUSED_LOCAL_VARIABLE_RULE_CODE;
    }

    @Override
    public String description() {
        return "Сканирует код и ищет локальные переменные, которые объявлены и нигде не используются";
    }

    @Override
    public List<Violation> check(SourceFile sourceFile) {
        List<Violation> violations = new ArrayList<>();
        for (VariableDeclarator variable : sourceFile.unit().findAll(VariableDeclarator.class)) {
            Optional<Node> callable = Nodes.enclosingCallable(variable);
            String name = variable.getNameAsString();
            if (callable.isEmpty() || !Resources.isLocalVariable(variable) || IGNORED_NAMES.contains(name)) {
                continue;
            }

            if (!findUsedNames(callable.get()).contains(name)) {
                violations.add(violation(sourceFile, variable,
                        "Локальная переменная '" + name + "' объявлена и не используется: либо она осталась от"
                                + " прежней версии кода, либо результат забыли применить; удалите ее или"
                                + " используйте"));
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
        return ErrorType.CODE_SMELL;
    }

    private Set<String> findUsedNames(Node callable) {
        Set<String> used = new HashSet<>();
        callable.findAll(NameExpr.class).forEach(name -> used.add(name.getNameAsString()));

        // value::equals - парсер не знает, переменная это или тип, и хранит value как тип, а не как NameExpr
        callable.findAll(MethodReferenceExpr.class).stream()
                .map(MethodReferenceExpr::getScope)
                .filter(Expression::isTypeExpr)
                .forEach(scope -> used.add(LocalTypes.typeName(scope.asTypeExpr().getType())));
        return used;
    }
}
