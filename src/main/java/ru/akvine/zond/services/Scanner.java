package ru.akvine.zond.services;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import ru.akvine.zond.loaders.ConfigLoader;
import ru.akvine.zond.loaders.SourceLoader;
import ru.akvine.zond.models.ConfigFile;
import ru.akvine.zond.models.LoadResult;
import ru.akvine.zond.models.ScanOptions;
import ru.akvine.zond.models.ScanResult;
import ru.akvine.zond.models.SourceFile;
import ru.akvine.zond.models.Violation;
import ru.akvine.zond.rules.ConfigRule;
import ru.akvine.zond.rules.ProjectRule;
import ru.akvine.zond.rules.Rule;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

@Service
@RequiredArgsConstructor
public class Scanner {
    private final SourceLoader sourceLoader;
    private final ConfigLoader configLoader;
    private final List<Rule> rules;
    private final ScanProgressListener progressListener;

    public ScanResult scan(Path root) {
        return scan(root, ScanOptions.defaults());
    }

    public ScanResult scan(Path root, ScanOptions options) {
        // Файлы, исключенные настройками, отсеиваются до разбора
        LoadResult loaded = sourceLoader.load(root, file -> options.includes(root, file));
        List<ConfigFile> configFiles = configLoader.load(root, file -> options.includes(root, file));

        // Правила идут по номеру кода, чтобы прогресс шел предсказуемо
        List<Rule> enabledRules = rules.stream().filter(Rule::enabled).toList();
        List<Rule> activeRules = enabledRules.stream()
                .filter(rule -> options.allows(rule.code(), rule.name(), rule.errorLevel()))
                .sorted(RuleCatalog.BY_CODE)
                .toList();

        List<Violation> violations = new ArrayList<>();
        for (int index = 0; index < activeRules.size(); index++) {
            Rule rule = activeRules.get(index);
            progressListener.onRuleStarted(index + 1, activeRules.size(), rule);
            violations.addAll(apply(rule, loaded.sources(), configFiles));
        }

        // Находки, которые в самом коде помечены комментарием zond:ignore
        Suppressions suppressions = new Suppressions();
        int found = violations.size();
        violations.removeIf(suppressions::isSuppressed);

        // Проверка идет по правилам, а читать отчет удобнее по файлам
        violations.sort(Comparator.comparing((Violation violation) -> violation.file().toString())
                .thenComparingInt(Violation::line));
        return new ScanResult(
                root,
                loaded.sources().size() + configFiles.size(),
                activeRules.size(),
                enabledRules.size() - activeRules.size(),
                violations,
                found - violations.size(),
                options.skipTests(),
                loaded.failedFiles());
    }

    // Правило проверяет либо файлы настроек, либо проект целиком, либо каждый Java-файл по отдельности
    private List<Violation> apply(Rule rule, List<SourceFile> sources, List<ConfigFile> configFiles) {
        if (rule instanceof ConfigRule configRule) {
            return configFiles.stream().flatMap(file -> configRule.checkConfig(file).stream()).toList();
        }
        if (rule instanceof ProjectRule projectRule) {
            return projectRule.checkProject(sources);
        }
        return sources.stream().flatMap(source -> rule.check(source).stream()).toList();
    }
}

