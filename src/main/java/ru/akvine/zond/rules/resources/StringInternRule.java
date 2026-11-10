package ru.akvine.zond.rules.resources;

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
import java.util.List;
import java.util.regex.Pattern;

/**
 * String.intern() на значениях из данных. Пул строк - общая на всю JVM таблица без предела числа записей:
 * поток разных значений (идентификаторы, имена, тексты из запросов) раздувает ее, а каждый intern() - это поиск
 * в общей таблице. Строки-константы и так лежат в пуле, а для остальных нужен свой ограниченный кеш.
 */
@Component
public class StringInternRule extends AbstractRule {
    private static final String INTERN = "intern";
    private static final String STRING = "String";
    private static final Pattern CONSTANT_NAME = Pattern.compile("^[A-Z][A-Z0-9_]*$");

    @Override
    public String code() {
        return RuleCodes.STRING_INTERN_RULE_CODE;
    }

    @Override
    public String description() {
        return "Сканирует код и ищет String.intern() на значениях из данных: пул строк JVM растет без предела";
    }

    @Override
    public List<Violation> check(SourceFile sourceFile) {
        List<Violation> violations = new ArrayList<>();
        for (MethodCallExpr call : sourceFile.unit().findAll(MethodCallExpr.class)) {
            if (!INTERN.equals(call.getNameAsString()) || !call.getArguments().isEmpty() || call.getScope().isEmpty()) {
                continue;
            }
            Expression value = Nodes.unwrap(call.getScope().get());
            // Свой метод intern() у другого типа, константа (она и так в пуле) и код, который выполняется один раз
            if (LocalTypes.typeOf(value).filter(type -> !STRING.equals(type)).isPresent() || isConstant(value)
                    || CodeContexts.isStartup(call) || TestClasses.isInside(call)) {
                continue;
            }
            violations.add(violation(sourceFile, call,
                    "'" + Nodes.text(call) + "'" + (Loops.isRepeated(call) ? " в цикле" : "") + ": строка из данных"
                            + " попадает в общий пул строк JVM. Пул ничем не ограничен: поток разных значений"
                            + " (идентификаторы, имена, тексты из запросов) раздувает его, память растет, а каждый"
                            + " intern() - это поиск в общей на все потоки таблице; если значения почти не"
                            + " повторяются, экономии нет вовсе. Для повторяющихся значений заведите кеш с пределом"
                            + " размера (Caffeine) либо включите -XX:+UseStringDeduplication"));
        }
        return violations;
    }

    @Override
    public ErrorLevel errorLevel() {
        return ErrorLevel.MINOR;
    }

    @Override
    public ErrorType errorType() {
        return ErrorType.RESOURCE;
    }

    // "text".intern(), PREFIX.intern(), ("a" + "b").intern(): значений конечное число
    private boolean isConstant(Expression value) {
        if (value.isLiteralExpr()) {
            return true;
        }
        if (value.isBinaryExpr()) {
            return isConstant(Nodes.unwrap(value.asBinaryExpr().getLeft()))
                    && isConstant(Nodes.unwrap(value.asBinaryExpr().getRight()));
        }
        if (value.isNameExpr()) {
            return CONSTANT_NAME.matcher(value.asNameExpr().getNameAsString()).matches();
        }
        return value.isFieldAccessExpr() && CONSTANT_NAME.matcher(value.asFieldAccessExpr().getNameAsString()).matches();
    }
}
