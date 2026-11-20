package ru.akvine.zond.rules.security;

import com.github.javaparser.ast.Node;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.expr.Expression;
import com.github.javaparser.ast.expr.MethodCallExpr;
import org.springframework.stereotype.Component;
import ru.akvine.zond.enums.ErrorLevel;
import ru.akvine.zond.enums.ErrorType;
import ru.akvine.zond.models.SourceFile;
import ru.akvine.zond.models.Violation;
import ru.akvine.zond.rules.AbstractTaintRule;
import ru.akvine.zond.rules.RuleCodes;
import ru.akvine.zond.rules.support.LocalTypes;
import ru.akvine.zond.rules.support.MethodCalls;
import ru.akvine.zond.rules.support.Nodes;
import ru.akvine.zond.rules.support.Taint;
import ru.akvine.zond.rules.support.Types;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;

@Component
public class PathTraversalRule extends AbstractTaintRule {
    private static final Set<String> FILE_TYPES =
            Set.of("File", "FileInputStream", "FileOutputStream", "FileReader", "FileWriter", "RandomAccessFile");
    private static final Set<String> PATH_FACTORIES = Set.of("Paths", "Path");
    private static final Set<String> PATH_FACTORY_METHODS = Set.of("get", "of");
    private static final String RESOLVE = "resolve";
    private static final Set<String> PATH_TYPES = Set.of("Path");
    // Готовый объект файла или потока: пути в нем уже нет либо он задан раньше
    private static final Set<String> PATH_OBJECT_TYPES = Set.of(
            "File", "Path", "InputStream", "FileInputStream", "MultipartFile", "Resource", "FileDescriptor");
    private static final Set<String> PATH_RETURNING_METHODS = Set.of(
            "get", "of", "toPath", "getParent", "resolve", "resolveSibling", "normalize", "toAbsolutePath",
            "toRealPath", "getRoot", "relativize");
    // uploadDir.resolve(name), getStorageRoot().resolve(name): тип неизвестен, но имя говорит о каталоге
    private static final Pattern PATH_NAME = Pattern.compile(
            "(?i).*(path|dir|directory|folder|root|base|location|home|target|dest|destination|storage|upload|"
                    + "workspace|temp|tmp|parent|file).*");
    // Насколько глубоко искать проверку во вспомогательных методах
    private static final int HELPER_DEPTH = 2;
    private static final Set<String> NAME_ONLY_METHODS = Set.of("getName", "getFileName");

    // Проверки, после которых путь не может выйти за пределы каталога
    private static final Set<String> SANITIZERS = Set.of(
            "normalize", "cleanPath", "getCanonicalPath", "getCanonicalFile", "toRealPath", "startsWith", "checkSlip");
    // FilenameUtils.getName(name), FileNameUtil.getName(name): от присланного пути остается одно имя файла
    private static final Set<String> NAME_EXTRACTORS = Set.of("getName", "getBaseName", "mainName");
    private static final Pattern FILE_NAME_UTILITIES = Pattern.compile(".*(FilenameUtils|FileNameUtil|FileNameUtils)$");
    // if (name.contains("..")) - путь с выходом на каталог выше отклоняют явно
    private static final Set<String> TEXT_SEARCHES = Set.of("contains", "indexOf", "matches");
    private static final String PARENT_DIRECTORY = "..";
    // Своя проверка имени файла в проекте: isIllegalFileName(name), checkAllowDownload(name), validateFilename(name)
    private static final Pattern OWN_CHECKS = Pattern.compile(
            "(?i)(is|check|assert|ensure|verify)\\w*(illegal|legal|valid|allow|safe|secure|slip|traversal)\\w*"
                    + "|validate\\w*(file|path|name)\\w*");

    @Override
    public String code() {
        return RuleCodes.PATH_TRAVERSAL_RULE_CODE;
    }

    @Override
    public String description() {
        return "Сканирует код и ищет пути к файлам, собранные из параметров запроса без проверки";
    }

    @Override
    protected List<Violation> check(SourceFile sourceFile, Taint taint) {
        List<Violation> violations = new ArrayList<>();
        for (Expression expression : sourceFile.findAll(Expression.class)) {
            List<Expression> arguments = pathArguments(expression);
            if (arguments.isEmpty() || isSanitized(expression) || isNameOnly(expression)) {
                continue;
            }
            Optional<Taint.Source> input = Optional.empty();
            for (Expression argument : arguments) {
                // new FileInputStream(file): готовый File или Path - не строка от клиента; путь задали там,
                // где этот объект создали, и если он собран из запроса, находка будет на том месте
                if (input.isEmpty() && !isPathObject(argument)) {
                    input = taint.findSource(argument);
                }
            }
            // Значение проверено в том методе, куда оно пришло: контроллер вызвал checkAllowDownload(name),
            // а файл открывает уже сервис
            if (input.isPresent() && !isSanitizedAt(input.get().callable())) {
                violations.add(violation(sourceFile, expression,
                        "Путь к файлу строится из данных запроса '" + input.get() + "' без проверки: значение"
                                + " вида ../../etc/passwd выведет за пределы каталога; приведите путь через"
                                + " normalize() и проверьте, что он начинается с разрешенного каталога"));
            }
        }
        return violations;
    }

