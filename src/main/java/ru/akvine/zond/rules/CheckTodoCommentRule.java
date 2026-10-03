package ru.akvine.zond.rules;

import org.springframework.stereotype.Component;
import ru.akvine.zond.enums.ErrorLevel;
import ru.akvine.zond.enums.ErrorType;
import ru.akvine.zond.models.SourceFile;
import ru.akvine.zond.models.Violation;

import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Component
public class CheckTodoCommentRule extends AbstractRule {
    private static final Pattern MARKER = Pattern.compile("\\b(TODO|FIXME)\\b");

    @Override
    public String code() {
        return RuleCodes.CHECK_TODO_COMMENT_RULE_CODE;
    }

    @Override
    public String description() {
        return "Сканирует код и ищет комментарии с TODO и FIXME";
    }

    @Override
    public List<Violation> check(SourceFile sourceFile) {
        return sourceFile.unit().getAllComments().stream()
                .filter(comment -> MARKER.matcher(comment.getContent()).find())
                .map(comment -> {
                    Matcher matcher = MARKER.matcher(comment.getContent());
                    matcher.find();
                    return violation(sourceFile, comment,
                            matcher.group() + " в комментарии: незавершенная работа осталась в коде;"
                                    + " доделайте или заведите задачу");
                })
                .sorted((left, right) -> Integer.compare(left.line(), right.line()))
                .toList();
    }

    @Override
    public ErrorLevel errorLevel() {
        return ErrorLevel.INFO;
    }

    @Override
    public ErrorType errorType() {
        return ErrorType.CODE_SMELL;
    }
}
