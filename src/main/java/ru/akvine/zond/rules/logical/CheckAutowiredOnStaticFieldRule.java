package ru.akvine.zond.rules.logical;

import com.github.javaparser.ast.body.FieldDeclaration;
import com.github.javaparser.ast.nodeTypes.NodeWithSimpleName;
import org.springframework.stereotype.Component;
import ru.akvine.zond.enums.ErrorLevel;
import ru.akvine.zond.enums.ErrorType;
import ru.akvine.zond.models.SourceFile;
import ru.akvine.zond.models.Violation;
import ru.akvine.zond.rules.Rule;
import ru.akvine.zond.rules.RuleCodes;

import java.util.List;
import java.util.stream.Collectors;

@Component
public class CheckAutowiredOnStaticFieldRule implements Rule {
    private static final String AUTOWIRED = "Autowired";

    @Override
    public String name() {
        return getClass().getSimpleName();
    }

    @Override
    public String code() {
        return RuleCodes.CHECK_AUTOWIRED_ON_STATIC_FIELD_RULE_CODE;
    }

    @Override
    public String description() {
        return "Сканирует код и ищет @Autowired над static-полями";
    }

    @Override
    public boolean enabled() {
        return true;
    }

    @Override
    public List<Violation> check(SourceFile sourceFile) {
        return sourceFile.unit().findAll(FieldDeclaration.class).stream()
                .filter(FieldDeclaration::isStatic)
                .filter(this::isAutowired)
                .map(field -> new Violation(
                        errorLevel(),
                        errorType(),
                        code(),
                        name(),
                        sourceFile.path(),
                        field.getBegin().map(position -> position.line).orElse(0),
                        "@Autowired над static-полем '" + fieldNames(field)
                                + "' не работает: Spring не внедряет зависимости в статические поля,"
                                + " поле останется null"))
                .toList();
    }

    @Override
    public ErrorLevel errorLevel() {
        return ErrorLevel.CRITICAL;
    }

    @Override
    public ErrorType errorType() {
        return ErrorType.LOGICAL;
    }

    // Сравниваем по простому имени, чтобы поймать и @Autowired, и полное имя
    private boolean isAutowired(FieldDeclaration field) {
        return field.getAnnotations().stream()
                .anyMatch(annotation -> AUTOWIRED.equals(annotation.getName().getIdentifier()));
    }

    // В одном объявлении может быть несколько полей: static A a, b;
    private String fieldNames(FieldDeclaration field) {
        return field.getVariables().stream()
                .map(NodeWithSimpleName::getNameAsString)
                .collect(Collectors.joining(", "));
    }
}
