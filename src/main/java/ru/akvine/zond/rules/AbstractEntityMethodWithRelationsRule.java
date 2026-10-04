package ru.akvine.zond.rules;

import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration;
import com.github.javaparser.ast.body.FieldDeclaration;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.expr.AnnotationExpr;
import com.github.javaparser.ast.expr.FieldAccessExpr;
import com.github.javaparser.ast.expr.MethodCallExpr;
import com.github.javaparser.ast.expr.NameExpr;
import ru.akvine.zond.models.SourceFile;
import ru.akvine.zond.models.Violation;
import ru.akvine.zond.rules.support.Annotations;
import ru.akvine.zond.rules.support.JpaEntities;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;

/**
 * Методы сущности (equals, hashCode, toString), которые обращаются к полям-связям:
 * написанные вручную либо сгенерированные Lombok.
 */
public abstract class AbstractEntityMethodWithRelationsRule extends AbstractRule {
    private static final String DATA = "Data";
    private static final String ONLY_EXPLICITLY_INCLUDED = "onlyExplicitlyIncluded";
    private static final String GETTER_PREFIX = "get";

    /**
     * @return имена проверяемых методов: equals и hashCode либо toString
     */
    protected abstract Set<String> methodNames();

    /**
     * @return аннотация Lombok, которая генерирует эти методы: EqualsAndHashCode либо ToString
     */
    protected abstract String lombokAnnotation();

    /**
     * @return текст нарушения
     */
    protected abstract String message(String entity, String source, String relations);

    @Override
    public List<Violation> check(SourceFile sourceFile) {
        List<Violation> violations = new ArrayList<>();
        for (ClassOrInterfaceDeclaration entity : JpaEntities.findEntities(sourceFile.unit())) {
            Set<String> relations = JpaEntities.relationNames(entity);
            if (relations.isEmpty()) {
                continue;
            }

            for (MethodDeclaration method : entity.getMethods()) {
                if (!methodNames().contains(method.getNameAsString())) {
                    continue;
                }
                Set<String> used = findUsedRelations(method, relations);
                if (!used.isEmpty()) {
                    violations.add(violation(sourceFile, method, message(
                            entity.getNameAsString(), method.getNameAsString() + "()", String.join(", ", used))));
                }
            }

            // @Data ловит отдельное правило
            Optional<AnnotationExpr> lombok = Annotations.find(entity, lombokAnnotation());
            if (lombok.isPresent() && !Annotations.has(entity, DATA) && !isExplicitOnly(lombok.get())) {
                Set<String> included = findNotExcludedRelations(entity);
                if (!included.isEmpty()) {
                    violations.add(violation(sourceFile, lombok.get(), message(
                            entity.getNameAsString(), "@" + lombokAnnotation(), String.join(", ", included))));
                }
            }
        }
        return violations;
    }

    // Обращение к связи по имени поля либо через геттер
    private Set<String> findUsedRelations(MethodDeclaration method, Set<String> relations) {
        Set<String> used = new TreeSet<>();
        method.findAll(NameExpr.class).forEach(name -> used.add(name.getNameAsString()));
        method.findAll(FieldAccessExpr.class).forEach(access -> used.add(access.getNameAsString()));
        method.findAll(MethodCallExpr.class).stream()
                .map(MethodCallExpr::getNameAsString)
                .filter(name -> name.startsWith(GETTER_PREFIX) && name.length() > GETTER_PREFIX.length())
                .map(name -> Character.toLowerCase(name.charAt(GETTER_PREFIX.length()))
                        + name.substring(GETTER_PREFIX.length() + 1))
                .forEach(used::add);
        used.retainAll(relations);
        return used;
    }

    // @EqualsAndHashCode(onlyExplicitlyIncluded = true): в метод попадают только явно отмеченные поля
    private boolean isExplicitOnly(AnnotationExpr annotation) {
        return annotation.isNormalAnnotationExpr()
                && annotation.asNormalAnnotationExpr().getPairs().stream()
                .anyMatch(pair -> ONLY_EXPLICITLY_INCLUDED.equals(pair.getNameAsString())
                        && pair.getValue().toString().equals("true"));
    }

    // Связи без @EqualsAndHashCode.Exclude / @ToString.Exclude
    private Set<String> findNotExcludedRelations(ClassOrInterfaceDeclaration entity) {
        String exclude = lombokAnnotation() + ".Exclude";
        Set<String> included = new TreeSet<>();
        for (FieldDeclaration field : JpaEntities.relationFields(entity)) {
            boolean excluded = field.getAnnotations().stream()
                    .anyMatch(annotation -> annotation.getNameAsString().equals(exclude));
            if (!excluded) {
                field.getVariables().forEach(variable -> included.add(variable.getNameAsString()));
            }
        }
        return included;
    }
}
