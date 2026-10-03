package ru.akvine.zond.cli;

import org.springframework.stereotype.Component;
import ru.akvine.zond.rules.Rule;
import ru.akvine.zond.services.ScanProgressListener;

@Component
public class ConsoleScanProgressListener implements ScanProgressListener {

    @Override
    public void onRuleStarted(int number, int total, Rule rule) {
        System.out.println("[" + number + " / " + total + "] " + rule.name() + " (" + rule.code() + ")");
    }
}
