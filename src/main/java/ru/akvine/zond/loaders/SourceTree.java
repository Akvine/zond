package ru.akvine.zond.loaders;

import com.github.javaparser.JavaParser;
import com.github.javaparser.ParseStart;
import com.github.javaparser.ParserConfiguration;
import com.github.javaparser.Providers;
import com.github.javaparser.ast.CompilationUnit;
import ru.akvine.zond.models.SourceFile;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.FileVisitOption;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Каталоги исходников и разобранные файлы, общие для всех корней проекта. Содержимое каталога читается
 * с диска один раз и запоминается: за сканирование файлы не меняются. Файлы, которые уже разобрал загрузчик,
 * берутся готовыми, остальные разбираются, когда понадобятся.
 * <p>
 * Пути сравниваются так же, как их сравнивает файловая система: в Windows без учета регистра.
 */
final class SourceTree {
    private static final String JAVA_EXTENSION = ".java";

    /**
     * Содержимое каталога
     *
     * @param javaFiles   файлы .java в том порядке, в котором их отдает файловая система
     * @param directories вложенные каталоги
     */
    private record Listing(Set<Path> javaFiles, Set<Path> directories) {
    }

    private final JavaParser parser;
    private final Map<Path, Optional<Listing>> listings = new ConcurrentHashMap<>();
    private final Map<Path, Optional<CompilationUnit>> units = new ConcurrentHashMap<>();
    private final Map<Path, List<CompilationUnit>> directoryUnits = new ConcurrentHashMap<>();

    SourceTree(List<SourceFile> loaded, ParserConfiguration configuration) {
        parser = new JavaParser(configuration);
        // Второе дерево того же файла заняло бы столько же памяти, сколько первое
        for (SourceFile source : loaded) {
            units.put(source.path().toAbsolutePath().normalize(), Optional.of(source.unit()));
        }
    }

    /**
     * @return каталоги root, root/names[0], ... root/names[0]/.../names[n-2] - те из них, что есть на диске подряд,
     * начиная с самого root. Отсутствующий каталог обрывает список: в нем не может быть и вложенных
     */
    List<Path> directories(Path root, String[] names) {
        List<Path> directories = new ArrayList<>();
        Path current = root;
        while (true) {
            Optional<Listing> listing = listing(current);
            if (listing.isEmpty()) {
                return directories;
            }
            directories.add(current);
            if (directories.size() >= names.length) {
                return directories;
            }
            Optional<Path> next = resolve(current, names[directories.size() - 1]);
            if (next.isEmpty() || !listing.get().directories().contains(next.get())) {
                return directories;
            }
            current = next.get();
        }
    }

    /**
     * @return файл directory/name.java, если он есть; directory должен существовать
     */
    Optional<Path> javaFile(Path directory, String name) {
        return resolve(directory, name + JAVA_EXTENSION)
                .filter(file -> listing(directory).filter(listing -> listing.javaFiles().contains(file)).isPresent());
    }

    /**
     * @return разобранные файлы .java каталога в том порядке, в котором их отдает файловая система
     */
    List<CompilationUnit> units(Path directory) {
        return directoryUnits.computeIfAbsent(directory, key -> {
            List<CompilationUnit> found = new ArrayList<>();
            for (Path file : listing(key).map(Listing::javaFiles).orElse(Set.of())) {
                unit(file).ifPresent(found::add);
            }
            return found;
        });
    }

    /**
     * @return дерево файла; пусто, если разобрать его не удалось
     */
    Optional<CompilationUnit> unit(Path file) {
        return units.computeIfAbsent(file, this::parse);
    }

    // Разбор такой же, как у JavaParserTypeSolver: дерево с ошибками тоже годится для поиска класса
    private Optional<CompilationUnit> parse(Path file) {
        try {
            synchronized (parser) {
                return parser.parse(ParseStart.COMPILATION_UNIT,
                                Providers.provider(file, parser.getParserConfiguration().getCharacterEncoding()))
                        .getResult()
                        .map(unit -> unit.setStorage(file));
            }
        } catch (IOException exception) {
            throw new UncheckedIOException("Не удалось прочитать " + file, exception);
        }
    }

    private Optional<Listing> listing(Path directory) {
        return listings.computeIfAbsent(directory, this::read);
    }

    // Обход на одну ступень отдает вместе с именами и атрибуты: тип каждого файла известен без отдельных
    // обращений к диску. Порядок тот же, что у Files.newDirectoryStream
    private Optional<Listing> read(Path directory) {
        Set<Path> javaFiles = new LinkedHashSet<>();
        Set<Path> directories = new HashSet<>();
        boolean[] isDirectory = {false};
        try {
            Files.walkFileTree(directory, Set.of(FileVisitOption.FOLLOW_LINKS), 1, new SimpleFileVisitor<>() {
                @Override
                public FileVisitResult preVisitDirectory(Path visited, BasicFileAttributes attributes) {
                    isDirectory[0] = true;
                    return FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult visitFile(Path file, BasicFileAttributes attributes) {
                    if (attributes.isDirectory()) {
                        directories.add(file);
                    } else if (attributes.isRegularFile() && isJava(file)) {
                        javaFiles.add(file);
                    }
                    return FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult visitFileFailed(Path file, IOException exception) {
                    return FileVisitResult.CONTINUE;
                }
            });
        } catch (IOException | UncheckedIOException exception) {
            return Optional.empty();
        }
        return isDirectory[0] ? Optional.of(new Listing(javaFiles, directories)) : Optional.empty();
    }

    private boolean isJava(Path file) {
        return file.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(JAVA_EXTENSION);
    }

    // Часть имени, которая не может быть именем файла (List<String>), файла и не обозначает
    private Optional<Path> resolve(Path directory, String name) {
        try {
            return Optional.of(directory.resolve(name));
        } catch (InvalidPathException exception) {
            return Optional.empty();
        }
    }
}
