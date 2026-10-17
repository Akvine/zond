package ru.akvine.zond.loaders;

import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.body.TypeDeclaration;
import com.github.javaparser.resolution.Navigator;
import com.github.javaparser.resolution.TypeSolver;
import com.github.javaparser.resolution.declarations.ResolvedReferenceTypeDeclaration;
import com.github.javaparser.resolution.model.SymbolReference;
import com.github.javaparser.symbolsolver.javaparsermodel.JavaParserFacade;

import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * Классы одного корня исходников (src/main/java и подобного). Ищет их так же, как JavaParserTypeSolver:
 * для имени a.b.C - файл a/b/C.java, затем остальные файлы каталога a/b, затем то же для a.b как файла
 * с вложенным классом C, и так до самого корня.
 * <p>
 * Отличие в том, откуда известно, какие файлы есть. JavaParserTypeSolver на каждое новое имя заново проверяет
 * на диске файл и каталог для каждой его части, и так в каждом корне. Имена при разрешении перебираются
 * вариантами (пакет файла, импорты со звездочкой, java.lang), а корней в многомодульном проекте десятки: это
 * миллионы обращений к диску, и сканирование большого проекта не заканчивается. Здесь содержимое каталога
 * читается один раз (см. {@link SourceTree}).
 * <p>
 * Найденное не запоминается: это делает общий решатель, в который входит этот, - сюда каждое имя приходит один раз.
 */
final class SourceRootTypeSolver implements TypeSolver {
    private static final String PACKAGE_SEPARATOR = "\\.";
    private static final String NESTED_SEPARATOR = ".";

    private final Path root;
    private final SourceTree tree;
    private TypeSolver parent;

    SourceRootTypeSolver(Path root, SourceTree tree) {
        this.root = root;
        this.tree = tree;
    }

    @Override
    public TypeSolver getParent() {
        return parent;
    }

    @Override
    public void setParent(TypeSolver parent) {
        Objects.requireNonNull(parent);
        if (this.parent != null) {
            throw new IllegalStateException("У решателя уже есть родитель");
        }
        if (parent == this) {
            throw new IllegalStateException("Решатель не может быть родителем сам себе");
        }
        this.parent = parent;
    }

    @Override
    public SymbolReference<ResolvedReferenceTypeDeclaration> tryToSolveType(String name) {
        String[] parts = name.split(PACKAGE_SEPARATOR);
        List<Path> directories = tree.directories(root, parts);
        for (int length = parts.length; length > 0; length--) {
            // Каталога нет - нет в нем и файлов
            if (length > directories.size()) {
                continue;
            }
            Path directory = directories.get(length - 1);
            String typeName = String.join(NESTED_SEPARATOR, Arrays.copyOfRange(parts, length - 1, parts.length));

            Optional<TypeDeclaration<?>> found = tree.javaFile(directory, parts[length - 1])
                    .flatMap(tree::unit)
                    .flatMap(unit -> Navigator.findType(unit, typeName));
            // Класс может быть объявлен и в файле с другим именем
            if (found.isEmpty()) {
                for (CompilationUnit unit : tree.units(directory)) {
                    found = Navigator.findType(unit, typeName);
                    if (found.isPresent()) {
                        break;
                    }
                }
            }
            if (found.isPresent()) {
                return SymbolReference.solved(JavaParserFacade.get(this).getTypeDeclaration(found.get()));
            }
        }
        return SymbolReference.unsolved();
    }

    @Override
    public String toString() {
        return "SourceRootTypeSolver{root=" + root + "}";
    }
}
