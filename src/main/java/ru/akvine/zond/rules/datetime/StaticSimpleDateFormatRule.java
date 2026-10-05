package ru.akvine.zond.rules.datetime;

import com.github.javaparser.ast.body.FieldDeclaration;
import com.github.javaparser.ast.body.VariableDeclarator;
import com.github.javaparser.ast.type.Type;
import org.springframework.stereotype.Component;
import ru.akvine.zond.enums.ErrorLevel;
import ru.akvine.zond.enums.ErrorType;
import ru.akvine.zond.models.SourceFile;
import ru.akvine.zond.models.Violation;
import ru.akvine.zond.rules.Rule;
import ru.akvine.zond.rules.RuleCodes;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;

@Component
public class StaticSimpleDateFormatRule implements Rule {
    private static final Set<String> NOT_THREAD_SAFE_TYPES =
            Set.of("SimpleDateFormat", "Calendar", "GregorianCalendar");

    @Override
    public String name() {
        return getClass().getSimpleName();
    }

    @Override
    public String code() {
        return RuleCodes.STATIC_SIMPLE_DATE_FORMAT_RULE_CODE;
    }

    @Override
    public String description() {
        return "Сканирует код и ищет SimpleDateFormat и Calendar в статических полях";
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
                findNotThreadSafeType(variable).ifPresent(type -> violations.add(new Violation(
                        errorLevel(),
                        errorType(),
                        code(),
                        name(),
                        sourceFile.path(),
                        variable.getBegin().map(position -> position.line).orElse(0),
                        type + " в статическом поле '" + variable.getNameAsString()
                                + "': класс не потокобезопасен, при одновременном использовании даты будут"
                                + " разбираться и форматироваться неверно; используйте типы java.time"
                                + " (DateTimeFormatter, LocalDateTime)")));
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
    private Optional<String> findNotThreadSafeType(VariableDeclarator variable) {
        String declaredType = simpleName(variable.getType());
        if (NOT_THREAD_SAFE_TYPES.contains(declaredType)) {
            return Optional.of(declaredType);
        }
        return variable.getInitializer()
                .filter(initializer -> initializer.isObjectCreationExpr())
                .map(initializer -> initializer.asObjectCreationExpr().getType().getNameAsString())
                .filter(NOT_THREAD_SAFE_TYPES::contains);
    }

    // java.text.SimpleDateFormat -> SimpleDateFormat
    private String simpleName(Type type) {
        return type.isClassOrInterfaceType() ? type.asClassOrInterfaceType().getNameAsString() : type.asString();
    }
}
