package ru.akvine.zond.services;

import ru.akvine.zond.rules.Rule;

public interface ScanProgressListener {

    /**
     * @param number порядковый номер правила, начиная с 1
     * @param total  сколько всего правил будет проверено
     */
    void onRuleStarted(int number, int total, Rule rule);
}
