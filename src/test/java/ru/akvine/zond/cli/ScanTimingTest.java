package ru.akvine.zond.cli;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import ru.akvine.zond.config.RuleSettings;
import ru.akvine.zond.enums.DurationUnit;
import ru.akvine.zond.loaders.FileSystemConfigLoader;
import ru.akvine.zond.loaders.FileSystemSourceLoader;
import ru.akvine.zond.loaders.FileSystemTextFileLoader;
import ru.akvine.zond.models.ScanOptions;
import ru.akvine.zond.rules.Rule;
import ru.akvine.zond.rules.codesmell.FieldInjectionRule;
import ru.akvine.zond.rules.logical.AutowiredOnStaticFieldRule;
import ru.akvine.zond.rules.logical.TransactionOnPrivateMethodRule;
import ru.akvine.zond.services.ScanProgressListener;
import ru.akvine.zond.services.Scanner;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Время работы каждого правила и единица, в которой оно показывается
 */
class ScanTimingTest {
    private static final long NANOS_1234_MS = 1_234_567_890L;

    private final Rule shortName = new FieldInjectionRule();
    private final Rule longName = new TransactionOnPrivateMethodRule();

    @Test
    void unitIsParsedFromCodeOrName() {
        assertThat(DurationUnit.parse("")).isEqualTo(DurationUnit.MILLISECONDS);
        assertThat(DurationUnit.parse(null)).isEqualTo(DurationUnit.MILLISECONDS);
        assertThat(DurationUnit.parse(" MS ")).isEqualTo(DurationUnit.MILLISECONDS);
        assertThat(DurationUnit.parse("s")).isEqualTo(DurationUnit.SECONDS);
        assertThat(DurationUnit.parse("seconds")).isEqualTo(DurationUnit.SECONDS);
        assertThatThrownBy(() -> DurationUnit.parse("minutes"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("minutes")
                .hasMessageContaining("ms, s");
    }

    @Test
    void timeIsShownInChosenUnit() {
        assertThat(DurationUnit.MILLISECONDS.format(NANOS_1234_MS)).isEqualTo("1234 мс");
        assertThat(DurationUnit.SECONDS.format(NANOS_1234_MS)).isEqualTo("1.235 с");
        // Быстрое правило в секундах не превращается в ноль без долей
        assertThat(DurationUnit.SECONDS.format(14_000_000L)).isEqualTo("0.014 с");
        assertThat(DurationUnit.MILLISECONDS.format(999_999L)).isEqualTo("0 мс");
    }

    @Test
    void timeStandsInItsOwnColumn() {
        ConsoleScanProgressListener listener = new ConsoleScanProgressListener(true);
        listener.onScanStarted(List.of(shortName, longName), ScanOptions.defaults());

        // Время выровнено по самому длинному имени правила
        String first = listener.format(1, 2, shortName, NANOS_1234_MS);
        String second = listener.format(2, 2, longName, 14_000_000L);
        assertThat(first).startsWith("[1 / 2]   50%  " + shortName.name()).endsWith("   1234 мс");
        assertThat(second).isEqualTo("[2 / 2]  100%  TransactionOnPrivateMethodRule (jr:1)       14 мс");
        assertThat(first).hasSameSizeAs(second);
    }

    @Test
    void secondsAreUsedWhenOptionsSaySo() {
        ConsoleScanProgressListener listener = new ConsoleScanProgressListener(false);
        listener.onScanStarted(List.of(longName), ScanOptions.defaults().withTimeUnit(DurationUnit.SECONDS));

        assertThat(listener.format(1, 1, longName, NANOS_1234_MS))
                .isEqualTo("[1 / 1]  TransactionOnPrivateMethodRule (jr:1)     1.235 с");
    }

    @Test
    void everyRuleReportsItsTime(@TempDir Path dir) throws IOException {
        Files.writeString(dir.resolve("Sample.java"), "class Sample {}");
        List<String> events = new CopyOnWriteArrayList<>();
        ScanProgressListener listener = new ScanProgressListener() {
            @Override
            public void onScanStarted(List<Rule> rules, ScanOptions options) {
                events.add("scan " + rules.size() + " " + options.timeUnit().getCode());
            }

            @Override
            public void onRuleStarted(int number, int total, Rule rule) {
                events.add("start " + number + " " + rule.code());
            }

            @Override
            public void onRuleFinished(int number, int total, Rule rule, long nanos) {
                assertThat(nanos).isNotNegative();
                events.add("finish " + number + " " + rule.code());
            }
        };
        Scanner scanner = new Scanner(
                new FileSystemSourceLoader(),
                new FileSystemConfigLoader(),
                new FileSystemTextFileLoader(),
                List.of(new AutowiredOnStaticFieldRule(), new TransactionOnPrivateMethodRule()),
                listener,
                RuleSettings.empty());

        scanner.scan(dir, ScanOptions.defaults().withTimeUnit(DurationUnit.SECONDS));
        assertThat(events).containsExactly(
                "scan 2 s", "start 1 jr:1", "finish 1 jr:1", "start 2 jr:4", "finish 2 jr:4");

        // В несколько потоков порядок окончания не определен, но время сообщает каждое правило
        events.clear();
        scanner.scan(dir, ScanOptions.defaults().withThreads(2));
        assertThat(events).containsExactlyInAnyOrder(
                "scan 2 ms", "start 1 jr:1", "finish 1 jr:1", "start 2 jr:4", "finish 2 jr:4")
                .doesNotHaveDuplicates();
    }
}
