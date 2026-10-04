package ru.akvine.zond.services;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import ru.akvine.zond.config.RuleSettings;
import ru.akvine.zond.enums.ErrorLevel;
import ru.akvine.zond.loaders.ConfigLoader;
import ru.akvine.zond.loaders.SourceLoader;
import ru.akvine.zond.loaders.TextFileLoader;
import ru.akvine.zond.models.ConfigFile;
import ru.akvine.zond.models.LoadResult;
import ru.akvine.zond.models.ScanContext;
import ru.akvine.zond.models.ScanOptions;
import ru.akvine.zond.models.ScanResult;
import ru.akvine.zond.models.TextFile;
import ru.akvine.zond.models.Violation;
import ru.akvine.zond.rules.ConfigRule;
import ru.akvine.zond.rules.ContextRule;
import ru.akvine.zond.rules.ProjectRule;
import ru.akvine.zond.rules.Rule;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;

@Service
@RequiredArgsConstructor
public class Scanner {
    private final SourceLoader sourceLoader;
    private final ConfigLoader configLoader;
    private final TextFileLoader textFileLoader;
    private final List<Rule> rules;
    private final ScanProgressListener progressListener;
    private final RuleSettings ruleSettings;

    public ScanResult scan(Path root) {
        return scan(root, ScanOptions.defaults());
    }

    public ScanResult scan(Path root, ScanOptions options) {
        // Файлы, исключенные настройками, отсеиваются до разбора
        LoadResult loaded = sourceLoader.load(
                root, file -> options.includes(root, file), options.classpath(), options.threadCount());
        List<ConfigFile> configFiles = configLoader.load(root, file -> options.includes(root, file));
        List<TextFile> textFiles = textFileLoader.load(root, file -> options.includes(root, file));
        ScanContext context = new ScanContext(root, loaded.sources(), configFiles, textFiles);

        // Правила идут по номеру кода, чтобы прогресс шел предсказуемо
        List<Rule> enabledRules = rules.stream().filter(Rule::enabled).toList();
        List<Rule> activeRules = enabledRules.stream()
                .filter(rule -> options.allows(rule.code(), rule.name(), levelOf(rule)))
                .sorted(RuleCatalog.BY_CODE)
                .toList();

        List<Violation> violations = new ArrayList<>();
        for (List<Violation> found : run(activeRules, context, options.threadCount())) {
            violations.addAll(found);
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
                loaded.sources().size() + configFiles.size() + textFiles.size(),
                activeRules.size(),
                enabledRules.size() - activeRules.size(),
                violations,
                found - violations.size(),
                options.skipTests(),
                loaded.failedFiles());
    }

    /**
     * @return находки каждого правила в порядке самих правил: от числа потоков итог не зависит
     */
    private List<List<Violation>> run(List<Rule> activeRules, ScanContext context, int threads) {
        AtomicInteger started = new AtomicInteger();
        if (threads <= 1) {
            return activeRules.stream().map(rule -> run(rule, context, started, activeRules.size())).toList();
        }

        ExecutorService executor = Executors.newFixedThreadPool(threads);
        try {
            List<Future<List<Violation>>> futures = new ArrayList<>();
            for (Rule rule : activeRules) {
                futures.add(executor.submit(() -> run(rule, context, started, activeRules.size())));
            }
            List<List<Violation>> results = new ArrayList<>();
            for (Future<List<Violation>> future : futures) {
                results.add(await(future));
            }
            return results;
        } finally {
            executor.shutdownNow();
        }
    }

    private List<Violation> run(Rule rule, ScanContext context, AtomicInteger started, int total) {
        progressListener.onRuleStarted(started.incrementAndGet(), total, rule);
        return withLevel(levelOf(rule), apply(rule, context));
    }

    // Ошибку правила отдаем наружу такой же, какой она была бы при работе в один поток
    private List<Violation> await(Future<List<Violation>> future) {
        try {
            return future.get();
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Сканирование прервано", exception);
        } catch (ExecutionException exception) {
            if (exception.getCause() instanceof RuntimeException failure) {
                throw failure;
            }
            throw new IllegalStateException("Правило завершилось с ошибкой", exception.getCause());
        }
    }

    // Уровень правила можно переопределить в настройках: zond.rule.jr-36.level=INFO
    private ErrorLevel levelOf(Rule rule) {
        return ruleSettings.level(rule.code(), rule.name()).orElseGet(rule::errorLevel);
    }

    // Правило проставляет находкам свой уровень - заменяем его действующим
    private List<Violation> withLevel(ErrorLevel level, List<Violation> violations) {
        return violations.stream()
                .map(violation -> violation.errorLevel() == level ? violation : new Violation(
                        level,
                        violation.errorType(),
                        violation.ruleCode(),
                        violation.ruleName(),
                        violation.file(),
                        violation.line(),
                        violation.message()))
                .toList();
    }

    // Правило проверяет либо все загруженное сразу, либо файлы настроек, либо проект целиком,
    // либо каждый Java-файл по отдельности
    private List<Violation> apply(Rule rule, ScanContext context) {
        if (rule instanceof ContextRule contextRule) {
            return contextRule.checkContext(context);
        }
        if (rule instanceof ConfigRule configRule) {
            return context.configFiles().stream().flatMap(file -> configRule.checkConfig(file).stream()).toList();
        }
        if (rule instanceof ProjectRule projectRule) {
            return projectRule.checkProject(context.sources());
        }
        return context.sources().stream().flatMap(source -> rule.check(source).stream()).toList();
    }
}
