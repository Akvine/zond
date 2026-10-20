package ru.akvine.zond.rules.support;

import com.github.javaparser.ast.Node;
import lombok.experimental.UtilityClass;
import ru.akvine.zond.enums.TextFileType;
import ru.akvine.zond.models.ScanContext;
import ru.akvine.zond.models.SourceFile;
import ru.akvine.zond.models.TestPaths;
import ru.akvine.zond.models.TextFile;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * Тексты запросов проекта: строки Java-кода (@Query, jdbcTemplate, createQuery; литералы, склеенные через +)
 * и операторы мапперов MyBatis. Тесты не считаются.
 */
@UtilityClass
public class QueryTexts {
    // Похоже на запрос, а не на сообщение: SELECT ... FROM, UPDATE ... SET, DELETE FROM
    private static final Pattern QUERY = Pattern.compile(
            "(?is).*\\b(select\\b.*\\bfrom|update\\b.*\\bset|delete\\s+from)\\b.*");
    private static final Pattern STATEMENT_START = Pattern.compile("<(select|update|delete|insert)\\b");
    private static final Pattern STATEMENT_END = Pattern.compile("</(select|update|delete|insert)>");

    /**
     * @param node литерал в Java-коде; пусто для запроса из маппера MyBatis
     */
    public record QueryText(Path file, int line, String text, Optional<Node> node) {
    }

    public List<QueryText> of(ScanContext context) {
        List<QueryText> queries = new ArrayList<>();
        for (SourceFile sourceFile : context.sources()) {
            if (TestPaths.isTest(sourceFile.path())) {
                continue;
            }
            for (StringLiterals.LiteralText literal : StringLiterals.findComplete(sourceFile.unit())) {
                if (QUERY.matcher(literal.text()).matches() && !TestClasses.isInside(literal.node())) {
                    queries.add(new QueryText(sourceFile.path(),
                            literal.node().getBegin().map(position -> position.line).orElse(1),
                            literal.text(), Optional.of(literal.node())));
                }
            }
        }
        for (TextFile file : context.textFiles()) {
            if (file.type() == TextFileType.MYBATIS_MAPPER && !TestPaths.isTest(file.path())) {
                queries.addAll(statements(file));
            }
        }
        return queries;
    }

    // <select id="..."> ... </select>: текст оператора целиком, строка - его начало
    private List<QueryText> statements(TextFile file) {
        List<QueryText> statements = new ArrayList<>();
        StringBuilder current = null;
        int start = 0;
        for (int index = 0; index < file.lines().size(); index++) {
            String line = file.lines().get(index);
            if (current == null && STATEMENT_START.matcher(line).find()) {
                current = new StringBuilder();
                start = index + 1;
            }
            if (current != null) {
                current.append(line).append(' ');
                if (STATEMENT_END.matcher(line).find()) {
                    statements.add(new QueryText(file.path(), start, current.toString(), Optional.empty()));
                    current = null;
                }
            }
        }
        return statements;
    }
}
