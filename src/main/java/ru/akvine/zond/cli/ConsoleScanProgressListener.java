package ru.akvine.zond.cli;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import ru.akvine.zond.config.ZondSettings;
import ru.akvine.zond.enums.DurationUnit;
import ru.akvine.zond.models.ScanOptions;
import ru.akvine.zond.rules.Rule;
import ru.akvine.zond.services.ScanProgressListener;

import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

@Component
public class ConsoleScanProgressListener implements ScanProgressListener {
    private static final int FULL = 100;
    private static final String PERCENT_FORMAT = "%4s";
    private static final String GAP = "  ";
    // Хватает на "9999.999 с" и на "9999999 мс": время стоит ровной колонкой
    private static final int TIME_WIDTH = 10;

    private final boolean showPercent;
    private final AtomicInteger finished = new AtomicInteger();

    private volatile DurationUnit unit = DurationUnit.MILLISECONDS;
    private volatile boolean parallel;
    // Ширина самого длинного "имя (код)": по ней выравнивается колонка времени
    private volatile int titleWidth;

    @Autowired
    public ConsoleScanProgressListener(ZondSettings settings) {
        this(settings.progressPercent());
    }

    public ConsoleScanProgressListener(boolean showPercent) {
        this.showPercent = showPercent;
    }

    @Override
    public void onScanStarted(List<Rule> rules, ScanOptions options) {
        unit = options.timeUnit();
        parallel = options.threadCount() > 1;
        titleWidth = rules.stream().mapToInt(rule -> title(rule).length()).max().orElse(0);
        finished.set(0);
    }

    // В один поток строка правила печатается сразу, а время дописывается в нее по окончании: так видно,
    // какое правило работает сейчас. В несколько потоков строки перемешались бы, поэтому там правило
    // печатается целиком, когда закончилось
    @Override
    public void onRuleStarted(int number, int total, Rule rule) {
        if (!parallel) {
            System.out.print(format(number, total, rule));
            System.out.flush();
        }
    }

    @Override
    public void onRuleFinished(int number, int total, Rule rule, long nanos) {
        if (parallel) {
            // Номер - по порядку окончания: иначе счетчик и процент шли бы вразнобой
            System.out.println(format(finished.incrementAndGet(), total, rule, nanos));
        } else {
            System.out.println(formatTime(rule, nanos));
        }
    }

    String format(int number, int total, Rule rule) {
        int width = String.valueOf(total).length();
        String counter = "[" + String.format("%" + width + "d", number) + " / " + total + "]";
        String percent = showPercent && total > 0
                ? GAP + String.format(PERCENT_FORMAT, number * FULL / total + "%")
                : "";
        return counter + percent + GAP + title(rule);
    }

    String format(int number, int total, Rule rule, long nanos) {
        return format(number, total, rule) + formatTime(rule, nanos);
    }

    // Продолжение строки правила: отступ до общей колонки и время, выровненное по правому краю
    String formatTime(Rule rule, long nanos) {
        int padding = Math.max(0, titleWidth - title(rule).length());
        return " ".repeat(padding) + GAP + String.format("%" + TIME_WIDTH + "s", unit.format(nanos));
    }

    private String title(Rule rule) {
        return rule.name() + " (" + rule.code() + ")";
    }
}
