package ru.akvine.zond.rules;

import com.github.javaparser.ast.Node;
import com.github.javaparser.ast.body.TypeDeclaration;
import ru.akvine.zond.models.SourceFile;
import ru.akvine.zond.models.Violation;

import java.util.ArrayList;
import java.util.List;

/**
 * Общая часть поиска неиспользуемого кода во всем проекте: обход классов и сведения о том, что используется
 */
public abstract class AbstractUnusedDeclarationRule extends AbstractRule implements ProjectRule {
    // По одному файлу не понять, пользуются ли его классами остальные
    private static final int MIN_FILES = 2;

    /**
     * Проверяет один класс проекта.
     */
    protected abstract void check(
            SourceFile sourceFile, TypeDeclaration<?> type, ProjectUsages usages, List<Violation> violations);

    @Override
    public List<Violation> checkProject(List<SourceFile> sourceFiles) {
        List<Violation> violations = new ArrayList<>();
        if (sourceFiles.size() < MIN_FILES) {
            return violations;
        }

        ProjectUsages usages = ProjectUsages.of(sourceFiles);
        for (SourceFile sourceFile : sourceFiles) {
            for (Node node : sourceFile.unit().findAll(Node.class)) {
                if (node instanceof TypeDeclaration<?> type) {
                    check(sourceFile, type, usages, violations);
                }
            }
        }
        return violations;
    }

    /**
     * @return true, если класс вложен в неиспользуемый класс: о нем уже сказано находкой на внешнем классе
     */
    protected boolean isInsideDead(TypeDeclaration<?> type, ProjectUsages usages) {
        Node current = type.getParentNode().orElse(null);
        while (current != null) {
            if (current instanceof TypeDeclaration<?> outer && usages.isTypeDead(outer)) {
                return true;
            }
            current = current.getParentNode().orElse(null);
        }
        return false;
    }
}
