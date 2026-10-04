package ru.akvine.zond.rules.files;

import lombok.experimental.UtilityClass;
import ru.akvine.zond.models.TextFile;
import ru.akvine.zond.parsers.YamlNode;
import ru.akvine.zond.parsers.YamlParser;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * Файлы CI: .gitlab-ci.yml и .github/workflows/*.yml
 */
@UtilityClass
public class CiFiles {
    // script, before_script, after_script - GitLab CI; run - GitHub Actions
    private static final Set<String> COMMAND_KEYS = Set.of("script", "before_script", "after_script", "run");
    private static final String LINE_SEPARATOR = "\n";

    /**
     * Одна строка команды вместе со строкой файла
     */
    public record Command(String text, int line) {
    }

    /**
     * @return документы файла CI; пусто, если файл другого вида либо не разбирается
     */
    public List<YamlNode> documents(TextFile file) {
        return TextFiles.isCi(file) ? YamlParser.parse(file.lines()).orElse(List.of()) : List.of();
    }

    /**
     * @return все команды, которые выполняются в задачах файла
     */
    public List<Command> commands(TextFile file) {
        List<Command> commands = new ArrayList<>();
        for (YamlNode document : documents(file)) {
            document.visit((key, node) -> {
                if (COMMAND_KEYS.contains(key)) {
                    collect(node, commands);
                }
            });
        }
        return commands;
    }

    // Команды записывают строкой, многострочным блоком либо списком, в том числе вложенным
    private void collect(YamlNode node, List<Command> commands) {
        node.items().forEach(item -> collect(item, commands));
        if (!node.isScalar()) {
            return;
        }
        String[] lines = node.text().split(LINE_SEPARATOR);
        // Многострочный блок ("run: |") начинается со строки, следующей за ключом
        int firstLine = lines.length > 1 ? node.line() + 1 : node.line();
        for (int index = 0; index < lines.length; index++) {
            commands.add(new Command(lines[index].trim(), firstLine + index));
        }
    }
}
