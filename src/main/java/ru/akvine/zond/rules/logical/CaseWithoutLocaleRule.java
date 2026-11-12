package ru.akvine.zond.rules.logical;

import com.github.javaparser.ast.Node;
import com.github.javaparser.ast.body.VariableDeclarator;
import com.github.javaparser.ast.expr.EnclosedExpr;
import com.github.javaparser.ast.expr.Expression;
import com.github.javaparser.ast.expr.MethodCallExpr;
import com.github.javaparser.ast.expr.NameExpr;
import com.github.javaparser.ast.expr.SwitchExpr;
import com.github.javaparser.ast.stmt.SwitchStmt;
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
    // Между сменой регистра и сравнением строку еще обрезают и чистят
    private static final Set<String> PASSING = Set.of("trim", "strip", "intern", "replace", "substring", "toString");
    // Результат - тот, кого сравнивают: name.toLowerCase().equals(...)
    private static final Set<String> COMPARING = Set.of(
            "equals", "contains", "startsWith", "endsWith", "matches", "indexOf", "compareTo", "hashCode",
            "contentEquals");
    // Результат - то, с чем сравнивают или по чему ищут: ROLES.get(name.toUpperCase()), Kind.valueOf(...)
    private static final Set<String> LOOKING_UP = Set.of(
            "equals", "contains", "startsWith", "endsWith", "indexOf", "compareTo", "contentEquals", "get",
            "getOrDefault", "put", "putIfAbsent", "computeIfAbsent", "containsKey", "remove", "add", "valueOf",
            "of", "forName", "matcher", "isEqualTo");

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
                .map(call -> report(sourceFile, call))
                .toList();
    }

    // Ошибка проявляется там, где результат с чем-то сверяют: в сравнении, в switch, в ключе Map и в имени
    // константы enum. Строка, которую только показывают или сохраняют, от локали выглядит иначе, но ничего не ломает
    private Violation report(SourceFile sourceFile, MethodCallExpr call) {
        String text = "'" + call + "' без локали: результат зависит от языка системы - в турецкой локали"
                + " \"TITLE\".toLowerCase() дает \"tıtle\", и сравнение ключей, имен и кодов"
                + " перестает работать; укажите локаль: " + call.getNameAsString() + "(Locale.ROOT)";
        if (isCompared(call) || isComparedLater(call)) {
            return violation(sourceFile, call, text);
        }
        return violation(sourceFile, call, text + ". Здесь результат ни с чем не сверяется, поэтому это замечание")
                .withLevel(ErrorLevel.INFO);
    }

    // name.toLowerCase().trim().equals("admin"), ROLES.get(name.toUpperCase()), switch (type.toLowerCase())
    private boolean isCompared(Expression value) {
        Node current = value;
        Node parent = value.getParentNode().orElse(null);
        // (name.toLowerCase()).trim(): сравнивают то, что получилось после обрезки и замены
        while (parent instanceof EnclosedExpr
                || parent instanceof MethodCallExpr chained && isScope(chained, current)
                && PASSING.contains(chained.getNameAsString())) {
            current = parent;
            parent = parent.getParentNode().orElse(null);
        }
        if (parent instanceof SwitchStmt || parent instanceof SwitchExpr) {
            return true;
        }
        return parent instanceof MethodCallExpr call
                && (isScope(call, current) ? COMPARING : LOOKING_UP).contains(call.getNameAsString());
    }

    private boolean isScope(MethodCallExpr call, Node node) {
        return call.getScope().filter(scope -> scope == node).isPresent();
    }

    // String key = name.toLowerCase(); ... roles.containsKey(key)
    private boolean isComparedLater(MethodCallExpr call) {
        if (!(call.getParentNode().orElse(null) instanceof VariableDeclarator variable)) {
            return false;
        }
        return Nodes.enclosingCallable(variable).stream()
                .flatMap(callable -> callable.findAll(NameExpr.class).stream())
                .anyMatch(usage -> usage.getNameAsString().equals(variable.getNameAsString()) && isCompared(usage));
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
