package ru.akvine.zond.rules.logical;

import com.github.javaparser.ast.Node;
import com.github.javaparser.ast.body.VariableDeclarator;
import com.github.javaparser.ast.expr.AssignExpr;
import com.github.javaparser.ast.expr.BinaryExpr;
import com.github.javaparser.ast.expr.Expression;
import com.github.javaparser.ast.expr.MethodCallExpr;
import com.github.javaparser.ast.expr.NameExpr;
import com.github.javaparser.ast.expr.UnaryExpr;
import com.github.javaparser.ast.stmt.DoStmt;
import com.github.javaparser.ast.stmt.ForStmt;
import com.github.javaparser.ast.stmt.Statement;
import com.github.javaparser.ast.stmt.WhileStmt;
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
import java.util.regex.Pattern;

/**
 * Цикл чтения, который не заканчивается на конце потока (CWE-835, бесконечный цикл): read() в конце возвращает -1,
 * а skip() - 0, и если этого не проверить, цикл крутится вечно. Так в PDFBox и Commons Compress присланный
 * обрезанный файл вешал разбор:
 * <ul>
 *     <li>while ((n = in.read(buffer)) != 0) - конец потока -1 не равен нулю;</li>
 *     <li>while (remaining > 0) { n = in.read(buffer, 0, size); remaining -= n; } - remaining растет на -1;</li>
 *     <li>while (toSkip > 0) { toSkip -= in.skip(toSkip); } - skip() на конце возвращает 0 бесконечно.</li>
 * </ul>
 */
@Component
public class ReadLoopWithoutEofRule extends AbstractRule {
    private static final String READ = "read";
    private static final String SKIP = "skip";
    // Своя константа конца потока: if (n == EOF)
    private static final String EOF = "EOF";
    // InputStream, Reader, FileChannel, RandomAccessFile и их наследники по имени типа
    private static final Pattern STREAM_TYPE = Pattern.compile(".*(InputStream|Reader|Channel|RandomAccessFile)$");
    private static final Set<String> STREAM_NAMES = Set.of("in", "is", "input", "inputStream", "stream", "reader", "channel");
    private static final Set<AssignExpr.Operator> COUNTING = Set.of(AssignExpr.Operator.MINUS, AssignExpr.Operator.PLUS);

    @Override
    public String code() {
        return RuleCodes.READ_LOOP_WITHOUT_EOF_RULE_CODE;
    }

    @Override
    public String description() {
        return "Сканирует код и ищет циклы чтения потока, которые не проверяют конец потока: read() == -1, skip() == 0";
    }

