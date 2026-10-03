package ru.akvine.zond.loaders;

import com.github.javaparser.JavaParser;
import com.github.javaparser.ParseResult;
import com.github.javaparser.ParserConfiguration;
import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.symbolsolver.JavaSymbolSolver;
import org.springframework.stereotype.Component;
import ru.akvine.zond.models.LoadResult;
import ru.akvine.zond.models.SourceFile;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Predicate;
import java.util.stream.Stream;

@Component
public class FileSystemSourceLoader implements SourceLoader {
    private static final String JAVA_EXTENSION = ".java";

    @Override
    public LoadResult load(Path root, Predicate<Path> included, List<Path> classpath) {
        if (!Files.exists(root)) {
            throw new IllegalArgumentException("Путь не существует: " + root);
        }

        // Разрешение типов ленивое: деревья получают ссылку на решатель, а считается тип только когда его спросит правило
        ProjectTypeSolver typeSolver = new ProjectTypeSolver(classpath);
        JavaParser parser = new JavaParser(configuration()
                .setSymbolResolver(new JavaSymbolSolver(typeSolver.solver())));

        List<SourceFile> sources = new ArrayList<>();
        List<Path> failedFiles = new ArrayList<>();
        for (Path file : findJavaFiles(root, included)) {
            try {
                ParseResult<CompilationUnit> result = parser.parse(file);
                if (result.isSuccessful() && result.getResult().isPresent()) {
                    sources.add(new SourceFile(file, result.getResult().get()));
                } else {
                    failedFiles.add(file);
                }
            } catch (IOException exception) {
                failedFiles.add(file);
            }
        }

        // Файлы, которые решатель разбирает сам, тоже должны уметь разрешать свои типы
        typeSolver.addSources(sources, configuration()
                .setSymbolResolver(new JavaSymbolSolver(typeSolver.solver())));
        return new LoadResult(sources, failedFiles);
    }

    private ParserConfiguration configuration() {
        return new ParserConfiguration()
                .setLanguageLevel(ParserConfiguration.LanguageLevel.JAVA_21)
                .setCharacterEncoding(StandardCharsets.UTF_8);
    }

    private List<Path> findJavaFiles(Path root, Predicate<Path> included) {
        try (Stream<Path> paths = Files.walk(root)) {
            return paths
                    .filter(Files::isRegularFile)
                    .filter(path -> path.getFileName().toString().endsWith(JAVA_EXTENSION))
                    .filter(included)
                    .sorted()
                    .toList();
        } catch (IOException exception) {
            throw new UncheckedIOException("Не удалось прочитать: " + root, exception);
        }
    }
}
