package ru.akvine.zond.rules;

import ru.akvine.zond.enums.Confidence;
import ru.akvine.zond.enums.ErrorLevel;
import ru.akvine.zond.enums.ErrorType;
import ru.akvine.zond.models.RuleParameter;
import ru.akvine.zond.models.SourceFile;
import ru.akvine.zond.models.Violation;

import java.util.List;

public interface Rule {

    String name();

    String code();

    String description();

    boolean enabled();

    /**
     * @return пороги правила, которые можно менять в настройках; у большинства правил их нет
     */
    default List<RuleParameter> parameters() {
        return List.of();
    }

    /**
     * Проверяет один исходный файл.
     *
     * @return найденные нарушения или пустой список
     */
    List<Violation> check(SourceFile sourceFile);


    ErrorLevel errorLevel();

    ErrorType errorType();

    /**
     * @return насколько правило уверено в своих находках, если сама находка этого не уточняет.
     * Замечание о качестве кода (длинный метод, лишний импорт) - факт, видный в тексте; остальное
     * без отдельного подтверждения считается вероятным
     */
    /**
     * @return true, если правило проверяет и тестовый код. По умолчанию правила проверяют только код приложения:
     * требования к нему - производительность, безопасность, устойчивость к null - к тестам не относятся,
     * а упавший тест и есть нужный исход ошибки в тесте
     */
    default boolean appliesToTests() {
        return false;
    }

    default Confidence confidence() {
        return errorType() == ErrorType.CODE_SMELL ? Confidence.CONFIRMED : Confidence.PROBABLE;
    }
}
