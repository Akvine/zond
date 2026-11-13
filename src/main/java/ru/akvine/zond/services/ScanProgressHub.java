package ru.akvine.zond.services;

import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Component;
import ru.akvine.zond.models.ScanOptions;
import ru.akvine.zond.rules.Rule;

import java.util.List;

/**
 * Раздает ход сканирования всем, кто за ним следит: консоли и странице в браузере.
 * Сканер знает только об этом получателе
 */
@Primary
@Component
public class ScanProgressHub implements ScanProgressListener {
    private final List<ScanProgressListener> listeners;

    public ScanProgressHub(List<ScanProgressListener> listeners) {
        this.listeners = List.copyOf(listeners);
    }

    @Override
    public void onScanStarted(List<Rule> rules, ScanOptions options) {
        listeners.forEach(listener -> listener.onScanStarted(rules, options));
    }

    @Override
    public void onRuleStarted(int number, int total, Rule rule) {
        listeners.forEach(listener -> listener.onRuleStarted(number, total, rule));
    }

    @Override
    public void onRuleFinished(int number, int total, Rule rule, long nanos) {
        listeners.forEach(listener -> listener.onRuleFinished(number, total, rule, nanos));
    }
}
