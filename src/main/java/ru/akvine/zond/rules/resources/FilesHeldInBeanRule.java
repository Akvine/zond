package ru.akvine.zond.rules.resources;

import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration;
import com.github.javaparser.ast.body.FieldDeclaration;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.body.VariableDeclarator;
import com.github.javaparser.ast.expr.MethodCallExpr;
import org.springframework.stereotype.Component;
import ru.akvine.zond.enums.ErrorLevel;
import ru.akvine.zond.enums.ErrorType;
import ru.akvine.zond.models.SourceFile;
import ru.akvine.zond.models.Violation;
import ru.akvine.zond.rules.AbstractRule;
import ru.akvine.zond.rules.RuleCodes;
import ru.akvine.zond.rules.support.CacheAnnotations;
import ru.akvine.zond.rules.support.CodeContexts;
import ru.akvine.zond.rules.support.SpringBeans;
import ru.akvine.zond.rules.support.TestClasses;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Файлы хранятся в памяти экземпляра приложения: в карте или списке внутри бина либо в кеше.
 * Так обычно делают "отложенную выгрузку" - отчет собрали, положили в карту по номеру задачи, клиент
 * придет за ним позже. Память растет на размер каждого файла, после перезапуска файлов нет, а запрос,
 * попавший на соседний экземпляр, файла не находит.
 */
@Component
public class FilesHeldInBeanRule extends AbstractRule {
    // Map<String, byte[]>, List<ByteArrayOutputStream>, Cache<UUID, MultipartFile>
    private static final Pattern FILE_HOLDER = Pattern.compile(
            "(Map|Cache|List|Set|Queue|Deque|Collection)\\w*<.*(byte\\[]|ByteArrayOutputStream|ByteArrayResource"
                    + "|MultipartFile|ByteBuffer)");
    // Набор постоянных значений (сигнатуры форматов, ключи), а не накопитель
    private static final Pattern CONSTANT = Pattern.compile(
            "^(Map\\.of|Map\\.ofEntries|List\\.of|Set\\.of|Collections\\.(unmodifiable|empty|singleton)"
                    + "|Arrays\\.asList|Immutable)");

    private static final Set<String> MUTATORS = Set.of(
            "put", "putIfAbsent", "putAll", "add", "addAll", "addFirst", "addLast", "offer", "push",
            "computeIfAbsent", "compute", "merge");
    private static final String THIS = "this.";

    private static final Set<String> CACHING = Set.of("Cacheable", "CachePut");
    private static final Pattern BYTES_RETURN = Pattern.compile(
            "^(byte\\[]|ByteArrayResource|(ResponseEntity|HttpEntity|Optional)<(byte\\[]|ByteArrayResource)>)$");

    @Override
    public String code() {
        return RuleCodes.FILES_HELD_IN_BEAN_RULE_CODE;
    }

    @Override
    public String description() {
        return "Сканирует код и ищет файлы, которые хранятся в памяти приложения: в поле бина или в кеше";
    }

    @Override
    public List<Violation> check(SourceFile sourceFile) {
        List<Violation> violations = new ArrayList<>();
        for (ClassOrInterfaceDeclaration type : sourceFile.unit().findAll(ClassOrInterfaceDeclaration.class)) {
            if (type.isInterface() || TestClasses.isInside(type)) {
                continue;
            }
            boolean bean = SpringBeans.isBean(type);
            for (FieldDeclaration field : type.getFields()) {
                if (bean || field.isStatic()) {
                    checkField(sourceFile, type, field, violations);
                }
            }
            for (MethodDeclaration method : type.getMethods()) {
                if (CacheAnnotations.has(method, CACHING) && BYTES_RETURN.matcher(method.getType().asString()).matches()) {
                    violations.add(violation(sourceFile, method.getName(),
                            "Метод '" + method.getNameAsString() + "' кладет в кеш файл целиком ("
                                    + method.getType().asString() + "): кеш в памяти растет на размер каждого"
                                    + " файла; кешируйте ссылку на файл или сведения о нем, а содержимое"
                                    + " отдавайте потоком из хранилища"));
                }
            }
        }
        return violations;
    }

    @Override
    public ErrorLevel errorLevel() {
        return ErrorLevel.MAJOR;
    }

    @Override
    public ErrorType errorType() {
        return ErrorType.RESOURCE;
    }

    // Карта, заполненная при создании (цвета, сигнатуры форматов), не растет: файлы в ней не копятся
    private boolean isFilledAtRuntime(ClassOrInterfaceDeclaration owner, String field) {
        return owner.findAll(MethodCallExpr.class).stream()
                .filter(call -> MUTATORS.contains(call.getNameAsString()))
                .filter(call -> call.getScope()
                        .map(scope -> scope.toString())
                        .filter(scope -> scope.equals(field) || scope.equals(THIS + field))
                        .isPresent())
                .anyMatch(call -> !CodeContexts.isStartup(call));
    }

    private void checkField(SourceFile sourceFile, ClassOrInterfaceDeclaration owner, FieldDeclaration field,
                            List<Violation> violations) {
        for (VariableDeclarator variable : field.getVariables()) {
            String type = variable.getType().asString();
            boolean constant = variable.getInitializer()
                    .filter(initializer -> CONSTANT.matcher(initializer.toString()).find())
                    .isPresent();
            if (!FILE_HOLDER.matcher(type).find() || constant || !isFilledAtRuntime(owner, variable.getNameAsString())) {
                continue;
            }
            violations.add(violation(sourceFile, variable,
                    "В поле '" + variable.getNameAsString() + "' класса '" + owner.getNameAsString()
                            + "' копятся файлы (" + type + "): они занимают память экземпляра, пропадают при"
                            + " перезапуске и не видны другим экземплярам приложения - запрос за файлом,"
                            + " попавший на соседний экземпляр, его не найдет; храните файлы во внешнем"
                            + " хранилище (диск, S3, база), а в памяти держите только ссылку"));
        }
    }
}
