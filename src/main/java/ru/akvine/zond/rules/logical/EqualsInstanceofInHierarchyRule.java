package ru.akvine.zond.rules.logical;

import com.github.javaparser.ast.Node;
import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration;
import com.github.javaparser.ast.body.FieldDeclaration;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.expr.AnnotationExpr;
import com.github.javaparser.ast.expr.InstanceOfExpr;
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
import ru.akvine.zond.rules.support.ProjectClasses;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * Наследник класса-значения с новым полем. Расширить класс, у которого есть equals, и добавить поле, не нарушив
 * договор equals, нельзя: либо сравнение перестает быть симметричным, либо новое поле в нем не участвует.
 * Поэтому классы-значения объявляют final, а новые данные добавляют композицией.
 */
@Component
public class EqualsInstanceofInHierarchyRule extends AbstractRule implements ProjectRule {
    private static final String EQUALS = "equals";
    private static final String GET_CLASS = "getClass";
    private static final String CALL_SUPER = "callSuper";

    // Lombok сам пишет equals и hashCode по полям класса
    private static final Set<String> LOMBOK_EQUALS = Set.of("Data", "Value", "EqualsAndHashCode");
    // Хранимые объекты сравнивают по идентификатору, а не по всем полям: наследник с новым полем им не мешает
    private static final Set<String> STORED = Set.of("Entity", "MappedSuperclass", "Embeddable", "Document", "Table");
    private static final String COMPOSITION = "; объявите класс-значение final, а новые данные добавляйте композицией:"
            + " отдельный класс, в котором исходный объект - одно из полей";

    // Как класс сравнивает объекты
    private enum Kind {
        /** Своего equals нет */
        NONE,
        /** Тип проверяется через instanceof: наследник проходит проверку предка */
        INSTANCEOF,
        /** Тип проверяется через getClass(): равны только объекты одного класса */
        GET_CLASS,
        /** equals написан Lombok: он сверяет тип с обеих сторон через canEqual */
        LOMBOK
    }

    @Override
    public String code() {
        return RuleCodes.EQUALS_INSTANCEOF_IN_HIERARCHY_RULE_CODE;
    }

    @Override
    public String description() {
        return "Сканирует проект и ищет наследников классов-значений с новыми полями: equals перестает быть"
                + " симметричным либо новые поля в сравнении не участвуют";
    }

