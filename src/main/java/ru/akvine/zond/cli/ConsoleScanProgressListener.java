package ru.akvine.zond.cli;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import ru.akvine.zond.config.ZondSettings;
import ru.akvine.zond.rules.Rule;
import ru.akvine.zond.services.ScanProgressListener;

@Component
public class ConsoleScanProgressListener implements ScanProgressListener {
    private static final int FULL = 100;
    // Ширина колонки с процентом: "100%"
    private static final String PERCENT_FORMAT = "%4s";

    private final boolean showPercent;

    @Autowired
    public ConsoleScanProgressListener(ZondSettings settings) {
        this(settings.progressPercent());
    }

    public ConsoleScanProgressListener(boolean showPercent) {
        this.showPercent = showPercent;
    }

    @Override
    public void onRuleStarted(int number, int total, Rule rule) {
        System.out.println(format(number, total, rule));
    }

    /**
     * @return строка хода сканирования: счетчик, процент отдельной колонкой (если он включен) и правило.
     * Счетчик и процент выровнены по ширине, чтобы колонки не прыгали
     */
    String format(int number, int total, Rule rule) {
        int width = String.valueOf(total).length();
        String counter = "[" + String.format("%" + width + "d", number) + " / " + total + "]";
        String percent = showPercent && total > 0
                ? "  " + String.format(PERCENT_FORMAT, number * FULL / total + "%")
                : "";
        return counter + percent + "  " + rule.name() + " (" + rule.code() + ")";
    }
}
