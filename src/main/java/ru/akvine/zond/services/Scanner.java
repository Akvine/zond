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
import java.util.List;

@Service
@RequiredArgsConstructor
public class Scanner {
    private final SourceLoader sourceLoader;
    private final List<Rule> rules;

    public ScanResult scan(Path root) {
        LoadResult loaded = sourceLoader.load(root);
        List<Rule> activeRules = rules.stream().filter(Rule::enabled).toList();

        List<Violation> violations = new ArrayList<>();
        for (SourceFile source : loaded.sources()) {
            for (Rule rule : activeRules) {
                violations.addAll(rule.check(source));
            }
        }
        return new ScanResult(
                root, loaded.sources().size(), activeRules.size(), violations, loaded.failedFiles());
    }
}