    @Override
    public List<Violation> check(SourceFile sourceFile) {
        List<Violation> violations = new ArrayList<>();
        List<Statement> loops = new ArrayList<>();
        loops.addAll(sourceFile.unit().findAll(WhileStmt.class));
        loops.addAll(sourceFile.unit().findAll(DoStmt.class));
        loops.addAll(sourceFile.unit().findAll(ForStmt.class));
        for (Statement loop : loops) {
            for (MethodCallExpr call : loop.findAll(MethodCallExpr.class)) {
                if (!isStreamCall(call) || Nodes.enclosingLoop(call).filter(found -> found == loop).isEmpty()) {
                    continue;
                }
                problem(call, loop).ifPresent(problem -> violations.add(violation(sourceFile, call,
                        "'" + call + "' в цикле: " + problem + "; на обрезанном или пустом потоке цикл не"
                                + " закончится и займет поток навсегда - проверяйте конец потока и выходите из цикла")));
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

    private boolean isStreamCall(MethodCallExpr call) {
        String method = call.getNameAsString();
        if (!READ.equals(method) && !SKIP.equals(method) || call.getScope().isEmpty()) {
            return false;
        }
        Expression scope = Nodes.unwrap(call.getScope().get());
        Optional<String> type = LocalTypes.typeOf(scope);
        if (type.isPresent()) {
            return STREAM_TYPE.matcher(type.get()).matches();
        }
        return scope.isNameExpr() && STREAM_NAMES.contains(scope.asNameExpr().getNameAsString());
    }

    private Optional<String> problem(MethodCallExpr call, Statement loop) {
        boolean skip = SKIP.equals(call.getNameAsString());
        String end = skip ? "skip() на конце потока возвращает 0" : "read() на конце потока возвращает -1";
        Node parent = parentSkippingParentheses(call);
        // while ((n = in.read(buffer)) != 0)
        Expression compared = parent instanceof AssignExpr assignment && assignment.getValue() == call
                ? assignment
                : call;
        Node comparisonParent = parentSkippingParentheses(compared);
        if (!skip && comparisonParent instanceof BinaryExpr comparison && comparesWithZeroOnly(comparison)) {
            return Optional.of(end + ", а условие сравнивает результат только с нулем");
        }
        // remaining -= in.read(buffer, 0, size), toSkip -= in.skip(toSkip)
        if (parent instanceof AssignExpr counting && counting.getValue() == call && COUNTING.contains(counting.getOperator())) {
            return Optional.of(end + ", и счетчик цикла перестает уменьшаться");
        }
        // long skipped = in.skip(toSkip); toSkip -= skipped; - без проверки skipped
        Optional<String> variable = resultVariable(call, parent);
        if (variable.isEmpty() || hasEndCheck(loop, variable.get(), skip)) {
            return Optional.empty();
        }
        boolean counts = loop.findAll(AssignExpr.class).stream()
                .anyMatch(assignment -> COUNTING.contains(assignment.getOperator())
                        && assignment.getValue().findAll(NameExpr.class).stream()
                        .anyMatch(name -> name.getNameAsString().equals(variable.get())));
        return counts ? Optional.of(end + ", а результат '" + variable.get() + "' на это не проверяется") : Optional.empty();
    }

    // != 0 и == 0: -1 под такое условие не попадает. > 0, != -1, < 0 конец потока замечают
    private boolean comparesWithZeroOnly(BinaryExpr comparison) {
        boolean byEquality = comparison.getOperator() == BinaryExpr.Operator.NOT_EQUALS
                || comparison.getOperator() == BinaryExpr.Operator.EQUALS;
        return byEquality && (isZero(comparison.getLeft()) || isZero(comparison.getRight()));
    }

    private Optional<String> resultVariable(MethodCallExpr call, Node parent) {
        if (parent instanceof VariableDeclarator variable && variable.getInitializer().filter(value -> value == call).isPresent()) {
            return Optional.of(variable.getNameAsString());
        }
        if (parent instanceof AssignExpr assignment && assignment.getOperator() == AssignExpr.Operator.ASSIGN
                && assignment.getValue() == call && assignment.getTarget().isNameExpr()) {
            return Optional.of(assignment.getTarget().asNameExpr().getNameAsString());
        }
        return Optional.empty();
    }

    // n < 0, n == -1, n <= 0, n != -1, n > 0 в цикле - конец потока проверен; для skip - сравнение с нулем
    private boolean hasEndCheck(Statement loop, String variable, boolean skip) {
        return loop.findAll(BinaryExpr.class).stream()
                .filter(comparison -> isComparison(comparison.getOperator()))
                .filter(comparison -> mentions(comparison.getLeft(), variable) || mentions(comparison.getRight(), variable))
                .anyMatch(comparison -> skip
                        ? isZero(comparison.getLeft()) || isZero(comparison.getRight())
                        || isOne(comparison.getLeft()) || isOne(comparison.getRight())
                        : isMinusOne(comparison.getLeft()) || isMinusOne(comparison.getRight())
                        || isZero(comparison.getLeft()) || isZero(comparison.getRight()));
    }

    private boolean isComparison(BinaryExpr.Operator operator) {
        return operator == BinaryExpr.Operator.LESS || operator == BinaryExpr.Operator.LESS_EQUALS
                || operator == BinaryExpr.Operator.GREATER || operator == BinaryExpr.Operator.GREATER_EQUALS
                || operator == BinaryExpr.Operator.EQUALS || operator == BinaryExpr.Operator.NOT_EQUALS;
    }

    private boolean mentions(Expression expression, String variable) {
        Expression value = Nodes.unwrap(expression);
        return value.isNameExpr() && value.asNameExpr().getNameAsString().equals(variable)
                || value.isAssignExpr() && value.asAssignExpr().getTarget().toString().equals(variable);
    }

    private boolean isZero(Expression expression) {
        return literal(expression, "0");
    }

    private boolean isOne(Expression expression) {
        return literal(expression, "1");
    }

    private boolean isMinusOne(Expression expression) {
        Expression value = Nodes.unwrap(expression);
        return value instanceof UnaryExpr unary && unary.getOperator() == UnaryExpr.Operator.MINUS
                && literal(unary.getExpression(), "1")
                || value.isNameExpr() && value.asNameExpr().getNameAsString().equals(EOF);
    }

    private boolean literal(Expression expression, String text) {
        Expression value = Nodes.unwrap(expression);
        return (value.isIntegerLiteralExpr() || value.isLongLiteralExpr())
                && value.toString().replaceAll("[lL]$", "").equals(text);
    }

    private Node parentSkippingParentheses(Node node) {
        Node parent = node.getParentNode().orElse(null);
        while (parent != null && parent instanceof Expression expression && expression.isEnclosedExpr()) {
            parent = parent.getParentNode().orElse(null);
        }
        return parent;
    }
}
