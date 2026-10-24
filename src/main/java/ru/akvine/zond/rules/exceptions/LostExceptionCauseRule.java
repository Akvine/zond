package ru.akvine.zond.rules.exceptions;

import com.github.javaparser.ast.Node;
import com.github.javaparser.ast.expr.MethodCallExpr;
import com.github.javaparser.ast.expr.NameExpr;
import com.github.javaparser.ast.expr.ObjectCreationExpr;
import com.github.javaparser.ast.stmt.CatchClause;
import com.github.javaparser.ast.stmt.IfStmt;
import com.github.javaparser.ast.stmt.ThrowStmt;
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
import ru.akvine.zond.rules.support.Nodes;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

@Component
public class LostExceptionCauseRule extends AbstractRule {
    // Способы привязать исходное исключение помимо конструктора
    private static final Set<String> CAUSE_METHODS = Set.of("initCause", "addSuppressed");
    private static final Set<String> BAD_INPUT = Set.of(
            "NumberFormatException", "DateTimeParseException", "ParseException", "InputMismatchException",
            // Невозможная ситуация: класс клонируемый, кодировка UTF-8 есть всегда
            "CloneNotSupportedException", "UnsupportedEncodingException",
            // В стеке таймаута только место ожидания - его видно и по стеку нового исключения
            "TimeoutException");
    // У нового исключения нет конструктора с причиной: ParseException и NumberFormatException сообщают о неверном
    // вводе (throw new ParseException("A numeric value between 1 and 5 must follow the '#' option", i)),
    // InterruptedException передает прерывание дальше
    private static final Set<String> WITHOUT_CAUSE_CONSTRUCTOR = Set.of(
            "ParseException", "NumberFormatException", "InputMismatchException", "InterruptedException");
    // EntityType.valueOf(type), UUID.fromString(id), Integer.parseInt(page): в try только разбор строки
    private static final String ILLEGAL_ARGUMENT = "IllegalArgumentException";
    private static final Set<String> PARSE_METHODS = Set.of("valueOf", "fromString");
    private static final String PARSE_PREFIX = "parse";

    @Override
    public String code() {
        return RuleCodes.LOST_EXCEPTION_CAUSE_RULE_CODE;
    }

    @Override
    public String description() {
        return "Сканирует код и ищет в catch создание нового исключения без передачи исходного как причины";
    }

    @Override
    public List<Violation> check(SourceFile sourceFile) {
        List<Violation> violations = new ArrayList<>();
        for (CatchClause clause : sourceFile.unit().findAll(CatchClause.class)) {
            String caught = clause.getParameter().getNameAsString();
            // Исключение записано в лог целиком: стек и причина сохранены, хоть и не едут дальше
            // Неверный ввод (не число, не дата): причина - сама строка, а место видно по стеку нового исключения
            if (attachesCauseManually(clause, caught) || CodeContexts.logsException(clause) || isBadInput(clause)) {
                continue;
            }

            for (ThrowStmt throwStmt : clause.getBody().findAll(ThrowStmt.class)) {
                // throw во вложенном catch относится к своему исключению, а в лямбде - к другой функции
                if (!throwStmt.getExpression().isObjectCreationExpr()
                        || nearestCatch(throwStmt) != clause
                        || Nodes.isInNestedScope(throwStmt, clause)) {
                    continue;
                }

                ObjectCreationExpr created = throwStmt.getExpression().asObjectCreationExpr();
                if (!passesCause(created, caught) && !isClassified(throwStmt, clause, caught)
                        && !WITHOUT_CAUSE_CONSTRUCTOR.contains(created.getType().getNameAsString())) {
                    violations.add(violation(sourceFile, throwStmt,
                            "Новое исключение " + created.getType().getNameAsString() + " создается без исходного '"
                                    + caught + "': стек и причина первоначальной ошибки теряются, найти ее по логу"
                                    + " будет нельзя; передайте '" + caught + "' в конструктор как cause"));
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
        return ErrorType.EXCEPTION;
    }

    // new X("text", e): само исключение должно быть аргументом. e.getMessage() причину не сохраняет
    private boolean passesCause(ObjectCreationExpr created, String caught) {
        return created.getArguments().stream()
                .map(Nodes::unwrap)
                .anyMatch(argument -> argument.isNameExpr() && argument.asNameExpr().getNameAsString().equals(caught));
    }

    private boolean attachesCauseManually(CatchClause clause, String caught) {
        return clause.getBody().findAll(MethodCallExpr.class).stream()
                .filter(call -> CAUSE_METHODS.contains(call.getNameAsString()))
                .anyMatch(call -> call.getArguments().stream().anyMatch(argument -> argument.toString().equals(caught)));
    }

    private Node nearestCatch(Node node) {
        Node current = node.getParentNode().orElse(null);
        while (current != null && !(current instanceof CatchClause)) {
            current = current.getParentNode().orElse(null);
        }
        return current;
    }

    // if (e.getStatus() == 400) throw new InvalidCredentialsException(...): исключение уже разобрано, это известный исход
    private boolean isClassified(ThrowStmt throwStmt, CatchClause clause, String caught) {
        Node child = throwStmt;
        Node current = throwStmt.getParentNode().orElse(null);
        while (current != null && current != clause) {
            if (current instanceof IfStmt branch && branch.getThenStmt() == child
                    && branch.getCondition().findAll(NameExpr.class).stream()
                    .anyMatch(name -> name.getNameAsString().equals(caught))) {
                return true;
            }
            child = current;
            current = current.getParentNode().orElse(null);
        }
        return false;
    }

    private boolean isBadInput(CatchClause clause) {
        Type type = clause.getParameter().getType();
        List<Type> caught = type.isUnionType() ? List.copyOf(type.asUnionType().getElements()) : List.of(type);
        return caught.stream().allMatch(element -> BAD_INPUT.contains(element.asString()))
                || ILLEGAL_ARGUMENT.equals(type.asString()) && isParsingTry(clause);
    }

    // try { groupType = RoleType.valueOf(strGroupType); } catch (IllegalArgumentException e): неверное значение
    // параметра, и новое исключение его называет
    private boolean isParsingTry(CatchClause clause) {
        return clause.getParentNode()
                .filter(parent -> parent instanceof TryStmt)
                .map(parent -> ((TryStmt) parent).getTryBlock().getStatements())
                .filter(statements -> statements.size() == 1)
                .filter(statements -> statements.get(0).findAll(MethodCallExpr.class).stream()
                        .anyMatch(call -> isParse(call.getNameAsString())
                                || call.getScope().filter(scope -> isParse(scope.toString())).isPresent()))
                .isPresent();
    }

    // valueOf.apply(param): функция разбора передана параметром
    private boolean isParse(String name) {
        return PARSE_METHODS.contains(name) || name.startsWith(PARSE_PREFIX);
    }
}
