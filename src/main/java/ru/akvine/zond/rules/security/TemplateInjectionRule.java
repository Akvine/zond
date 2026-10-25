package ru.akvine.zond.rules.security;

import com.github.javaparser.ast.Node;
import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.expr.Expression;
import com.github.javaparser.ast.expr.MethodCallExpr;
import com.github.javaparser.ast.expr.ObjectCreationExpr;
import com.github.javaparser.ast.stmt.ReturnStmt;
import org.springframework.stereotype.Component;
import ru.akvine.zond.enums.ErrorLevel;
import ru.akvine.zond.enums.ErrorType;
import ru.akvine.zond.models.SourceFile;
import ru.akvine.zond.models.Violation;
import ru.akvine.zond.rules.AbstractTaintRule;
import ru.akvine.zond.rules.RuleCodes;
import ru.akvine.zond.rules.support.Annotations;
import ru.akvine.zond.rules.support.LocalTypes;
import ru.akvine.zond.rules.support.MethodCalls;
import ru.akvine.zond.rules.support.Nodes;
import ru.akvine.zond.rules.support.Taint;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Текст шаблона FreeMarker, Velocity, Thymeleaf, Pebble или Groovy собран из данных клиента: в шаблоне можно
 * вызвать методы объектов и классов Java, и клиент выполнит на сервере свой код (Server-Side Template Injection).
 * Сюда же относится имя представления, которое контроллер Spring MVC берет из запроса: Thymeleaf выполняет
 * выражение __${...}__ прямо в имени
 */
@Component
public class TemplateInjectionRule extends AbstractTaintRule {
    private static final String TEMPLATE = "Template";
    private static final String STRING_READER = "StringReader";
    private static final String EVALUATE = "evaluate";
    // Velocity.evaluate(context, writer, logTag, template): текст шаблона - четвертый
    private static final int VELOCITY_TEMPLATE = 3;
    private static final Pattern VELOCITY = Pattern.compile("(?i).*velocity.*");
    private static final String COMPILED_TEMPLATE = "Template";
    private static final String GET_TEMPLATE = "getTemplate";
    private static final Set<String> TEMPLATE_TEXT_METHODS = Set.of(
            "getLiteralTemplate", "createTemplate", "renderString", "render", "compile", "process");
    private static final Pattern TEMPLATE_ENGINE =
            Pattern.compile("(?i).*(template|pebble|jinjava|mustache|thymeleaf|freemarker|handlebars).*");
    private static final Set<String> CONTROLLER = Set.of("Controller");
    private static final Set<String> RESPONSE_BODY = Set.of("RestController", "ResponseBody");
    private static final Pattern MAPPING = Pattern.compile("(Get|Post|Put|Delete|Patch|Request)Mapping");
    private static final String REDIRECT = "\"redirect:";

    @Override
    public String code() {
        return RuleCodes.TEMPLATE_INJECTION_RULE_CODE;
    }

    @Override
    public String description() {
        return "Сканирует код и ищет шаблоны FreeMarker, Velocity, Thymeleaf и других движков, текст или имя которых"
                + " собран из данных запроса (Server-Side Template Injection)";
    }

    @Override
    protected List<Violation> check(SourceFile sourceFile, Taint taint) {
        List<Violation> violations = new ArrayList<>();
        for (ObjectCreationExpr creation : sourceFile.unit().findAll(ObjectCreationExpr.class)) {
            // new Template("name", new StringReader(text), cfg) и new Template("name", text, cfg) во FreeMarker
            if (TEMPLATE.equals(creation.getType().getNameAsString()) && creation.getArguments().size() >= 2) {
                report(sourceFile, creation, templateText(creation.getArgument(1)), taint, violations);
            }
        }
        for (MethodCallExpr call : sourceFile.unit().findAll(MethodCallExpr.class)) {
            report(sourceFile, call, templateOf(call), taint, violations);
        }
        for (MethodDeclaration method : sourceFile.unit().findAll(MethodDeclaration.class)) {
            if (isViewHandler(method)) {
                method.findAll(ReturnStmt.class).stream()
                        .filter(statement -> !Nodes.isInNestedScope(statement, method))
                        .flatMap(statement -> statement.getExpression().stream())
                        .filter(expression -> !expression.toString().startsWith(REDIRECT))
                        .forEach(expression -> report(sourceFile, expression, Optional.of(expression), taint,
                                violations));
            }
        }
        return violations;
    }

