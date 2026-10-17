package ru.akvine.zond.loaders;

import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.Node;
import com.github.javaparser.resolution.TypeSolver;
import com.github.javaparser.resolution.declarations.ResolvedReferenceTypeDeclaration;
import com.github.javaparser.resolution.model.SymbolReference;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import ru.akvine.zond.models.SourceFile;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Классы исходников проекта решатель находит так же, как JavaParserTypeSolver: по пути файла, по остальным
 * файлам каталога и вложенные, - но не проверяя диск на каждое имя
 */
class SourceRootTypeSolverTest {
    @TempDir
    Path dir;

    private List<SourceFile> sources;
    private TypeSolver solver;

    @BeforeEach
    void setUp() throws IOException {
        write("src/main/java/demo/Account.java", """
                package demo;

                public class Account {
                    public static class Entry {
                        public static class Line {
                        }
                    }
                }
                """);
        // Класс не в своем файле: искать его приходится по всем файлам каталога
        write("src/main/java/demo/Shapes.java", """
                package demo;

                public class Shapes {
                }

                class Circle {
                }
                """);
        write("src/main/java/demo/billing/Invoice.java", """
                package demo.billing;

                public class Invoice {
                }
                """);
        // Отдельный модуль - отдельный корень исходников
        write("module/src/main/java/other/Report.java", """
                package other;

                public class Report {
                }
                """);
        // Файл, который загрузчик не читает, решатель все равно находит и разбирает сам
        write("src/main/java/demo/Hidden.java", """
                package demo;

                public class Hidden {
                }
                """);

        sources = new FileSystemSourceLoader()
                .load(dir, file -> !file.endsWith("Hidden.java"), List.of())
                .sources();
        solver = sources.get(0).unit().getData(FileSystemSourceLoader.TYPE_SOLVER);
    }

    @Test
    void findsClassByFilePath() {
        assertThat(solve("demo.Account")).contains("demo.Account");
        assertThat(solve("demo.billing.Invoice")).contains("demo.billing.Invoice");
        assertThat(solve("other.Report")).contains("other.Report");
    }

    @Test
    void findsNestedClasses() {
        assertThat(solve("demo.Account.Entry")).contains("demo.Account.Entry");
        assertThat(solve("demo.Account.Entry.Line")).contains("demo.Account.Entry.Line");
    }

    @Test
    void findsClassDeclaredInFileWithOtherName() {
        assertThat(solve("demo.Circle")).contains("demo.Circle");
    }

    @Test
    void parsesFileThatLoaderSkipped() {
        assertThat(sources).noneMatch(source -> source.path().endsWith("Hidden.java"));
        assertThat(solve("demo.Hidden")).contains("demo.Hidden");
    }

    @Test
    void unknownNamesStayUnsolved() {
        assertThat(solve("demo.Missing")).isEmpty();
        assertThat(solve("demo.Account.Missing")).isEmpty();
        assertThat(solve("missing.Account")).isEmpty();
        assertThat(solve("demo.billing")).isEmpty();
        // Не имя файла вовсе: в решатель приходят и такие
        assertThat(solve("demo.List<String>")).isEmpty();
    }

    @Test
    void reusesLoadedTrees() {
        CompilationUnit loaded = sources.stream()
                .filter(source -> source.path().endsWith("Account.java"))
                .map(SourceFile::unit)
                .findFirst()
                .orElseThrow();

        SymbolReference<ResolvedReferenceTypeDeclaration> found = solver.tryToSolveType("demo.Account");

        assertThat(found.getCorrespondingDeclaration().toAst().flatMap(Node::findCompilationUnit))
                .containsSame(loaded);
    }

    private Optional<String> solve(String name) {
        SymbolReference<ResolvedReferenceTypeDeclaration> found = solver.tryToSolveType(name);
        return found.isSolved() ? Optional.of(found.getCorrespondingDeclaration().getQualifiedName()) : Optional.empty();
    }

    private void write(String file, String code) throws IOException {
        Path path = dir.resolve(file);
        Files.createDirectories(path.getParent());
        Files.writeString(path, code);
    }
}
