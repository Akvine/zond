package ru.akvine.zond.rules.exceptions;

import com.github.javaparser.ast.Node;
import com.github.javaparser.ast.body.FieldDeclaration;
import com.github.javaparser.ast.body.InitializerDeclaration;
import com.github.javaparser.ast.expr.LambdaExpr;
import com.github.javaparser.ast.expr.MethodCallExpr;
import com.github.javaparser.ast.stmt.ThrowStmt;
import com.github.javaparser.ast.stmt.TryStmt;
import org.springframework.stereotype.Component;
import ru.akvine.zond.enums.ErrorLevel;
import ru.akvine.zond.enums.ErrorType;
import ru.akvine.zond.models.SourceFile;
import ru.akvine.zond.models.Violation;
import ru.akvine.zond.rules.AbstractRule;
import ru.akvine.zond.rules.RuleCodes;
import ru.akvine.zond.rules.support.Nodes;
import ru.akvine.zond.rules.support.TestClasses;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * Исключение при загрузке класса. Если статический инициализатор бросает исключение, первый вызов класса
 * заканчивается ExceptionInInitializerError, а каждый следующий - NoClassDefFoundError, в котором причины уже нет.
 * Класс остается сломанным до перезапуска приложения.
 */
@Component
public class StaticInitializerFailureRule extends AbstractRule {
    // Разбор значения: на пустом или неверном входе бросает исключение
    private static final Set<String> PARSERS = Set.of(
            "parseInt", "parseLong", "parseDouble", "parseFloat", "parseShort", "valueOf", "fromString", "parse",
            "requireNonNull", "decode", "of");
    private static final Set<String> EXTERNAL_VALUES = Set.of("getenv", "getProperty");
    private static final String SYSTEM = "System";
    private static final String CONSEQUENCE = ": класс не загрузится - первый вызов закончится"
            + " ExceptionInInitializerError, а каждый следующий - NoClassDefFoundError, в котором причины уже нет,"
            + " и до перезапуска класс останется сломанным; ";

    @Override
    public String code() {
        return RuleCodes.STATIC_INITIALIZER_FAILURE_RULE_CODE;
    }

    @Override
    public String description() {
        return "Сканирует код и ищет исключения при загрузке класса: throw в статическом блоке и разбор"
                + " переменных окружения в статических полях";
    }

    @Override
    public List<Violation> check(SourceFile sourceFile) {
        List<Violation> violations = new ArrayList<>();
        for (InitializerDeclaration block : sourceFile.unit().findAll(InitializerDeclaration.class)) {
            if (!block.isStatic() || TestClasses.isInside(block)) {
                continue;
            }
            // Об одном блоке сообщаем один раз - на первом исключении
            block.findAll(ThrowStmt.class).stream()
                    .filter(thrown -> !Nodes.isInNestedScope(thrown, block))
                    .findFirst()
                    .ifPresent(thrown -> violations.add(violation(sourceFile, thrown,
                            "throw в статическом блоке инициализации" + CONSEQUENCE + "загружайте данные при первом"
                                    + " обращении либо в бине (@PostConstruct), а здесь подставляйте значение по"
                                    + " умолчанию и пишите ошибку в лог")));
        }

        for (MethodCallExpr call : sourceFile.unit().findAll(MethodCallExpr.class)) {
            if (isUnsafeParse(call) && isClassInitialization(call) && !TestClasses.isInside(call)) {
                violations.add(violation(sourceFile, call,
                        "'" + Nodes.text(call) + "' при загрузке класса: если переменная не задана или записана"
                                + " с ошибкой, разбор бросит исключение" + CONSEQUENCE + "задайте значение по"
                                + " умолчанию (System.getProperty(name, default), Integer.getInteger(name, default))"
                                + " либо читайте настройку через @Value / @ConfigurationProperties"));
            }
        }
        violations.sort(Comparator.comparingInt(Violation::line));
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

    // Integer.parseInt(System.getenv("LIMIT")): значение приходит снаружи и может быть пустым
    private boolean isUnsafeParse(MethodCallExpr call) {
        if (!PARSERS.contains(call.getNameAsString()) || call.getArguments().isEmpty()) {
            return false;
        }
        return call.getArguments().stream()
                .map(Nodes::unwrap)
                .filter(argument -> argument.isMethodCallExpr())
                .map(argument -> argument.asMethodCallExpr())
                // System.getProperty(name, default) пустым не бывает
                .anyMatch(source -> EXTERNAL_VALUES.contains(source.getNameAsString()) && source.getArguments().size() == 1
                        && source.getScope().filter(scope -> SYSTEM.equals(scope.toString())).isPresent());
    }

    // Значение статического поля либо статический блок, и вокруг нет try с catch
    private boolean isClassInitialization(MethodCallExpr call) {
        Optional<Node> callable = Nodes.enclosingCallable(call);
        boolean staticBlock = callable.filter(found -> found instanceof InitializerDeclaration block && block.isStatic())
                .isPresent();
        boolean staticField = callable.isEmpty()
                && call.findAncestor(FieldDeclaration.class).filter(FieldDeclaration::isStatic).isPresent();
        boolean caught = call.findAncestor(TryStmt.class).filter(guard -> !guard.getCatchClauses().isEmpty()).isPresent();
        // Лямбда в статическом поле выполнится позже, при вызове, а не при загрузке класса
        boolean deferred = call.findAncestor(LambdaExpr.class).isPresent();
        return (staticBlock || staticField) && !caught && !deferred;
    }
}
