package ru.akvine.zond.rules.flow;

import com.github.javaparser.ast.Node;
import com.github.javaparser.ast.body.CallableDeclaration;
import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration;
import com.github.javaparser.ast.body.ConstructorDeclaration;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.body.Parameter;
import com.github.javaparser.ast.body.TypeDeclaration;
import com.github.javaparser.ast.expr.AnnotationExpr;
import com.github.javaparser.ast.expr.MethodCallExpr;
import ru.akvine.zond.enums.Confidence;
import ru.akvine.zond.models.SourceFile;
import ru.akvine.zond.rules.support.CallGraph;
import ru.akvine.zond.rules.support.TestClasses;
import ru.akvine.zond.rules.support.Types;

import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Анализ потока данных по всему проекту: значения переменных прослеживаются внутри метода и через вызовы.
 * Считается один раз на сканирование - правила разбирают готовые находки по видам.
 * <p>
 * Через вызов значение проходит по сводке метода: что он возвращает, к каким параметрам обращается без
 * проверки, бросает ли исключение всегда. Сводка считается при первом обращении и запоминается, поэтому
 * глубина цепочки вызовов не ограничена.
 */
public final class FlowAnalysis implements FlowInterpreter.Host {
    private static final String BOOLEAN_TYPE = "boolean";
    private static final Set<String> NULLABLE_ANNOTATIONS = Set.of("Nullable", "CheckForNull");
    private static final Set<String> NON_NULL_ANNOTATIONS = Set.of("NonNull", "Nonnull");

    // Проверять, что вернет метод для null, имеет смысл только у коротких методов-проверок
    private static final int MAX_CHECKED_PARAMETERS = 3;

    private static WeakReference<List<SourceFile>> cachedSources = new WeakReference<>(null);
    private static FlowAnalysis cached;

    public enum Kind {
        NULL_DEREFERENCE,
        POSSIBLE_NULL_DEREFERENCE,
        NULL_ARGUMENT,
        CONSTANT_CONDITION,
        UNREACHABLE_CODE,
        DIVISION_BY_ZERO,
        INDEX_OUT_OF_BOUNDS,
        EMPTY_OPTIONAL
    }

    /**
     * Находка анализа, еще не привязанная к правилу
     */
    public record Finding(Kind kind, Node node, String message, Confidence confidence) {

        /**
         * Находка, уверенность которой определяет правило
         */
        public Finding(Kind kind, Node node, String message) {
            this(kind, node, message, null);
        }
    }

    /**
     * Находка вместе с файлом, в котором она сделана
     */
    public record Located(SourceFile file, Finding finding) {
    }

    private final CallGraph callGraph;
    private final Map<MethodDeclaration, FlowSummary> summaries = new IdentityHashMap<>();
    private final Set<MethodDeclaration> inProgress = new HashSet<>();
    // Имя и число параметров -> сколько таких методов в проекте
    private final Map<String, Integer> signatures = new HashMap<>();
    private final List<Located> findings = new ArrayList<>();
    // Имена методов проекта, помеченных @Nullable либо @NonNull: только их вызовы стоит разрешать ради аннотации
    private final Set<String> annotatedMethods = new HashSet<>();
    private final Map<MethodCallExpr, Optional<FlowSummary>> resolved = new IdentityHashMap<>();

    public static synchronized FlowAnalysis of(List<SourceFile> sources) {
        if (cachedSources.get() != sources) {
            cached = new FlowAnalysis(sources);
            cachedSources = new WeakReference<>(sources);
        }
        return cached;
    }

    private FlowAnalysis(List<SourceFile> sources) {
        this.callGraph = CallGraph.of(sources);
        for (SourceFile source : sources) {
            for (MethodDeclaration method : source.unit().findAll(MethodDeclaration.class)) {
                signatures.merge(signature(method), 1, Integer::sum);
                if (annotatedReturn(method) != null) {
                    annotatedMethods.add(method.getNameAsString());
                }
            }
        }
        for (SourceFile source : sources) {
            analyze(source);
        }
    }

    public List<Located> findings(Set<Kind> kinds) {
        return findings.stream().filter(located -> kinds.contains(located.finding().kind())).toList();
    }

    private void analyze(SourceFile source) {
        List<CallableDeclaration<?>> callables = new ArrayList<>(source.unit().findAll(MethodDeclaration.class));
        callables.addAll(source.unit().findAll(ConstructorDeclaration.class));
        // Одно и то же место проходится несколько раз (ветка finally, лямбда) - находка нужна одна
        Set<String> seen = new HashSet<>();
        for (CallableDeclaration<?> callable : callables) {
            // В тестах null передают нарочно, проверяя поведение
            if (TestClasses.isInside(callable)) {
                continue;
            }
            FlowInterpreter interpreter = new FlowInterpreter(this, true);
            try {
                interpreter.run(callable, Map.of());
            } catch (RuntimeException exception) {
                // Конструкция, которую интерпретатор не понял: по этому методу находок не будет
                continue;
            }
            for (Finding finding : interpreter.findings()) {
                String key = finding.kind() + "@" + finding.node().getRange().map(Object::toString).orElse("");
                if (seen.add(key)) {
                    findings.add(new Located(source, finding));
                }
            }
        }
    }

