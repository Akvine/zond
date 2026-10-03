package ru.akvine.zond.services;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import ru.akvine.zond.loaders.SourceLoader;
import ru.akvine.zond.models.LoadResult;
import ru.akvine.zond.models.ScanResult;
import ru.akvine.zond.models.SourceFile;
import ru.akvine.zond.models.Violation;
import ru.akvine.zond.rules.Rule;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

@Service
@RequiredArgsConstructor
public class Scanner {
    private static final String CODE_SEPARATOR = ":";

    private final SourceLoader sourceLoader;
    private final List<Rule> rules;
    private final ScanProgressListener progressListener;

    public ScanResult scan(Path root) {
        LoadResult loaded = sourceLoader.load(root);

        // Spring отдает правила в произвольном порядке - выстраиваем по номеру, чтобы прогресс шел предсказуемо
        List<Rule> activeRules = rules.stream()
                .filter(Rule::enabled)
                .sorted(Comparator.comparingInt(rule -> codeNumber(rule.code())))
                .toList();

        List<Violation> violations = new ArrayList<>();
        for (int index = 0; index < activeRules.size(); index++) {
            Rule rule = activeRules.get(index);
            progressListener.onRuleStarted(index + 1, activeRules.size(), rule);
            for (SourceFile source : loaded.sources()) {
                violations.addAll(rule.check(source));
            }
        }

        // Проверка идет по правилам, а читать отчет удобнее по файлам
        violations.sort(Comparator.comparing((Violation violation) -> violation.file().toString())
                .thenComparingInt(Violation::line));
        return new ScanResult(
                root, loaded.sources().size(), activeRules.size(), violations, loaded.failedFiles());
    }

    // jr:12 -> 12; код без номера уходит в конец
    private int codeNumber(String code) {
        try {
            return Integer.parseInt(code.substring(code.lastIndexOf(CODE_SEPARATOR) + 1));
        } catch (NumberFormatException exception) {
            return Integer.MAX_VALUE;
        }
    }
}
