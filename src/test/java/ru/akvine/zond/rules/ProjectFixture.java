package ru.akvine.zond.rules;

import ru.akvine.zond.loaders.FileSystemConfigLoader;
import ru.akvine.zond.loaders.FileSystemTextFileLoader;
import ru.akvine.zond.models.ScanContext;
import ru.akvine.zond.models.SourceFile;
import ru.akvine.zond.models.Violation;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Проект для проверки правил, которым кроме Java-кода нужны настройки, миграции и файлы сборки:
 * код задается строками, остальные файлы пишутся во временную папку
 */
class ProjectFixture {
    private final Path dir;
    private final List<SourceFile> sources = new ArrayList<>();

    ProjectFixture(Path dir) {
        this.dir = dir;
    }

    ProjectFixture source(String name, String code) {
        sources.add(RuleTests.parse(Path.of(name), code));
        return this;
    }

    ProjectFixture write(String path, String content) {
        Path file = dir.resolve(path);
        try {
            Files.createDirectories(file.getParent());
            Files.writeString(file, content);
        } catch (IOException exception) {
            throw new UncheckedIOException(exception);
        }
        return this;
    }

    ScanContext context() {
        return new ScanContext(
                dir,
                List.copyOf(sources),
                new FileSystemConfigLoader().load(dir, file -> true),
                new FileSystemTextFileLoader().load(dir, file -> true));
    }

    List<Violation> check(ContextRule rule) {
        return rule.checkContext(context());
    }

    // Находки в виде "файл:строка", по алфавиту
    List<String> lines(ContextRule rule) {
        return check(rule).stream()
                .map(violation -> violation.file().getFileName() + ":" + violation.line())
                .sorted()
                .toList();
    }

    // То же для правила, которому хватает одного кода
    List<String> lines(ProjectRule rule) {
        return rule.checkProject(List.copyOf(sources)).stream()
                .map(violation -> violation.file().getFileName() + ":" + violation.line())
                .sorted()
                .toList();
    }
}
