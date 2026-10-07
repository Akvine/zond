package ru.akvine.zond.config;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import ru.akvine.zond.models.ScanResult;
import ru.akvine.zond.printers.HtmlPrinter;
import ru.akvine.zond.printers.ReportFormatter;
import ru.akvine.zond.printers.SarifPrinter;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class ZondVersionTest {

    @Test
    void versionComesFromBuild() {
        // Тесты запускает Gradle, поэтому файл с версией из build.gradle уже собран
        assertThat(ZondVersion.current()).matches("\\d+\\.\\d+\\.\\d+(-[A-Za-z0-9.]+)?");
        assertThat(ZondVersion.title()).isEqualTo("Zond " + ZondVersion.current());
    }

    @Test
    void reportsSayWhichVersionMadeThem(@TempDir Path dir) throws IOException {
        ScanResult result = new ScanResult(dir, 1, 1, 0, List.of(), 0, false, List.of());
        String version = ZondVersion.current();

        assertThat(new ReportFormatter().format(result)).contains("Версия Zond: " + version);

        Path html = dir.resolve("report.html");
        new HtmlPrinter(html, true).print(result);
        assertThat(Files.readString(html)).contains("Версия Zond: <b>" + version + "</b>");

        Path sarif = dir.resolve("report.sarif");
        new SarifPrinter(sarif, true).print(result);
        assertThat(Files.readString(sarif)).contains("\"version\": \"" + version + "\"");
    }
}
