package ru.akvine.zond.rules.performance;

import com.github.javaparser.ast.Node;
import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration;
import com.github.javaparser.ast.body.FieldDeclaration;
import com.github.javaparser.ast.body.VariableDeclarator;
import com.github.javaparser.ast.expr.Expression;
import com.github.javaparser.ast.expr.MethodCallExpr;
import org.springframework.stereotype.Component;
import ru.akvine.zond.enums.ErrorLevel;
import ru.akvine.zond.enums.ErrorType;
import ru.akvine.zond.models.SourceFile;
import ru.akvine.zond.models.Violation;
import ru.akvine.zond.rules.AbstractRule;
import ru.akvine.zond.rules.ProjectRule;
import ru.akvine.zond.rules.RuleCodes;
import ru.akvine.zond.rules.support.Annotations;
import ru.akvine.zond.rules.support.LocalTypes;
import ru.akvine.zond.rules.support.Loops;
import ru.akvine.zond.rules.support.Nodes;
import ru.akvine.zond.rules.support.ProjectClasses;
import ru.akvine.zond.rules.support.Repositories;
import ru.akvine.zond.rules.support.TestClasses;

import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * Новая сущность с идентификатором, заданным вручную, сохраняется через save(). Spring Data считает запись
 * новой, пока ее id пуст; с заполненным id она вызывает merge, и Hibernate перед INSERT читает строку из базы -
 * вдруг она уже есть. На каждую вставку уходит лишний SELECT.
 */
@Component
public class ManualIdEntitySaveRule extends AbstractRule implements ProjectRule {
    private static final String ENTITY = "Entity";
    private static final Set<String> ID_ANNOTATIONS = Set.of("Id", "EmbeddedId");
    private static final String VERSION = "Version";
    private static final String PERSISTABLE = "Persistable";
    // @GeneratedValue, @UuidGenerator, @GenericGenerator, @Generated: значение задает Hibernate или база
    private static final String GENERATOR_MARK = "Generat";
    private static final String TSID = "Tsid";
    private static final Set<String> SAVE_METHODS = Set.of("save", "saveAndFlush");
    private static final String BUILD = "build";
    private static final String BUILDER = "builder";
    private static final String SETTER_PREFIX = "set";

    /**
     * Объект, созданный прямо здесь: конструктором или builder
     *
     * @param type     имя класса
     * @param variable переменная, в которой он лежит, если она есть
     * @param creation выражение, которое его создало
     */
    private record Created(String type, Optional<VariableDeclarator> variable, Expression creation) {
    }

    @Override
    public String code() {
        return RuleCodes.MANUAL_ID_ENTITY_SAVE_RULE_CODE;
    }

    @Override
    public String description() {
        return "Сканирует код и ищет сохранение через save() новой сущности с идентификатором, заданным вручную:"
                + " перед каждым INSERT выполняется лишний SELECT";
    }

    @Override
    public List<Violation> checkProject(List<SourceFile> sourceFiles) {
        ProjectClasses classes = ProjectClasses.of(sourceFiles);
        List<Violation> violations = new ArrayList<>();
        for (SourceFile sourceFile : sourceFiles) {
            for (MethodCallExpr save : sourceFile.unit().findAll(MethodCallExpr.class)) {
                if (!SAVE_METHODS.contains(save.getNameAsString()) || save.getArguments().size() != 1
                        || !Repositories.isRepositoryCall(save) || TestClasses.isInside(save)) {
                    continue;
                }
                created(save.getArgument(0))
                        .flatMap(created -> check(sourceFile, save, created, classes))
                        .ifPresent(violations::add);
            }
        }
        return violations;
    }

    @Override
    public ErrorLevel errorLevel() {
        return ErrorLevel.MAJOR;
    }

    @Override
    public ErrorType errorType() {
        return ErrorType.PERFORMANCE;
    }

    private Optional<Violation> check(SourceFile sourceFile, MethodCallExpr save, Created created, ProjectClasses classes) {
        Optional<ClassOrInterfaceDeclaration> entity = classes.find(created.type())
                .filter(type -> Annotations.has(type, ENTITY));
        // Предок из библиотеки мог сам реализовать Persistable: о такой сущности судить нельзя
        if (entity.isEmpty() || classes.hasUnknownAncestor(entity.get()) || decidesItself(entity.get(), classes)) {
            return Optional.empty();
        }
        Optional<FieldDeclaration> id = hierarchy(entity.get(), classes).stream()
                .flatMap(type -> type.getFields().stream())
                .filter(field -> Annotations.hasAny(field, ID_ANNOTATIONS))
                .findFirst();
        if (id.isEmpty()) {
            return Optional.empty();
        }
        boolean inLoop = Loops.enclosingIteration(save).isPresent();
        String name = entity.get().getNameAsString();
        if (!isGenerated(id.get())) {
            Violation violation = violation(sourceFile, save,
                    "Новая сущность '" + name + "' сохраняется через save(), а ее @Id задается вручную, без"
                            + " @GeneratedValue: Spring Data считает запись новой, только пока id пуст, поэтому здесь"
                            + " вызывает merge - перед INSERT уходит лишний SELECT"
                            + (inLoop ? ", и так на каждой итерации цикла" : "")
                            + "; реализуйте в сущности Persistable<ID> с isNew(), добавьте поле @Version объектного"
                            + " типа либо вставляйте через entityManager.persist()");
            return Optional.of(inLoop ? violation : violation.withLevel(ErrorLevel.MINOR));
        }
        // id генерируется, но новому объекту его все равно задали руками
        String idName = id.get().getVariable(0).getNameAsString();
        return manualAssignment(save, created, idName).map(assignment -> violation(sourceFile, save,
                "Новой сущности '" + name + "' id задан вручную ('" + assignment + "'), хотя поле помечено как"
                        + " генерируемое: с заполненным id Spring Data считает запись существующей и вызывает merge -"
                        + " перед INSERT уходит лишний SELECT, а Hibernate с версии 6.6 на merge записи, которой"
                        + " нет в базе, отвечает исключением; не задавайте id новой записи либо уберите генерацию"
                        + " и реализуйте Persistable<ID>"));
    }

