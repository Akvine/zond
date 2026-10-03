package ru.akvine.zond.rules;

import com.github.javaparser.ast.Node;
import com.github.javaparser.ast.body.AnnotationDeclaration;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.body.TypeDeclaration;
import ru.akvine.zond.models.SourceFile;
import ru.akvine.zond.models.Violation;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Общая часть поиска неиспользуемого кода во всем проекте: обход классов и отбор тех, о которых можно судить
 */
public abstract class AbstractUnusedDeclarationRule extends AbstractRule implements ProjectRule {
    private static final String MAIN = "main";

    // По одному файлу не понять, пользуются ли его классами остальные
    private static final int MIN_FILES = 2;

    private static final String TEST_DIRECTORY = "test";

    /**
     * Проверяет один класс проекта.
     *
     * @param unused true, если на сам класс в проекте нет ни одной ссылки
     */
    protected abstract void check(
            SourceFile sourceFile, TypeDeclaration<?> type, boolean unused, ProjectUsages usages,
            List<Violation> violations);

    @Override
    public List<Violation> checkProject(List<SourceFile> sourceFiles) {
        List<Violation> violations = new ArrayList<>();
        if (sourceFiles.size() < MIN_FILES) {
            return violations;
        }

        ProjectUsages usages = ProjectUsages.of(sourceFiles);
        for (SourceFile sourceFile : sourceFiles) {
            // Тесты запускает не код проекта: класс в src/test без единой ссылки на него - обычное дело
            if (isInTestDirectory(sourceFile)) {
                continue;
            }
            for (Node node : sourceFile.unit().findAll(Node.class)) {
                if (node instanceof TypeDeclaration<?> type && isCheckable(type)) {
                    check(sourceFile, type, isUnused(type, usages), usages, violations);
                }
            }
        }
        return violations;
    }

    // Аннотации используются без вызовов, а методы тестов запускает не код проекта
    private boolean isCheckable(TypeDeclaration<?> type) {
        return !(type instanceof AnnotationDeclaration) && !TestClasses.isInside(type);
    }

    private boolean isInTestDirectory(SourceFile sourceFile) {
        for (Path part : sourceFile.path()) {
            if (TEST_DIRECTORY.equals(part.toString())) {
                return true;
            }
        }
        return false;
    }

    // Класс с аннотацией (@Service, @Entity, @Configuration) создает и вызывает фреймворк, класс с main - JVM
    private boolean isUnused(TypeDeclaration<?> type, ProjectUsages usages) {
        return !usages.isFrameworkEntry(type) && !hasMain(type) && !usages.isTypeUsed(type);
    }

    /**
     * @return true, если класс вложен в класс, на который в проекте нет ссылок
     */
    protected boolean isInsideUnused(TypeDeclaration<?> type, ProjectUsages usages) {
        Node current = type.getParentNode().orElse(null);
        while (current != null) {
            if (current instanceof TypeDeclaration<?> outer && isUnused(outer, usages)) {
                return true;
            }
            current = current.getParentNode().orElse(null);
        }
        return false;
    }

    private boolean hasMain(TypeDeclaration<?> type) {
        return type.getMethods().stream().anyMatch(this::isMain);
    }

    protected boolean isMain(MethodDeclaration method) {
        return MAIN.equals(method.getNameAsString()) && method.isStatic();
    }
}
