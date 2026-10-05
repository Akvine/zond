package ru.akvine.zond.rules.performance;

import org.springframework.stereotype.Component;
import ru.akvine.zond.enums.ErrorLevel;
import ru.akvine.zond.enums.ErrorType;
import ru.akvine.zond.models.SourceFile;
import ru.akvine.zond.models.Violation;
import ru.akvine.zond.rules.AbstractRule;
import ru.akvine.zond.rules.RuleCodes;
import ru.akvine.zond.rules.support.StringLiterals;
import ru.akvine.zond.rules.support.TestClasses;

import java.util.List;
import java.util.regex.Pattern;

@Component
public class LikeWithLeadingWildcardRule extends AbstractRule {
    // like '%abc', like %:name%, like concat('%', :name)
    private static final Pattern LEADING_WILDCARD = Pattern.compile(
            "\\blike\\s+(lower\\s*\\(\\s*)?('%|%\\s*:|%\\s*\\?|concat\\s*\\(\\s*'%')", Pattern.CASE_INSENSITIVE);

    @Override
    public String code() {
        return RuleCodes.LIKE_WITH_LEADING_WILDCARD_RULE_CODE;
    }

    @Override
    public String description() {
        return "Сканирует код и ищет запросы с LIKE, шаблон которого начинается с %";
    }

    @Override
    public List<Violation> check(SourceFile sourceFile) {
        return StringLiterals.findComplete(sourceFile.unit()).stream()
                .filter(literal -> LEADING_WILDCARD.matcher(literal.text()).find())
                .filter(literal -> !TestClasses.isInside(literal.node()))
                .map(literal -> violation(sourceFile, literal.node(),
                        "LIKE с шаблоном, который начинается с %: индекс по колонке для такого поиска"
                                + " не используется, база читает таблицу целиком; ищите по началу строки либо"
                                + " используйте полнотекстовый или триграммный индекс"))
                .toList();
    }

    @Override
    public ErrorLevel errorLevel() {
        return ErrorLevel.MINOR;
    }

    @Override
    public ErrorType errorType() {
        return ErrorType.PERFORMANCE;
    }
}
