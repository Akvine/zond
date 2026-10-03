package ru.akvine.zond.rules;

import com.github.javaparser.ast.stmt.Statement;
import com.github.javaparser.ast.stmt.SwitchEntry;
import com.github.javaparser.ast.stmt.SwitchStmt;
import org.springframework.stereotype.Component;
import ru.akvine.zond.enums.ErrorLevel;
import ru.akvine.zond.enums.ErrorType;
import ru.akvine.zond.models.SourceFile;
import ru.akvine.zond.models.Violation;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

@Component
public class CheckSwitchFallThroughRule extends AbstractRule {
    // Комментарий, которым проваливание помечают как намеренное
    private static final Pattern INTENTIONAL = Pattern.compile(".*fall.?through.*", Pattern.CASE_INSENSITIVE | Pattern.DOTALL);

    @Override
    public String code() {
        return RuleCodes.CHECK_SWITCH_FALL_THROUGH_RULE_CODE;
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
        Statement last = entry.getStatements().get(entry.getStatements().size() - 1);
        while (last.isBlockStmt() && !last.asBlockStmt().getStatements().isEmpty()) {
            last = last.asBlockStmt().getStatements().get(last.asBlockStmt().getStatements().size() - 1);
        }
        return last.isBreakStmt() || last.isReturnStmt() || last.isThrowStmt() || last.isContinueStmt()
                || last.isYieldStmt();
    }

    private boolean isMarkedIntentional(SwitchEntry entry, SwitchEntry next) {
        return entry.getAllContainedComments().stream().anyMatch(comment -> INTENTIONAL.matcher(comment.getContent()).matches())
                || next.getComment().filter(comment -> INTENTIONAL.matcher(comment.getContent()).matches()).isPresent();
    }
}
