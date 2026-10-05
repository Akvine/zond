package ru.akvine.zond.services;

import ru.akvine.zond.models.ScanOptions;
import ru.akvine.zond.rules.Rule;

import java.util.List;

public interface ScanProgressListener {

    /**
     * Вызывается один раз перед первым правилом
     *
     * @param rules правила, которые будут проверены, в порядке запуска
     */
    default void onScanStarted(List<Rule> rules, ScanOptions options) {
    }

    /**
     * @param number порядковый номер правила, начиная с 1
     * @param total  сколько всего правил будет проверено
     */
    void onRuleStarted(int number, int total, Rule rule);

    /**
     * Вызывается и тогда, когда правило завершилось ошибкой. При работе в несколько потоков правила
     * заканчиваются не в том порядке, в каком начались.
     *
     * @param number тот же номер, что был у правила в начале
     * @param nanos  сколько правило работало, в наносекундах
     */
    default void onRuleFinished(int number, int total, Rule rule, long nanos) {
    }
}
