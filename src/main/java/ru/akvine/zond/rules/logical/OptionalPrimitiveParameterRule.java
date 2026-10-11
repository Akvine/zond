package ru.akvine.zond.rules.logical;

import com.github.javaparser.ast.body.Parameter;
import com.github.javaparser.ast.expr.AnnotationExpr;
import org.springframework.stereotype.Component;
import ru.akvine.zond.enums.Confidence;
import ru.akvine.zond.enums.ErrorLevel;
import ru.akvine.zond.enums.ErrorType;
import ru.akvine.zond.models.SourceFile;
import ru.akvine.zond.models.Violation;
import ru.akvine.zond.rules.AbstractRule;
import ru.akvine.zond.rules.RuleCodes;
import ru.akvine.zond.rules.support.Annotations;
import ru.akvine.zond.rules.support.Queries;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Необязательный параметр запроса с типом-примитивом. Когда клиент его не передает, Spring должен положить
 * в параметр null, а в int или boolean null не помещается - запрос заканчивается ошибкой.
 */
@Component
public class OptionalPrimitiveParameterRule extends AbstractRule {
    private static final Set<String> REQUEST_VALUES = Set.of(
            "RequestParam", "RequestHeader", "CookieValue", "PathVariable", "MatrixVariable", "RequestAttribute",
            "SessionAttribute");
    private static final String REQUIRED = "required";
    private static final String DEFAULT_VALUE = "defaultValue";
    private static final String FALSE = "false";
    private static final Map<String, String> WRAPPERS = Map.of(
            "int", "Integer", "long", "Long", "boolean", "Boolean", "double", "Double", "float", "Float",
            "short", "Short", "byte", "Byte", "char", "Character");

    @Override
    public String code() {
        return RuleCodes.OPTIONAL_PRIMITIVE_PARAMETER_RULE_CODE;
    }

    @Override
    public String description() {
        return "Сканирует код и ищет необязательные параметры запроса (required = false) с типом-примитивом";
    }

    // И необязательность, и тип записаны прямо в объявлении параметра
    @Override
    public Confidence confidence() {
        return Confidence.CONFIRMED;
    }

    @Override
    public List<Violation> check(SourceFile sourceFile) {
        List<Violation> violations = new ArrayList<>();
        for (Parameter parameter : sourceFile.unit().findAll(Parameter.class)) {
            String type = parameter.getType().asString();
            Optional<AnnotationExpr> source = Annotations.find(parameter, REQUEST_VALUES);
            if (!WRAPPERS.containsKey(type) || source.isEmpty() || !isOptionalWithoutDefault(source.get())) {
                continue;
            }
            violations.add(violation(sourceFile, parameter,
                    "Параметр '" + parameter.getNameAsString() + "' объявлен необязательным (required = false),"
                            + " но имеет тип " + type + ": если клиент его не передаст, Spring не сможет положить"
                            + " null в примитив и ответит ошибкой; задайте defaultValue либо смените тип на "
                            + WRAPPERS.get(type)));
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

    // С defaultValue параметр всегда получает значение: null в него не попадет
    private boolean isOptionalWithoutDefault(AnnotationExpr annotation) {
        boolean optional = Queries.member(annotation, REQUIRED)
                .filter(value -> FALSE.equals(value.toString()))
                .isPresent();
        return optional && Queries.member(annotation, DEFAULT_VALUE).isEmpty();
    }
}