    @Override
    public List<Violation> checkProject(List<SourceFile> sourceFiles) {
        ProjectClasses classes = ProjectClasses.of(sourceFiles);
        List<Violation> violations = new ArrayList<>();
        for (SourceFile sourceFile : sourceFiles) {
            for (ClassOrInterfaceDeclaration type : sourceFile.unit().findAll(ClassOrInterfaceDeclaration.class)) {
                Optional<ClassOrInterfaceDeclaration> parent = classes.parent(type);
                Optional<String> added = addedField(type);
                // У абстрактного предка своих объектов нет: сравнивать наследника не с кем
                if (added.isEmpty() || parent.isEmpty() || parent.get().isAbstract() || parent.get().isInterface()
                        || Annotations.hasAny(type, STORED) || Annotations.hasAny(parent.get(), STORED)) {
                    continue;
                }
                Node place = explicitEquals(type).map(method -> (Node) method).orElse(type.getName());
                describe(type, parent.get(), added.get()).ifPresent(problem -> violations.add(
                        violation(sourceFile, place, problem.text() + COMPOSITION).withConfidence(problem.confidence())));
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

    private record Problem(String text, Confidence confidence) {
    }

    private Optional<Problem> describe(ClassOrInterfaceDeclaration type, ClassOrInterfaceDeclaration parent, String added) {
        Kind inherited = kindOf(parent);
        Kind own = kindOf(type);
        String child = type.getNameAsString();
        String ancestor = parent.getNameAsString();
        boolean closed = explicitEquals(parent).filter(MethodDeclaration::isFinal).isPresent();
        if (inherited == Kind.NONE || closed) {
            return Optional.empty();
        }

        if (own == Kind.NONE) {
            // Свой equals есть, но тип он не сверяет - как он сравнивает, по коду не понять
            return explicitEquals(type).isPresent() ? Optional.empty() : Optional.of(new Problem(
                    "Класс '" + child + "' наследует класс-значение '" + ancestor
                    + "' и добавляет поле '" + added + "', а equals и hashCode остаются от предка: новое поле в"
                    + " сравнении не участвует, и два объекта с разным '" + added + "' считаются равными - в"
                    + " HashSet и в ключах Map один из них пропадет", Confidence.PROBABLE));
        }
        if (inherited == Kind.INSTANCEOF) {
            String how = own == Kind.INSTANCEOF
                    ? "equals у '" + child + "' и у предка '" + ancestor + "' сверяют тип через instanceof,"
                    + " а наследник сравнивает еще и свои поля"
                    : "equals у предка '" + ancestor + "' сверяет тип через instanceof и принимает наследника,"
                    + " а equals у '" + child + "' предка не принимает";
            return Optional.of(new Problem(how + ": предок.equals(наследник) вернет true, а"
                    + " наследник.equals(предок) - false. Сравнение перестает быть симметричным, и коллекции с"
                    + " такими объектами ведут себя непредсказуемо", Confidence.CONFIRMED));
        }
        if (own == Kind.LOMBOK && !decidesOnSuper(type)) {
            return Optional.of(new Problem("Lombok пишет equals и hashCode у '" + child + "' только по его"
                    + " собственным полям: поля предка '" + ancestor + "' в сравнении не участвуют, и объекты,"
                    + " которые различаются только ими, считаются равными; укажите"
                    + " @EqualsAndHashCode(callSuper = true)", Confidence.CONFIRMED));
        }
        return Optional.empty();
    }

    private Kind kindOf(ClassOrInterfaceDeclaration type) {
        Optional<MethodDeclaration> explicit = explicitEquals(type);
        if (explicit.isEmpty()) {
            return Annotations.hasAny(type, LOMBOK_EQUALS) ? Kind.LOMBOK : Kind.NONE;
        }
        boolean byClass = explicit.get().findAll(MethodCallExpr.class).stream()
                .anyMatch(call -> GET_CLASS.equals(call.getNameAsString()));
        if (byClass) {
            return Kind.GET_CLASS;
        }
        // equals, который только передает сравнение дальше, сам тип не сверяет: судить о нем нельзя
        return explicit.get().findAll(InstanceOfExpr.class).isEmpty() ? Kind.NONE : Kind.INSTANCEOF;
    }

    private Optional<MethodDeclaration> explicitEquals(ClassOrInterfaceDeclaration type) {
        return type.getMethodsByName(EQUALS).stream()
                .filter(method -> method.getParameters().size() == 1 && method.getBody().isPresent())
                .findFirst();
    }

    // @EqualsAndHashCode(callSuper = ...): участвуют ли поля предка, решено явно - и true, и false здесь осознанный выбор
    private boolean decidesOnSuper(ClassOrInterfaceDeclaration type) {
        return type.getAnnotations().stream()
                .filter(AnnotationExpr::isNormalAnnotationExpr)
                .flatMap(annotation -> annotation.asNormalAnnotationExpr().getPairs().stream())
                .anyMatch(pair -> CALL_SUPER.equals(pair.getNameAsString()));
    }

    /**
     * @return имя первого поля с данными, которое класс добавляет к полям предка
     */
    private Optional<String> addedField(ClassOrInterfaceDeclaration type) {
        return type.getFields().stream()
                .filter(field -> !field.isStatic() && !field.isTransient())
                .map(FieldDeclaration::getVariables)
                .filter(variables -> !variables.isEmpty())
                .map(variables -> variables.get(0).getNameAsString())
                .findFirst();
    }
}
