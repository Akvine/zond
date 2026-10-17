package ru.akvine.zond.rules.logical;

import com.github.javaparser.ast.expr.Expression;
import com.github.javaparser.ast.expr.MethodCallExpr;
import org.springframework.stereotype.Component;
import ru.akvine.zond.enums.ErrorLevel;
import ru.akvine.zond.enums.ErrorType;
import ru.akvine.zond.models.SourceFile;
import ru.akvine.zond.models.Violation;
import ru.akvine.zond.rules.AbstractRule;
import ru.akvine.zond.rules.RuleCodes;
import ru.akvine.zond.rules.support.LocalTypes;
import ru.akvine.zond.rules.support.Nodes;
import ru.akvine.zond.rules.support.TestClasses;

import java.util.List;
import java.util.Set;

@Component
public class CaseWithoutLocaleRule extends AbstractRule {
    private static final String TO_STRING = "toString";
    private static final String UUID = "UUID";
    private static final String RANDOM_UUID = "UUID.randomUUID()";
    private static final Set<String> HEX_METHODS = Set.of("toHexString", "encodeHexString", "formatHex", "toHex");
    private static final Set<String> CASE_METHODS = Set.of("toLowerCase", "toUpperCase");

    @Override
    public String code() {
        return RuleCodes.CASE_WITHOUT_LOCALE_RULE_CODE;
    }

    @Override
    public String description() {
        return "Сканирует код и ищет toLowerCase() и toUpperCase() без указания локали";
    }

    @Override
    public List<Violation> check(SourceFile sourceFile) {
        return sourceFile.unit().findAll(MethodCallExpr.class).stream()
                .filter(call -> CASE_METHODS.contains(call.getNameAsString()))
                .filter(call -> call.getArguments().isEmpty() && call.getScope().isPresent())
                .filter(call -> !TestClasses.isInside(call) && !isLocaleNeutral(call.getScope().get()))
                .map(call -> violation(sourceFile, call,
                        "'" + call + "' без локали: результат зависит от языка системы - в турецкой локали"
                                + " \"TITLE\".toLowerCase() дает \"tıtle\", и сравнение ключей, имен и кодов"
                                + " перестает работать; укажите локаль: " + call.getNameAsString() + "(Locale.ROOT)"))
                .toList();
    }

    /**
     * В строке нет букв, которые меняются по-разному в разных языках: UUID и шестнадцатеричная запись состоят
     * из цифр, букв a-f и дефиса.
     */
    private boolean isLocaleNeutral(Expression text) {
        Expression value = Nodes.unwrap(text);
        if (!value.isMethodCallExpr()) {
            return false;
        }
        MethodCallExpr call = value.asMethodCallExpr();
        if (HEX_METHODS.contains(call.getNameAsString())) {
            return true;
        }
        return TO_STRING.equals(call.getNameAsString()) && call.getScope()
                .filter(scope -> scope.toString().startsWith(RANDOM_UUID)
                        || LocalTypes.typeOf(scope).filter(UUID::equals).isPresent())
                .isPresent();
    }

    @Override
    public ErrorLevel errorLevel() {
        return ErrorLevel.MINOR;
    }

    @Override
    public ErrorType errorType() {
        return ErrorType.LOGICAL;
    }
}
