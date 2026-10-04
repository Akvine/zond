package ru.akvine.zond.rules;

import lombok.experimental.UtilityClass;
import ru.akvine.zond.enums.TextFileType;
import ru.akvine.zond.models.TextFile;

import java.util.ArrayList;
import java.util.List;

/**
 * Разбор миграции на отдельные команды SQL. Комментарии отбрасываются, точка с запятой внутри строк
 * и внутри тела функции ($$ ... $$) команду не завершает.
 */
@UtilityClass
class SqlStatements {
    private static final String LINE_COMMENT = "--";
    private static final String BLOCK_COMMENT_START = "/*";
    private static final String BLOCK_COMMENT_END = "*/";
    private static final String DOLLAR_QUOTE = "$$";

    /**
     * @param text команда без комментариев, пробелы и переводы строк сведены к одному пробелу
     * @param line строка, с которой команда начинается
     */
    record Statement(String text, int line) {
    }

    /**
     * @return команды миграции: из SQL-файла как есть, из журнала Liquibase - в переводе на SQL
     */
    List<Statement> of(TextFile file) {
        if (!TextFiles.isMigration(file)) {
            return List.of();
        }
        return file.type() == TextFileType.SQL ? parse(file.lines()) : Liquibase.statements(file);
    }

    List<Statement> parse(List<String> lines) {
        List<Statement> statements = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        int startLine = 0;
        boolean inBlockComment = false;
        boolean inString = false;
        boolean inDollarQuote = false;

        for (int lineIndex = 0; lineIndex < lines.size(); lineIndex++) {
            String line = lines.get(lineIndex);
            for (int index = 0; index < line.length(); index++) {
                if (inBlockComment) {
                    if (line.startsWith(BLOCK_COMMENT_END, index)) {
                        inBlockComment = false;
                        index++;
                    }
                    continue;
                }
                char symbol = line.charAt(index);
                if (!inString && !inDollarQuote && line.startsWith(LINE_COMMENT, index)) {
                    break;
                }
                if (!inString && !inDollarQuote && line.startsWith(BLOCK_COMMENT_START, index)) {
                    inBlockComment = true;
                    index++;
                    continue;
                }
                if (!inString && line.startsWith(DOLLAR_QUOTE, index)) {
                    inDollarQuote = !inDollarQuote;
                } else if (!inDollarQuote && symbol == '\'') {
                    inString = !inString;
                }

                if (symbol == ';' && !inString && !inDollarQuote) {
                    add(statements, current, startLine);
                    continue;
                }
                if (current.isEmpty()) {
                    // Пробелы и переводы строк перед командой не копятся, иначе ее строка определится неверно
                    if (Character.isWhitespace(symbol)) {
                        continue;
                    }
                    startLine = lineIndex + 1;
                }
                current.append(symbol);
            }
            if (!current.isEmpty()) {
                current.append(' ');
            }
        }
        // Последняя команда может быть без точки с запятой
        add(statements, current, startLine);
        return statements;
    }

    private void add(List<Statement> statements, StringBuilder current, int line) {
        String text = current.toString().trim().replaceAll("\\s+", " ");
        if (!text.isEmpty()) {
            statements.add(new Statement(text, line));
        }
        current.setLength(0);
    }
}
