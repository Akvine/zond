package ru.akvine.zond.loaders;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import ru.akvine.zond.config.ZondSettings;
import ru.akvine.zond.enums.TextFileType;
import ru.akvine.zond.models.ScanOptions;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.function.Predicate;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Файлы, которые изменились с прошлой проверки, когда спросить об этом некого: папка не лежит в репозитории git.
 * После проверки запоминаются хеши файлов, а при следующей измененным считается файл, которого тогда не было
 * либо у которого хеш стал другим. Дата файла не учитывается: ее меняет и простое копирование проекта.
 */
@Component
public class HashChangedFiles {
    private static final String ALGORITHM = "SHA-256";
    private static final int BUFFER_SIZE = 8192;
    private static final String HOME_PROPERTY = "user.home";
    private static final String HOME_DIRECTORY = ".zond";
    private static final String SNAPSHOTS_DIRECTORY = "snapshots";
    private static final String SNAPSHOT_EXTENSION = ".sha256";
    private static final int KEY_LENGTH = 16;
    private static final String DEFAULT_NAME = "project";
    private static final Pattern UNSAFE_NAME_CHARACTER = Pattern.compile("[^\\w.-]");

    private static final String COMMENT = "#";
    private static final String HEADER = "# zond: хеши файлов на момент прошлой проверки. По ним ищутся измененные файлы";
    // Как в выводе sha256sum: хеш, два пробела, путь от корня сканирования через "/"
    private static final String SEPARATOR = "  ";
    private static final Pattern ENTRY = Pattern.compile("([0-9a-f]{64}) {2}(.+)");

    private static final String JAVA_EXTENSION = ".java";
    private static final Pattern CONFIG_NAME = Pattern.compile(".*\\.(properties|ya?ml)");
    // Служебные каталоги: проверяемого кода в них нет, а файлов бывают десятки тысяч
    private static final Set<String> SERVICE_DIRECTORIES = Set.of(".git", ".gradle", ".idea", "node_modules");
    // Что считать каталогом сборки, знает ScanOptions: находки оттуда в отчет не попадают, и хеши их не нужны
    private static final ScanOptions PATHS = ScanOptions.defaults();

    private final Path directory;

    /**
     * @param target что сканировали
     * @param hashes хеш каждого проверяемого файла по его пути от корня сканирования
     */
    public record Snapshot(Path target, Map<String, String> hashes) {
    }

    /**
     * @param files    измененные файлы: абсолютные пути без точек и повторов
     * @param previous когда была прошлая проверка; null - ее не было, и измененным считается все
     * @param current  нынешние хеши: их нужно запомнить, когда проверка закончится
     */
    public record Changes(Set<Path> files, Instant previous, Snapshot current) {
    }

    @Autowired
    public HashChangedFiles(ZondSettings settings) {
        this(settings.snapshotDirectory().orElseGet(HashChangedFiles::defaultDirectory));
    }

    /**
     * @param directory где хранить хеши: по файлу на каждую папку, которую сканировали
     */
    public HashChangedFiles(Path directory) {
        this.directory = directory;
    }

    /**
     * Что изменилось с прошлой проверки: новые файлы и файлы с другим содержимым. Удаленные файлы не входят -
     * проверять в них нечего.
     *
     * @param target   что сканируют
     * @param included какие файлы проверяются при нынешних настройках: остальные не запоминаются и, когда их
     *                 начнут проверять, окажутся новыми
     */
    public Changes find(Path target, Predicate<Path> included) {
        Snapshot current = new Snapshot(target, hashes(target, included));
        Path file = fileOf(target);
        Optional<Map<String, String>> previous = read(file);
        Path root = rootOf(target);
        Set<Path> changed = new LinkedHashSet<>();
        current.hashes().forEach((name, hash) -> {
            if (previous.isEmpty() || !hash.equals(previous.get().get(name))) {
                changed.add(root.resolve(name).toAbsolutePath().normalize());
            }
        });
        return new Changes(changed, previous.isPresent() ? savedAt(file) : null, current);
    }

    /**
     * Запоминает хеши: следующая проверка будет сравнивать с ними.
     *
     * @return файл, в который они записаны
     * @throws UncheckedIOException если записать не удалось
     */
    public Path remember(Snapshot snapshot) {
        Path file = fileOf(snapshot.target());
        List<String> lines = new ArrayList<>();
        lines.add(HEADER);
        lines.add(COMMENT + " " + snapshot.target().toAbsolutePath().normalize());
        snapshot.hashes().forEach((name, hash) -> lines.add(hash + SEPARATOR + name));
        try {
            Files.createDirectories(file.getParent());
            Files.write(file, lines, StandardCharsets.UTF_8);
        } catch (IOException exception) {
            throw new UncheckedIOException("Не удалось сохранить хеши файлов в " + file, exception);
        }
        return file;
    }

