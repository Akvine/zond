package ru.akvine.zond.rules.logical;

import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration;
import com.github.javaparser.ast.body.TypeDeclaration;
import org.springframework.stereotype.Component;
import ru.akvine.zond.enums.ErrorLevel;
import ru.akvine.zond.enums.ErrorType;
import ru.akvine.zond.loaders.ProjectDeclarations;
import ru.akvine.zond.models.ScanContext;
import ru.akvine.zond.models.SourceFile;
import ru.akvine.zond.models.Violation;
import ru.akvine.zond.rules.AbstractRule;
import ru.akvine.zond.rules.ContextRule;
import ru.akvine.zond.rules.ProjectRule;
import ru.akvine.zond.rules.RuleCodes;
import ru.akvine.zond.rules.support.SpringBeans;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.stream.Collectors;

@Component
public class CircularDependencyRule extends AbstractRule implements ProjectRule, ContextRule {
    private static final String ARROW = " -> ";
    // Циклы разрешены настройкой: приложение стартует, о самой настройке говорит jr:395
    private static final String ALLOW_CIRCULAR_REFERENCES = "spring.main.allow-circular-references";
    private static final String TRUE = "true";

    /**
     * Бин и файл, в котором он объявлен
     */
    private record Bean(ClassOrInterfaceDeclaration type, SourceFile sourceFile) {
    }

    private static final String KEY_SEPARATOR = "\u0000";

    @Override
    public String code() {
        return RuleCodes.CIRCULAR_DEPENDENCY_RULE_CODE;
    }

    @Override
    public String description() {
        return "Сканирует проект и ищет циклические зависимости между Spring-бинами";
    }

    @Override
    public List<Violation> check(SourceFile sourceFile) {
        return checkProject(List.of(sourceFile));
    }

    @Override
    public List<Violation> checkContext(ScanContext context) {
        boolean allowed = context.configFiles().stream()
                .filter(file -> !file.isNonProduction())
                .flatMap(file -> file.find(ALLOW_CIRCULAR_REFERENCES).stream())
                .anyMatch(property -> TRUE.equalsIgnoreCase(property.value().replace("\"", "").trim()));
        return find(context.sources(), allowed);
    }

    @Override
    public List<Violation> checkProject(List<SourceFile> sourceFiles) {
        return find(sourceFiles, false);
    }

    private List<Violation> find(List<SourceFile> sourceFiles, boolean allowed) {
        // Сортировка по имени делает обход и отчет предсказуемыми. Два класса OrderService из разных пакетов -
        // два разных бина, поэтому к имени в ключе добавлено полное имя класса
        Map<String, Bean> beans = new TreeMap<>();
        Map<TypeDeclaration<?>, Set<String>> implementations = new IdentityHashMap<>();
        for (SourceFile sourceFile : sourceFiles) {
            for (ClassOrInterfaceDeclaration type : sourceFile.findAll(ClassOrInterfaceDeclaration.class)) {
                if (!SpringBeans.isBean(type)) {
                    continue;
                }
                String key = keyOf(type);
                beans.put(key, new Bean(type, sourceFile));
                ProjectDeclarations.of(type).ancestors(type).forEach(parent -> implementations
                        .computeIfAbsent(parent, found -> new TreeSet<>())
                        .add(key));
            }
        }

        Map<String, Set<String>> dependencies = new HashMap<>();
        beans.forEach((key, bean) -> dependencies.put(key, findBeanDependencies(bean, beans, implementations)));

        List<Violation> violations = new ArrayList<>();
        for (Map.Entry<String, Bean> entry : beans.entrySet()) {
            String start = entry.getKey();
            List<String> cycle = findCycle(start, dependencies);
            if (cycle.isEmpty()) {
                continue;
            }

            Bean bean = entry.getValue();
            String path = cycle.stream().map(key -> beans.get(key).type().getNameAsString())
                    .collect(Collectors.joining(ARROW)) + ARROW + bean.type().getNameAsString();
            violations.add(allowed
                    ? violation(bean.sourceFile(), bean.type(), "Циклическая зависимость бинов: " + path
                            + ": приложение стартует только благодаря " + ALLOW_CIRCULAR_REFERENCES + "=true, а бин"
                            + " из цикла получает зависимость, которая еще не готова; разорвите цикл, вынеся общую"
                            + " логику в отдельный бин").withLevel(ErrorLevel.MINOR)
                    : violation(bean.sourceFile(), bean.type(), "Циклическая зависимость бинов: " + path
                            + ": Spring не сможет создать их в правильном порядке, с версии Boot 2.6 приложение"
                            + " не стартует; разорвите цикл, вынеся общую логику в отдельный бин"));
        }
        return violations;
    }

