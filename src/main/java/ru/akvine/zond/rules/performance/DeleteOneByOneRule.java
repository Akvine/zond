package ru.akvine.zond.rules.performance;

import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.expr.MethodCallExpr;
import org.springframework.stereotype.Component;
import ru.akvine.zond.enums.ErrorLevel;
import ru.akvine.zond.enums.ErrorType;
import ru.akvine.zond.models.SourceFile;
import ru.akvine.zond.models.Violation;
import ru.akvine.zond.rules.AbstractRule;
import ru.akvine.zond.rules.ProjectRule;
import ru.akvine.zond.rules.RuleCodes;
import ru.akvine.zond.rules.support.Nodes;
import ru.akvine.zond.rules.support.ProjectClasses;
import ru.akvine.zond.rules.support.Queries;
import ru.akvine.zond.rules.support.Repositories;
import ru.akvine.zond.rules.support.TestClasses;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Массовое удаление по одной записи. deleteAll() и методы deleteBy... у Spring Data сначала читают сущности,
 * а потом удаляют каждую отдельным запросом DELETE - так срабатывают каскады и @PreRemove. Когда они не нужны,
 * это тысячи запросов там, где хватило бы одного.
 */
@Component
public class DeleteOneByOneRule extends AbstractRule implements ProjectRule {
    private static final String DELETE_ALL = "deleteAll";
    // deleteByStatus, removeByOwnerId, deleteAllByCreatedAtBefore
    private static final Pattern DERIVED_DELETE = Pattern.compile("^(delete|remove)(All)?By\\p{Upper}.*");
    // Удаление по ключу: одна запись либо готовый пакетный метод Spring Data
    private static final Set<String> BY_ID = Set.of("deleteById", "deleteAllById", "deleteAllByIdInBatch", "removeById");
    private static final String CASCADES = " По одной записи удалять нужно, только если важны каскады, orphanRemoval"
            + " или обработчики @PreRemove";

    @Override
    public String code() {
        return RuleCodes.DELETE_ONE_BY_ONE_RULE_CODE;
    }

    @Override
    public String description() {
        return "Сканирует проект и ищет массовое удаление по одной записи: deleteAll() и методы deleteBy... без"
                + " @Query читают сущности и удаляют каждую отдельным запросом";
    }

    @Override
    public List<Violation> checkProject(List<SourceFile> sourceFiles) {
        ProjectClasses classes = ProjectClasses.of(sourceFiles);
        List<Violation> violations = new ArrayList<>();
        for (SourceFile sourceFile : sourceFiles) {
            for (ClassOrInterfaceDeclaration type : sourceFile.unit().findAll(ClassOrInterfaceDeclaration.class)) {
                if (!Queries.isSpringDataRepository(type, classes)) {
                    continue;
                }
                for (MethodDeclaration method : type.getMethods()) {
                    boolean derived = DERIVED_DELETE.matcher(method.getNameAsString()).matches()
                            && !BY_ID.contains(method.getNameAsString())
                            && Queries.find(method).isEmpty() && method.getBody().isEmpty();
                    if (derived) {
                        violations.add(violation(sourceFile, method.getName(),
                                "Метод '" + method.getNameAsString() + "' без @Query: Spring Data сначала читает все"
                                        + " подходящие сущности, а затем удаляет их по одной - отдельным DELETE на"
                                        + " каждую строку; для массового удаления напишите запрос:"
                                        + " @Modifying @Query(\"delete from ... where ...\")." + CASCADES));
                    }
                }
            }
            for (MethodCallExpr call : sourceFile.unit().findAll(MethodCallExpr.class)) {
                if (!DELETE_ALL.equals(call.getNameAsString()) || call.getArguments().size() > 1
                        || !Repositories.isRepositoryCall(call) || TestClasses.isInside(call)) {
                    continue;
                }
                violations.add(violation(sourceFile, call, call.getArguments().isEmpty()
                        ? "'" + Nodes.text(call) + "' читает всю таблицу в память и удаляет записи по одной -"
                        + " отдельным DELETE на каждую строку; одним запросом таблицу очищает deleteAllInBatch()."
                        + CASCADES
                        : "'" + Nodes.text(call) + "' удаляет сущности по одной - отдельным DELETE на каждую; одним"
                        + " запросом их удаляют deleteAllInBatch(entities) и deleteAllByIdInBatch(ids)." + CASCADES));
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
        return ErrorType.PERFORMANCE;
    }
}