    /**
     * @return файл с хешами этой папки: у каждой сканируемой папки он свой
     */
    public Path fileOf(Path target) {
        Path absolute = canonical(target);
        String name = absolute.getFileName() == null ? DEFAULT_NAME : absolute.getFileName().toString();
        String key = HexFormat.of().formatHex(digest().digest(absolute.toString().getBytes(StandardCharsets.UTF_8)));
        return directory.resolve(UNSAFE_NAME_CHARACTER.matcher(name).replaceAll("_")
                + "-" + key.substring(0, KEY_LENGTH) + SNAPSHOT_EXTENSION);
    }

    private static Path defaultDirectory() {
        return Path.of(System.getProperty(HOME_PROPERTY), HOME_DIRECTORY, SNAPSHOTS_DIRECTORY);
    }

    // Один и тот же каталог должен давать один файл, как бы его путь ни набрали: с точками, в другом регистре
    private Path canonical(Path target) {
        try {
            return target.toRealPath();
        } catch (IOException exception) {
            return target.toAbsolutePath().normalize();
        }
    }

    private Path rootOf(Path target) {
        Path absolute = target.toAbsolutePath().normalize();
        return Files.isDirectory(absolute) || absolute.getParent() == null ? absolute : absolute.getParent();
    }

    private Map<String, String> hashes(Path target, Predicate<Path> included) {
        Map<String, String> hashes = new TreeMap<>();
        if (!Files.exists(target)) {
            return hashes;
        }
        Path root = rootOf(target);
        try {
            Files.walkFileTree(target.toAbsolutePath().normalize(), new SimpleFileVisitor<>() {
                @Override
                public FileVisitResult preVisitDirectory(Path visited, BasicFileAttributes attributes) {
                    return isSkipped(root, visited) ? FileVisitResult.SKIP_SUBTREE : FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult visitFile(Path file, BasicFileAttributes attributes) {
                    if (attributes.isRegularFile() && isChecked(file) && included.test(file)) {
                        hash(file).ifPresent(hash -> hashes.put(nameOf(root, file), hash));
                    }
                    return FileVisitResult.CONTINUE;
                }

                // Недоступный файл или каталог пропускаем: проверить его все равно не получится
                @Override
                public FileVisitResult visitFileFailed(Path file, IOException exception) {
                    return FileVisitResult.CONTINUE;
                }
            });
        } catch (IOException exception) {
            throw new UncheckedIOException("Не удалось прочитать: " + target, exception);
        }
        return hashes;
    }

    private boolean isSkipped(Path root, Path visited) {
        if (visited.equals(root)) {
            return false;
        }
        return SERVICE_DIRECTORIES.contains(visited.getFileName().toString()) || PATHS.isBuildOutput(root, visited);
    }

    // Те же файлы, что читают загрузчики: код на Java, настройки и то, что может оказаться SQL, сборкой, Docker и прочим
    private boolean isChecked(Path file) {
        String name = file.getFileName().toString().toLowerCase(Locale.ROOT);
        return name.endsWith(JAVA_EXTENSION) || CONFIG_NAME.matcher(name).matches() || TextFileType.isCandidate(file);
    }

    private String nameOf(Path root, Path file) {
        return root.relativize(file).toString().replace('\\', '/');
    }

    /**
     * @return хеш содержимого; пусто, если файл не удалось прочитать
     */
    private Optional<String> hash(Path file) {
        MessageDigest digest = digest();
        byte[] buffer = new byte[BUFFER_SIZE];
        try (InputStream content = Files.newInputStream(file)) {
            for (int read = content.read(buffer); read >= 0; read = content.read(buffer)) {
                digest.update(buffer, 0, read);
            }
        } catch (IOException exception) {
            return Optional.empty();
        }
        return Optional.of(HexFormat.of().formatHex(digest.digest()));
    }

    private MessageDigest digest() {
        try {
            return MessageDigest.getInstance(ALGORITHM);
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException(ALGORITHM + " обязан быть в любой Java", exception);
        }
    }

    /**
     * @return хеши прошлой проверки; пусто, если ее не было либо файл с хешами испорчен - тогда изменено все
     */
    private Optional<Map<String, String>> read(Path file) {
        if (!Files.isRegularFile(file)) {
            return Optional.empty();
        }
        Map<String, String> hashes = new TreeMap<>();
        try {
            for (String line : Files.readAllLines(file, StandardCharsets.UTF_8)) {
                if (line.isBlank() || line.startsWith(COMMENT)) {
                    continue;
                }
                Matcher entry = ENTRY.matcher(line);
                if (!entry.matches()) {
                    return Optional.empty();
                }
                hashes.put(entry.group(2), entry.group(1));
            }
        } catch (IOException | RuntimeException exception) {
            return Optional.empty();
        }
        return Optional.of(hashes);
    }

    private Instant savedAt(Path file) {
        try {
            return Files.getLastModifiedTime(file).toInstant();
        } catch (IOException exception) {
            return Instant.EPOCH;
        }
    }
}
