package ru.akvine.zond.rules.performance;

import com.github.javaparser.ast.Node;
import com.github.javaparser.ast.expr.MethodCallExpr;
import com.github.javaparser.ast.expr.ObjectCreationExpr;
import com.github.javaparser.ast.stmt.BlockStmt;
import com.github.javaparser.ast.stmt.CatchClause;
import com.github.javaparser.ast.stmt.DoStmt;
import com.github.javaparser.ast.stmt.ThrowStmt;
import com.github.javaparser.ast.stmt.TryStmt;
import com.github.javaparser.ast.stmt.WhileStmt;
import com.github.javaparser.ast.type.Type;
import org.springframework.stereotype.Component;
import ru.akvine.zond.enums.Confidence;
import ru.akvine.zond.enums.ErrorLevel;
import ru.akvine.zond.enums.ErrorType;
import ru.akvine.zond.models.SourceFile;
import ru.akvine.zond.models.Violation;
import ru.akvine.zond.rules.AbstractRule;
import ru.akvine.zond.rules.RuleCodes;
import ru.akvine.zond.rules.support.CodeContexts;
import ru.akvine.zond.rules.support.LocalTypes;
import ru.akvine.zond.rules.support.Loops;
import ru.akvine.zond.rules.support.MethodCalls;
import ru.akvine.zond.rules.support.TestClasses;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * В цикле по данным значение разбирают через try / catch, и catch молча идет дальше: неподходящее значение -
 * обычный случай, а обрабатывается он исключением. Создание исключения со стеком вызовов в сотни раз дороже
 * проверки формата, и на плохих данных цикл тратит время в основном на них.
 */
@Component
public class ExceptionAsBranchInLoopRule extends AbstractRule {
    // Исключение -> методы разбора, которые его бросают на неподходящем значении
    private static final Map<String, Pattern> PARSERS = Map.of(
            "NumberFormatException", Pattern.compile("parse(Int|Long|Double|Float|Short|Byte)|valueOf|decode"),
            "DateTimeParseException", Pattern.compile("parse"),
            "ParseException", Pattern.compile("parse\\w*"),
            "IllegalArgumentException", Pattern.compile("valueOf|fromString"));
    private static final Set<String> NUMBER_TYPES = Set.of("BigDecimal", "BigInteger");
    private static final String NUMBER_FORMAT = "NumberFormatException";
    private static final Pattern LOGGER = Pattern.compile("(?i)log(ger)?|printStackTrace");

    @Override
    public String code() {
        return RuleCodes.EXCEPTION_AS_BRANCH_IN_LOOP_RULE_CODE;
    }

    @Override
    public String description() {
        return "Сканирует код и ищет циклы, в которых неподходящее значение отсеивают исключением: разбор числа"
                + " или даты в try, а catch молча идет дальше";
    }

    @Override
    public List<Violation> check(SourceFile sourceFile) {
        List<Violation> violations = new ArrayList<>();
        for (TryStmt attempt : sourceFile.unit().findAll(TryStmt.class)) {
            if (!isTriedForEveryElement(attempt) || TestClasses.isInside(attempt)) {
                continue;
            }
            for (CatchClause clause : attempt.getCatchClauses()) {
                findExpected(attempt, clause).ifPresent(exception -> violations.add(violation(sourceFile, clause,
                        "'" + exception + "' в цикле служит обычной веткой: на каждую неудачную попытку разбора"
                                + " создается исключение со стеком вызовов, это в сотни раз дороже проверки;"
                                + " проверьте формат значения до разбора (для дат -"
                                + " DateTimeFormatter.parseUnresolved)")));
            }
        }
        return violations;
    }

    @Override
    public ErrorLevel errorLevel() {
        return ErrorLevel.INFO;
    }

    @Override
    public ErrorType errorType() {
        return ErrorType.PERFORMANCE;
    }

    // Как часто попадаются плохие значения, по коду не видно
    @Override
    public Confidence confidence() {
        return Confidence.PROBABLE;
    }

    /**
     * try стоит прямо в теле цикла или поэлементной лямбды. if (name.equals("timeout")) { try { parse } } разбирает
     * одно значение из многих - там исключение остается редкостью
     */
    private boolean isTriedForEveryElement(TryStmt attempt) {
        if (!CodeContexts.isRepeatedOverData(attempt)) {
            return false;
        }
        Node body = attempt.getParentNode().filter(parent -> parent instanceof BlockStmt).orElse(attempt);
        Node owner = body.getParentNode().orElse(null);
        // while (true) { спросить, разобрать, при ошибке спросить снова } - повтор попытки, а не обход данных
        boolean endless = owner instanceof DoStmt
                || owner instanceof WhileStmt loop && loop.getCondition().isBooleanLiteralExpr();
        return !endless && Loops.enclosingIteration(attempt).filter(loop -> loop == owner).isPresent();
    }

    // Исключение, которое здесь ждут: в try его бросает разбор, а catch не сообщает о нем и не пробрасывает
    private Optional<String> findExpected(TryStmt attempt, CatchClause clause) {
        if (!isSilent(clause)) {
            return Optional.empty();
        }
        Type type = clause.getParameter().getType();
        List<Type> caught = type.isUnionType() ? new ArrayList<>(type.asUnionType().getElements()) : List.of(type);
        return caught.stream()
                .map(LocalTypes::typeName)
                .filter(name -> Optional.ofNullable(PARSERS.get(name))
                        .filter(parser -> parses(attempt, name, parser))
                        .isPresent())
                .findFirst();
    }

    private boolean isSilent(CatchClause clause) {
        return clause.findAll(ThrowStmt.class).isEmpty()
                && clause.findAll(MethodCallExpr.class).stream().noneMatch(call ->
                LOGGER.matcher(call.getNameAsString()).matches() || call.getScope()
                        .filter(scope -> LOGGER.matcher(MethodCalls.receiverName(scope)).matches())
                        .isPresent());
    }

    private boolean parses(TryStmt attempt, String exception, Pattern parser) {
        boolean byCall = attempt.getTryBlock().findAll(MethodCallExpr.class).stream()
                .anyMatch(call -> parser.matcher(call.getNameAsString()).matches());
        // new BigDecimal(text)
        return byCall || NUMBER_FORMAT.equals(exception) && attempt.getTryBlock().findAll(ObjectCreationExpr.class)
                .stream()
                .anyMatch(creation -> NUMBER_TYPES.contains(creation.getType().getNameAsString()));
    }
}
