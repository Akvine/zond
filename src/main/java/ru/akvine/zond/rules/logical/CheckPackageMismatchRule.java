package ru.akvine.zond.rules.logical;

import com.github.javaparser.ast.PackageDeclaration;
import org.springframework.stereotype.Component;
import ru.akvine.zond.enums.ErrorLevel;
import ru.akvine.zond.enums.ErrorType;
import ru.akvine.zond.models.SourceFile;
import ru.akvine.zond.models.Violation;
import ru.akvine.zond.rules.AbstractRule;
import ru.akvine.zond.rules.RuleCodes;

import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;

@Component
public class CheckPackageMismatchRule extends AbstractRule {
    private static final String PACKAGE_SEPARATOR = "\\.";

    @Override
    public String code() {
        return RuleCodes.CHECK_PACKAGE_MISMATCH_RULE_CODE;
    }

    @Override
    public String description() {
        return "Сканирует код и ищет файлы, у которых package не совпадает с путем к файлу";
    }

    @Override
    public List<Violation> check(SourceFile sourceFile) {
        Optional<PackageDeclaration> declaration = sourceFile.unit().getPackageDeclaration();
        Path directory = sourceFile.path().toAbsolutePath().normalize().getParent();
        if (declaration.isEmpty() || directory == null) {
            return List.of();
        }

        // ru.akvine.zond -> каталог должен заканчиваться на ru/akvine/zond
        String[] parts = declaration.get().getNameAsString().split(PACKAGE_SEPARATOR);
        Path expected = Path.of(parts[0], Arrays.copyOfRange(parts, 1, parts.length));
        if (directory.endsWith(expected)) {
            return List.of();
        }

        return List.of(violation(sourceFile, declaration.get(),
                "package '" + declaration.get().getNameAsString() + "' не совпадает с расположением файла '"
                        + directory + "': класс не найдется по своему полному имени, а сборка положит его не туда;"
                        + " перенесите файл либо исправьте package"));
    }

    @Override
    public ErrorLevel errorLevel() {
        return ErrorLevel.MAJOR;
    }

    @Override
    public ErrorType errorType() {
        return ErrorType.LOGICAL;
    }
}
