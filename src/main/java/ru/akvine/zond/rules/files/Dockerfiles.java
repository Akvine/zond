package ru.akvine.zond.rules.files;

import lombok.experimental.UtilityClass;
import ru.akvine.zond.models.TextFile;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Разбор Dockerfile на инструкции: FROM, RUN, USER и остальные
 */
@UtilityClass
public class Dockerfiles {
    public static final String FROM = "FROM";

    private static final String COMMENT = "#";
    private static final String CONTINUATION = "\\";

    /**
     * @param keyword   имя инструкции в верхнем регистре
     * @param arguments все, что идет после имени, одной строкой
     * @param line      строка, с которой инструкция начинается
     */
    public record Instruction(String keyword, String arguments, int line) {
    }

    public List<Instruction> instructions(TextFile file) {
        List<Instruction> instructions = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        int startLine = 0;
        for (int index = 0; index < file.lines().size(); index++) {
            String line = file.lines().get(index).trim();
            if (line.isEmpty() || line.startsWith(COMMENT)) {
                continue;
            }
            if (current.isEmpty()) {
                startLine = index + 1;
            }
            // Инструкция продолжается на следующей строке, если текущая кончается обратной косой чертой
            boolean continues = line.endsWith(CONTINUATION);
            current.append(continues ? line.substring(0, line.length() - 1) : line).append(' ');
            if (!continues) {
                add(instructions, current.toString().trim(), startLine);
                current.setLength(0);
            }
        }
        if (!current.isEmpty()) {
            add(instructions, current.toString().trim(), startLine);
        }
        return instructions;
    }

    /**
     * @return инструкции последнего этапа сборки, начиная с его FROM: в итоговый образ попадает только он
     */
    public List<Instruction> finalStage(List<Instruction> instructions) {
        int lastFrom = 0;
        for (int index = 0; index < instructions.size(); index++) {
            if (FROM.equals(instructions.get(index).keyword())) {
                lastFrom = index;
            }
        }
        return instructions.subList(lastFrom, instructions.size());
    }

    private void add(List<Instruction> instructions, String text, int line) {
        String[] parts = text.split("\\s+", 2);
        instructions.add(new Instruction(parts[0].toUpperCase(Locale.ROOT), parts.length > 1 ? parts[1].trim() : "", line));
    }
}
