package ru.akvine.zond.rules.exceptions;

import com.github.javaparser.ast.Node;
import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration;
import com.github.javaparser.ast.expr.MethodCallExpr;
import com.github.javaparser.ast.expr.NameExpr;
import com.github.javaparser.ast.expr.ObjectCreationExpr;
import com.github.javaparser.ast.stmt.CatchClause;
import com.github.javaparser.ast.stmt.IfStmt;
import com.github.javaparser.ast.type.Type;
import org.springframework.stereotype.Component;
import ru.akvine.zond.enums.ErrorLevel;
import ru.akvine.zond.enums.ErrorType;
import ru.akvine.zond.models.SourceFile;
import ru.akvine.zond.models.Violation;
import ru.akvine.zond.rules.AbstractRule;
import ru.akvine.zond.rules.ProjectRule;
import ru.akvine.zond.rules.RuleCodes;
import ru.akvine.zond.rules.support.CodeContexts;
import ru.akvine.zond.rules.support.LocalTypes;
import ru.akvine.zond.rules.support.Loggers;
import ru.akvine.zond.rules.support.Nodes;
import ru.akvine.zond.rules.support.ProjectClasses;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Predicate;
import java.util.regex.Pattern;

@Component
public class LostStackTraceInLogRule extends AbstractRule implements ProjectRule {
    private static final String INTERRUPTED = "InterruptedException";
    private static final Pattern EXPECTED_FAILURE = Pattern.compile("(?i).*(BadCredentials|Disabled|Locked"
            + "|AccessDenied|Authentication|UsernameNotFound|Unauthorized|Forbidden|Validation|ConstraintViolation"
            + "|BindException|MethodArgumentNotValid|NumberFormat|DateTimeParse|Expired)Exception");
    // Вместо самого исключения в лог попадает только его текст
    private static final Set<String> TEXT_METHODS = Set.of("getMessage", "getLocalizedMessage", "toString");
    private static final String EXCEPTION_SUFFIX = "Exception";

    @Override
    public String code() {
        return RuleCodes.LOST_STACK_TRACE_IN_LOG_RULE_CODE;
    }

    @Override
    public String description() {
        return "Сканирует код и ищет в catch запись в лог текста исключения без самого исключения";
    }

    @Override
    public List<Violation> checkProject(List<SourceFile> sourceFiles) {
        ProjectClasses classes = ProjectClasses.of(sourceFiles);
        // Свои исключения проекта: какие из них создают и какие - внутри catch, то есть вокруг другой ошибки
        Set<String> created = new HashSet<>();
        Set<String> wrapping = new HashSet<>();
        for (SourceFile sourceFile : sourceFiles) {
            for (ObjectCreationExpr creation : sourceFile.unit().findAll(ObjectCreationExpr.class)) {
                String name = creation.getType().getNameAsString();
                if (!classes.isDeclared(name)) {
                    continue;
                }
                created.add(name);
                if (isInsideCatch(creation)) {
                    wrapping.add(name);
                }
            }
        }
        List<Violation> violations = new ArrayList<>();
        for (SourceFile sourceFile : sourceFiles) {
            check(sourceFile, clause -> isPlainBusinessFailure(clause, classes, created, wrapping), violations);
        }
        return violations;
    }

    private void check(SourceFile sourceFile, Predicate<CatchClause> isPlainFailure, List<Violation> violations) {
        for (CatchClause clause : sourceFile.unit().findAll(CatchClause.class)) {
            String caught = clause.getParameter().getNameAsString();
            // Исключение выбрасывается дальше - стек уходит с ним и будет записан там, где его обработают.
            // У InterruptedException стек ничего не говорит: это просьба остановиться, а не ошибка
            // Исключение целиком отдано дальше - logAndPersist("onStop", e), callback.onFailure(e): стек сохранит тот,
            // кто его получил
            if (CodeContexts.rethrowsWithCause(clause) || handsOver(clause, caught)
                    || INTERRUPTED.equals(clause.getParameter().getType().asString())
                    || isExpectedFailure(clause) || isPlainFailure.test(clause)) {
                continue;
            }
            for (MethodCallExpr call : clause.getBody().findAll(MethodCallExpr.class)) {
                if (Loggers.isLogCall(call)
                        && Loggers.isEnabledLevel(call)
                        && logsOnlyText(call, caught)
                        && !passesException(call, caught)
                        && !isClassified(call, clause, caught)) {
                    violations.add(violation(sourceFile, call,
                            "В лог пишется только текст исключения '" + caught + "', без стека: по записи не"
                                    + " понять, где возникла ошибка, а у NullPointerException текста может не быть"
                                    + " вовсе; передайте исключение последним аргументом: log.error(\"...\", "
                                    + caught + ")"));
                }
            }
        }
    }

