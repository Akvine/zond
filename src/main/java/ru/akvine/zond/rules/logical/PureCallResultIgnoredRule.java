package ru.akvine.zond.rules.logical;

import com.github.javaparser.ast.Node;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.expr.LambdaExpr;
import com.github.javaparser.ast.expr.MethodCallExpr;
import com.github.javaparser.ast.expr.SwitchExpr;
import com.github.javaparser.ast.stmt.ExpressionStmt;
import com.github.javaparser.ast.stmt.SwitchEntry;
import org.springframework.stereotype.Component;
import ru.akvine.zond.enums.ErrorLevel;
import ru.akvine.zond.enums.ErrorType;
import ru.akvine.zond.models.SourceFile;
import ru.akvine.zond.models.Violation;
import ru.akvine.zond.rules.AbstractRule;
import ru.akvine.zond.rules.ProjectRule;
import ru.akvine.zond.rules.RuleCodes;
import ru.akvine.zond.rules.support.CallGraph;
import ru.akvine.zond.rules.support.SideEffects;
import ru.akvine.zond.rules.support.TestClasses;

import java.util.ArrayList;
import java.util.List;

/**
 * Вызов своего метода, который только вычисляет результат, стоит отдельной строкой: результат отброшен, и вызов
 * ничего не делает. Обычно это забытое присваивание - normalize(name); вместо name = normalize(name); - или
 * проверка, ответ которой никто не смотрит: isAllowed(user, order);
 * <p>
 * Что у метода нет действия, видно по его телу (SideEffects): он не пишет в поля и чужие объекты, не бросает
 * исключений и вызывает только такие же методы. Геттеры не проверяются: user.getRoles() отдельной строкой
 * подгружает ленивую коллекцию Hibernate
 */
@Component
public class PureCallResultIgnoredRule extends AbstractRule implements ProjectRule {
    private static final List<String> GETTER_PREFIXES = List.of("get", "is", "has");

    @Override
    public String code() {
        return RuleCodes.PURE_CALL_RESULT_IGNORED_RULE_CODE;
    }

    @Override
    public String description() {
        return "Сканирует проект и ищет вызовы методов без побочных эффектов, результат которых отброшен";
    }

    @Override
    public List<Violation> checkProject(List<SourceFile> sourceFiles) {
        CallGraph graph = CallGraph.of(sourceFiles);
        SideEffects effects = SideEffects.of(sourceFiles);
        List<Violation> violations = new ArrayList<>();
        for (SourceFile sourceFile : sourceFiles) {
            for (MethodCallExpr call : sourceFile.unit().findAll(MethodCallExpr.class)) {
                if (!isIgnored(call) || TestClasses.isInside(call)) {
                    continue;
                }
                List<MethodDeclaration> targets = graph.targetsOf(call);
                boolean computesOnly = !targets.isEmpty()
                        && targets.stream().noneMatch(target -> target.getType().isVoidType() || isGetter(target))
                        && targets.stream().allMatch(effects::isPure);
                if (computesOnly) {
                    violations.add(violation(sourceFile, call,
                            "Результат '" + call + "' отброшен, а метод " + call.getNameAsString() + " ничего не"
                                    + " меняет и только возвращает значение: вызов ничего не делает. Похоже, забыто"
                                    + " присваивание (value = " + call.getNameAsString() + "(value)) или проверка"
                                    + " результата"));
                }
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
        return ErrorType.LOGICAL;
    }

    private boolean isGetter(MethodDeclaration method) {
        String name = method.getNameAsString();
        return method.getParameters().isEmpty() && GETTER_PREFIXES.stream().anyMatch(prefix -> name.startsWith(prefix)
                && (name.length() == prefix.length() || Character.isUpperCase(name.charAt(prefix.length()))));
    }

    // Вызов - целый оператор. Тело лямбды-выражения и ветка case X -> value(...) выражения switch - это значение
    private boolean isIgnored(MethodCallExpr call) {
        Node parent = call.getParentNode().orElse(null);
        if (!(parent instanceof ExpressionStmt statement)) {
            return false;
        }
        Node owner = statement.getParentNode().orElse(null);
        boolean switchValue = owner instanceof SwitchEntry entry
                && entry.getParentNode().filter(SwitchExpr.class::isInstance).isPresent();
        return !(owner instanceof LambdaExpr) && !switchValue;
    }
}