    @Override
    public ErrorLevel errorLevel() {
        return ErrorLevel.CRITICAL;
    }

    @Override
    public ErrorType errorType() {
        return ErrorType.SECURITY;
    }

    private void report(SourceFile sourceFile, Node place, Optional<Expression> template,
                        Taint taint, List<Violation> violations) {
        template.flatMap(taint::findSource).ifPresent(source -> violations.add(violation(sourceFile, place,
                "Шаблон собирается из данных клиента '" + source + "': в шаблоне можно вызывать методы Java, и"
                        + " клиент выполнит на сервере свой код; передавайте данные в модель шаблона, а сам шаблон"
                        + " берите из своих файлов")));
    }

    // new StringReader(text) - текст шаблона; другое значение - сам текст
    private Optional<Expression> templateText(Expression argument) {
        Expression value = Nodes.unwrap(argument);
        if (value instanceof ObjectCreationExpr reader && STRING_READER.equals(reader.getType().getNameAsString())
                && !reader.getArguments().isEmpty()) {
            return Optional.of(reader.getArgument(0));
        }
        return Optional.of(value);
    }

    // velocity.evaluate(...), pebble.getLiteralTemplate(text), templateEngine.process(text, context)
    private Optional<Expression> templateOf(MethodCallExpr call) {
        String method = call.getNameAsString();
        String receiver = call.getScope().map(MethodCalls::receiverName).orElse("");
        if (EVALUATE.equals(method) && call.getArguments().size() > VELOCITY_TEMPLATE
                && VELOCITY.matcher(receiver).matches()) {
            return Optional.of(call.getArgument(VELOCITY_TEMPLATE));
        }
        if (TEMPLATE_TEXT_METHODS.contains(method) && !call.getArguments().isEmpty()
                && TEMPLATE_ENGINE.matcher(receiver).matches() && !isCompiledTemplate(call)) {
            return templateText(call.getArgument(0));
        }
        return Optional.empty();
    }

    // htmlTemplate.process(model, writer) у Template из FreeMarker: шаблон уже собран, а первым идет модель данных.
    // Текст шаблона там, где Template создают: new Template(name, new StringReader(text), cfg)
    private boolean isCompiledTemplate(MethodCallExpr call) {
        Optional<Expression> scope = call.getScope().map(Nodes::unwrap);
        if (scope.isEmpty()) {
            return false;
        }
        if (LocalTypes.typeOf(scope.get()).filter(COMPILED_TEMPLATE::equals).isPresent()) {
            return true;
        }
        return LocalTypes.findInitializer(scope.get())
                .map(Nodes::unwrap)
                .filter(initializer -> initializer.isObjectCreationExpr()
                        && COMPILED_TEMPLATE.equals(initializer.asObjectCreationExpr().getType().getNameAsString())
                        || initializer.isMethodCallExpr()
                        && GET_TEMPLATE.equals(initializer.asMethodCallExpr().getNameAsString()))
                .isPresent();
    }

    // Обработчик @Controller (не @RestController и не @ResponseBody), который возвращает имя представления
    private boolean isViewHandler(MethodDeclaration method) {
        boolean mapped = method.getAnnotations().stream()
                .anyMatch(annotation -> MAPPING.matcher(annotation.getName().getIdentifier()).matches());
        Optional<ClassOrInterfaceDeclaration> owner = method.findAncestor(ClassOrInterfaceDeclaration.class);
        return mapped && "String".equals(method.getType().asString())
                && owner.filter(type -> Annotations.hasAny(type, CONTROLLER) && !Annotations.hasAny(type, RESPONSE_BODY))
                .isPresent()
                && !Annotations.hasAny(method, RESPONSE_BODY);
    }
}
