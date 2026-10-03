package ru.akvine.zond.rules;

import com.github.javaparser.ast.Node;
import org.springframework.beans.factory.annotation.Autowired;
import ru.akvine.zond.config.RuleSettings;
import ru.akvine.zond.models.RuleParameter;
import ru.akvine.zond.models.SourceFile;
import ru.akvine.zond.models.Violation;

/**
 * Общая часть правил: имя, признак активности, настраиваемые пороги и сборка нарушения
 */
public abstract class AbstractRule implements Rule {
    // Без Spring (в тестах) правило работает со значениями по умолчанию
    private RuleSettings settings = RuleSettings.empty();

    @Autowired
    public void setSettings(RuleSettings settings) {
        this.settings = settings;
    }

    @Override
    public String name() {
        return getClass().getSimpleName();
    }

    @Override
    public boolean enabled() {
        return true;
    }

    /**
     * @return значение порога: из настроек (zond.rule.<правило>.<параметр>) либо по умолчанию
     */
    protected int value(RuleParameter parameter) {
        return settings.value(code(), name(), parameter);
    }

    protected Violation violation(SourceFile sourceFile, Node node, String message) {
        return new Violation(
                errorLevel(),
                errorType(),
                code(),
                name(),
                sourceFile.path(),
                node.getBegin().map(position -> position.line).orElse(0),
                message);
    }
}
