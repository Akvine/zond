package ru.akvine.zond.services;

import org.junit.jupiter.api.Test;
import ru.akvine.zond.models.ScanOptions;
import ru.akvine.zond.rules.Rule;
import ru.akvine.zond.rules.logical.TransactionOnPrivateMethodRule;
import ru.akvine.zond.ui.UiScanProgress;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class ScanProgressHubTest {
    private static final long NANOS = 5L;

    private final Rule rule = new TransactionOnPrivateMethodRule();

    // За ходом проверки следят и консоль, и страница в браузере: каждое событие должны получить оба
    @Test
    void everyListenerHearsEveryEvent() {
        List<String> heard = new ArrayList<>();
        ScanProgressHub hub = new ScanProgressHub(
                List.of(new Recorder("консоль", heard), new Recorder("страница", heard)));

        hub.onScanStarted(List.of(rule), ScanOptions.defaults());
        hub.onRuleStarted(1, 1, rule);
        hub.onRuleFinished(1, 1, rule, NANOS);

        assertThat(heard).containsExactly(
                "консоль: начало, правил 1", "страница: начало, правил 1",
                "консоль: правило 1 из 1", "страница: правило 1 из 1",
                "консоль: готово 1 за 5", "страница: готово 1 за 5");
    }

    @Test
    void pageProgressFollowsTheScan() {
        UiScanProgress progress = new UiScanProgress();
        ScanProgressHub hub = new ScanProgressHub(List.of(progress));

        hub.onScanStarted(List.of(rule, rule), ScanOptions.defaults());
        hub.onRuleStarted(1, 2, rule);
        hub.onRuleFinished(1, 2, rule, NANOS);
        UiScanProgress.Snapshot inTheMiddle = progress.snapshot();
        progress.reset();

        assertThat(inTheMiddle.finished()).isEqualTo(1);
        assertThat(inTheMiddle.total()).isEqualTo(2);
        assertThat(inTheMiddle.rule()).isEqualTo(rule.name() + " (" + rule.code() + ")");
        assertThat(progress.snapshot().total()).isZero();
        assertThat(progress.snapshot().rule()).isEmpty();
    }

    private record Recorder(String name, List<String> heard) implements ScanProgressListener {
        @Override
        public void onScanStarted(List<Rule> rules, ScanOptions options) {
            heard.add(name + ": начало, правил " + rules.size());
        }

        @Override
        public void onRuleStarted(int number, int total, Rule rule) {
            heard.add(name + ": правило " + number + " из " + total);
        }

        @Override
        public void onRuleFinished(int number, int total, Rule rule, long nanos) {
            heard.add(name + ": готово " + number + " за " + nanos);
        }
    }
}
