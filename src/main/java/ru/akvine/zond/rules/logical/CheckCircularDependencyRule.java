package ru.akvine.zond.rules.logical;

import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration;
import org.springframework.stereotype.Component;
import ru.akvine.zond.enums.ErrorLevel;
import ru.akvine.zond.enums.ErrorType;
import ru.akvine.zond.models.SourceFile;
import ru.akvine.zond.models.Violation;
import ru.akvine.zond.rules.AbstractRule;
import ru.akvine.zond.rules.ProjectRule;
import ru.akvine.zond.rules.RuleCodes;
import ru.akvine.zond.rules.support.SpringBeans;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;

@Component
public class CheckCircularDependencyRule extends AbstractRule implements ProjectRule {
    private static final String ARROW = " -> ";

    /**
     * Бин и файл, в котором он объявлен
     */
    private record Bean(ClassOrInterfaceDeclaration type, SourceFile sourceFile) {
    }

    @Override
    public String code() {
        return RuleCodes.CHECK_CIRCULAR_DEPENDENCY_RULE_CODE;
    }

    @Override
    public String description() {
        return "Сканирует проект и ищет циклические зависимости между Spring-бинами";
    }

    @Override
    public List<Violation> checkProject(List<SourceFile> sourceFiles) {
        // Сортировка по имени делает обход и отчет предсказуемыми
        Map<String, Bean> beans = new TreeMap<>();
        Map<String, Set<String>> implementations = new HashMap<>();
        for (SourceFile sourceFile : sourceFiles) {
            for (ClassOrInterfaceDeclaration type : sourceFile.unit().findAll(ClassOrInterfaceDeclaration.class)) {
                if (!SpringBeans.isBean(type)) {
                    continue;
                }
                beans.put(type.getNameAsString(), new Bean(type, sourceFile));
                type.getImplementedTypes().forEach(parent -> implementations
                        .computeIfAbsent(parent.getNameAsString(), key -> new TreeSet<>())
                        .add(type.getNameAsString()));
            }
        }

        Map<String, Set<String>> dependencies = new HashMap<>();
        beans.forEach((name, bean) -> dependencies.put(name, findBeanDependencies(bean, beans, implementations)));

        List<Violation> violations = new ArrayList<>();
        for (String start : beans.keySet()) {
            List<String> cycle = findCycle(start, dependencies);
            if (cycle.isEmpty()) {
                continue;
            }

            Bean bean = beans.get(start);
            violations.add(violation(bean.sourceFile(), bean.type(),
                    "Циклическая зависимость бинов: " + String.join(ARROW, cycle) + ARROW + start
                            + ": Spring не сможет создать их в правильном порядке, с версии Boot 2.6 приложение"
                            + " не стартует; разорвите цикл, вынеся общую логику в отдельный бин"));
        }
        return violations;
    }

    @Override
    public ErrorLevel errorLevel() {
        return ErrorLevel.CRITICAL;
    }

    @Override
    public ErrorType errorType() {
        return ErrorType.LOGICAL;
    }

    // Зависимости с @Lazy и ObjectProvider не считаем: они получают бин позже и цикл при создании не образуют
    private Set<String> findBeanDependencies(
            Bean bean, Map<String, Bean> beans, Map<String, Set<String>> implementations) {
        Set<String> result = new TreeSet<>();
        for (SpringBeans.Dependency dependency : SpringBeans.findDependencies(bean.type())) {
            if (dependency.deferred()) {
                continue;
            }
            if (beans.containsKey(dependency.type())) {
                result.add(dependency.type());
                continue;
            }
            // Зависимость объявлена через интерфейс: берем реализацию, если она в проекте одна
            Set<String> candidates = implementations.getOrDefault(dependency.type(), Set.of());
            if (candidates.size() == 1) {
                result.addAll(candidates);
            }
        }
        return result;
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