    // Простое имя идет первым: порядок обхода и бин, к которому привязана находка, зависят от него, как и раньше
    private String keyOf(ClassOrInterfaceDeclaration type) {
        return type.getNameAsString() + KEY_SEPARATOR + type.getFullyQualifiedName().orElse("");
    }

    @Override
    public ErrorLevel errorLevel() {
        return ErrorLevel.CRITICAL;
    }

    @Override
    public ErrorType errorType() {
        return ErrorType.LOGICAL;
    }

    /**
     * Зависимости с @Lazy и ObjectProvider не считаем: они получают бин позже и цикл при создании не образуют.
     * Тип зависимости ищется так, как его видит сам бин, - по импортам и пакету: MetadataService из пакета service
     * и одноименный класс из пакета grpc - разные типы, и совпадение простых имен их не связывает
     */
    private Set<String> findBeanDependencies(
            Bean bean, Map<String, Bean> beans, Map<TypeDeclaration<?>, Set<String>> implementations) {
        Set<String> result = new TreeSet<>();
        ProjectDeclarations declarations = ProjectDeclarations.of(bean.type());
        for (SpringBeans.Dependency dependency : SpringBeans.findDependencies(bean.type())) {
            if (dependency.deferred()) {
                continue;
            }
            Optional<TypeDeclaration<?>> declared = declarations.find(dependency.type(), bean.type());
            if (declared.isEmpty()) {
                // Тип в проекте не объявлен (интерфейс библиотеки) либо по импортам не найден: связываем по
                // простому имени, только если подходящий бин один
                List<String> sameName = beans.entrySet().stream()
                        .filter(entry -> isNamedOrImplements(entry.getValue().type(), dependency.type()))
                        .map(Map.Entry::getKey)
                        .toList();
                if (sameName.size() == 1) {
                    result.add(sameName.get(0));
                }
                continue;
            }
            Optional<String> itself = beans.entrySet().stream()
                    .filter(entry -> entry.getValue().type() == declared.get())
                    .map(Map.Entry::getKey)
                    .findFirst();
            if (itself.isPresent()) {
                result.add(itself.get());
                continue;
            }
            // Зависимость объявлена через интерфейс: берем реализацию, если она в проекте одна
            Set<String> candidates = implementations.getOrDefault(declared.get(), Set.of());
            if (candidates.size() == 1) {
                result.addAll(candidates);
            }
        }
        return result;
    }

    private boolean isNamedOrImplements(ClassOrInterfaceDeclaration bean, String typeName) {
        return bean.getNameAsString().equals(typeName)
                || bean.getImplementedTypes().stream().anyMatch(parent -> parent.getNameAsString().equals(typeName))
                || bean.getExtendedTypes().stream().anyMatch(parent -> parent.getNameAsString().equals(typeName));
    }

    /**
     * @return цикл, начинающийся с данного бина, либо пустой список. Чтобы один цикл не попал в отчет
     * по разу на каждый бин, ищем только циклы, в которых данный бин первый по алфавиту
     */
    private List<String> findCycle(String start, Map<String, Set<String>> dependencies) {
        Deque<String> path = new ArrayDeque<>(List.of(start));
        return walk(start, start, path, new HashSet<>(), dependencies) ? new ArrayList<>(path) : List.of();
    }

    private boolean walk(
            String start,
            String current,
            Deque<String> path,
            Set<String> visited,
            Map<String, Set<String>> dependencies) {
        for (String next : dependencies.getOrDefault(current, Set.of())) {
            if (next.equals(start)) {
                return true;
            }
            if (next.compareTo(start) > 0 && visited.add(next)) {
                path.addLast(next);
                if (walk(start, next, path, visited, dependencies)) {
                    return true;
                }
                path.removeLast();
            }
        }
        return false;
    }
}
