package ru.akvine.zond.rules.security;

import com.github.javaparser.ast.Node;
import com.github.javaparser.ast.body.FieldDeclaration;
import com.github.javaparser.ast.body.VariableDeclarator;
import com.github.javaparser.ast.expr.BinaryExpr;
import com.github.javaparser.ast.expr.Expression;
import com.github.javaparser.ast.expr.MethodCallExpr;
import com.github.javaparser.ast.expr.Name;
import com.github.javaparser.ast.expr.NameExpr;
import com.github.javaparser.ast.expr.ObjectCreationExpr;
import com.github.javaparser.ast.expr.SimpleName;
import com.github.javaparser.ast.stmt.DoStmt;
import com.github.javaparser.ast.stmt.WhileStmt;
import org.springframework.stereotype.Component;
import ru.akvine.zond.enums.Confidence;
import ru.akvine.zond.enums.ErrorLevel;
import ru.akvine.zond.enums.ErrorType;
import ru.akvine.zond.models.SourceFile;
import ru.akvine.zond.models.Violation;
import ru.akvine.zond.rules.AbstractRule;
import ru.akvine.zond.rules.RuleCodes;
import ru.akvine.zond.rules.support.LocalTypes;
import ru.akvine.zond.rules.support.Nodes;
import ru.akvine.zond.rules.support.TestClasses;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * IOUtils.toByteArray(new GZIPInputStream(upload)), zis.readAllBytes(), цикл read/write по ZipInputStream без
 * счетчика: архив в килобайты разворачивается в гигабайты (zip-бомба), и приложение падает от нехватки памяти
 * или места на диске. Распаковывать нужно с пределом - по размеру записи и по общему объему
 */
@Component
public class UnboundedDecompressionRule extends AbstractRule {
    private static final Set<String> DECOMPRESSING_STREAMS = Set.of(
            "ZipInputStream", "GZIPInputStream", "InflaterInputStream", "ZipArchiveInputStream",
            "GzipCompressorInputStream", "BZip2CompressorInputStream", "XZCompressorInputStream",
            "JarInputStream", "SevenZFile", "TarArchiveInputStream");
    private static final Set<String> COMPRESSING_STREAMS = Set.of(
            "GZIPOutputStream", "DeflaterOutputStream", "ZipOutputStream", "Deflater", "GzipCompressorOutputStream");
    private static final String GET_INPUT_STREAM = "getInputStream";
    private static final Pattern ZIP_FILE = Pattern.compile("(?i).*zip.*|.*jar.*|.*archive.*");
    // Прочитать все до конца: из потока, а потом в память, файл или другой поток
    private static final Set<String> READ_ALL = Set.of("readAllBytes", "transferTo");
    private static final Set<String> COPY_ALL = Set.of(
            "toByteArray", "copy", "copyLarge", "toString", "copyToByteArray", "copyToString", "readAllBytes");
    private static final String READ = "read";
    private static final Set<BinaryExpr.Operator> COMPARISONS = Set.of(BinaryExpr.Operator.LESS,
            BinaryExpr.Operator.LESS_EQUALS, BinaryExpr.Operator.GREATER, BinaryExpr.Operator.GREATER_EQUALS);
    // Предел есть: имя с max или limit, размер записи, ограничивающая обертка
    private static final Pattern LIMIT = Pattern.compile(
            "(?i).*(max|limit|bound|quota|threshold|allowed).*|getSize|getCompressedSize|BoundedInputStream"
                    + "|CountingInputStream|LimitedInputStream|LimitInputStream");
    // Архив из ресурсов самого приложения: его собрали вместе с кодом
    private static final Pattern CLASSPATH_RESOURCE = Pattern.compile(
            "getResourceAsStream|getSystemResourceAsStream|getResource\\(");
    // Через сколько переменных прослеживается, откуда взялся архив
    private static final int ORIGIN_DEPTH = 3;
    // Файл принимает данные извне: загрузку, тело запроса, ответ по сети
    private static final Pattern UNTRUSTED_INPUT = Pattern.compile(
            "MultipartFile|HttpServletRequest|ServerHttpRequest|ServerRequest|FilePart|RequestBody|RequestParam"
                    + "|RequestPart|PostMapping|PutMapping|FormDataParam|URLConnection|HttpURLConnection"
                    + "|openStream|openConnection|(?i).*upload.*");

    @Override
    public String code() {
        return RuleCodes.UNBOUNDED_DECOMPRESSION_RULE_CODE;
    }

    @Override
    public String description() {
        return "Сканирует код и ищет распаковку ZIP, GZIP и других архивов целиком без предела размера (zip-бомба)";
    }

    @Override
    public List<Violation> check(SourceFile sourceFile) {
        List<Node> places = new ArrayList<>();
        sourceFile.unit().findAll(MethodCallExpr.class).stream()
                .filter(this::readsEverything)
                .forEach(places::add);
        Stream.concat(sourceFile.unit().findAll(WhileStmt.class).stream().map(WhileStmt::getCondition),
                        sourceFile.unit().findAll(DoStmt.class).stream().map(DoStmt::getCondition))
                .filter(this::isCopyLoop)
                .map(condition -> condition.getParentNode().orElse(condition))
                .forEach(places::add);
        // Архив без признаков данных извне - свой файл, артефакт сборки, ресурс: скорее всего ему можно верить
        Confidence confidence = takesUntrustedInput(sourceFile) ? null : Confidence.SUSPICION;
        return places.stream()
                .filter(place -> !TestClasses.isInside(place) && !hasLimit(place))
                .map(place -> violation(sourceFile, place, message()).withConfidence(confidence))
                .toList();
    }

