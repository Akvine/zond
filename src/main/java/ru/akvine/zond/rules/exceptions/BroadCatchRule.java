package ru.akvine.zond.rules.exceptions;

import com.github.javaparser.ast.stmt.CatchClause;
import com.github.javaparser.ast.stmt.ThrowStmt;
import com.github.javaparser.ast.type.Type;
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

@Component
public class BroadCatchRule extends AbstractRule {
    private static final Set<String> BROAD_TYPES = Set.of("Throwable", "Exception");

    @Override
    public String code() {
        return RuleCodes.BROAD_CATCH_RULE_CODE;
    }

    @Override
    public String description() {
        return "Сканирует код и ищет catch (Throwable) и catch (Exception) без повторного выброса исключения";
    }

    @Override
    public List<Violation> check(SourceFile sourceFile) {
        List<Violation> violations = new ArrayList<>();
        for (CatchClause clause : sourceFile.unit().findAll(CatchClause.class)) {
            // Пустой catch ловит отдельное правило
            if (clause.getBody().getStatements().isEmpty()) {
                continue;
            }

            Optional<String> broadType = findBroadType(clause.getParameter().getType());
            if (broadType.isPresent() && !rethrows(clause)) {
                violations.add(violation(sourceFile, clause,
                        "catch (" + broadType.get() + ") без повторного выброса: вместе с ожидаемыми ошибками"
                                + " перехватываются и ошибки программирования (NullPointerException и т.п.);"
                                + " ловите конкретные исключения или пробрасывайте дальше"));
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
        return ErrorType.EXCEPTION;
    }

    // catch (Exception e) либо catch (IOException | Exception e)
    private Optional<String> findBroadType(Type type) {
        List<Type> types = type.isUnionType() ? new ArrayList<>(type.asUnionType().getElements()) : List.of(type);
        return types.stream()
                .map(LocalTypes::typeName)
                .filter(BROAD_TYPES::contains)
                .findFirst();
    }

    private boolean rethrows(CatchClause clause) {
        return clause.getBody().findAll(ThrowStmt.class).stream()
                .anyMatch(throwStmt -> !Nodes.isInNestedScope(throwStmt, clause.getBody()));
    }
}
