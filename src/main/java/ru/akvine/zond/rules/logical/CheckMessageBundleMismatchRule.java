package ru.akvine.zond.rules.logical;

import org.springframework.stereotype.Component;
import ru.akvine.zond.enums.ErrorLevel;
import ru.akvine.zond.enums.ErrorType;
import ru.akvine.zond.models.ScanContext;
import ru.akvine.zond.models.TextFile;
import ru.akvine.zond.models.Violation;
import ru.akvine.zond.rules.AbstractContextRule;
import ru.akvine.zond.rules.RuleCodes;
import ru.akvine.zond.rules.files.TextFiles;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Component
public class CheckMessageBundleMismatchRule extends AbstractContextRule {
    // messages.properties, messages_ru.properties, messages_en_US.properties -> messages
    private static final Pattern LOCALE_SUFFIX = Pattern.compile("(_[a-z]{2,3}(_[A-Z]{2})?)?\\.properties$");

    // {0}, {1,number}
    private static final Pattern ARGUMENT = Pattern.compile("\\{(\\d+)[^}]*}");
    private static final int SHOWN_KEYS = 5;

    @Override
    public String code() {
        return RuleCodes.CHECK_MESSAGE_BUNDLE_MISMATCH_RULE_CODE;
    }

    @Override
    public String description() {
        return "Сканирует файлы messages*.properties и ищет ключи, которых нет в части языков, и разные подстановки";
    }

    @Override
    public List<Violation> checkContext(ScanContext context) {
        // Каталог и имя без языка -> файлы одного набора сообщений
        Map<String, List<TextFile>> bundles = new LinkedHashMap<>();
        for (TextFile file : context.textFiles()) {
            if (TextFiles.isMessages(file)) {
                String base = LOCALE_SUFFIX.matcher(file.name()).replaceFirst("");
                bundles.computeIfAbsent(file.path().toAbsolutePath().getParent() + "/" + base, key -> new ArrayList<>())
                        .add(file);
            }
        }

        List<Violation> violations = new ArrayList<>();
        for (List<TextFile> bundle : bundles.values()) {
            if (bundle.size() > 1) {
                check(bundle, violations);
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

    private void check(List<TextFile> bundle, List<Violation> violations) {
        Map<TextFile, Map<String, TextFiles.Entry>> entries = new LinkedHashMap<>();
        Set<String> allKeys = new TreeSet<>();
        for (TextFile file : bundle) {
            Map<String, TextFiles.Entry> byKey = new LinkedHashMap<>();
            TextFiles.properties(file.lines()).forEach(entry -> byKey.putIfAbsent(entry.key(), entry));
            entries.put(file, byKey);
            allKeys.addAll(byKey.keySet());
        }

        // Ключ есть в одном языке и отсутствует в другом: пользователь увидит код сообщения вместо текста
        entries.forEach((file, byKey) -> {
            List<String> missing = allKeys.stream().filter(key -> !byKey.containsKey(key)).toList();
            if (!missing.isEmpty()) {
                violations.add(violation(file.path(),
                        "В '" + file.name() + "' нет сообщений, которые есть в других языках (" + missing.size() + "): "
                                + String.join(", ", missing.subList(0, Math.min(SHOWN_KEYS, missing.size())))
                                + (missing.size() > SHOWN_KEYS ? " и другие" : "")
                                + "; для этого языка вместо текста будет показан ключ либо ошибка"));
            }
        });

        // Одно и то же сообщение ждет разные аргументы: часть значений в одном из языков потеряется
        for (String key : allKeys) {
            Set<String> expected = null;
            String expectedIn = "";
            for (Map.Entry<TextFile, Map<String, TextFiles.Entry>> file : entries.entrySet()) {
                TextFiles.Entry entry = file.getValue().get(key);
                if (entry == null) {
                    continue;
                }
                Set<String> arguments = argumentsOf(entry.value());
                if (expected == null) {
                    expected = arguments;
                    expectedIn = file.getKey().name();
                } else if (!expected.equals(arguments)) {
                    violations.add(violation(file.getKey().path(), entry.line(),
                            "Сообщение '" + key + "' использует подстановки " + arguments + ", а в '" + expectedIn
                                    + "' - " + expected + ": в одном из языков часть значений не выведется;"
                                    + " приведите подстановки к одному набору"));
                }
            }
        }
    }

    private Set<String> argumentsOf(String value) {
        Set<String> arguments = new LinkedHashSet<>();
        Matcher matcher = ARGUMENT.matcher(value);
        while (matcher.find()) {
            arguments.add("{" + matcher.group(1) + "}");
        }
        return new TreeSet<>(arguments);
    }
}
