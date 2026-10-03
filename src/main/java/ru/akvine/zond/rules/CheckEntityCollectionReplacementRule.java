package ru.akvine.zond.rules;

import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration;
import com.github.javaparser.ast.body.FieldDeclaration;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.expr.AssignExpr;
import com.github.javaparser.ast.expr.Expression;
import org.springframework.stereotype.Component;
import ru.akvine.zond.enums.ErrorLevel;
import ru.akvine.zond.enums.ErrorType;
import ru.akvine.zond.models.SourceFile;
import ru.akvine.zond.models.Violation;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

@Component
public class CheckEntityCollectionReplacementRule extends AbstractRule {
    private static final String ORPHAN_REMOVAL = "orphanRemoval";
    private static final String TRUE = "true";

    @Override
    public String code() {
        return RuleCodes.CHECK_ENTITY_COLLECTION_REPLACEMENT_RULE_CODE;
    }

    @Override
    public String description() {
        return "Сканирует код и ищет замену коллекции с orphanRemoval новым объектом";
    }

    @Override
    public List<Violation> check(SourceFile sourceFile) {
        List<Violation> violations = new ArrayList<>();
        for (ClassOrInterfaceDeclaration entity : JpaEntities.findEntities(sourceFile.unit())) {
            Set<String> collections = findOrphanRemovalCollections(entity);
            if (collections.isEmpty()) {
                continue;
            }

            // Присваивание в конструкторе - это инициализация, а не замена
            for (MethodDeclaration method : entity.getMethods()) {
                for (AssignExpr assign : method.findAll(AssignExpr.class)) {
                    String target = fieldName(assign.getTarget());
                    if (assign.getOperator() == AssignExpr.Operator.ASSIGN && collections.contains(target)) {
                        violations.add(violation(sourceFile, assign,
                                "Коллекция '" + target + "' с orphanRemoval заменяется новым объектом: Hibernate"
                                        + " следит за исходной коллекцией и на подмене бросает исключение"
                                        + " \"collection with cascade=all-delete-orphan was no longer referenced\";"
                                        + " меняйте содержимое: clear() и addAll(...)"));
                    }
                }
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
        return ErrorType.LOGICAL;
    }

    private Set<String> findOrphanRemovalCollections(ClassOrInterfaceDeclaration entity) {
        Set<String> names = new HashSet<>();
        for (FieldDeclaration field : JpaEntities.relationFields(entity)) {
            boolean orphanRemoval = field.getAnnotations().stream()
                    .filter(annotation -> annotation.isNormalAnnotationExpr())
                    .flatMap(annotation -> annotation.asNormalAnnotationExpr().getPairs().stream())
                    .anyMatch(pair -> ORPHAN_REMOVAL.equals(pair.getNameAsString())
                            && TRUE.equals(pair.getValue().toString()));
            if (orphanRemoval) {
                field.getVariables().forEach(variable -> names.add(variable.getNameAsString()));
            }
        }
        return names;
    }

    // this.items -> items
    private String fieldName(Expression target) {
        return target.isFieldAccessExpr() ? target.asFieldAccessExpr().getNameAsString() : target.toString();
    }
}
