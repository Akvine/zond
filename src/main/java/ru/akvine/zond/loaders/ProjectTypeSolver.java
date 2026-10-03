package ru.akvine.zond.loaders;

import com.github.javaparser.ParserConfiguration;
import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.PackageDeclaration;
import com.github.javaparser.resolution.TypeSolver;
import com.github.javaparser.symbolsolver.resolution.typesolvers.CombinedTypeSolver;
import com.github.javaparser.symbolsolver.resolution.typesolvers.JarTypeSolver;
import com.github.javaparser.symbolsolver.resolution.typesolvers.JavaParserTypeSolver;
import com.github.javaparser.symbolsolver.resolution.typesolvers.ReflectionTypeSolver;
import ru.akvine.zond.models.SourceFile;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Stream;

/**
 * Откуда берутся типы при разрешении: JDK, библиотеки проекта (jar) и его собственные исходники.
 * Тип, которого нет ни в одном из источников, остается неразрешенным - правила в этом случае
 * возвращаются к проверке по именам.
 */
class ProjectTypeSolver {
    private static final String JAR_EXTENSION = ".jar";
    private static final String PACKAGE_SEPARATOR = "\\.";

    // Ошибку в одном источнике (битый jar, незнакомый синтаксис) не считаем причиной прекращать поиск в остальных
    private final CombinedTypeSolver solver = new CombinedTypeSolver(CombinedTypeSolver.ExceptionHandlers.IGNORE_ALL);
    private final Set<Path> sourceRoots = new LinkedHashSet<>();

    /**
     * @param classpath jar-файлы и папки, в которых jar-файлы ищутся рекурсивно
     */
    ProjectTypeSolver(List<Path> classpath) {
        // Только классы JDK: классы самого zond и его зависимостей к проверяемому проекту отношения не имеют
        solver.add(new ReflectionTypeSolver());
        for (Path jar : findJars(classpath)) {
            try {
                solver.add(new JarTypeSolver(jar));
            } catch (IOException | RuntimeException exception) {
                System.err.println("Не удалось прочитать библиотеку " + jar + ": " + exception.getMessage());
            }
        }
    }

    TypeSolver solver() {
        return solver;
    }

    /**
     * Подключает исходники проекта. Корни (src/main/java и подобные) заранее неизвестны, поэтому
     * вычисляются по разобранным файлам: путь к файлу минус каталоги его пакета.
     */
    void addSources(List<SourceFile> sources, ParserConfiguration configuration) {
        for (SourceFile source : sources) {
            sourceRoot(source)
                    .filter(sourceRoots::add)
                    .ifPresent(root -> solver.add(new JavaParserTypeSolver(root, configuration)));
        }
    }

    // ru.akvine.zond в .../src/main/java/ru/akvine/zond/Foo.java -> .../src/main/java
    private Optional<Path> sourceRoot(SourceFile source) {
        Path directory = source.path().toAbsolutePath().normalize().getParent();
        if (directory == null) {
            return Optional.empty();
        }

        CompilationUnit unit = source.unit();
        String packageName = unit.getPackageDeclaration().map(PackageDeclaration::getNameAsString).orElse("");
        if (packageName.isEmpty()) {
            return Optional.of(directory);
        }

        String[] parts = packageName.split(PACKAGE_SEPARATOR);
        for (int index = parts.length - 1; index >= 0; index--) {
            // Каталоги не совпадают с пакетом - по такому файлу корень не определить
            if (directory == null || directory.getFileName() == null
                    || !directory.getFileName().toString().equals(parts[index])) {
                return Optional.empty();
            }
            directory = directory.getParent();
        }
        return Optional.ofNullable(directory);
    }

    private List<Path> findJars(List<Path> classpath) {
        List<Path> jars = new ArrayList<>();
        for (Path entry : classpath) {
            if (Files.isDirectory(entry)) {
                try (Stream<Path> paths = Files.walk(entry)) {
                    paths.filter(Files::isRegularFile).filter(this::isJar).sorted().forEach(jars::add);
                } catch (IOException | RuntimeException exception) {
                    System.err.println("Не удалось прочитать папку с библиотеками " + entry
                            + ": " + exception.getMessage());
                }
            } else if (Files.isRegularFile(entry) && isJar(entry)) {
                jars.add(entry);
            } else {
                System.err.println("Библиотека не найдена: " + entry);
            }
        }
        return jars;
    }

    private boolean isJar(Path file) {
        return file.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(JAR_EXTENSION);
    }
}