    @Override
    public Optional<FlowSummary> summaryOf(MethodCallExpr call) {
        Optional<FlowSummary> known = resolved.get(call);
        if (known != null) {
            return known;
        }
        Optional<FlowSummary> summary = bodySummary(call);
        // Аннотация - обещание автора, она важнее того, что видно в теле, и действует для переопределяемых методов
        if (annotatedMethods.contains(call.getNameAsString())) {
            FlowValue annotated = Types.declaration(call).map(this::annotatedReturn).orElse(null);
            if (annotated != null) {
                summary = Optional.of(summary.orElseGet(FlowSummary::unknown).withReturned(annotated));
            }
        }
        resolved.put(call, summary);
        return summary;
    }

    /**
     * @return что обещает аннотация метода о его результате; null, если аннотации нет
     */
    private FlowValue annotatedReturn(MethodDeclaration method) {
        for (AnnotationExpr annotation : method.getAnnotations()) {
            String name = annotation.getName().getIdentifier();
            if (NULLABLE_ANNOTATIONS.contains(name)) {
                return FlowValue.maybeNull("метод помечен @Nullable", method);
            }
            if (NON_NULL_ANNOTATIONS.contains(name)) {
                return FlowValue.notNull("метод помечен @NonNull", method);
            }
        }
        return null;
    }

    private Optional<FlowSummary> bodySummary(MethodCallExpr call) {
        List<MethodDeclaration> targets = callGraph.targetsOf(call);
        if (targets.size() != 1) {
            return Optional.empty();
        }
        MethodDeclaration target = targets.get(0);
        boolean isSameArity = target.getParameters().size() == call.getArguments().size()
                && target.getParameters().stream().noneMatch(Parameter::isVarArgs);
        if (!isSameArity || target.getBody().isEmpty() || !isFinalBehavior(target)) {
            return Optional.empty();
        }
        return Optional.of(summary(target));
    }

    // Сводка верна, только если при вызове выполнится именно это тело: метод нельзя переопределить
    // либо в проекте нет другого метода с тем же именем и числом параметров
    private boolean isFinalBehavior(MethodDeclaration method) {
        if (method.isPrivate() || method.isStatic() || method.isFinal()) {
            return true;
        }
        Optional<TypeDeclaration<?>> type = method.findAncestor(TypeDeclaration.class).map(found -> (TypeDeclaration<?>) found);
        boolean isFinalType = type.isPresent() && (type.get().isRecordDeclaration() || type.get().isEnumDeclaration()
                || type.get() instanceof ClassOrInterfaceDeclaration declaration && declaration.isFinal());
        return isFinalType || signatures.getOrDefault(signature(method), 0) == 1;
    }

    private String signature(MethodDeclaration method) {
        return method.getNameAsString() + "/" + method.getParameters().size();
    }

    private FlowSummary summary(MethodDeclaration method) {
        FlowSummary known = summaries.get(method);
        if (known != null) {
            return known;
        }
        // Рекурсия: пока метод разбирается, о нем ничего не известно
        if (!inProgress.add(method)) {
            return FlowSummary.unknown();
        }
        FlowSummary summary;
        try {
            summary = compute(method);
        } catch (RuntimeException exception) {
            summary = FlowSummary.unknown();
        } finally {
            inProgress.remove(method);
        }
        summaries.put(method, summary);
        return summary;
    }

    private FlowSummary compute(MethodDeclaration method) {
        FlowSummary base = new FlowInterpreter(this, false).run(method, Map.of());
        boolean isCheck = BOOLEAN_TYPE.equals(method.getType().asString())
                && method.getParameters().size() <= MAX_CHECKED_PARAMETERS;
        if (!isCheck) {
            return base;
        }

        // Метод-проверка вида isBlank(x): узнаем, что он вернет, если параметр равен null
        Map<Integer, Boolean> resultForNull = new HashMap<>();
        for (int index = 0; index < method.getParameters().size(); index++) {
            Parameter parameter = method.getParameter(index);
            if (parameter.getType().isPrimitiveType()) {
                continue;
            }
            FlowValue returned = new FlowInterpreter(this, false)
                    .run(method, FlowInterpreter.nullParameter(index, parameter))
                    .returned();
            if (returned.isConstant(1) || returned.isConstant(0)) {
                resultForNull.put(index, returned.isConstant(1));
            }
        }
        return base.withResultForNullParam(resultForNull);
    }
}
