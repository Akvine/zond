package ru.akvine.zond.rules.datetime;

import com.github.javaparser.ast.Node;
import com.github.javaparser.ast.expr.AnnotationExpr;
import com.github.javaparser.ast.expr.MemberValuePair;
import com.github.javaparser.ast.expr.MethodCallExpr;
import com.github.javaparser.ast.expr.ObjectCreationExpr;
import com.github.javaparser.ast.expr.StringLiteralExpr;
import org.springframework.stereotype.Component;
import ru.akvine.zond.enums.ErrorLevel;
import ru.akvine.zond.enums.ErrorType;
import ru.akvine.zond.models.SourceFile;
import ru.akvine.zond.models.Violation;
import ru.akvine.zond.rules.AbstractRule;
import ru.akvine.zond.rules.RuleCodes;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;

@Component
public class CheckDatePatternRule extends AbstractRule {
    private static final String OF_PATTERN = "ofPattern";
    private static final String SIMPLE_DATE_FORMAT = "SimpleDateFormat";
    private static final Set<String> FORMAT_ANNOTATIONS = Set.of("JsonFormat", "DateTimeFormat");

    // Текст в одинарных кавычках выводится как есть и к полям шаблона не относится
    private static final Pattern QUOTED_TEXT = Pattern.compile("'[^']*'");

    @Override
    public String code() {
        return RuleCodes.CHECK_DATE_PATTERN_RULE_CODE;
    }

    @Override
    public String description() {
        return "Сканирует код и ищет ошибки в шаблонах дат: YYYY вместо yyyy, hh без a, DD вместо dd";
    }

    @Override
    public List<Violation> check(SourceFile sourceFile) {
        List<Violation> violations = new ArrayList<>();
        for (StringLiteralExpr literal : sourceFile.unit().findAll(StringLiteralExpr.class)) {
            if (!isDatePattern(literal)) {
                continue;
            }
            String pattern = QUOTED_TEXT.matcher(literal.asString()).replaceAll("");
            describeProblem(pattern).ifPresent(problem -> violations.add(violation(sourceFile, literal,
                    "Шаблон даты \"" + literal.asString() + "\": " + problem)));
        }
        return violations;
    }

    @Override
    public ErrorLevel errorLevel() {
        return ErrorLevel.MAJOR;
    }

    @Override
    public ErrorType errorType() {
        return ErrorType.DATE_AND_TIME;
    }

    // DateTimeFormatter.ofPattern("..."), new SimpleDateFormat("..."), @JsonFormat(pattern = "...")
    private boolean isDatePattern(StringLiteralExpr literal) {
        Node parent = literal.getParentNode().orElse(null);
        if (parent instanceof MethodCallExpr call) {
            return OF_PATTERN.equals(call.getNameAsString());
        }
        if (parent instanceof ObjectCreationExpr creation) {
            return SIMPLE_DATE_FORMAT.equals(creation.getType().getNameAsString());
        }
        if (parent instanceof MemberValuePair pair) {
            parent = pair.getParentNode().orElse(null);
        }
        return parent instanceof AnnotationExpr annotation
                && FORMAT_ANNOTATIONS.contains(annotation.getName().getIdentifier());
    }

    private Optional<String> describeProblem(String pattern) {
        if (pattern.contains("YYYY") && !pattern.contains("w")) {
            return Optional.of("YYYY - это год по номеру недели, в последних числах декабря и первых числах января"
                    + " он отличается от календарного; используйте yyyy");
        }
        if (pattern.contains("DD") && pattern.contains("M")) {
            return Optional.of("DD - это номер дня в году (1-366), а не в месяце; используйте dd");
        }
        if (pattern.contains("hh") && !pattern.contains("a")) {
            return Optional.of("hh - это часы в 12-часовом формате, без признака AM/PM (a) 15:00 и 03:00"
                    + " неразличимы; используйте HH");
        }
        return Optional.empty();
    }
}
