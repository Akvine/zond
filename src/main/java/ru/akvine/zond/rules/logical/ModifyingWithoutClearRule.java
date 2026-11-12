package ru.akvine.zond.rules.logical;

import com.github.javaparser.Position;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.body.VariableDeclarator;
import com.github.javaparser.ast.expr.MethodCallExpr;
import org.springframework.stereotype.Component;
import ru.akvine.zond.enums.Confidence;
import ru.akvine.zond.enums.ErrorLevel;
import ru.akvine.zond.enums.ErrorType;
import ru.akvine.zond.models.SourceFile;
import ru.akvine.zond.models.Violation;
import ru.akvine.zond.rules.AbstractRule;
import ru.akvine.zond.rules.ProjectRule;
import ru.akvine.zond.rules.RuleCodes;
import ru.akvine.zond.rules.support.Annotations;
import ru.akvine.zond.rules.support.LocalTypes;
import ru.akvine.zond.rules.support.ProjectClasses;
import ru.akvine.zond.rules.support.Repositories;
import ru.akvine.zond.rules.support.RepositoryEntities;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * {@code @Modifying}-запрос без clearAutomatically меняет строки в обход контекста Hibernate: сущности, загруженные
 * до него, остаются со старыми значениями. Мешает это только там, где такая сущность в контексте уже есть,
 * поэтому находка увереннее, когда вызывающий метод перед запросом читал или сохранял сущность того же типа.
 */
@Component
public class ModifyingWithoutClearRule extends AbstractRule implements ProjectRule {
    private static final String MODIFYING = "Modifying";
    private static final String CLEAR_AUTOMATICALLY = "clearAutomatically = true";
    // Вызовы, после которых сущность лежит в контексте
    private static final Pattern LOADING = Pattern.compile("(find|get|read|query|search|load|fetch|select|save)\\w*");
    private static final String NOT_LOADED = ". Там, где этот метод вызывают, сущность перед ним не загружают, поэтому"
            + " устаревших объектов в контексте сейчас нет";

    @Override
    public String code() {
        return RuleCodes.MODIFYING_WITHOUT_CLEAR_RULE_CODE;
    }

    @Override
    public String description() {
        return "Сканирует код и ищет @Modifying-запросы без clearAutomatically";
    }

    @Override
    public List<Violation> checkProject(List<SourceFile> sourceFiles) {
        ProjectClasses classes = ProjectClasses.of(sourceFiles);
        Map<String, List<MethodCallExpr>> calls = callsByName(sourceFiles);
        List<Violation> violations = new ArrayList<>();
        for (SourceFile sourceFile : sourceFiles) {
            for (MethodDeclaration method : sourceFile.unit().findAll(MethodDeclaration.class)) {
                if (Annotations.find(method, MODIFYING)
                        .filter(annotation -> !annotation.toString().contains(CLEAR_AUTOMATICALLY))
                        .isEmpty()) {
                    continue;
                }
                String text = "@Modifying на методе '" + method.getNameAsString() + "' без clearAutomatically = true:"
                        + " запрос меняет строки в обход контекста Hibernate, и уже загруженные сущности"
                        + " остаются со старыми значениями; добавьте clearAutomatically = true";
                boolean stale = calls.getOrDefault(method.getNameAsString(), List.of()).stream()
                        .anyMatch(call -> isCallOf(call, method, classes) && hasLoadedEntity(call, classes));
                violations.add(stale
                        ? violation(sourceFile, method, text)
                        : violation(sourceFile, method, text + NOT_LOADED).withConfidence(Confidence.SUSPICION));
            }
        }
        return violations;
    }

    @Override
    public ErrorLevel errorLevel() {
        return ErrorLevel.MINOR;
    }

    @Override
    public ErrorType errorType() {
        return ErrorType.LOGICAL;
    }

    // У методов репозитория нет тела, и в граф вызовов они не попадают: вызовы ищутся по имени и получателю
    private Map<String, List<MethodCallExpr>> callsByName(List<SourceFile> sourceFiles) {
        Map<String, List<MethodCallExpr>> calls = new HashMap<>();
        for (SourceFile sourceFile : sourceFiles) {
            for (MethodCallExpr call : sourceFile.unit().findAll(MethodCallExpr.class)) {
                if (call.getScope().isPresent()) {
                    calls.computeIfAbsent(call.getNameAsString(), key -> new ArrayList<>()).add(call);
                }
            }
        }
        return calls;
    }

    private boolean isCallOf(MethodCallExpr call, MethodDeclaration method, ProjectClasses classes) {
        return call.getArguments().size() == method.getParameters().size()
                && RepositoryEntities.repositoryOf(call.getScope().orElseThrow(), classes)
                .filter(repository -> method.getParentNode().filter(owner -> owner == repository).isPresent())
                .isPresent();
    }

    // До запроса вызывающий метод читал или сохранял через тот же репозиторий либо держит сущность его типа
    private boolean hasLoadedEntity(MethodCallExpr site, ProjectClasses classes) {
        Optional<Position> begin = site.getBegin();
        Optional<MethodDeclaration> caller = site.findAncestor(MethodDeclaration.class);
        if (caller.isEmpty() || begin.isEmpty()) {
            return false;
        }
        String repository = site.getScope().orElseThrow().toString();
        boolean readEarlier = caller.get().findAll(MethodCallExpr.class).stream()
                .filter(other -> other.getBegin().filter(position -> position.isBefore(begin.get())).isPresent())
                .anyMatch(other -> Repositories.isRepositoryCall(other)
                        && LOADING.matcher(other.getNameAsString()).matches()
                        && other.getScope().filter(scope -> scope.toString().equals(repository)).isPresent());
        return readEarlier || RepositoryEntities.entityOf(site.getScope().orElseThrow(), classes)
                .filter(entity -> holds(caller.get(), entity.getNameAsString(), begin.get()))
                .isPresent();
    }

    // Параметр или переменная с типом сущности, объявленные до запроса
    private boolean holds(MethodDeclaration caller, String entity, Position before) {
        boolean passed = caller.getParameters().stream()
                .anyMatch(parameter -> entity.equals(LocalTypes.typeName(parameter.getType())));
        return passed || caller.findAll(VariableDeclarator.class).stream()
                .anyMatch(variable -> entity.equals(LocalTypes.typeName(variable.getType()))
                        && variable.getBegin().filter(position -> position.isBefore(before)).isPresent());
    }
}
