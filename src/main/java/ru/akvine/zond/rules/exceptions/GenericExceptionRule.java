package ru.akvine.zond.rules.exceptions;

import com.github.javaparser.ast.Node;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.body.TypeDeclaration;
import com.github.javaparser.ast.expr.MethodCallExpr;
import com.github.javaparser.ast.stmt.CatchClause;
import com.github.javaparser.ast.stmt.ThrowStmt;
import org.springframework.stereotype.Component;
import ru.akvine.zond.enums.ErrorLevel;
import ru.akvine.zond.enums.ErrorType;
import ru.akvine.zond.models.SourceFile;
import ru.akvine.zond.models.Violation;
import ru.akvine.zond.rules.AbstractRule;
import ru.akvine.zond.rules.RuleCodes;
import ru.akvine.zond.rules.support.Annotations;
import ru.akvine.zond.rules.support.CodeContexts;
import ru.akvine.zond.rules.support.LocalTypes;
import ru.akvine.zond.rules.support.Nodes;
import ru.akvine.zond.rules.support.TestClasses;
import ru.akvine.zond.rules.support.Types;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

@Component
public class GenericExceptionRule extends AbstractRule {
    private static final String OVERRIDE = "Override";
    private static final String MAIN = "main";
    private static final String PRE_DESTROY = "PreDestroy";

    private static final Set<String> GENERIC_THROWN = Set.of("Exception", "RuntimeException", "Throwable", "Error");
    private static final Set<String> GENERIC_DECLARED = Set.of("Exception", "Throwable");

    @Override
    public String code() {
        return RuleCodes.GENERIC_EXCEPTION_RULE_CODE;
    }

    @Override
    public String description() {
        return "Сканирует код и ищет слишком общие исключения: throw new RuntimeException и throws Exception";
    }

    @Override
    public List<Violation> check(SourceFile sourceFile) {
        List<Violation> violations = new ArrayList<>();

        for (ThrowStmt throwStmt : sourceFile.findAll(ThrowStmt.class)) {
            if (throwStmt.getExpression().isObjectCreationExpr() && !TestClasses.isInside(throwStmt)) {
                String type = throwStmt.getExpression().asObjectCreationExpr().getType().getNameAsString();
                if (GENERIC_THROWN.contains(type)) {
                    violations.add(violation(sourceFile, throwStmt,
                            "throw new " + type + "(...): вызывающий код не сможет отличить эту ошибку от любой"
                                    + " другой и обработать ее отдельно; заведите исключение с говорящим именем"));
                }
            }
        }

        // У переопределенного метода и у main сигнатуру диктует не автор; в тестах throws Exception - норма
        for (MethodDeclaration method : sourceFile.findAll(MethodDeclaration.class)) {
            if (Annotations.has(method, OVERRIDE) || MAIN.equals(method.getNameAsString()) || TestClasses.isInside(method)) {
                continue;
            }
            // Метод, который вызывает контейнер (@Bean, @PostConstruct, @PreDestroy): ловить его исключения
            // некому, кроме самого контейнера. Метод функционального интерфейса обязан принять любую лямбду
            if (CodeContexts.isStartupMethod(method) || Annotations.has(method, PRE_DESTROY)
                    || CodeContexts.isFunctionalInterfaceMethod(method)) {
                continue;
            }
            // Обработчик запроса, слушатель, задача по расписанию, совет аспекта: вызывающего кода в проекте
            // у них нет, исключение получит библиотека
            if (CodeContexts.isCalledByFramework(method)) {
                continue;
            }
            method.getThrownExceptions().stream()
                    .map(LocalTypes::typeName)
                    .filter(GENERIC_DECLARED::contains)
                    .findFirst()
                    .filter(type -> !isForced(method))
                    .ifPresent(type -> violations.add(violation(sourceFile, method,
                            "Метод '" + method.getNameAsString() + "' объявляет throws " + type + ": вызывающий код"
                                    + " вынужден ловить все подряд, включая ошибки программирования; перечислите"
                                    + " конкретные исключения")));
        }

        violations.sort(Comparator.comparingInt(Violation::line));
        return violations;
    }

    /**
     * Общее исключение в throws навязано тем, что метод вызывает или бросает: {@code http.build()},
     * {@code callable.call()}, {@code joinPoint.proceed()} сами объявлены с throws Exception, а
     * {@code throw failure} пробрасывает переданное исключение как есть. Перечислить конкретные исключения
     * здесь нечем. Свой метод с throws Exception дает свою находку - на нем самом
     */
    private boolean isForced(MethodDeclaration method) {
        if (method.getBody().isEmpty()) {
            return false;
        }
        for (MethodCallExpr call : method.getBody().get().findAll(MethodCallExpr.class)) {
            if (isOutside(call, method) || CodeContexts.isCaught(call, GENERIC_DECLARED)) {
                continue;
            }
            Set<String> thrown = Types.thrownBy(call).orElseGet(() -> thrownByOwnMethod(call, method));
            if (thrown.stream().anyMatch(GENERIC_DECLARED::contains)) {
                return true;
            }
        }
        for (ThrowStmt thrown : method.getBody().get().findAll(ThrowStmt.class)) {
            if (!isOutside(thrown, method) && thrown.getExpression().isNameExpr() && isGenericVariable(thrown)) {
                return true;
            }
        }
        return false;
    }

    // Лямбда и анонимный класс внутри метода - отдельный код со своими правилами об исключениях
    private boolean isOutside(Node node, MethodDeclaration method) {
        return Nodes.enclosingCallable(node).orElse(null) != method;
    }

    // Без решателя типов виден только метод своего класса, вызванный без объекта
    private Set<String> thrownByOwnMethod(MethodCallExpr call, MethodDeclaration method) {
        if (call.getScope().filter(scope -> !scope.isThisExpr()).isPresent()) {
            return Set.of();
        }
        return method.findAncestor(TypeDeclaration.class).stream()
                .flatMap(type -> ((TypeDeclaration<?>) type).getMethodsByName(call.getNameAsString()).stream())
                .filter(own -> own != method && own.getParameters().size() == call.getArguments().size())
                .flatMap(own -> own.getThrownExceptions().stream())
                .map(LocalTypes::typeName)
                .collect(Collectors.toSet());
    }

    // throw failure, где failure - параметр, поле или переменная типа Exception. Исключение из своего же catch
    // компилятор пробрасывает с точным типом, и общего throws оно не требует
    private boolean isGenericVariable(ThrowStmt thrown) {
        return LocalTypes.findDeclaration(thrown.getExpression())
                .filter(declaration -> declaration.findAncestor(CatchClause.class)
                        .filter(clause -> clause.getParameter() == declaration)
                        .isEmpty())
                .flatMap(LocalTypes::declaredType)
                .filter(GENERIC_DECLARED::contains)
                .isPresent();
    }

    @Override
    public ErrorLevel errorLevel() {
        return ErrorLevel.MINOR;
    }

    @Override
    public ErrorType errorType() {
        return ErrorType.EXCEPTION;
    }
}