    /**
     * @return объект, созданный здесь же: new Country(...), Country.builder()...build() либо переменная с ним
     */
    private Optional<Created> created(Expression argument) {
        Expression value = Nodes.unwrap(argument);
        if (value.isNameExpr()) {
            return LocalTypes.findDeclaration(value)
                    .filter(declaration -> declaration instanceof VariableDeclarator)
                    .map(declaration -> (VariableDeclarator) declaration)
                    .flatMap(variable -> variable.getInitializer()
                            .flatMap(this::creation)
                            .map(found -> new Created(found.type(), Optional.of(variable), found.creation())));
        }
        return creation(value);
    }

    private Optional<Created> creation(Expression expression) {
        Expression value = Nodes.unwrap(expression);
        if (value.isObjectCreationExpr()) {
            return Optional.of(new Created(value.asObjectCreationExpr().getType().getNameAsString(), Optional.empty(), value));
        }
        if (!value.isMethodCallExpr() || !BUILD.equals(value.asMethodCallExpr().getNameAsString())) {
            return Optional.empty();
        }
        // Country.builder().code("RU").name("Россия").build(): идем к началу цепочки
        Expression current = value;
        while (current.isMethodCallExpr() && current.asMethodCallExpr().getScope().isPresent()) {
            MethodCallExpr link = current.asMethodCallExpr();
            Expression scope = Nodes.unwrap(link.getScope().get());
            if (BUILDER.equals(link.getNameAsString()) && scope.isNameExpr()) {
                return Optional.of(new Created(scope.asNameExpr().getNameAsString(), Optional.empty(), value));
            }
            current = scope;
        }
        return Optional.empty();
    }

    // Сущность сама сообщает, новая ли она: Persistable.isNew() либо версия, пустая у новой записи
    private boolean decidesItself(ClassOrInterfaceDeclaration entity, ProjectClasses classes) {
        return hierarchy(entity, classes).stream().anyMatch(type ->
                type.getImplementedTypes().stream().anyMatch(parent -> PERSISTABLE.equals(parent.getNameAsString()))
                        || type.getFields().stream().anyMatch(field -> Annotations.has(field, VERSION)
                        && !field.getElementType().isPrimitiveType()));
    }

    private List<ClassOrInterfaceDeclaration> hierarchy(ClassOrInterfaceDeclaration entity, ProjectClasses classes) {
        List<ClassOrInterfaceDeclaration> types = new ArrayList<>();
        Set<ClassOrInterfaceDeclaration> seen = Collections.newSetFromMap(new IdentityHashMap<>());
        Optional<ClassOrInterfaceDeclaration> current = Optional.of(entity);
        while (current.isPresent() && seen.add(current.get())) {
            types.add(current.get());
            current = classes.parent(current.get());
        }
        return types;
    }

    private boolean isGenerated(FieldDeclaration id) {
        return id.getAnnotations().stream()
                .map(annotation -> annotation.getName().getIdentifier())
                .anyMatch(name -> name.contains(GENERATOR_MARK) || TSID.equals(name));
    }

    /**
     * @return чем id задан у нового объекта: вызов сеттера на переменной либо шаг builder с именем поля
     */
    private Optional<String> manualAssignment(MethodCallExpr save, Created created, String idName) {
        String setter = SETTER_PREFIX + Character.toUpperCase(idName.charAt(0)) + idName.substring(1);
        // Шаги builder - только в цепочке вызовов; у new Country(...) аргументы конструктора ничего не говорят
        Optional<String> inBuilder = created.creation().findAll(MethodCallExpr.class).stream()
                .filter(step -> created.creation().isMethodCallExpr())
                .filter(step -> idName.equals(step.getNameAsString()) && step.getArguments().size() == 1)
                .map(step -> idName + "(...)")
                .findFirst();
        if (inBuilder.isPresent() || created.variable().isEmpty()) {
            return inBuilder;
        }
        VariableDeclarator variable = created.variable().get();
        Node callable = Nodes.enclosingCallable(save).orElse(save);
        return callable.findAll(MethodCallExpr.class).stream()
                .filter(call -> setter.equals(call.getNameAsString()))
                .filter(call -> call.getScope()
                        .filter(scope -> LocalTypes.findDeclaration(scope).filter(found -> found == variable).isPresent())
                        .isPresent())
                .map(call -> variable.getNameAsString() + "." + setter + "(...)")
                .findFirst();
    }
}
