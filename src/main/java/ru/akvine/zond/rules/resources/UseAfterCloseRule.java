package ru.akvine.zond.rules.resources;

import com.github.javaparser.ast.expr.AssignExpr;
import com.github.javaparser.ast.expr.Expression;
import com.github.javaparser.ast.expr.MethodCallExpr;
import com.github.javaparser.ast.expr.NameExpr;
import com.github.javaparser.ast.stmt.BlockStmt;
import com.github.javaparser.ast.stmt.ExpressionStmt;
import com.github.javaparser.ast.stmt.Statement;
import org.springframework.stereotype.Component;
import ru.akvine.zond.enums.ErrorLevel;
import ru.akvine.zond.enums.ErrorType;
import ru.akvine.zond.models.SourceFile;
import ru.akvine.zond.models.Violation;
import ru.akvine.zond.rules.AbstractRule;
import ru.akvine.zond.rules.RuleCodes;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * reader.close(); ... reader.readLine(): обращение к уже закрытому потоку, соединению или сессии. Поток бросит
 * IOException ("Stream closed"), соединение - SQLException, а буферизованная запись молча потеряется
 */
@Component
public class UseAfterCloseRule extends AbstractRule {
    private static final String CLOSE = "close";
    // Ввод-вывод, запросы и транзакции: их закрытый объект выполнить не может. Геттеры (getPath(), getResult(),
    // endpoint()) после close() работают - часто объект и закрывают, чтобы потом забрать итог
    private static final Pattern IO_OPERATION = Pattern.compile(
            "(read|write|print|send|execute|prepare|query|fetch|load|save|persist|insert|delete|commit|rollback"
                    + "|flush|transfer|skip|seek|receive|accept|poll|submit|publish|create|next|append|put|mark"
                    + "|available|lock|begin|merge|update|find|scan|upload|download|getInputStream|getOutputStream"
                    + "|getResultSet|getConnection|getMetaData|getReader|getWriter|getChannel).*");
    // rs.getString("name") у закрытого ResultSet
    private static final Pattern ROW_GETTER = Pattern.compile(
            "get(Int|Long|Short|Byte|String|Bytes|Object|Double|Float|Boolean|Date|Time|Timestamp|BigDecimal)");

    @Override
    public String code() {
        return RuleCodes.USE_AFTER_CLOSE_RULE_CODE;
    }

    @Override
    public String description() {
        return "Сканирует код и ищет обращение к потоку, соединению или сессии после того, как их закрыли";
    }

    @Override
    public List<Violation> check(SourceFile sourceFile) {
        List<Violation> violations = new ArrayList<>();
        for (BlockStmt block : sourceFile.unit().findAll(BlockStmt.class)) {
            List<Statement> statements = block.getStatements();
            for (int index = 0; index < statements.size(); index++) {
                Optional<String> closed = closedVariable(statements.get(index));
                if (closed.isEmpty()) {
                    continue;
                }
                findUseAfter(statements, index + 1, closed.get()).ifPresent(use -> violations.add(violation(sourceFile,
                        use, "'" + use + "' обращается к '" + closed.get() + "' после " + closed.get() + ".close():"
                                + " закрытый поток или соединение бросит исключение, а запись в него потеряется;"
                                + " перенесите close() после последнего обращения либо используйте"
                                + " try-with-resources")));
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
        return ErrorType.RESOURCE;
    }

    // reader.close(); как отдельный оператор: в условии или в цепочке закрытие может и не случиться
    private Optional<String> closedVariable(Statement statement) {
        if (!(statement instanceof ExpressionStmt expression) || !expression.getExpression().isMethodCallExpr()) {
            return Optional.empty();
        }
        MethodCallExpr call = expression.getExpression().asMethodCallExpr();
        return call.getArguments().isEmpty() && CLOSE.equals(call.getNameAsString())
                ? call.getScope().filter(Expression::isNameExpr).map(scope -> scope.asNameExpr().getNameAsString())
                : Optional.empty();
    }

    // Первое обращение к переменной в следующих операторах того же блока, пока ей не присвоили новый объект
    private Optional<MethodCallExpr> findUseAfter(List<Statement> statements, int from, String variable) {
        for (int index = from; index < statements.size(); index++) {
            Statement statement = statements.get(index);
            boolean reassigned = statement.findAll(AssignExpr.class).stream()
                    .anyMatch(assignment -> assignment.getTarget().isNameExpr()
                            && assignment.getTarget().asNameExpr().getNameAsString().equals(variable));
            Optional<MethodCallExpr> use = statement.findAll(MethodCallExpr.class).stream()
                    .filter(call -> call.getScope().filter(scope -> scope instanceof NameExpr name
                            && name.getNameAsString().equals(variable)).isPresent())
                    .filter(this::needsOpenResource)
                    .findFirst();
            if (use.isPresent() && !reassigned) {
                return use;
            }
            if (reassigned) {
                return Optional.empty();
            }
        }
        return Optional.empty();
    }

    private boolean needsOpenResource(MethodCallExpr call) {
        String method = call.getNameAsString();
        return IO_OPERATION.matcher(method).matches()
                || !call.getArguments().isEmpty() && ROW_GETTER.matcher(method).matches();
    }
}
