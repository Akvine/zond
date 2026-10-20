package ru.akvine.zond.rules.performance;

import com.github.javaparser.ast.Node;
import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.body.TypeDeclaration;
import com.github.javaparser.ast.body.VariableDeclarator;
import com.github.javaparser.ast.expr.Expression;
import com.github.javaparser.ast.expr.MethodCallExpr;
import com.github.javaparser.ast.expr.NameExpr;
import org.springframework.stereotype.Component;
import ru.akvine.zond.enums.ErrorLevel;
import ru.akvine.zond.enums.ErrorType;
import ru.akvine.zond.models.SourceFile;
import ru.akvine.zond.models.Violation;
import ru.akvine.zond.rules.AbstractRule;
import ru.akvine.zond.rules.RuleCodes;
import ru.akvine.zond.rules.support.Annotations;
import ru.akvine.zond.rules.support.LocalTypes;
import ru.akvine.zond.rules.support.MethodCalls;
import ru.akvine.zond.rules.support.Nodes;
import ru.akvine.zond.rules.support.Repositories;
import ru.akvine.zond.rules.support.TestClasses;
import ru.akvine.zond.rules.support.Types;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Список из репозитория загружают только ради числа или проверки наличия: repository.findByStatus(s).size(),
 * if (repository.findAllByOwner(o).isEmpty()). База отдает все строки, а Hibernate создает по сущности на каждую,
 * хотя нужно одно число - countBy / existsBy.
 */
@Component
public class ListLoadedForCountRule extends AbstractRule {
    // findByStatus, findAllByOwner, getByGroup, queryByType, listByUser; одиночные findById / findFirst - не списки
    private static final Pattern LIST_QUERY = Pattern.compile("^(find|get|query|list|search|read|load)(All)?(By[A-Z].*)?$");
    private static final Pattern SINGLE_RESULT = Pattern.compile(
            "^(find|get|query|read|load)(ById|One|First|Top|ByIdIn)?$|.*(First|Top\\d*|One|ById)(By.*)?$");
    private static final Set<String> COLLECTION_TYPES = Set.of("List", "Set", "Collection", "Iterable", "Page", "Slice");
    private static final String SIZE = "size";
    private static final String IS_EMPTY = "isEmpty";
    private static final String STREAM = "stream";
    private static final String COUNT = "count";
    private static final Set<String> STORAGE_ANNOTATIONS = Set.of("Repository", "Mapper");
    // JpaRepository, CrudRepository, MongoRepository, BaseMapper
    private static final Pattern STORAGE_PARENT = Pattern.compile(".*(Repository|Mapper)$");

    @Override
    public String code() {
        return RuleCodes.LIST_LOADED_FOR_COUNT_RULE_CODE;
    }

    @Override
    public String description() {
        return "Сканирует код и ищет списки из репозитория, которые загружают только ради size() или isEmpty()";
    }

    @Override
    public List<Violation> check(SourceFile sourceFile) {
        List<Violation> violations = new ArrayList<>();
        for (MethodCallExpr call : sourceFile.unit().findAll(MethodCallExpr.class)) {
            if (!isListQuery(call) || TestClasses.isInside(call)) {
                continue;
            }
            describeUse(call).ifPresent(use -> violations.add(violation(sourceFile, call,
                    "'" + MethodCalls.receiverName(call.getScope().get()) + "." + call.getNameAsString() + "(...)'"
                            + " загружает все подходящие строки, а нужно только "
                            + (COUNT.equals(use) ? "их число" : "знать, есть ли они")
                            + ": база отдает строки, а Hibernate создает по сущности на каждую; используйте "
                            + suggestion(call.getNameAsString(), use) + " - база вернет одно число")));
        }
        return violations;
    }

    @Override
    public ErrorLevel errorLevel() {
        return ErrorLevel.MINOR;
    }

    @Override
    public ErrorType errorType() {
        return ErrorType.PERFORMANCE;
    }

    private boolean isListQuery(MethodCallExpr call) {
        String name = call.getNameAsString();
        return LIST_QUERY.matcher(name).matches() && !SINGLE_RESULT.matcher(name).matches()
                && Repositories.isRepositoryCall(call) && call.getScope().filter(Expression::isNameExpr).isPresent()
                && !returnsSingleValue(call) && !isProjectNonStorage(call);
    }

