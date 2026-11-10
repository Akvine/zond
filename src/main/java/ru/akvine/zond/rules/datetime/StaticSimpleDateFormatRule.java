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
    private static final Set<String> DATE_TYPES = Set.of("SimpleDateFormat", "DateFormat", "Calendar", "GregorianCalendar");
    private static final Set<String> FORMAT_TYPES = Set.of("DecimalFormat", "NumberFormat", "MessageFormat", "ChoiceFormat");
    // Объекты с состоянием вычисления: дайджест, шифр, подпись, сопоставление по шаблону
    private static final Set<String> STATEFUL_TYPES = Set.of("MessageDigest", "Cipher", "Mac", "Signature", "Matcher");

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
        return "Сканирует код и ищет в статических полях объекты, которые нельзя делить между потоками:"
                + " SimpleDateFormat, Calendar, DecimalFormat, NumberFormat, MessageDigest, Cipher, Matcher";
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
                        type + " в статическом поле '" + variable.getNameAsString() + "': " + consequence(type))));
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

    private String consequence(String type) {
        if (DATE_TYPES.contains(type)) {
            return "класс не потокобезопасен, при одновременном использовании даты будут"
                    + " разбираться и форматироваться неверно; используйте типы java.time"
                    + " (DateTimeFormatter, LocalDateTime)";
        }
        if (FORMAT_TYPES.contains(type)) {
            return "класс не потокобезопасен, при одновременном использовании числа и тексты будут"
                    + " форматироваться и разбираться неверно; создавайте объект на каждое использование"
                    + " либо держите его в ThreadLocal";
        }
        return "у объекта общее внутреннее состояние, и параллельные вызовы испортят результат друг другу;"
                + " создавайте его на каждое использование, а общим держите только неизменяемое: Pattern, ключ,"
                + " название алгоритма";
    }

    // По типу поля либо по инициализатору: static DateFormat FORMAT = new SimpleDateFormat(...).
    // ThreadLocal<SimpleDateFormat> сюда не попадает - это как раз безопасный вариант
    private Optional<String> findNotThreadSafeType(VariableDeclarator variable) {
        // Сначала по созданному объекту: он называет класс точнее, чем тип поля
        Optional<String> created = variable.getInitializer()
                .filter(initializer -> initializer.isObjectCreationExpr())
                .map(initializer -> initializer.asObjectCreationExpr().getType().getNameAsString())
                .filter(this::isNotThreadSafe);
        String declaredType = simpleName(variable.getType());
        return created.isPresent() || !isNotThreadSafe(declaredType) ? created : Optional.of(declaredType);
    }

    private boolean isNotThreadSafe(String type) {
        return DATE_TYPES.contains(type) || FORMAT_TYPES.contains(type) || STATEFUL_TYPES.contains(type);
    }

    // java.text.SimpleDateFormat -> SimpleDateFormat
    private String simpleName(Type type) {
        return type.isClassOrInterfaceType() ? type.asClassOrInterfaceType().getNameAsString() : type.asString();
    }
}
