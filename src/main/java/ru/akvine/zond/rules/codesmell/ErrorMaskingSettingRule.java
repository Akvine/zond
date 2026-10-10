package ru.akvine.zond.rules.codesmell;

import org.springframework.stereotype.Component;
import ru.akvine.zond.enums.ErrorLevel;
import ru.akvine.zond.enums.ErrorType;
import ru.akvine.zond.models.ConfigFile;
import ru.akvine.zond.models.Violation;
import ru.akvine.zond.rules.AbstractConfigRule;
import ru.akvine.zond.rules.RuleCodes;

import java.util.ArrayList;
import java.util.List;

/**
 * Настройки, которые не чинят ошибку, а прячут ее: приложение запускается и работает, но причина остается
 * в коде и проявляется позже - медленными запросами, полуготовыми бинами, подмененной реализацией.
 */
@Component
public class ErrorMaskingSettingRule extends AbstractConfigRule {
    private static final String ENABLED = "true";

    /**
     * @param key     настройка
     * @param problem что она прячет и что делать вместо нее
     */
    private record Setting(String key, String problem) {
    }

    private static final List<Setting> SETTINGS = List.of(
            new Setting("spring.jpa.properties.hibernate.enable_lazy_load_no_trans",
                    "ленивая связь, прочитанная вне транзакции, не падает с LazyInitializationException, а молча"
                            + " открывает на каждое обращение новое соединение и транзакцию - запросов становится"
                            + " по одному на элемент, а данные читаются в разные моменты времени; загружайте нужное"
                            + " внутри транзакции (fetch join, @EntityGraph) и уберите настройку"),
            new Setting("spring.main.allow-circular-references",
                    "циклические зависимости между бинами разрешены: Spring Boot запрещает их по умолчанию, потому"
                            + " что бин из цикла получает зависимость, которая еще не готова; разорвите цикл -"
                            + " вынесите общую часть в третий бин - и уберите настройку"),
            new Setting("spring.main.allow-bean-definition-overriding",
                    "бин с тем же именем молча заменяет объявленный раньше: какая реализация работает, зависит"
                            + " от порядка загрузки, а случайное совпадение имен не даст ошибки при запуске;"
                            + " дайте бинам разные имена и уберите настройку"));

    @Override
    public String code() {
        return RuleCodes.ERROR_MASKING_SETTING_RULE_CODE;
    }

    @Override
    public String description() {
        return "Сканирует файлы настроек и ищет настройки, которые прячут ошибки: enable_lazy_load_no_trans,"
                + " allow-circular-references, allow-bean-definition-overriding";
    }

    @Override
    public List<Violation> checkConfig(ConfigFile configFile) {
        List<Violation> violations = new ArrayList<>();
        // В настройках тестов подмена бинов - обычный прием, а не спрятанная ошибка
        if (configFile.isNonProduction()) {
            return violations;
        }
        for (Setting setting : SETTINGS) {
            configFile.find(setting.key())
                    .filter(property -> property.value() != null && ENABLED.equalsIgnoreCase(property.value().trim()))
                    .ifPresent(property -> violations.add(violation(configFile, property.line(),
                            property.key() + "=true: " + setting.problem())));
        }
        return violations;
    }

    @Override
    public ErrorLevel errorLevel() {
        return ErrorLevel.MAJOR;
    }

    @Override
    public ErrorType errorType() {
        return ErrorType.CODE_SMELL;
    }
}
