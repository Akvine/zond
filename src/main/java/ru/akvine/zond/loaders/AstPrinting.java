package ru.akvine.zond.loaders;

import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.Node;
import com.github.javaparser.printer.DefaultPrettyPrinter;
import com.github.javaparser.printer.configuration.DefaultConfigurationOption;
import com.github.javaparser.printer.configuration.DefaultPrinterConfiguration;
import com.github.javaparser.printer.configuration.DefaultPrinterConfiguration.ConfigOption;
import com.github.javaparser.printer.configuration.PrinterConfiguration;
import com.github.javaparser.utils.LineSeparator;
import lombok.experimental.UtilityClass;

/**
 * Готовит разобранный файл к тому, что его узлы будут печатать (toString) несколько потоков сразу.
 * <p>
 * У файла в JavaParser одни настройки печати на все его узлы. toString() файла целиком при каждом вызове
 * заново вписывает в них символ конца строки: удаляет прежнюю запись и добавляет новую. toString() любого
 * узла того же файла в это время эти настройки читает. Когда два правила в разных потоках печатают один
 * файл, читающее получает ConcurrentModificationException без текста - в консоли это выглядело как
 * "Ошибка: null" и повторялось не при каждом запуске.
 * <p>
 * Здесь символ конца строки вписывается один раз, а пометка, по которой JavaParser делал бы это при каждой
 * печати, снимается: дальше настройки только читаются. Заодно печать узла перестает зависеть от того,
 * печатал ли кто-то раньше файл целиком: конец строки всегда тот же, что в самом файле
 */
@UtilityClass
class AstPrinting {

    /**
     * Вызывается сразу после разбора, пока с файлом работает один поток
     */
    void prepare(CompilationUnit unit) {
        // Тот же символ, который JavaParser вписал бы сам: как в файле, а если по файлу его не понять
        // (одна строка, смесь разных концов строк) - принятый в системе
        LineSeparator separator = unit.getLineEndingStyleOrDefault(LineSeparator.SYSTEM);
        PrinterConfiguration configuration = new DefaultPrinterConfiguration().addOption(
                new DefaultConfigurationOption(ConfigOption.END_OF_LINE_CHARACTER, separator.asRawString()));
        unit.printer(new DefaultPrettyPrinter(configuration));
        // Без этой пометки toString() файла настройки печати больше не трогает
        unit.removeData(Node.LINE_SEPARATOR_KEY);
    }
}