    // IWorkflowRepository: свой класс проекта без Spring Data, @Repository и @Mapper - кеш в памяти, а не таблица
    private boolean isProjectNonStorage(MethodCallExpr call) {
        return call.getScope().flatMap(this::projectType)
                .filter(type -> !Annotations.hasAny(type, STORAGE_ANNOTATIONS))
                .filter(type -> !(type instanceof ClassOrInterfaceDeclaration declaration
                        && declaration.getExtendedTypes().stream().anyMatch(extended -> STORAGE_PARENT.matcher(
                        extended.getNameAsString()).matches())))
                .isPresent();
    }

    // Тип объекта из проекта: через решатель, а если он не справился - по объявлению поля в этом же файле
    private Optional<TypeDeclaration<?>> projectType(Expression scope) {
        Optional<TypeDeclaration<?>> resolved = Types.projectType(scope);
        if (resolved.isPresent()) {
            return resolved;
        }
        return LocalTypes.findDeclaration(scope)
                .flatMap(LocalTypes::declaredType)
                .flatMap(name -> scope.findCompilationUnit()
                        .flatMap(unit -> unit.findFirst(ClassOrInterfaceDeclaration.class,
                                type -> type.getNameAsString().equals(name))))
                .map(type -> type);
    }

    // Метод репозитория объявлен в проекте и возвращает не коллекцию (Optional<User>, User): это не список
    private boolean returnsSingleValue(MethodCallExpr call) {
        return Types.declaration(call)
                .map(MethodDeclaration::getType)
                .map(LocalTypes::typeName)
                .filter(type -> !COLLECTION_TYPES.contains(type))
                .isPresent();
    }

    /**
     * @return "их число" или "факт наличия", если результат используют только так: сразу (.size(), .isEmpty(),
     * .stream().count()) либо через переменную, у которой больше ничего не берут
     */
    private Optional<String> describeUse(MethodCallExpr call) {
        Optional<String> direct = useOf(call);
        if (direct.isPresent()) {
            return direct;
        }
        Optional<VariableDeclarator> variable = call.getParentNode()
                .filter(parent -> parent instanceof VariableDeclarator declarator
                        && declarator.getInitializer().filter(initializer -> initializer == call).isPresent())
                .map(parent -> (VariableDeclarator) parent);
        if (variable.isEmpty()) {
            return Optional.empty();
        }
        Optional<Node> callable = Nodes.enclosingCallable(call);
        if (callable.isEmpty()) {
            return Optional.empty();
        }
        String name = variable.get().getNameAsString();
        List<NameExpr> usages = callable.get().findAll(NameExpr.class).stream()
                .filter(usage -> usage.getNameAsString().equals(name))
                .toList();
        if (usages.isEmpty()) {
            return Optional.empty();
        }
        List<String> uses = usages.stream()
                .map(usage -> useOf(usage).orElse(null))
                .toList();
        if (uses.contains(null)) {
            return Optional.empty();
        }
        return uses.contains(COUNT) ? Optional.of(COUNT) : Optional.of(IS_EMPTY);
    }

    // list.size(), list.isEmpty(), list.stream().count()
    private Optional<String> useOf(Expression result) {
        Optional<MethodCallExpr> next = result.getParentNode()
                .filter(parent -> parent instanceof MethodCallExpr owner && owner.getScope().orElse(null) == result)
                .map(parent -> (MethodCallExpr) parent)
                .filter(owner -> owner.getArguments().isEmpty());
        if (next.isEmpty()) {
            return Optional.empty();
        }
        String name = next.get().getNameAsString();
        if (SIZE.equals(name)) {
            return Optional.of(COUNT);
        }
        if (IS_EMPTY.equals(name)) {
            return Optional.of(IS_EMPTY);
        }
        if (STREAM.equals(name)) {
            return next.get().getParentNode()
                    .filter(parent -> parent instanceof MethodCallExpr owner && COUNT.equals(owner.getNameAsString()))
                    .map(parent -> COUNT);
        }
        return Optional.empty();
    }

    // findAllByStatus -> countByStatus / existsByStatus
    private String suggestion(String method, String use) {
        Matcher matcher = LIST_QUERY.matcher(method);
        String condition = matcher.matches() && matcher.group(3) != null ? matcher.group(3) : "";
        String prefix = COUNT.equals(use) ? "count" : "exists";
        if (condition.isEmpty()) {
            return COUNT.equals(use) ? "count()" : "count() == 0";
        }
        return prefix + condition + "(...)";
    }
}
