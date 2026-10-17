package ru.akvine.zond.rules.support;

import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.ImportDeclaration;
import com.github.javaparser.ast.expr.Expression;
import com.github.javaparser.ast.expr.MethodCallExpr;
import lombok.experimental.UtilityClass;

import java.util.Optional;
import java.util.regex.Pattern;

@UtilityClass
public class Repositories {
    private final static String REPOSITORY_ANNOTATION = "Repository";

    // Имя объекта либо типа: userRepository, orderDao, JpaRepository
    private final static Pattern REPOSITORY = Pattern.compile(".*(repository|repo|dao)$", Pattern.CASE_INSENSITIVE);

    // USER_REPOSITORY - константа, а не внедренный бин
    private final static Pattern CONSTANT_NAME = Pattern.compile("^[A-Z][A-Z0-9_]*$");
    private final static String PACKAGE_SEPARATOR = ".";

    public boolean isRepository(String receiver) {
        return REPOSITORY.matcher(receiver).matches() && !CONSTANT_NAME.matcher(receiver).matches();
    }

    /**
     * @return true для вызова вида userRepository.findById(...)
     */
    public boolean isRepositoryCall(MethodCallExpr call) {
        return call.getScope().filter(Repositories::isRepositoryObject).isPresent();
    }

    // По типу: сам он либо его предок - репозиторий (interface Users extends JpaRepository), либо на нем @Repository.
    // Если тип неизвестен - по имени объекта
    private boolean isRepositoryObject(Expression scope) {
        if (Types.annotations(scope).contains(REPOSITORY_ANNOTATION)) {
            return true;
        }
        return Types.matches(scope, type -> REPOSITORY.matcher(type).matches())
                .orElseGet(() -> !isLibraryType(scope) && isRepository(MethodCalls.receiverName(scope)));
    }

    // ClassOrInterfaceDeclaration repository: тип объявлен в коде и импортирован из библиотеки (не из пакетов
    // проекта), а по имени на репозиторий не похож - имя переменной тут ничего не говорит
    private boolean isLibraryType(Expression scope) {
        Optional<String> declared = LocalTypes.typeOf(scope);
        Optional<CompilationUnit> unit = scope.findCompilationUnit();
        if (declared.isEmpty() || unit.isEmpty() || REPOSITORY.matcher(declared.get()).matches()) {
            return false;
        }
        String own = unit.get().getPackageDeclaration().map(declaration -> rootOf(declaration.getNameAsString())).orElse("");
        return !own.isEmpty() && unit.get().getImports().stream()
                .filter(declaration -> !declaration.isAsterisk() && !declaration.isStatic())
                .map(ImportDeclaration::getNameAsString)
                .filter(name -> name.endsWith(PACKAGE_SEPARATOR + declared.get()))
                .anyMatch(name -> !name.startsWith(own + PACKAGE_SEPARATOR));
    }

    // ru.akvine.zond.rules -> ru.akvine: первые две части имени пакета принадлежат проекту
    private String rootOf(String packageName) {
        String[] parts = packageName.split("\\.");
        return parts.length < 2 ? "" : parts[0] + PACKAGE_SEPARATOR + parts[1];
    }
}
