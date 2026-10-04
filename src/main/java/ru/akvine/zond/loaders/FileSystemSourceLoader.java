package ru.akvine.zond.loaders;

import com.github.javaparser.JavaParser;
import com.github.javaparser.ParseResult;
import com.github.javaparser.ParserConfiguration;
import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.DataKey;
import com.github.javaparser.ast.expr.SimpleName;
import com.github.javaparser.resolution.TypeSolver;
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
import java.util.Optional;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.function.Predicate;
import java.util.stream.Stream;

@Component
public class FileSystemSourceLoader implements SourceLoader {
    /**
     * Решатель типов, с которым разобран файл: по нему правила находят класс по его имени
     */
    public static final DataKey<TypeSolver> TYPE_SOLVER = new DataKey<>() {
    };

    private static final String JAVA_EXTENSION = ".java";

    @Override
    public LoadResult load(Path root, Predicate<Path> included, List<Path> classpath, int threads) {
        if (!Files.exists(root)) {
            throw new IllegalArgumentException("Путь не существует: " + root);
        }

        // Разрешение типов ленивое: деревья получают ссылку на решатель, а считается тип только когда его спросит правило
        ProjectTypeSolver typeSolver = new ProjectTypeSolver(classpath);
        ParserConfiguration configuration = configuration()
                .setSymbolResolver(new JavaSymbolSolver(typeSolver.solver()));

        List<Path> files = findJavaFiles(root, included);
        List<Optional<CompilationUnit>> units = threads <= 1
                ? files.stream().map(file -> parse(file, configuration, typeSolver.solver())).toList()
                : parseInParallel(files, configuration, typeSolver.solver(), threads);

        List<SourceFile> sources = new ArrayList<>();
        List<Path> failedFiles = new ArrayList<>();
        for (int index = 0; index < files.size(); index++) {
            Path file = files.get(index);
            units.get(index).ifPresentOrElse(
                    unit -> sources.add(new SourceFile(file, unit)),
                    () -> failedFiles.add(file));
        }

        // Файлы, которые решатель разбирает сам, тоже должны уметь разрешать свои типы
        typeSolver.addSources(sources, configuration()
                .setSymbolResolver(new JavaSymbolSolver(typeSolver.solver())));
        return new LoadResult(sources, failedFiles);
    }

    // Файлы независимы друг от друга, а порядок результата задается списком файлов, а не тем, кто раньше закончил
    private List<Optional<CompilationUnit>> parseInParallel(
            List<Path> files, ParserConfiguration configuration, TypeSolver solver, int threads) {
        ExecutorService executor = Executors.newFixedThreadPool(threads);
        try {
            List<Future<Optional<CompilationUnit>>> futures = new ArrayList<>();
            for (Path file : files) {
                futures.add(executor.submit(() -> parse(file, configuration, solver)));
            }
            List<Optional<CompilationUnit>> units = new ArrayList<>();
            for (Future<Optional<CompilationUnit>> future : futures) {
                units.add(await(future));
            }
            return units;
        } finally {
            executor.shutdownNow();
        }
    }

    private Optional<CompilationUnit> await(Future<Optional<CompilationUnit>> future) {
        try {
            return future.get();
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Загрузка кода прервана", exception);
        } catch (ExecutionException exception) {
            return Optional.empty();
        }
    }

    // Разборщик хранит состояние и не рассчитан на несколько потоков - на каждый файл создается свой
    private Optional<CompilationUnit> parse(Path file, ParserConfiguration configuration, TypeSolver solver) {
        try {
            ParseResult<CompilationUnit> result = new JavaParser(configuration).parse(file);
            if (!result.isSuccessful() || result.getResult().isEmpty()) {
                return Optional.empty();
            }
            CompilationUnit unit = result.getResult().get();
            unit.setData(TYPE_SOLVER, solver);
            // Дерево запоминает объект для печати при первом toString(); делаем это сейчас, пока с файлом
            // работает один поток, чтобы правила потом только читали дерево
            unit.findFirst(SimpleName.class).ifPresent(SimpleName::toString);
            return Optional.of(unit);
        } catch (IOException | RuntimeException exception) {
            return Optional.empty();
        }
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