    // Данные попадают в путь к файлу: экранирование для другого места их не обезвреживает
    @Override
    protected Taint.Sink sink() {
        return Taint.Sink.PATH;
    }

    @Override
    public ErrorLevel errorLevel() {
        return ErrorLevel.CRITICAL;
    }

    @Override
    public ErrorType errorType() {
        return ErrorType.SECURITY;
    }

    // new File(...), Paths.get(...), Path.of(...), base.resolve(...)
    private List<Expression> pathArguments(Expression expression) {
        if (expression.isObjectCreationExpr()
                && FILE_TYPES.contains(expression.asObjectCreationExpr().getType().getNameAsString())) {
            return expression.asObjectCreationExpr().getArguments();
        }
        if (!expression.isMethodCallExpr()) {
            return List.of();
        }

        MethodCallExpr call = expression.asMethodCallExpr();
        boolean isFactory = PATH_FACTORY_METHODS.contains(call.getNameAsString())
                && call.getScope().map(MethodCalls::receiverName).filter(PATH_FACTORIES::contains).isPresent();
        // base.resolve(name) у пути; SchemaType.resolve(type, name) у класса, свой resolve(name) и
        // dependencyResolver.resolve(descriptors) у объекта другого типа - другие методы
        boolean isResolve = RESOLVE.equals(call.getNameAsString())
                && call.getScope().filter(scope -> !isClassReference(scope) && isPath(scope)).isPresent();
        return isFactory || isResolve ? call.getArguments() : List.of();
    }

    // Получатель resolve(...) - путь: по типу, а когда тип неизвестен - по тому, как он получен или назван
    private boolean isPath(Expression scope) {
        Expression value = Nodes.unwrap(scope);
        Optional<String> type = LocalTypes.typeOf(value);
        if (type.isPresent()) {
            return PATH_TYPES.contains(type.get());
        }
        if (value.isMethodCallExpr()) {
            MethodCallExpr source = value.asMethodCallExpr();
            return PATH_RETURNING_METHODS.contains(source.getNameAsString())
                    || PATH_NAME.matcher(source.getNameAsString()).matches();
        }
        String name = value.isNameExpr() ? value.asNameExpr().getNameAsString()
                : value.isFieldAccessExpr() ? value.asFieldAccessExpr().getNameAsString() : "";
        return PATH_NAME.matcher(name).matches();
    }

    private boolean isPathObject(Expression argument) {
        return LocalTypes.typeOf(Nodes.unwrap(argument)).filter(PATH_OBJECT_TYPES::contains).isPresent();
    }

    private boolean isClassReference(Expression scope) {
        return scope.isNameExpr() && Character.isUpperCase(scope.asNameExpr().getNameAsString().charAt(0))
                && LocalTypes.findDeclaration(scope).isEmpty();
    }

    // new File(path).getName(), Paths.get(path).getFileName(): от пути берут только имя файла без каталогов
    private boolean isNameOnly(Expression path) {
        return path.getParentNode()
                .filter(parent -> parent instanceof MethodCallExpr call
                        && call.getScope().filter(scope -> Nodes.unwrap(scope) == path || scope == path).isPresent()
                        && NAME_ONLY_METHODS.contains(call.getNameAsString()))
                .isPresent();
    }

    // Где-либо в методе путь нормализуют или сверяют с базовым каталогом
    private boolean isSanitized(Node node) {
        return Nodes.enclosingCallable(node).filter(this::isSanitizedAt).isPresent();
    }

    private boolean isSanitizedAt(Node callable) {
        return callable != null && sanitizes(callable, HELPER_DEPTH);
    }

    /**
     * Проверка стоит в самом методе либо в методе проекта, который он вызывает: fileUploadCheck(file) ->
     * isIllegalFileName(name). Чужие методы не разбираются: о них говорит только имя
     */
    private boolean sanitizes(Node callable, int depth) {
        for (MethodCallExpr call : callable.findAll(MethodCallExpr.class)) {
            if (SANITIZERS.contains(call.getNameAsString()) || takesNameOnly(call) || rejectsParentDirectory(call)
                    || OWN_CHECKS.matcher(call.getNameAsString()).matches() && !call.getArguments().isEmpty()) {
                return true;
            }
            if (depth > 0 && !call.getArguments().isEmpty()) {
                Optional<MethodDeclaration> helper = Types.declaration(call);
                if (helper.isPresent() && helper.get() != callable && sanitizes(helper.get(), depth - 1)) {
                    return true;
                }
            }
        }
        return false;
    }

    private boolean takesNameOnly(MethodCallExpr call) {
        return NAME_EXTRACTORS.contains(call.getNameAsString()) && call.getScope()
                .filter(scope -> FILE_NAME_UTILITIES.matcher(scope.toString()).matches())
                .isPresent();
    }

    private boolean rejectsParentDirectory(MethodCallExpr call) {
        return TEXT_SEARCHES.contains(call.getNameAsString()) && call.getArguments().stream()
                .anyMatch(argument -> argument.isStringLiteralExpr()
                        && argument.asStringLiteralExpr().getValue().contains(PARENT_DIRECTORY));
    }
}
