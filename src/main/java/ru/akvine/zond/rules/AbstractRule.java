package ru.akvine.zond.rules;

import com.github.javaparser.ast.Node;
import ru.akvine.zond.models.SourceFile;
import ru.akvine.zond.models.Violation;

/**
 * Общая часть правил: имя, признак активности и сборка нарушения
 */
public abstract class AbstractRule implements Rule {

    @Override
    public String name() {
        return getClass().getSimpleName();
    }

    @Override
    public boolean enabled() {
        return true;
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
