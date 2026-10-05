package ru.akvine.zond.rules.codesmell;

import com.github.javaparser.ast.comments.Comment;
import org.springframework.stereotype.Component;
import ru.akvine.zond.enums.ErrorLevel;
import ru.akvine.zond.enums.ErrorType;
import ru.akvine.zond.models.SourceFile;
import ru.akvine.zond.models.Violation;
import ru.akvine.zond.rules.AbstractRule;
import ru.akvine.zond.rules.RuleCodes;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.regex.Pattern;

@Component
public class CommentedOutCodeRule extends AbstractRule {
    // Оператор целиком: начинается как код и заканчивается точкой с запятой
    private static final Pattern STATEMENT = Pattern.compile(
            "^(return\\b|throw\\s+new\\b|(private|public|protected|final|static)\\s"
                    + "|[A-Za-z_][\\w.<>\\[\\], ]*\\s+[a-z_]\\w*\\s*=[^=]"
                    + "|[a-z_][\\w.]*\\s*=[^=]"
                    + "|[A-Za-z_][\\w.]*\\(.*\\)).*;$");

    // Заголовок блока: if (...) {, } else {, } catch (...) {
    private static final Pattern BLOCK_HEADER = Pattern.compile(
            "^(\\}\\s*)?(if|for|while|try|else|switch|do|catch|finally)\\b.*\\{$");

    // Пояснение с примером кода (// x == null либо this.x == null, // if (false) { ... }) - это не забытый код
    private static final Pattern PROSE = Pattern.compile(".*(\\p{IsCyrillic}|\\.\\.\\.).*");

    @Override
    public String code() {
        return RuleCodes.COMMENTED_OUT_CODE_RULE_CODE;
    }

    @Override
    public String description() {
        return "Сканирует код и ищет закомментированный код";
    }

    @Override
    public List<Violation> check(SourceFile sourceFile) {
        List<Comment> comments = sourceFile.unit().getAllComments().stream()
                .filter(comment -> !comment.isJavadocComment())
                .filter(this::looksLikeCode)
                .sorted(Comparator.comparingInt(this::line))
                .toList();

        // Несколько закомментированных строк подряд - это один фрагмент, сообщаем о нем один раз
        List<Violation> violations = new ArrayList<>();
        int previousLine = -1;
        for (Comment comment : comments) {
            int line = line(comment);
            if (line != previousLine + 1) {
                violations.add(violation(sourceFile, comment,
                        "Закомментированный код: читающий не знает, можно ли его удалить и почему он оставлен,"
                                + " а сам код устаревает; удалите его - прежняя версия останется в истории git"));
            }
            previousLine = comment.getEnd().map(position -> position.line).orElse(line);
        }
        return violations;
    }

    @Override
    public ErrorLevel errorLevel() {
        return ErrorLevel.INFO;
    }

    @Override
    public ErrorType errorType() {
        return ErrorType.CODE_SMELL;
    }

    // Хотя бы одна строка комментария похожа на код
    private boolean looksLikeCode(Comment comment) {
        return comment.getContent().lines()
                .map(line -> line.replaceFirst("^\\s*\\*?\\s*", "").trim())
                .filter(line -> !PROSE.matcher(line).matches())
                .anyMatch(line -> STATEMENT.matcher(line).matches() || BLOCK_HEADER.matcher(line).matches());
    }

    private int line(Comment comment) {
        return comment.getBegin().map(position -> position.line).orElse(0);
    }
}
