package ru.akvine.zond.rules.concurrency;

import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.expr.AnnotationExpr;
import com.github.javaparser.ast.expr.Expression;
import org.springframework.stereotype.Component;
import ru.akvine.zond.enums.ErrorLevel;
import ru.akvine.zond.enums.ErrorType;
import ru.akvine.zond.models.ScanContext;
import ru.akvine.zond.models.SourceFile;
import ru.akvine.zond.models.Violation;
import ru.akvine.zond.rules.AbstractRule;
import ru.akvine.zond.rules.ContextRule;
import ru.akvine.zond.rules.RuleCodes;
import ru.akvine.zond.rules.support.Annotations;
import ru.akvine.zond.rules.support.LocalTypes;

import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * {@code @Async} без имени пула - находка, только пока исполнитель по умолчанию никто не настроил. Когда проект
 * задает его сам (AsyncConfigurer, бин taskExecutor, единственный бин-исполнитель, свойства
 * spring.task.execution.*), задачи без имени уходят в этот настроенный пул, и указывать его в каждой аннотации
 * незачем
 */
@Component
public class AsyncWithoutExecutorRule extends AbstractRule implements ContextRule {
    private static final String ASYNC = "Async";
    private static final String VALUE = "value";
    private static final String BEAN = "Bean";
    private static final Set<String> CONFIGURERS = Set.of("AsyncConfigurer", "AsyncConfigurerSupport");
    // Бин с таким именем Spring берет исполнителем по умолчанию
    private static final Set<String> DEFAULT_NAMES = Set.of("taskExecutor", "applicationTaskExecutor");
    // Единственный бин такого типа тоже становится исполнителем по умолчанию
    private static final Set<String> TASK_EXECUTORS = Set.of(
            "TaskExecutor", "AsyncTaskExecutor", "ThreadPoolTaskExecutor", "SimpleAsyncTaskExecutor",
            "ConcurrentTaskExecutor", "VirtualThreadTaskExecutor", "DelegatingSecurityContextAsyncTaskExecutor");
    private static final String EXECUTION_PROPERTIES = "spring.task.execution.";

    @Override
    public String code() {
        return RuleCodes.ASYNC_WITHOUT_EXECUTOR_RULE_CODE;
    }

    @Override
    public String description() {
        return "Сканирует проект и ищет @Async без указания executor там, где исполнитель по умолчанию не настроен";
    }

    @Override
    public List<Violation> checkContext(ScanContext context) {
        boolean configuredByProperties = context.configFiles().stream()
                .flatMap(file -> file.properties().stream())
                .anyMatch(property -> property.key().startsWith(EXECUTION_PROPERTIES));
        if (configuredByProperties || hasDefaultExecutor(context.sources())) {
            return List.of();
        }
        return context.sources().stream().flatMap(sourceFile -> find(sourceFile).stream()).toList();
    }

    // Один файл без остального проекта: так правило вызывают для отдельного класса
    @Override
    public List<Violation> check(SourceFile sourceFile) {
        return hasDefaultExecutor(List.of(sourceFile)) ? List.of() : find(sourceFile);
    }

    @Override
    public ErrorLevel errorLevel() {
        return ErrorLevel.MAJOR;
    }

    @Override
    public ErrorType errorType() {
        return ErrorType.CONCURRENCY;
    }

    private List<Violation> find(SourceFile sourceFile) {
        return sourceFile.findAll(AnnotationExpr.class).stream()
                .filter(annotation -> ASYNC.equals(annotation.getName().getIdentifier()))
                .filter(annotation -> !hasExecutor(annotation))
                .map(annotation -> violation(sourceFile, annotation,
                        "@Async без имени executor: задачи уйдут в исполнитель по умолчанию, общий для всего"
                                + " приложения, а без настроенного пула - в SimpleAsyncTaskExecutor, который создает"
                                + " новый поток на каждый вызов; укажите пул явно: @Async(\"имяExecutor\")"))
                .toList();
    }

    private boolean hasDefaultExecutor(List<SourceFile> sourceFiles) {
        int executors = 0;
        for (SourceFile sourceFile : sourceFiles) {
            for (ClassOrInterfaceDeclaration type : sourceFile.findAll(ClassOrInterfaceDeclaration.class)) {
                boolean configurer = type.getImplementedTypes().stream()
                        .anyMatch(parent -> CONFIGURERS.contains(parent.getNameAsString()))
                        || type.getExtendedTypes().stream()
                        .anyMatch(parent -> CONFIGURERS.contains(parent.getNameAsString()));
                if (configurer) {
                    return true;
                }
            }
            for (MethodDeclaration method : sourceFile.findAll(MethodDeclaration.class)) {
                Optional<AnnotationExpr> bean = Annotations.find(method, BEAN);
                if (bean.isEmpty()) {
                    continue;
                }
                boolean namedAsDefault = DEFAULT_NAMES.contains(method.getNameAsString())
                        || DEFAULT_NAMES.stream().anyMatch(name -> bean.get().toString().contains("\"" + name + "\""));
                if (namedAsDefault) {
                    return true;
                }
                if (TASK_EXECUTORS.contains(LocalTypes.typeName(method.getType()))) {
                    executors++;
                }
            }
        }
        return executors == 1;
    }

    // @Async("pool") либо @Async(value = "pool"); пустая строка означает исполнитель по умолчанию
    private boolean hasExecutor(AnnotationExpr annotation) {
        Optional<Expression> value = Optional.empty();
        if (annotation.isSingleMemberAnnotationExpr()) {
            value = Optional.of(annotation.asSingleMemberAnnotationExpr().getMemberValue());
        } else if (annotation.isNormalAnnotationExpr()) {
            value = annotation.asNormalAnnotationExpr().getPairs().stream()
                    .filter(pair -> VALUE.equals(pair.getNameAsString()))
                    .map(pair -> pair.getValue())
                    .findFirst();
        }
        return value
                .filter(executor -> !executor.isStringLiteralExpr() || !executor.asStringLiteralExpr().asString().isBlank())
                .isPresent();
    }
}
