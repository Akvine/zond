package ru.akvine.zond.rules;

import org.springframework.stereotype.Component;
import ru.akvine.zond.enums.ErrorLevel;
import ru.akvine.zond.enums.ErrorType;
import ru.akvine.zond.models.ConfigFile;
import ru.akvine.zond.models.ScanContext;
import ru.akvine.zond.models.TextFile;
import ru.akvine.zond.models.Violation;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Component
public class CheckDuplicatePropertyKeyRule extends AbstractContextRule {
    private static final String PROPERTIES_EXTENSION = ".properties";

    // #--- делит application.properties на документы для разных профилей - там повтор ключа законен
    private static final String DOCUMENT_SEPARATOR = "---";

    @Override
    public String code() {
        return RuleCodes.CHECK_DUPLICATE_PROPERTY_KEY_RULE_CODE;
    }

    @Override
    public String description() {
        return "Сканирует файлы .properties (настройки и сообщения) и ищет ключи, заданные дважды";
    }

    @Override
    public List<Violation> checkContext(ScanContext context) {
        List<Violation> violations = new ArrayList<>();
        for (TextFile file : context.textFiles()) {
            if (TextFiles.isMessages(file)) {
                check(file.path(), file.lines(), violations);
            }
        }
        for (ConfigFile configFile : context.configFiles()) {
            if (configFile.path().getFileName().toString().endsWith(PROPERTIES_EXTENSION)) {
                check(configFile.path(), read(configFile.path()), violations);
            }
        }
        return violations;
    }

    @Override
    public ErrorLevel errorLevel() {
        return ErrorLevel.MAJOR;
    }

    @Override
    public ErrorType errorType() {
        return ErrorType.LOGICAL;
    }

    private void check(Path file, List<String> lines, List<Violation> violations) {
        boolean multiDocument = lines.stream()
                .map(String::trim)
                .anyMatch(line -> line.equals("#" + DOCUMENT_SEPARATOR) || line.equals("!" + DOCUMENT_SEPARATOR));
        if (multiDocument) {
            return;
        }
        // Ключ -> строка, где он встретился впервые
        Map<String, Integer> firstLines = new HashMap<>();
        for (TextFiles.Entry entry : TextFiles.properties(lines)) {
            Integer first = firstLines.putIfAbsent(entry.key(), entry.line());
            if (first != null) {
                violations.add(violation(file, entry.line(),
                        "Ключ '" + entry.key() + "' уже задан на строке " + first + ": действует последнее"
                                + " значение, а первое молча теряется; оставьте одно"));
            }
        }
    }

    private List<String> read(Path file) {
        try {
            return Files.readAllLines(file, StandardCharsets.UTF_8);
        } catch (IOException | RuntimeException exception) {
            return List.of();
        }
    }
}
