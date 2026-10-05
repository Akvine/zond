package ru.akvine.zond.printers;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.mock.env.MockEnvironment;
import ru.akvine.zond.config.ZondSettings;
import ru.akvine.zond.enums.Confidence;
import ru.akvine.zond.enums.ErrorLevel;
import ru.akvine.zond.enums.ErrorType;
import ru.akvine.zond.models.ScanResult;
import ru.akvine.zond.models.Violation;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Уверенность находок в отчетах выводится по настройке zond.report.confidence
 */
class ConfidenceInReportsTest {
    @TempDir
    Path dir;

    @Test
    void textReportShowsConfidenceByDefault() {
        String report = new ReportFormatter().format(result());

        assertThat(report)
                .contains("[CRITICAL] [SECURITY] [подтверждено] [jr:18]")
                .contains("[MINOR] [SECURITY] [подозрение] [jr:251]")
                .contains("По уверенности: подтверждено: 1, вероятно: 0, подозрение: 1")
                .contains("Скрыто находок с уверенностью ниже заданной: 4");
    }

    @Test
    void textReportHidesConfidenceWhenSwitchedOff() {
        String report = new ReportFormatter(false).format(result());

        assertThat(report)
                .contains("[CRITICAL] [SECURITY] [jr:18]")
                .doesNotContain("подтверждено")
                .doesNotContain("По уверенности")
                // Отбор по уверенности при этом продолжает работать, и о скрытых находках сообщается
                .contains("Скрыто находок с уверенностью ниже заданной: 4");
    }

    @Test
    void fileReportsFollowTheSameSetting() throws IOException {
        Path shownHtml = dir.resolve("shown.html");
        Path hiddenHtml = dir.resolve("hidden.html");
        Path shownSarif = dir.resolve("shown.sarif");
        Path hiddenSarif = dir.resolve("hidden.sarif");
        PrinterFactory shown = new PrinterFactory(new ReportFormatter(true));
        PrinterFactory hidden = new PrinterFactory(new ReportFormatter(false));

        shown.create(shownHtml).print(result());
        hidden.create(hiddenHtml).print(result());
        shown.create(shownSarif).print(result());
        hidden.create(hiddenSarif).print(result());

        assertThat(Files.readString(shownHtml)).contains("подтверждено");
        assertThat(Files.readString(hiddenHtml)).doesNotContain("подтверждено");
        assertThat(Files.readString(shownSarif)).contains("\"confidence\": \"CONFIRMED\"");
        assertThat(Files.readString(hiddenSarif)).doesNotContain("confidence");
    }

    @Test
    void confidenceIsShownUnlessSettingSaysFalse() {
        assertThat(settings(new MockEnvironment()).reportConfidence()).isTrue();
        assertThat(settings(new MockEnvironment().withProperty("zond.report.confidence", "false")).reportConfidence())
                .isFalse();
        assertThat(new ReportFormatter(settings(new MockEnvironment().withProperty("zond.report.confidence", "false")))
                .showsConfidence()).isFalse();
    }

    private ZondSettings settings(MockEnvironment environment) {
        return new ZondSettings("", "", "", "", "", "", "", environment);
    }

    private ScanResult result() {
        Path file = dir.resolve("Users.java");
        List<Violation> violations = List.of(
                new Violation(ErrorLevel.CRITICAL, ErrorType.SECURITY, "jr:18", "CheckSqlConcatenationRule", file, 5,
                        "В запрос попадают данные запроса", Confidence.CONFIRMED),
                new Violation(ErrorLevel.MINOR, ErrorType.SECURITY, "jr:251", "CheckSecretComparisonRule", file, 9,
                        "Сравнение секретов", Confidence.SUSPICION));
        return new ScanResult(dir, 1, 2, 0, violations, 0, false, List.of(), 4);
    }
}
