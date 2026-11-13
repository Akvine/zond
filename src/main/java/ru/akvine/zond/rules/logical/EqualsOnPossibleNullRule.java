package ru.akvine.zond.rules.logical;

import com.github.javaparser.ast.body.Parameter;
import com.github.javaparser.ast.expr.Expression;
import com.github.javaparser.ast.expr.LambdaExpr;
import com.github.javaparser.ast.expr.MethodCallExpr;
import com.github.javaparser.ast.stmt.BlockStmt;
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

import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

@Component
public class EqualsOnPossibleNullRule extends AbstractRule {
    private static final String ENTRY_KEY = "getKey";
    private static final Set<String> EQUALS_METHODS = Set.of("equals", "equalsIgnoreCase");
    private static final Pattern GETTER = Pattern.compile("^get[A-Z].*");
    private static final Set<String> NAME_GETTERS = Set.of("getName", "getSimpleName", "getTypeName");
    private static final Set<String> NAMED_TYPES = Set.of(
            "Method", "Field", "Class", "Constructor", "Executable", "Member", "Thread", "File", "Package");

    @Override
    public String code() {
        return RuleCodes.EQUALS_ON_POSSIBLE_NULL_RULE_CODE;
    }

    @Override
    public String description() {
        return "Сканирует код и ищет equals со строкой-литералом у значения, которое может быть null";
    }

    @Override
    public List<Violation> check(SourceFile sourceFile) {
        return sourceFile.unit().findAll(MethodCallExpr.class).stream()
                .filter(call -> EQUALS_METHODS.contains(call.getNameAsString()) && call.getArguments().size() == 1)
                .filter(call -> call.getArgument(0).isStringLiteralExpr() && call.getScope().isPresent())
                .filter(call -> mayBeNull(call.getScope().get()) && !TestClasses.isInside(call))
                .filter(call -> !isNullChecked(call) && !isDereferencedEarlier(call))
                .map(call -> violation(sourceFile, call,
                        "'" + call + "': если '" + call.getScope().get() + "' окажется null, будет"
                                + " NullPointerException; поменяйте местами - " + call.getArgument(0) + "."
                                + call.getNameAsString() + "(" + call.getScope().get() + ")"))
                .toList();
    }

    @Override
    public ErrorLevel errorLevel() {
        return ErrorLevel.MINOR;
    }

    @Override
    public ErrorType errorType() {
        return ErrorType.LOGICAL;
    }

    // Значение пришло извне: параметр метода либо свойство другого объекта (order.getStatus()).
    // toString(), trim() и подобные null не возвращают; локальная переменная и поле - на совести автора
    private boolean mayBeNull(Expression scope) {
        Expression value = Nodes.unwrap(scope);
        if (value.isMethodCallExpr()) {
            return GETTER.matcher(value.asMethodCallExpr().getNameAsString()).matches()
                    && value.asMethodCallExpr().getArguments().isEmpty()
                    // entry.getKey(): пустых ключей в картах на практике не держат
                    && !ENTRY_KEY.equals(value.asMethodCallExpr().getNameAsString())
                    && !isAlwaysNamed(value.asMethodCallExpr());
        }
        return value.isNameExpr() && LocalTypes.findDeclaration(value)
                .filter(declaration -> declaration instanceof Parameter)
                .isPresent();
    }

    // method.getName(), type.getSimpleName(), file.getName(): имя у метода, класса, потока и файла есть всегда
    private boolean isAlwaysNamed(MethodCallExpr getter) {
        return NAME_GETTERS.contains(getter.getNameAsString()) && getter.getScope()
                .flatMap(LocalTypes::typeOf)
                .map(type -> type.replaceAll("<.*", ""))
                .filter(NAMED_TYPES::contains)
                .isPresent();
    }

    /**
     * str.split("_") ... str.equals("x"): к значению уже обращались выше по ходу метода - будь оно null,
     * исключение возникло бы там, и до сравнения дело бы не дошло. Обращение в соседней ветке не считается
     */
    private boolean isDereferencedEarlier(MethodCallExpr call) {
        String value = call.getScope().get().toString();
        int position = call.getBegin().map(begin -> begin.line).orElse(0);
        int column = call.getBegin().map(begin -> begin.column).orElse(0);
        return Nodes.enclosingCallable(call).stream()
                .flatMap(callable -> callable.findAll(MethodCallExpr.class).stream())
                .filter(earlier -> earlier != call
                        && earlier.getScope().filter(scope -> scope.toString().equals(value)).isPresent())
                .filter(earlier -> earlier.getBegin()
                        .filter(begin -> begin.line < position || begin.line == position && begin.column < column)
                        .isPresent())
                .anyMatch(earlier -> earlier.findAncestor(BlockStmt.class)
                        .filter(block -> Nodes.isInside(call, block))
                        .isPresent()
                        && earlier.findAncestor(LambdaExpr.class).equals(call.findAncestor(LambdaExpr.class)));
    }

    private boolean isNullChecked(MethodCallExpr call) {
        String value = call.getScope().get().toString();
        return Guards.isGuarded(call, check -> Guards.isNullCheck(check, value));
    }
}
