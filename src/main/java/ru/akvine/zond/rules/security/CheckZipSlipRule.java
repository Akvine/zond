package ru.akvine.zond.rules.security;

import com.github.javaparser.ast.Node;
import com.github.javaparser.ast.expr.MethodCallExpr;
import com.github.javaparser.ast.expr.ObjectCreationExpr;
import org.springframework.stereotype.Component;
import ru.akvine.zond.enums.ErrorLevel;
import ru.akvine.zond.enums.ErrorType;
import ru.akvine.zond.models.SourceFile;
import ru.akvine.zond.models.Violation;
import ru.akvine.zond.rules.AbstractRule;
import ru.akvine.zond.rules.RuleCodes;
import ru.akvine.zond.rules.support.LocalTypes;
import ru.akvine.zond.rules.support.MethodCalls;
import ru.akvine.zond.rules.support.Nodes;

import java.util.List;
import java.util.Set;

@Component
public class CheckZipSlipRule extends AbstractRule {
    private static final String GET_NAME = "getName";
    private static final Set<String> ARCHIVE_ENTRY_TYPES =
            Set.of("ZipEntry", "JarEntry", "ZipArchiveEntry", "TarArchiveEntry", "ArchiveEntry");

    // Куда имя записи архива попадает как часть пути
    private static final String RESOLVE = "resolve";
    private static final Set<String> PATH_FACTORIES = Set.of("Paths", "Path");
    private static final Set<String> PATH_FACTORY_METHODS = Set.of("get", "of");
    private static final Set<String> SANITIZERS =
            Set.of("normalize", "getCanonicalPath", "getCanonicalFile", "toRealPath", "startsWith");

    @Override
    public String code() {
        return RuleCodes.CHECK_ZIP_SLIP_RULE_CODE;
    }

    @Override
    public String description() {
        return "Сканирует код и ищет распаковку архива, где имя записи используется как путь без проверки (Zip Slip)";
    }

    @Override
    public List<Violation> check(SourceFile sourceFile) {
        return sourceFile.unit().findAll(MethodCallExpr.class).stream()
                .filter(call -> GET_NAME.equals(call.getNameAsString()) && call.getArguments().isEmpty())
                .filter(call -> call.getScope()
                        .flatMap(LocalTypes::typeOf)
                        .filter(ARCHIVE_ENTRY_TYPES::contains)
                        .isPresent())
                .filter(this::isUsedAsPath)
                .filter(call -> !isSanitized(call))
                .map(call -> violation(sourceFile, call,
                        "Имя записи архива '" + call + "' используется как путь без проверки: запись с именем"
                                + " ../../file перезапишет файл за пределами каталога распаковки (Zip Slip);"
                                + " после resolve() вызовите normalize() и проверьте startsWith(каталог)"))
                .toList();
    }

    @Override
    public ErrorLevel errorLevel() {
        return ErrorLevel.CRITICAL;
    }

    @Override
    public ErrorType errorType() {
        return ErrorType.SECURITY;
    }

    // new File(dir, entry.getName()), dir.resolve(entry.getName()), Paths.get(dir, entry.getName())
    private boolean isUsedAsPath(MethodCallExpr entryName) {
        Node parent = entryName.getParentNode().orElse(null);
        if (parent instanceof ObjectCreationExpr) {
            return true;
        }
        if (!(parent instanceof MethodCallExpr call)) {
            return false;
        }
        // get и of считаем только на Paths / Path: иначе под правило попадет map.get(entry.getName())
        return RESOLVE.equals(call.getNameAsString())
                || (PATH_FACTORY_METHODS.contains(call.getNameAsString())
                && call.getScope().map(MethodCalls::receiverName).filter(PATH_FACTORIES::contains).isPresent());
    }

    private boolean isSanitized(Node node) {
        return Nodes.enclosingCallable(node)
                .filter(callable -> callable.findAll(MethodCallExpr.class).stream()
                        .anyMatch(call -> SANITIZERS.contains(call.getNameAsString())))
                .isPresent();
    }
}
