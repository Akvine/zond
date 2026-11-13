package ru.akvine.zond.ui;

import org.springframework.stereotype.Component;
import ru.akvine.zond.models.ScanOptions;
import ru.akvine.zond.rules.Rule;
import ru.akvine.zond.services.ScanProgressListener;

import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Запоминает ход сканирования, чтобы страница в браузере могла его показать: сколько правил всего,
 * сколько уже отработало и какое началось последним
 */
@Component
public class UiScanProgress implements ScanProgressListener {
    private final AtomicInteger total = new AtomicInteger();
    private final AtomicInteger finished = new AtomicInteger();
    private volatile String current = "";

    /**
     * @param finished сколько правил уже отработало
     * @param total    сколько правил в этом запуске; 0 - код еще загружается
     * @param rule     правило, которое началось последним: «имя (код)»
     */
    public record Snapshot(int finished, int total, String rule) {
    }

    @Override
    public void onScanStarted(List<Rule> rules, ScanOptions options) {
        total.set(rules.size());
        finished.set(0);
        current = "";
    }

    @Override
    public void onRuleStarted(int number, int count, Rule rule) {
        total.set(count);
        current = rule.name() + " (" + rule.code() + ")";
    }

    @Override
    public void onRuleFinished(int number, int count, Rule rule, long nanos) {
        finished.incrementAndGet();
    }

    // Перед новым запуском: пока код загружается, страница не должна показывать числа прошлого запуска
    public void reset() {
        total.set(0);
        finished.set(0);
        current = "";
    }

    public Snapshot snapshot() {
        return new Snapshot(finished.get(), total.get(), current);
    }
}
