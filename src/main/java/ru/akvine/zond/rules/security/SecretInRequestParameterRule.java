package ru.akvine.zond.rules.security;

import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.body.Parameter;
import com.github.javaparser.ast.expr.AnnotationExpr;
import com.github.javaparser.ast.expr.StringLiteralExpr;
import org.springframework.stereotype.Component;
import ru.akvine.zond.enums.ErrorLevel;
import ru.akvine.zond.enums.ErrorType;
import ru.akvine.zond.models.SourceFile;
import ru.akvine.zond.models.Violation;
import ru.akvine.zond.rules.AbstractRule;
import ru.akvine.zond.rules.RuleCodes;
import ru.akvine.zond.rules.support.Annotations;
import ru.akvine.zond.rules.support.Handlers;
import ru.akvine.zond.rules.support.Mappings;
import ru.akvine.zond.rules.support.Secrets;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;

@Component
public class SecretInRequestParameterRule extends AbstractRule {
    private static final String PATH_VARIABLE = "PathVariable";
    private static final String REQUEST_PARAM = "RequestParam";
    // У POST и PUT @RequestParam может прийти из тела формы; у этих методов тела нет - параметр точно в адресе
    private static final Set<String> QUERY_ONLY_METHODS = Set.of("GET", "DELETE", "");

    @Override
    public String code() {
        return RuleCodes.SECRET_IN_REQUEST_PARAMETER_RULE_CODE;
    }

    @Override
    public String description() {
        return "Сканирует код и ищет обработчики, которые принимают пароль, токен или ключ в адресе запроса";
    }

    @Override
    public List<Violation> check(SourceFile sourceFile) {
        List<Violation> violations = new ArrayList<>();
        for (MethodDeclaration handler : sourceFile.unit().findAll(MethodDeclaration.class)) {
            Optional<AnnotationExpr> mapping = Mappings.find(handler);
            // Интерфейс HTTP-клиента описывает чужой сервис: каким быть его адресу, решает он. Обычный же
            // интерфейс с адресами - объявление своего контроллера, и аннотации параметров стоят как раз в нем
            boolean client = handler.findAncestor(ClassOrInterfaceDeclaration.class).filter(Handlers::isClient).isPresent();
            if (mapping.isEmpty() || client) {
                continue;
            }
            String httpMethod = Mappings.httpMethod(mapping.get());
            for (Parameter parameter : handler.getParameters()) {
                Optional<AnnotationExpr> source = Annotations.find(parameter, PATH_VARIABLE)
                        .or(() -> Annotations.find(parameter, REQUEST_PARAM)
                                .filter(annotation -> QUERY_ONLY_METHODS.contains(httpMethod)));
                if (source.isEmpty()) {
                    continue;
                }
                String name = nameOf(source.get(), parameter);
                if (Secrets.isSecretName(name)) {
                    boolean inPath = PATH_VARIABLE.equals(source.get().getNameAsString());
                    violations.add(violation(sourceFile, parameter,
                            "Секрет '" + name + "' приходит в адресе запроса (" + (inPath ? "часть пути" : "параметр")
                                    + "): адрес целиком попадает в журналы веб-сервера и прокси, в историю браузера"
                                    + " и в заголовок Referer; передавайте секрет в заголовке или в теле запроса"));
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
        return ErrorType.SECURITY;
    }

    // @RequestParam("api_key") String key: имя параметра запроса задано в аннотации
    private String nameOf(AnnotationExpr annotation, Parameter parameter) {
        return annotation.findFirst(StringLiteralExpr.class)
                .map(StringLiteralExpr::getValue)
                .filter(value -> !value.isBlank())
                .orElseGet(parameter::getNameAsString);
    }
}
