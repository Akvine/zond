package ru.akvine.zond.printers;

import lombok.experimental.UtilityClass;
import ru.akvine.zond.models.ScanResult;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Общее для отчетов-файлов: запись текста и путь файла относительно корня сканирования
 */
@UtilityClass
class ReportFiles {

    /**
     * @throws UncheckedIOException если файл не удалось записать
     */
    void write(Path reportFile, String content, ScanResult result) {
        try {
            Path parent = reportFile.toAbsolutePath().getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
            Files.writeString(reportFile, content, StandardCharsets.UTF_8);
        } catch (IOException exception) {
            throw new UncheckedIOException("Не удалось записать отчет: " + reportFile, exception);
        }
        System.out.println("Отчет записан: " + reportFile.toAbsolutePath().normalize()
                + " (проблем: " + result.violations().size() + ")");
    }

    /**
     * @return путь от корня сканирования через прямую косую черту: он короче и не зависит от того,
     * где лежит проект и в какой системе его проверяли
     */
    String relativize(Path root, Path file) {
        Path absoluteRoot = root.toAbsolutePath().normalize();
        Path absoluteFile = file.toAbsolutePath().normalize();
        boolean inside = absoluteFile.startsWith(absoluteRoot) && !absoluteFile.equals(absoluteRoot);
        Path shown = inside ? absoluteRoot.relativize(absoluteFile) : file.getFileName();
        return shown.toString().replace('\\', '/');
    }
}
