package ru.akvine.zond.rules;

import com.github.javaparser.ast.expr.Expression;
import com.github.javaparser.ast.expr.MethodCallExpr;
import org.springframework.stereotype.Component;
import ru.akvine.zond.enums.ErrorLevel;
import ru.akvine.zond.enums.ErrorType;
import ru.akvine.zond.models.SourceFile;
import ru.akvine.zond.models.Violation;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;

@Component
public class CheckResponseWriteWithoutEscapingRule extends AbstractTaintRule {
    private static final Set<String> WRITE_METHODS = Set.of("write", "print", "println", "append", "printf");
    private static final String GET_WRITER = "getWriter()";
    private static final String GET_OUTPUT_STREAM = "getOutputStream()";

    @Override
    public String code() {
        return RuleCodes.CHECK_RESPONSE_WRITE_WITHOUT_ESCAPING_RULE_CODE;
    }

    @Override
    public String description() {
        return "Сканирует код и ищет вывод данных запроса в HTTP-ответ без экранирования (XSS)";
    }

    @Override
    protected List<Violation> check(SourceFile sourceFile, Taint taint) {
        List<Violation> violations = new ArrayList<>();
        for (MethodCallExpr call : sourceFile.unit().findAll(MethodCallExpr.class)) {
            if (!WRITE_METHODS.contains(call.getNameAsString())
                    || call.getScope().filter(this::isResponseOutput).isEmpty()) {
                continue;
            }
            // escapeHtml(value), encode(value): после них значение данными клиента уже не считается
            call.getArguments().stream()
                    .map(taint::findSource)
                    .flatMap(Optional::stream)
                    .findFirst()
                    .ifPresent(source -> violations.add(violation(sourceFile, call,
                            "В ответ выводятся данные клиента '" + source + "' без экранирования: вместе"
                                    + " с ними в страницу попадет чужой скрипт (XSS); экранируйте значение"
                                    + " (HtmlUtils.htmlEscape) или отдавайте данные как JSON")));
        }
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

    // response.getWriter().write(...) либо переменная, в которую этот writer сохранен
    private boolean isResponseOutput(Expression scope) {
        String direct = scope.toString();
        if (direct.contains(GET_WRITER) || direct.contains(GET_OUTPUT_STREAM)) {
            return true;
        }
        return LocalTypes.findInitializer(scope)
                .map(Expression::toString)
                .filter(text -> text.contains(GET_WRITER) || text.contains(GET_OUTPUT_STREAM))
                .isPresent();
    }
}
