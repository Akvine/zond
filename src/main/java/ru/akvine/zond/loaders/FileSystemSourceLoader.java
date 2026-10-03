package ru.akvine.zond.loaders;

import com.github.javaparser.JavaParser;
import com.github.javaparser.ParseResult;
import com.github.javaparser.ParserConfiguration;
import com.github.javaparser.ast.CompilationUnit;
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
    public LoadResult load(Path root, Predicate<Path> included) {
        if (!Files.exists(root)) {
            throw new IllegalArgumentException("Путь не существует: " + root);
        }

        JavaParser parser = new JavaParser(new ParserConfiguration()
                .setLanguageLevel(ParserConfiguration.LanguageLevel.JAVA_21)
                .setCharacterEncoding(StandardCharsets.UTF_8));

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
        return new LoadResult(sources, failedFiles);
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
