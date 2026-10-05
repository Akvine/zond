package ru.akvine.zond.cli;

import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;
import ru.akvine.zond.config.ZondSettings;
import ru.akvine.zond.rules.Rule;
import ru.akvine.zond.rules.logical.CheckTransactionOnPrivateMethodRule;

import static org.assertj.core.api.Assertions.assertThat;

class ConsoleScanProgressListenerTest {
    private final Rule rule = new CheckTransactionOnPrivateMethodRule();

    @Test
    void showsPercentInItsOwnColumn() {
        ConsoleScanProgressListener listener = new ConsoleScanProgressListener(true);

        // Счетчик и процент выровнены по ширине: строки хода сканирования стоят ровными колонками
        assertThat(listener.format(5, 328, rule)).isEqualTo("[  5 / 328]    1%  CheckTransactionOnPrivateMethodRule (jr:1)");
        assertThat(listener.format(164, 328, rule)).isEqualTo("[164 / 328]   50%  CheckTransactionOnPrivateMethodRule (jr:1)");
        assertThat(listener.format(328, 328, rule)).isEqualTo("[328 / 328]  100%  CheckTransactionOnPrivateMethodRule (jr:1)");
    }

    @Test
    void percentCanBeSwitchedOff() {
        ConsoleScanProgressListener listener = new ConsoleScanProgressListener(false);

        assertThat(listener.format(5, 328, rule)).isEqualTo("[  5 / 328]  CheckTransactionOnPrivateMethodRule (jr:1)");
    }

    @Test
    void percentIsShownUnlessSettingSaysFalse() {
        assertThat(settings(new MockEnvironment()).progressPercent()).isTrue();
        assertThat(settings(new MockEnvironment().withProperty("zond.progress.percent", "true")).progressPercent()).isTrue();
        assertThat(settings(new MockEnvironment().withProperty("zond.progress.percent", "false")).progressPercent()).isFalse();
    }

    private ZondSettings settings(MockEnvironment environment) {
        return new ZondSettings("", "", "", "", "", "", "", environment);
    }
}
