package ru.akvine.zond.rules.logical;

import com.github.javaparser.ast.expr.ArrayAccessExpr;
import com.github.javaparser.ast.expr.Expression;
import com.github.javaparser.ast.expr.MethodCallExpr;
import org.springframework.stereotype.Component;
import ru.akvine.zond.enums.ErrorLevel;
import ru.akvine.zond.enums.ErrorType;
import ru.akvine.zond.models.SourceFile;
import ru.akvine.zond.models.Violation;
import ru.akvine.zond.rules.AbstractRule;
import ru.akvine.zond.rules.RuleCodes;
import ru.akvine.zond.rules.support.Guards;
import ru.akvine.zond.rules.support.LocalTypes;
import ru.akvine.zond.rules.support.Nodes;
import ru.akvine.zond.rules.support.TestClasses;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Set;

@Component
public class CheckIndexWithoutLengthCheckRule extends AbstractRule {
    private static final String STRING = "String";
    private static final String SPLIT = "split";
    private static final String LENGTH = "length";
    private static final String FIRST_INDEX = "0";
    private static final Set<String> POSITION_METHODS = Set.of("charAt", "substring");

    // Проверки, после которых о длине строки что-то известно
    private static final Set<String> LENGTH_CHECKS = Set.of(
            "length", "isEmpty", "isBlank", "startsWith", "endsWith", "contains", "indexOf", "lastIndexOf", "matches",
            "hasText", "hasLength", "isNotBlank", "isNotEmpty");

    @Override
    public String code() {
        return RuleCodes.CHECK_INDEX_WITHOUT_LENGTH_CHECK_RULE_CODE;
    }

    @Override
    public String description() {
        return "Сканирует код и ищет charAt, substring и элемент массива после split без проверки длины";
    }

    @Override
    public List<Violation> check(SourceFile sourceFile) {
        List<Violation> violations = new ArrayList<>();

        // text.charAt(0), text.substring(0, 10)
        for (MethodCallExpr call : sourceFile.unit().findAll(MethodCallExpr.class)) {
            if (!POSITION_METHODS.contains(call.getNameAsString()) || call.getScope().isEmpty()
                    || call.getArguments().stream().noneMatch(Expression::isIntegerLiteralExpr)) {
                continue;
            }
            Expression scope = Nodes.unwrap(call.getScope().get());
            if (scope.isNameExpr() && LocalTypes.typeOf(scope).filter(STRING::equals).isPresent()
                    && !TestClasses.isInside(call) && !isLengthChecked(call, scope.toString())) {
                violations.add(violation(sourceFile, call,
                        "'" + call + "' без проверки длины строки: на короткой или пустой строке будет"
                                + " StringIndexOutOfBoundsException; проверьте length() или isEmpty()"));
            }
        }

        // String[] parts = line.split(","); parts[1]
        for (ArrayAccessExpr access : sourceFile.unit().findAll(ArrayAccessExpr.class)) {
            // Нулевой элемент есть всегда: split возвращает хотя бы одну часть
            if (!access.getIndex().isIntegerLiteralExpr() || !access.getName().isNameExpr()
                    || FIRST_INDEX.equals(access.getIndex().asIntegerLiteralExpr().getValue())) {
                continue;
            }
            boolean fromSplit = LocalTypes.findInitializer(access.getName())
                    .map(Nodes::unwrap)
                    .filter(value -> value.isMethodCallExpr() && SPLIT.equals(value.asMethodCallExpr().getNameAsString()))
                    .isPresent();
            if (fromSplit && !TestClasses.isInside(access) && !isArrayLengthChecked(access)) {
                violations.add(violation(sourceFile, access,
                        "'" + access + "' без проверки числа частей после split: если разделителя в строке"
                                + " не окажется, будет ArrayIndexOutOfBoundsException; проверьте length"));
            }
        }

        violations.sort(Comparator.comparingInt(Violation::line));
        return violations;
    }

    @Override
    public ErrorLevel errorLevel() {
        return ErrorLevel.MINOR;
    }

    @Override
    public ErrorType errorType() {
        return ErrorType.LOGICAL;
    }

    private boolean isLengthChecked(MethodCallExpr call, String text) {
        return Guards.isGuarded(call, check -> check.isMethodCallExpr()
                && LENGTH_CHECKS.contains(check.asMethodCallExpr().getNameAsString())
                && (check.asMethodCallExpr().getScope().filter(scope -> scope.toString().equals(text)).isPresent()
                || check.asMethodCallExpr().getArguments().stream().anyMatch(argument -> argument.toString().equals(text))));
    }

    private boolean isArrayLengthChecked(ArrayAccessExpr access) {
        String array = access.getName().toString();
        return Guards.isGuarded(access, check -> check.isFieldAccessExpr()
                && LENGTH.equals(check.asFieldAccessExpr().getNameAsString())
                && check.asFieldAccessExpr().getScope().toString().equals(array));
    }
}
