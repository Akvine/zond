package ru.akvine.zond.rules.logical;

import com.github.javaparser.ast.NodeList;
import com.github.javaparser.ast.stmt.IfStmt;
import com.github.javaparser.ast.stmt.Statement;
import com.github.javaparser.ast.stmt.SwitchEntry;
import com.github.javaparser.ast.stmt.SwitchStmt;
import com.github.javaparser.ast.stmt.TryStmt;
import org.springframework.stereotype.Component;
import ru.akvine.zond.enums.ErrorLevel;
import ru.akvine.zond.enums.ErrorType;
import ru.akvine.zond.models.SourceFile;
import ru.akvine.zond.models.Violation;
import ru.akvine.zond.rules.AbstractRule;
import ru.akvine.zond.rules.RuleCodes;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

@Component
public class SwitchFallThroughRule extends AbstractRule {
    // Комментарий, которым проваливание помечают как намеренное
    private static final Pattern INTENTIONAL = Pattern.compile(".*fall.?through.*", Pattern.CASE_INSENSITIVE | Pattern.DOTALL);

    @Override
    public String code() {
        return RuleCodes.SWITCH_FALL_THROUGH_RULE_CODE;
    }

    @Override
    public String description() {
        return "Сканирует код и ищет case без break, из которого выполнение проваливается в следующий";
    }

    @Override
    public List<Violation> check(SourceFile sourceFile) {
        List<Violation> violations = new ArrayList<>();
        for (SwitchStmt switchStmt : sourceFile.unit().findAll(SwitchStmt.class)) {
            List<SwitchEntry> entries = switchStmt.getEntries();

            // У последней ветки проваливаться некуда; у веток со стрелкой (case X ->) проваливания нет
            for (int index = 0; index < entries.size() - 1; index++) {
                SwitchEntry entry = entries.get(index);
                if (entry.getType() == SwitchEntry.Type.STATEMENT_GROUP
                        && !entry.getStatements().isEmpty()
                        && !endsWithJump(entry)
                        && !isMarkedIntentional(entry, entries.get(index + 1))) {
                    violations.add(violation(sourceFile, entry,
                            "case без break: после этой ветки выполнится и следующая; добавьте break либо пометьте"
                                    + " намеренное проваливание комментарием // fall through"));
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

    private boolean endsWithJump(SwitchEntry entry) {
        return isJump(entry.getStatements().get(entry.getStatements().size() - 1));
    }

    // Из команды нельзя выйти к следующей: break, return, throw, а также if / else и try, все ветки которых
    // так заканчиваются - if (x) { return a; } else { return b; }
    private boolean isJump(Statement statement) {
        if (statement.isBlockStmt()) {
            NodeList<Statement> statements = statement.asBlockStmt().getStatements();
            return !statements.isEmpty() && isJump(statements.get(statements.size() - 1));
        }
        if (statement.isIfStmt()) {
            IfStmt branch = statement.asIfStmt();
            return branch.getElseStmt().isPresent() && isJump(branch.getThenStmt()) && isJump(branch.getElseStmt().get());
        }
        if (statement.isTryStmt()) {
            TryStmt attempt = statement.asTryStmt();
            boolean allBranches = isJump(attempt.getTryBlock())
                    && attempt.getCatchClauses().stream().allMatch(clause -> isJump(clause.getBody()));
            return allBranches || attempt.getFinallyBlock().filter(this::isJump).isPresent();
        }
        if (statement.isSynchronizedStmt()) {
            return isJump(statement.asSynchronizedStmt().getBody());
        }
        return statement.isBreakStmt() || statement.isReturnStmt() || statement.isThrowStmt()
                || statement.isContinueStmt() || statement.isYieldStmt();
    }

    private boolean isMarkedIntentional(SwitchEntry entry, SwitchEntry next) {
        return entry.getAllContainedComments().stream().anyMatch(comment -> INTENTIONAL.matcher(comment.getContent()).matches())
                || next.getComment().filter(comment -> INTENTIONAL.matcher(comment.getContent()).matches()).isPresent();
    }
}
