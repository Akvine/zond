package ru.akvine.zond.rules.exceptions;

import com.github.javaparser.ast.Node;
import com.github.javaparser.ast.body.BodyDeclaration;
import com.github.javaparser.ast.expr.Expression;
import com.github.javaparser.ast.expr.MethodCallExpr;
import com.github.javaparser.ast.expr.NameExpr;
import com.github.javaparser.ast.stmt.TryStmt;
import com.github.javaparser.ast.type.Type;
import org.springframework.stereotype.Component;
import ru.akvine.zond.enums.ErrorLevel;
import ru.akvine.zond.enums.ErrorType;
import ru.akvine.zond.models.SourceFile;
import ru.akvine.zond.models.Violation;
import ru.akvine.zond.rules.AbstractRule;
import ru.akvine.zond.rules.RuleCodes;
import ru.akvine.zond.rules.support.CodeContexts;
import ru.akvine.zond.rules.support.Guards;
import ru.akvine.zond.rules.support.LocalTypes;
import ru.akvine.zond.rules.support.MethodCalls;
import ru.akvine.zond.rules.support.Nodes;
import ru.akvine.zond.rules.support.TestClasses;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;

@Component
public class UnhandledNumberFormatRule extends AbstractRule {
    private static final Map<String, String> PARSE_METHODS = Map.of(
            "parseInt", "Integer",
            "parseLong", "Long",
            "parseDouble", "Double",
            "parseFloat", "Float",
            "parseShort", "Short",
            "parseByte", "Byte");

    // matcher.group(n) и literal.asIntegerLiteralExpr(): текст, формат которого уже проверен
    // Проверки, после которых в строке остаются только цифры
    private static final Set<String> VALIDATIONS = Set.of(
            "matches", "isNumeric", "isDigits", "isParsable", "isCreatable", "isNumber");
    private static final Set<String> MATCHED_TEXT = Set.of("group", "asIntegerLiteralExpr", "asLongLiteralExpr");
    private static final Set<String> HANDLING_EXCEPTIONS = Set.of(
            "NumberFormatException", "IllegalArgumentException", "RuntimeException", "Exception", "Throwable");

    @Override
    public String code() {
        return RuleCodes.UNHANDLED_NUMBER_FORMAT_RULE_CODE;
    }

    @Override
    public String description() {
        return "Сканирует код и ищет разбор чисел из строк без обработки NumberFormatException";
    }

    @Override
    public List<Violation> check(SourceFile sourceFile) {
        List<Violation> violations = new ArrayList<>();
        for (MethodCallExpr call : sourceFile.unit().findAll(MethodCallExpr.class)) {
            String type = PARSE_METHODS.get(call.getNameAsString());
            if (type == null || !MethodCalls.isCallOn(call, type, call.getNameAsString())) {
                continue;
            }

            // Число из настроек при запуске: неверное значение - ошибка конфигурации, и упасть сразу правильно.
            // В методе с throws Exception (декодер протокола) неверный ввод и так уходит вызывающему; тесты не трогаем
            if (!parsesCheckedText(call) && !isHandled(call) && !TestClasses.isInside(call)
                    && !CodeContexts.isStartup(call)
                    && !CodeContexts.isDeclaredToFail(call)) {
                violations.add(violation(sourceFile, call,
                        "'" + call + "' без обработки NumberFormatException: на нечисловой строке метод упадет"
                                + " с исключением, которое вызывающий код не ждет; оберните разбор в try / catch"
                                + " и верните понятную ошибку"));
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
        return ErrorType.EXCEPTION;
    }

    /**
     * Литерал ("42") разобрать нельзя неправильно. Integer.parseInt(matcher.group(1)): строку вырезал шаблон,
     * и цифры в ней он уже проверил. То же - значение целочисленного литерала из дерева разбора
     */
    private boolean parsesCheckedText(MethodCallExpr call) {
        if (call.getArguments().isEmpty()) {
            return false;
        }
        Expression argument = Nodes.unwrap(call.getArgument(0));
        if (argument.isStringLiteralExpr() || isValidated(call, argument)) {
            return true;
        }
        Expression source = argument.isNameExpr()
                ? LocalTypes.findInitializer(argument).map(Nodes::unwrap).orElse(argument)
                : argument;
        return source.findAll(MethodCallExpr.class).stream()
                .anyMatch(inner -> MATCHED_TEXT.contains(inner.getNameAsString()) && inner.getScope().isPresent());
    }

    // if (!NUMBER.matcher(value).matches()) { continue; } ... Integer.parseInt(value): цифры уже проверены.
    // То же - value.matches("\\d+") и StringUtils.isNumeric(value)
    private boolean isValidated(MethodCallExpr call, Expression argument) {
        if (!argument.isNameExpr()) {
            return false;
        }
        String text = argument.asNameExpr().getNameAsString();
        return Guards.isGuarded(call, check -> check.isMethodCallExpr()
                && VALIDATIONS.contains(check.asMethodCallExpr().getNameAsString())
                && check.findAll(NameExpr.class).stream().anyMatch(name -> name.getNameAsString().equals(text)));
    }

    // Вызов стоит в блоке try, у которого есть catch для NumberFormatException или его родителей
    private boolean isHandled(MethodCallExpr call) {
        Node child = call;
        Node current = call.getParentNode().orElse(null);
        while (current != null && !(current instanceof BodyDeclaration<?>)) {
            if (current instanceof TryStmt tryStmt && tryStmt.getTryBlock() == child && catchesNumberFormat(tryStmt)) {
                return true;
            }
            child = current;
            current = current.getParentNode().orElse(null);
        }
        return false;
    }

    private boolean catchesNumberFormat(TryStmt tryStmt) {
        return tryStmt.getCatchClauses().stream()
                .map(clause -> clause.getParameter().getType())
                .flatMap(type -> type.isUnionType()
                        ? type.asUnionType().getElements().stream().map(element -> (Type) element)
                        : Stream.of(type))
                .map(LocalTypes::typeName)
                .anyMatch(HANDLING_EXCEPTIONS::contains);
    }
}
