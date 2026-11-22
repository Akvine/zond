package ru.akvine.zond.rules.concurrency;

import com.github.javaparser.ast.expr.AnnotationExpr;
import com.github.javaparser.ast.expr.ObjectCreationExpr;
import org.springframework.stereotype.Component;
import ru.akvine.zond.enums.ErrorLevel;
import ru.akvine.zond.enums.ErrorType;
import ru.akvine.zond.models.SourceFile;
import ru.akvine.zond.models.TestPaths;
import ru.akvine.zond.models.Violation;
import ru.akvine.zond.rules.AbstractRule;
import ru.akvine.zond.rules.ProjectRule;
import ru.akvine.zond.rules.RuleCodes;
import ru.akvine.zond.rules.support.Modules;
import ru.akvine.zond.rules.support.TestClasses;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * {@code InheritableThreadLocal} передает значение потоку-потомку один раз - в момент его создания. Поток пула
 * создается при первой задаче и потом живет долго: он навсегда уносит значение того запроса, при котором
 * появился, и выполняет с ним чужие задачи. Если в переменной лежит арендатор, пользователь или идентификатор
 * запроса, задача в пуле работает от чужого имени.
 */
@Component
public class InheritableThreadLocalWithPoolRule extends AbstractRule implements ProjectRule {
    private static final String INHERITABLE = "InheritableThreadLocal";
    // Пулы потоков и запуск задач в них
    private static final Set<String> POOL_TYPES = Set.of(
            "ThreadPoolTaskExecutor", "ThreadPoolTaskScheduler", "ThreadPoolExecutor", "ScheduledThreadPoolExecutor",
            "ForkJoinPool");
    private static final Pattern POOL_FACTORY = Pattern.compile(
            "Executors\\.new\\w+|CompletableFuture\\.(supplyAsync|runAsync)|\\.parallelStream\\(\\)");
    // Потоки планировщика (@Scheduled) создаются при запуске, а не из потока запроса: чужого значения они не
    // унаследуют, поэтому признаком служит только @Async
    private static final Set<String> POOL_ANNOTATIONS = Set.of("Async");

    /**
     * Признак пула потоков
     *
     * @param file файл, в котором он найден
     * @param sign чем пул выдал себя: тип, фабрика или аннотация
     */
    private record Pool(Path file, String sign) {
    }

    /**
     * Место, где создана наследуемая переменная потока
     */
    private record Holder(SourceFile file, ObjectCreationExpr creation) {
    }

    @Override
    public String code() {
        return RuleCodes.INHERITABLE_THREAD_LOCAL_WITH_POOL_RULE_CODE;
    }

    @Override
    public String description() {
        return "Сканирует проект с пулами потоков и ищет InheritableThreadLocal: поток пула наследует значение один"
                + " раз, при создании, и выполняет с ним чужие задачи";
    }

    @Override
    public List<Violation> checkProject(List<SourceFile> sourceFiles) {
        List<Holder> holders = new ArrayList<>();
        for (SourceFile sourceFile : sourceFiles) {
            for (ObjectCreationExpr creation : sourceFile.findAll(ObjectCreationExpr.class)) {
                if (INHERITABLE.equals(creation.getType().getNameAsString()) && !TestClasses.isInside(creation)) {
                    holders.add(new Holder(sourceFile, creation));
                }
            }
        }
        if (holders.isEmpty()) {
            return List.of();
        }
        List<Pool> pools = new ArrayList<>();
        for (SourceFile sourceFile : sourceFiles) {
            poolIn(sourceFile).ifPresent(sign -> pools.add(new Pool(sourceFile.path(), sign)));
        }
        Modules modules = Modules.of(sourceFiles);
        List<Violation> violations = new ArrayList<>();
        for (Holder holder : holders) {
            poolFor(holder, pools, modules).ifPresent(pool -> violations.add(violation(holder.file(), holder.creation(),
                    "InheritableThreadLocal в приложении с пулами потоков (" + pool.sign() + "): значение"
                            + " наследуется один раз, когда поток создают, а поток пула живет долго - он"
                            + " уносит значение того запроса, при котором появился, и выполняет с ним чужие"
                            + " задачи, а новые значения до него не доходят. Передавайте значение в задачу"
                            + " явно - аргументом либо оберткой (TaskDecorator), которая ставит его перед"
                            + " выполнением и убирает после")));
        }
        return violations;
    }

    // Пул соседнего приложения из того же хранилища задач этого приложения не выполняет
    private Optional<Pool> poolFor(Holder holder, List<Pool> pools, Modules modules) {
        for (Pool pool : pools) {
            if (!modules.areSeparate(holder.file().path(), pool.file())) {
                return Optional.of(pool);
            }
        }
        return Optional.empty();
    }

    @Override
    public ErrorLevel errorLevel() {
        return ErrorLevel.MAJOR;
    }

    @Override
    public ErrorType errorType() {
        return ErrorType.CONCURRENCY;
    }

    // Первое место в файле, по которому видно, что задачи выполняют потоки пула
    private Optional<String> poolIn(SourceFile sourceFile) {
        Optional<String> created = sourceFile.findAll(ObjectCreationExpr.class).stream()
                .filter(creation -> !TestClasses.isInside(creation))
                .map(creation -> creation.getType().getNameAsString())
                .filter(POOL_TYPES::contains)
                .findFirst();
        if (created.isPresent()) {
            return created;
        }
        Optional<String> annotated = sourceFile.findAll(AnnotationExpr.class).stream()
                .filter(annotation -> !TestClasses.isInside(annotation))
                .map(annotation -> annotation.getNameAsString())
                .filter(POOL_ANNOTATIONS::contains)
                .map(name -> "@" + name)
                .findFirst();
        if (annotated.isPresent()) {
            return annotated;
        }
        Matcher factory = POOL_FACTORY.matcher(sourceFile.text());
        return factory.find() && !TestPaths.isTest(sourceFile.path())
                ? Optional.of(factory.group().replace(".parallelStream()", "parallelStream()"))
                : Optional.empty();
    }
}