    @Override
    public ErrorLevel errorLevel() {
        return ErrorLevel.MAJOR;
    }

    @Override
    public ErrorType errorType() {
        return ErrorType.SECURITY;
    }

    private String message() {
        return "Архив распаковывается целиком без предела размера: присланный файл в килобайты развернется в"
                + " гигабайты (zip-бомба), и приложению не хватит памяти или места на диске; считайте прочитанные"
                + " байты и прерывайте распаковку на заданном пределе, проверяйте размер и число записей";
    }

    // new ZipInputStream(in), zipFile.getInputStream(entry)
    private boolean isDecompressing(Expression expression) {
        Expression value = Nodes.unwrap(expression);
        if (value instanceof ObjectCreationExpr creation) {
            return DECOMPRESSING_STREAMS.contains(creation.getType().getNameAsString());
        }
        return value instanceof MethodCallExpr call && GET_INPUT_STREAM.equals(call.getNameAsString())
                && call.getArguments().size() == 1
                && call.getScope().filter(scope -> ZIP_FILE.matcher(scope.toString()).matches()).isPresent();
    }

    // Само создание потока либо переменная, объявленная с ним или с его типом
    private boolean isStream(Expression expression) {
        Expression value = Nodes.unwrap(expression);
        if (isDecompressing(value)) {
            return !isTrusted(value);
        }
        Optional<Node> declaration = LocalTypes.findDeclaration(value);
        if (declaration.isEmpty()) {
            return false;
        }
        Optional<Expression> initializer = declaration.get() instanceof VariableDeclarator variable
                ? variable.getInitializer().map(Nodes::unwrap)
                : Optional.empty();
        if (initializer.filter(this::isDecompressing).isPresent()) {
            return !isTrusted(initializer.get());
        }
        return LocalTypes.declaredType(declaration.get()).filter(DECOMPRESSING_STREAMS::contains).isPresent();
    }

    // getResourceAsStream(...) или данные, которые класс сжал сам и держит в поле
    private boolean isTrusted(Expression decompressing) {
        return CLASSPATH_RESOURCE.matcher(originOf(decompressing, 0)).find() || isOwnCompressedData(decompressing);
    }

    // Текст выражения и начальных значений переменных, из которых оно собрано
    private String originOf(Expression expression, int depth) {
        StringBuilder origin = new StringBuilder(expression.toString());
        if (depth < ORIGIN_DEPTH) {
            for (NameExpr name : expression.findAll(NameExpr.class)) {
                LocalTypes.findInitializer(name)
                        .ifPresent(initializer -> origin.append(' ').append(originOf(initializer, depth + 1)));
            }
        }
        return origin.toString();
    }

    // new GZIPInputStream(new ByteArrayInputStream(compressedBytes)), где compressedBytes - поле, которое
    // заполнил GZIPOutputStream этого же файла
    private boolean isOwnCompressedData(Expression decompressing) {
        boolean compressesItself = decompressing.findCompilationUnit().stream()
                .flatMap(unit -> unit.findAll(ObjectCreationExpr.class).stream())
                .anyMatch(creation -> COMPRESSING_STREAMS.contains(creation.getType().getNameAsString()));
        List<NameExpr> names = decompressing.findAll(NameExpr.class);
        return compressesItself && !names.isEmpty() && names.stream()
                .allMatch(name -> LocalTypes.findDeclaration(name)
                        .filter(declaration -> declaration.findAncestor(FieldDeclaration.class).isPresent())
                        .isPresent());
    }

    // zis.readAllBytes(), zis.transferTo(out), IOUtils.toByteArray(zis), Files.copy(zis, target)
    private boolean readsEverything(MethodCallExpr call) {
        String method = call.getNameAsString();
        if (READ_ALL.contains(method) && call.getScope().filter(this::isStream).isPresent()) {
            return true;
        }
        return COPY_ALL.contains(method) && !call.getArguments().isEmpty() && isStream(call.getArgument(0));
    }

    // while ((length = zis.read(buffer)) > 0) out.write(buffer, 0, length); счетчик в условии
    // (total < size && (n = zis.read(...)) != -1) ограничивает цикл
    private boolean isCopyLoop(Expression condition) {
        boolean reads = condition.findAll(MethodCallExpr.class).stream()
                .anyMatch(call -> READ.equals(call.getNameAsString()) && call.getScope().filter(this::isStream).isPresent());
        boolean counted = condition.findAll(BinaryExpr.class).stream()
                .filter(comparison -> COMPARISONS.contains(comparison.getOperator()))
                .anyMatch(comparison -> comparison.findAll(MethodCallExpr.class).stream()
                        .noneMatch(call -> READ.equals(call.getNameAsString())));
        return reads && !counted;
    }

    // MAX_ENTRY_SIZE, limit, entry.getSize(), BoundedInputStream - где-нибудь в методе
    private boolean hasLimit(Node place) {
        Node scope = Nodes.enclosingCallable(place).orElse(place);
        return scope.findAll(SimpleName.class).stream()
                .anyMatch(name -> LIMIT.matcher(name.getIdentifier()).matches());
    }

    // Загрузка файла, тело запроса, соединение по сети - в именах типов, аннотаций и переменных файла
    private boolean takesUntrustedInput(SourceFile sourceFile) {
        Stream<String> simpleNames = sourceFile.unit().findAll(SimpleName.class).stream().map(SimpleName::getIdentifier);
        Stream<String> names = sourceFile.unit().findAll(Name.class).stream().map(Name::getIdentifier);
        return Stream.concat(simpleNames, names).anyMatch(name -> UNTRUSTED_INPUT.matcher(name).matches());
    }
}
