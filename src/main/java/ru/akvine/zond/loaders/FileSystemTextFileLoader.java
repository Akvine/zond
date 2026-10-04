package ru.akvine.zond.loaders;

import org.springframework.stereotype.Component;
import ru.akvine.zond.enums.FileKind;
import ru.akvine.zond.models.TextFile;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.function.Predicate;
import java.util.stream.Stream;

@Component
public class FileSystemTextFileLoader implements TextFileLoader {
    // Каталоги сборки и служебные: там лежат копии тех же файлов либо чужой код
    private static final Set<String> SKIPPED_DIRECTORIES =
            Set.of("build", "target", "out", "node_modules", ".git", ".gradle", ".idea");

    @Override
    public List<TextFile> load(Path root, Predicate<Path> included) {
        List<TextFile> files = new ArrayList<>();
        if (!Files.exists(root)) {
            return files;
        }
        try (Stream<Path> paths = Files.walk(root)) {
            for (Path path : paths.filter(Files::isRegularFile).filter(this::isChecked).filter(included).sorted().toList()) {
                read(path, files);
            }
        } catch (IOException | RuntimeException exception) {
            // Каталог, который не удалось обойти, не должен ронять проверку кода
            return files;
        }
        return files;
    }

    // Файл в другой кодировке или недоступный для чтения пропускаем
    private void read(Path path, List<TextFile> files) {
        try {
            files.add(new TextFile(path, Files.readAllLines(path, StandardCharsets.UTF_8)));
        } catch (IOException | RuntimeException exception) {
            System.err.println("Не удалось прочитать " + path + ": " + exception.getMessage());
        }
    }

    private boolean isChecked(Path path) {
        for (Path part : path) {
            if (SKIPPED_DIRECTORIES.contains(part.toString())) {
                return false;
            }
        }
        // Файлы настроек Spring читает и разбирает ConfigLoader
        return FileKind.of(path.getFileName().toString()).filter(kind -> kind != FileKind.CONFIG).isPresent();
    }
}
