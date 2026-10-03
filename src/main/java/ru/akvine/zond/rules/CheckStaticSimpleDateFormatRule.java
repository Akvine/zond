package ru.akvine.zond.rules;

import com.github.javaparser.ast.body.FieldDeclaration;
import com.github.javaparser.ast.body.VariableDeclarator;
import com.github.javaparser.ast.type.Type;
import org.springframework.stereotype.Component;
import ru.akvine.zond.enums.ErrorLevel;
import ru.akvine.zond.enums.ErrorType;
import ru.akvine.zond.models.SourceFile;
import ru.akvine.zond.models.Violation;

import java.util.ArrayList;
import java.util.List;

@Component
public class CheckStaticSimpleDateFormatRule implements Rule {
    private static final String SIMPLE_DATE_FORMAT = "SimpleDateFormat";

    @Override
    public String name() {
        return getClass().getSimpleName();
    }

    @Override
    public String code() {
        return RuleCodes.CHECK_STATIC_SIMPLE_DATE_FORMAT_RULE_CODE;
    }

    @Override
    public String description() {
        return "Сканирует код и ищет SimpleDateFormat в статических полях";
    }

    @Override
    public boolean enabled() {
        return true;
    }

    @Override
    public List<Violation> check(SourceFile sourceFile) {
        List<Violation> violations = new ArrayList<>();
        for (FieldDeclaration field : sourceFile.unit().findAll(FieldDeclaration.class)) {
            if (!field.isStatic()) {
                continue;
            }

            for (VariableDeclarator variable : field.getVariables()) {
                if (!isSimpleDateFormat(variable)) {
                    continue;
                }
                violations.add(new Violation(
                        errorLevel(),
                        errorType(),
                        code(),
                        name(),
                        sourceFile.path(),
                        variable.getBegin().map(position -> position.line).orElse(0),
                        "SimpleDateFormat в статическом поле '" + variable.getNameAsString()
                                + "': класс не потокобезопасен, при одновременном использовании даты будут"
                                + " разбираться и форматироваться неверно; используйте DateTimeFormatter"));
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
        return ErrorType.DATE_AND_TIME;
    }

    // По типу поля либо по инициализатору: static DateFormat FORMAT = new SimpleDateFormat(...).
    // ThreadLocal<SimpleDateFormat> сюда не попадает - это как раз безопасный вариант
    private boolean isSimpleDateFormat(VariableDeclarator variable) {
        if (SIMPLE_DATE_FORMAT.equals(simpleName(variable.getType()))) {
            return true;
        }
        return variable.getInitializer()
                .filter(initializer -> initializer.isObjectCreationExpr())
                .map(initializer -> initializer.asObjectCreationExpr().getType().getNameAsString())
                .filter(SIMPLE_DATE_FORMAT::equals)
                .isPresent();
    }

    // java.text.SimpleDateFormat -> SimpleDateFormat
    private String simpleName(Type type) {
        return type.isClassOrInterfaceType() ? type.asClassOrInterfaceType().getNameAsString() : type.asString();
    }
}
