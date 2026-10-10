package ru.akvine.zond.services;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import ru.akvine.zond.config.RuleSettings;
import ru.akvine.zond.enums.Confidence;
import ru.akvine.zond.enums.ErrorLevel;
import ru.akvine.zond.enums.FileKind;
import ru.akvine.zond.loaders.ConfigLoader;
import ru.akvine.zond.loaders.SourceLoader;
import ru.akvine.zond.loaders.TextFileLoader;
import ru.akvine.zond.models.ConfigFile;
import ru.akvine.zond.models.LoadResult;
import ru.akvine.zond.models.RuleTiming;
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
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;

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
        // Виды файлов, отключенные настройками zond.scan.*, в проверку не попадают
        List<ConfigFile> configFiles = options.scans(FileKind.CONFIG)
                ? configLoader.load(root, file -> options.includes(root, file))
                : List.of();
        List<TextFile> textFiles = textFileLoader.load(root, file -> options.includes(root, file)).stream()
                .filter(file -> options.scans(file.type().getKind()))
                .toList();
        ScanContext context = new ScanContext(root, loaded.sources(), configFiles, textFiles);
        // Правила, которым нужен проект целиком (граф вызовов, поиск неиспользуемого, сверка со схемой БД),
        // получают его весь и при проверке только измененных файлов: иначе выводы были бы неверными. Правила,
        // которые смотрят файл сам по себе, запускаются только на измененных
        ScanContext perFile = options.changedOnly()
                ? new ScanContext(
                        root,
                        loaded.sources().stream().filter(source -> options.isChanged(source.path())).toList(),
                        configFiles.stream().filter(file -> options.isChanged(file.path())).toList(),
                        textFiles)
                : context;

        // Правила идут по номеру кода, чтобы прогресс шел предсказуемо
        List<Rule> enabledRules = rules.stream().filter(Rule::enabled).toList();
        List<Rule> activeRules = enabledRules.stream()
                .filter(rule -> options.allows(rule.code(), rule.name(), levelOf(rule)))
                .sorted(RuleCatalog.BY_CODE)
                .toList();

        progressListener.onScanStarted(activeRules, options);
        List<Violation> violations = new ArrayList<>();
        Map<Rule, Long> spent = new ConcurrentHashMap<>();
        // Отсчет времени проверки начинается здесь: код к этому моменту уже загружен и разобран
        long checkStartedAt = System.nanoTime();
        for (List<Violation> found : run(activeRules, context, perFile, options.threadCount(), spent)) {
            violations.addAll(found);
        }

        long checkNanos = System.nanoTime() - checkStartedAt;

        // Просили только измененные файлы: находки в остальных не показываются
        if (options.changedOnly()) {
            violations.removeIf(violation -> !options.isChanged(violation.file()));
        }

        // Копии исходников и сгенерированный код в каталогах сборки читаются - по ним видно, что класс или
        // метод используется, - но находки в них не показываются: править там нечего, а копия дала бы
        // каждую находку дважды
        violations.removeIf(violation -> options.isBuildOutput(root, violation.file()));

        // В тестовом коде остаются находки только тех правил, которые тесты и проверяют
        if (options.testRulesOnly()) {
            Set<String> forTests = activeRules.stream().filter(Rule::appliesToTests).map(Rule::code).collect(Collectors.toSet());
            violations.removeIf(violation -> !forTests.contains(violation.ruleCode()) && options.isTestFile(root, violation.file()));
        }

        // Находки, которые в самом коде помечены комментарием zond:ignore
        Suppressions suppressions = new Suppressions();
        int found = violations.size();
        violations.removeIf(suppressions::isSuppressed);

        // Находки, в которых анализатор уверен меньше заданного порога
        int beforeConfidence = violations.size();
        violations.removeIf(violation -> !violation.confidenceOrDefault().isAtLeast(options.minConfidence()));
        int lowConfidence = beforeConfidence - violations.size();

        // Проверка идет по правилам, а читать отчет удобнее по файлам
        violations.sort(Comparator.comparing((Violation violation) -> violation.file().toString())
                .thenComparingInt(Violation::line));
        return new ScanResult(
                root,
                loaded.sources().size() + configFiles.size() + textFiles.size(),
                activeRules.size(),
                enabledRules.size() - activeRules.size(),
                violations,
                found - beforeConfidence,
                options.skipTests(),
                loaded.failedFiles(),
                lowConfidence,
                activeRules.stream()
                        .map(rule -> new RuleTiming(rule.code(), rule.name(), spent.getOrDefault(rule, 0L)))
                        .toList(),
                checkNanos,
                options.changedOnly() ? changedCount(options, loaded, configFiles, textFiles) : null);
    }

    // Сколько из прочитанных файлов изменено: по ним и показаны находки
    private int changedCount(ScanOptions options, LoadResult loaded, List<ConfigFile> configFiles, List<TextFile> textFiles) {
        long sources = loaded.sources().stream().filter(source -> options.isChanged(source.path())).count();
        long configs = configFiles.stream().filter(file -> options.isChanged(file.path())).count();
        long texts = textFiles.stream().filter(file -> options.isChanged(file.path())).count();
        return (int) (sources + configs + texts);
    }

    /**
     * @return находки каждого правила в порядке самих правил: от числа потоков итог не зависит
     */
    private List<List<Violation>> run(
            List<Rule> activeRules, ScanContext context, ScanContext perFile, int threads, Map<Rule, Long> spent) {
        AtomicInteger started = new AtomicInteger();
        if (threads <= 1) {
            return activeRules.stream().map(rule -> run(rule, context, perFile, started, activeRules.size(), spent)).toList();
        }

        ExecutorService executor = Executors.newFixedThreadPool(threads);
        try {
            List<Future<List<Violation>>> futures = new ArrayList<>();
            for (Rule rule : activeRules) {
                futures.add(executor.submit(() -> run(rule, context, perFile, started, activeRules.size(), spent)));
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

    private List<Violation> run(
            Rule rule, ScanContext context, ScanContext perFile, AtomicInteger started, int total, Map<Rule, Long> spent) {
        int number = started.incrementAndGet();
        progressListener.onRuleStarted(number, total, rule);
        // Время считается в том же потоке, где работает правило: ожидание в очереди в него не входит
        long startedAt = System.nanoTime();
        try {
            return withLevel(levelOf(rule), rule.confidence(), apply(rule, context, perFile));
        } finally {
            long nanos = System.nanoTime() - startedAt;
            spent.put(rule, nanos);
            progressListener.onRuleFinished(number, total, rule, nanos);
        }
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

    // Правило проставляет находкам свой уровень - заменяем его действующим; находка, для которой правило
    // не уточнило уверенность, получает уверенность самого правила
    private List<Violation> withLevel(ErrorLevel level, Confidence confidence, List<Violation> violations) {
        return violations.stream()
                .map(violation -> violation.errorLevel() == level ? violation : violation.withLevel(level))
                .map(violation -> violation.confidence() == null ? violation.withConfidence(confidence) : violation)
                .toList();
    }

    // Правило проверяет либо все загруженное сразу, либо файлы настроек, либо проект целиком,
    // либо каждый Java-файл по отдельности
    private List<Violation> apply(Rule rule, ScanContext context, ScanContext perFile) {
        if (rule instanceof ContextRule contextRule) {
            return contextRule.checkContext(context);
        }
        if (rule instanceof ConfigRule configRule) {
            return perFile.configFiles().stream().flatMap(file -> configRule.checkConfig(file).stream()).toList();
        }
        if (rule instanceof ProjectRule projectRule) {
            return projectRule.checkProject(context.sources());
        }
        return perFile.sources().stream().flatMap(source -> rule.check(source).stream()).toList();
    }
}
