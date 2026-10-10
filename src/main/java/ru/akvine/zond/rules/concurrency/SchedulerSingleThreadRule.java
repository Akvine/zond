package ru.akvine.zond.rules.concurrency;

import com.github.javaparser.ast.body.MethodDeclaration;
import org.springframework.stereotype.Component;
import ru.akvine.zond.enums.ErrorLevel;
import ru.akvine.zond.enums.ErrorType;
import ru.akvine.zond.models.ConfigProperty;
import ru.akvine.zond.models.ScanContext;
import ru.akvine.zond.models.SourceFile;
import ru.akvine.zond.models.Violation;
import ru.akvine.zond.rules.AbstractContextRule;
import ru.akvine.zond.rules.RuleCodes;
import ru.akvine.zond.rules.support.Annotations;
import ru.akvine.zond.rules.support.ProjectWords;
import ru.akvine.zond.rules.support.TestClasses;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Планировщик Spring по умолчанию работает в один поток: все @Scheduled-задачи приложения стоят в одной
 * очереди, и долгая задача задерживает остальные.
 */
@Component
public class SchedulerSingleThreadRule extends AbstractContextRule {
    private static final Set<String> SCHEDULED = Set.of("Scheduled", "Schedules");
    private static final String ASYNC = "Async";
    private static final int ENOUGH_TASKS = 2;
    private static final String SOURCE_ROOT = "/src/";

    // Свой планировщик либо свой пул: число потоков задано там
    private static final Set<String> OWN_SCHEDULER = Set.of(
            "ThreadPoolTaskScheduler", "SchedulingConfigurer", "TaskScheduler", "ScheduledExecutorService",
            "ScheduledThreadPoolExecutor", "newScheduledThreadPool", "SimpleAsyncTaskScheduler",
            "ConcurrentTaskScheduler", "setScheduler");
    private static final String POOL_SIZE = "spring.task.scheduling.pool.size";
    private static final String VIRTUAL_THREADS = "spring.threads.virtual.enabled";
    private static final String TRUE = "true";
    private static final String SINGLE = "1";

    private record Task(SourceFile source, MethodDeclaration method) {
    }

    @Override
    public String code() {
        return RuleCodes.SCHEDULER_SINGLE_THREAD_RULE_CODE;
    }

    @Override
    public String description() {
        return "Сканирует проект и ищет несколько @Scheduled-задач при планировщике в один поток";
    }

    @Override
    public List<Violation> checkContext(ScanContext context) {
        // Задачи разных модулей работают в разных приложениях и друг другу не мешают
        Map<String, List<Task>> byModule = new LinkedHashMap<>();
        for (SourceFile source : context.sources()) {
            for (MethodDeclaration method : source.unit().findAll(MethodDeclaration.class)) {
                // @Async уводит задачу в другой пул: очередь планировщика она не занимает
                if (Annotations.hasAny(method, SCHEDULED) && !Annotations.has(method, ASYNC) && !TestClasses.isInside(method)) {
                    byModule.computeIfAbsent(moduleOf(source), module -> new ArrayList<>()).add(new Task(source, method));
                }
            }
        }
        if (byModule.isEmpty() || ProjectWords.hasAny(context.sources(), OWN_SCHEDULER) || hasPool(context)) {
            return List.of();
        }
        List<Violation> violations = new ArrayList<>();
        for (List<Task> tasks : byModule.values()) {
            if (tasks.size() < ENOUGH_TASKS) {
                continue;
            }
            Task first = tasks.get(0);
            violations.add(violation(first.source(), first.method(),
                    "В приложении " + tasks.size() + " задач @Scheduled, а планировщик по умолчанию работает в один"
                            + " поток: пока выполняется одна задача, остальные ждут, и долгая задача (или зависший"
                            + " сетевой вызов) останавливает все расписание; задайте"
                            + " spring.task.scheduling.pool.size либо объявите свой ThreadPoolTaskScheduler"));
        }
        return violations;
    }

    // Модуль - все, что лежит до каталога src: у каждого модуля свое приложение со своим планировщиком
    private String moduleOf(SourceFile source) {
        String path = source.path().toString().replace('\\', '/');
        int sources = path.lastIndexOf(SOURCE_ROOT);
        return sources < 0 ? "" : path.substring(0, sources);
    }

    @Override
    public ErrorLevel errorLevel() {
        return ErrorLevel.MAJOR;
    }

    @Override
    public ErrorType errorType() {
        return ErrorType.CONCURRENCY;
    }

    private boolean hasPool(ScanContext context) {
        return context.configFiles().stream().anyMatch(file ->
                file.find(POOL_SIZE).map(ConfigProperty::value).filter(value -> !SINGLE.equals(value.trim())).isPresent()
                        || file.find(VIRTUAL_THREADS).map(ConfigProperty::value)
                        .filter(value -> TRUE.equalsIgnoreCase(value.trim())).isPresent());
    }
}