    @Override
    public ErrorLevel errorLevel() {
        return ErrorLevel.MAJOR;
    }

    @Override
    public ErrorType errorType() {
        return ErrorType.EXCEPTION;
    }

    /**
     * Ловятся только ожидаемые исходы: неверный пароль, нет доступа, не прошла проверка, не разобрано число.
     * Текст такого исключения и есть вся причина, стек о ней ничего не добавит.
     */
    private boolean isExpectedFailure(CatchClause clause) {
        Type type = clause.getParameter().getType();
        List<Type> caught = type.isUnionType() ? List.copyOf(type.asUnionType().getElements()) : List.of(type);
        return caught.stream().allMatch(element -> EXPECTED_FAILURE.matcher(element.asString()).matches());
    }

    /**
     * Ловится свое бизнес-исключение, которое бросают само по себе: {@code throw new InsufficientFundsException(...)}
     * там, где сработало правило предметной области. Причины у него нет, а место известно по его типу и тексту -
     * стек о таком отказе ничего не добавит, записи в лог достаточно. Исключение, которое хоть где-то создают
     * в catch вокруг другой ошибки, несет причину: ее без стека не увидеть, и находка остается.
     */
    private boolean isPlainBusinessFailure(
            CatchClause clause, ProjectClasses classes, Set<String> created, Set<String> wrapping) {
        Type type = clause.getParameter().getType();
        List<Type> caught = type.isUnionType() ? List.copyOf(type.asUnionType().getElements()) : List.of(type);
        return caught.stream().allMatch(element -> classes.find(LocalTypes.typeName(element))
                .filter(declared -> declared.getNameAsString().endsWith(EXCEPTION_SUFFIX))
                .map(declared -> family(declared, classes))
                // catch (BusinessException e) ловит и наследников: смотрим на всю семью
                .filter(family -> family.stream().anyMatch(created::contains)
                        && family.stream().noneMatch(wrapping::contains))
                .isPresent());
    }

    private Set<String> family(ClassOrInterfaceDeclaration type, ProjectClasses classes) {
        Set<String> names = new HashSet<>();
        names.add(type.getNameAsString());
        classes.subclasses(type).forEach(child -> names.add(child.getNameAsString()));
        return names;
    }

    private boolean isInsideCatch(Node node) {
        return node.findAncestor(CatchClause.class).isPresent();
    }

    // e.getMessage() где-либо в аргументах
    private boolean logsOnlyText(MethodCallExpr logCall, String caught) {
        return logCall.getArguments().stream()
                .flatMap(argument -> argument.findAll(MethodCallExpr.class).stream())
                .anyMatch(call -> TEXT_METHODS.contains(call.getNameAsString())
                        && call.getScope().filter(scope -> scope.toString().equals(caught)).isPresent());
    }

    /**
     * Запись стоит в ветке, которая уже разобрала, что это за исключение:
     * {@code if (ex.getCause() instanceof PSQLException p && UNIQUE_VIOLATION.equals(p.getSQLState())) log.warn(...)}.
     * Это известный случай, а не неожиданная ошибка: стек о нем ничего не скажет.
     */
    private boolean isClassified(MethodCallExpr logCall, CatchClause clause, String caught) {
        Node child = logCall;
        Node current = logCall.getParentNode().orElse(null);
        while (current != null && current != clause) {
            // Только ветка "да": в else попадает все остальное, в том числе неожиданное
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

    // logAndPersist("onStop", e): исключение целиком ушло в свой метод, а не в логгер
    private boolean handsOver(CatchClause clause, String caught) {
        return clause.getBody().findAll(MethodCallExpr.class).stream()
                .filter(call -> !Loggers.isLogCall(call))
                .anyMatch(call -> passesException(call, caught));
    }

    // Само исключение передано отдельным аргументом
    private boolean passesException(MethodCallExpr logCall, String caught) {
        return logCall.getArguments().stream()
                .map(Nodes::unwrap)
                .anyMatch(argument -> argument.isNameExpr() && argument.asNameExpr().getNameAsString().equals(caught));
    }
}
