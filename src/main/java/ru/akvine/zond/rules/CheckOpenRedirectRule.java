package ru.akvine.zond.rules;

import com.github.javaparser.ast.expr.BinaryExpr;
import com.github.javaparser.ast.expr.Expression;
import com.github.javaparser.ast.expr.MethodCallExpr;
import com.github.javaparser.ast.expr.ObjectCreationExpr;
import org.springframework.stereotype.Component;
import ru.akvine.zond.enums.ErrorLevel;
import ru.akvine.zond.enums.ErrorType;
import ru.akvine.zond.models.SourceFile;
import ru.akvine.zond.models.Violation;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

@Component
public class CheckOpenRedirectRule extends AbstractRule {
    private static final String REDIRECT_PREFIX = "redirect:";
    private static final String SEND_REDIRECT = "sendRedirect";
    private static final String REDIRECT_VIEW = "RedirectView";

    @Override
    public String code() {
        return RuleCodes.CHECK_OPEN_REDIRECT_RULE_CODE;
    }

    @Override
    public String description() {
        return "Сканирует код и ищет перенаправление на адрес, взятый из запроса";
    }

    @Override
    public List<Violation> check(SourceFile sourceFile) {
        List<Violation> violations = new ArrayList<>();

        // "redirect:" + url. Вариант "redirect:/orders/" + id не трогаем: адрес остается внутри приложения
        for (BinaryExpr concatenation : sourceFile.unit().findAll(BinaryExpr.class)) {
            boolean redirectsToInput = concatenation.getOperator() == BinaryExpr.Operator.PLUS
                    && StringLiterals.textOf(concatenation.getLeft()).filter(REDIRECT_PREFIX::equals).isPresent();
            if (redirectsToInput) {
                report(sourceFile, concatenation, concatenation.getRight(), violations);
            }
        }

        // response.sendRedirect(url)
        for (MethodCallExpr call : sourceFile.unit().findAll(MethodCallExpr.class)) {
            if (SEND_REDIRECT.equals(call.getNameAsString()) && call.getArguments().size() == 1) {
                report(sourceFile, call, call.getArgument(0), violations);
            }
        }

        // new RedirectView(url)
        for (ObjectCreationExpr creation : sourceFile.unit().findAll(ObjectCreationExpr.class)) {
            if (REDIRECT_VIEW.equals(creation.getType().getNameAsString()) && !creation.getArguments().isEmpty()) {
                report(sourceFile, creation, creation.getArgument(0), violations);
            }
        }

        violations.sort(Comparator.comparingInt(Violation::line));
        return violations;
    }

    @Override
    public ErrorLevel errorLevel() {
        return ErrorLevel.MAJOR;
    }

    @Override
    public ErrorType errorType() {
        return ErrorType.SECURITY;
    }

    private void report(SourceFile sourceFile, Expression redirect, Expression target, List<Violation> violations) {
        WebInput.findUserInput(target).ifPresent(input -> violations.add(violation(sourceFile, redirect,
                "Перенаправление на адрес из запроса '" + input + "': ссылкой на ваш сайт пользователя можно"
                        + " увести на чужой (фишинг); перенаправляйте только на адреса из заранее заданного списка"
                        + " либо на относительные пути")));
    }
}
