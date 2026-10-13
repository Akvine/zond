package ru.akvine.zond.rules.performance;

import com.github.javaparser.ast.Node;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.expr.Expression;
import com.github.javaparser.ast.expr.MethodCallExpr;
import com.github.javaparser.ast.expr.ObjectCreationExpr;
import org.springframework.stereotype.Component;
import ru.akvine.zond.enums.Confidence;
import ru.akvine.zond.enums.ErrorLevel;
import ru.akvine.zond.enums.ErrorType;
import ru.akvine.zond.models.SourceFile;
import ru.akvine.zond.models.Violation;
import ru.akvine.zond.rules.AbstractRule;
import ru.akvine.zond.rules.ProjectRule;
import ru.akvine.zond.rules.RuleCodes;
import ru.akvine.zond.rules.support.Handlers;
import ru.akvine.zond.rules.support.InMemoryFiles;
import ru.akvine.zond.rules.support.LocalTypes;
import ru.akvine.zond.rules.support.Nodes;
import ru.akvine.zond.rules.support.ProjectClasses;
import ru.akvine.zond.rules.support.TestClasses;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Файл отдается в HTTP-ответе массивом байт. Сколько запросов на скачивание идет одновременно,
 * столько копий файла лежит в памяти; потоковая отдача занимает на запрос только буфер.
 */
@Component
public class FileBytesInResponseRule extends AbstractRule implements ProjectRule {
    private static final String BODY = "body";
    private static final String RESPONSE_ENTITY = "ResponseEntity";
    private static final String BUILDER = "Builder";
    private static final String WRITE = "write";
    private static final String GET_OUTPUT_STREAM = "getOutputStream";
    private static final String SERVLET_RESPONSE = "HttpServletResponse";
    private static final String BYTE_ARRAY_RESOURCE = "ByteArrayResource";
    private static final String INPUT_STREAM_RESOURCE = "InputStreamResource";
    private static final String BYTE_ARRAY_INPUT_STREAM = "ByteArrayInputStream";

    // Вызовы, результат которых - содержимое целиком в массиве байт
    private static final Set<String> BYTE_SOURCES =
            Set.of("toByteArray", "readAllBytes", "getBytes", "getContentAsByteArray");

    private static final Pattern RESPONSE_TYPE = Pattern.compile("ResponseEntity|HttpEntity|Resource");
    private static final Pattern BYTES_RETURN = Pattern.compile(
            "^(byte\\[]|(ResponseEntity|HttpEntity)<(byte\\[]|ByteArrayResource)>|ByteArrayResource)$");

    // Признаки того, что в ответе файл, а не короткое двоичное значение
    private static final Pattern FILE_RESPONSE = Pattern.compile(
            "(?i)content.?disposition|attachment|octet.?stream|application/pdf|APPLICATION_PDF|spreadsheet"
                    + "|ms-excel|text/csv|application/zip|filename");
    private static final Pattern DOWNLOAD_NAME =
            Pattern.compile("(?i)(download|export|report|file|document|attachment|archive|excel|xlsx|pdf|csv|zip)");

    @Override
    public String code() {
        return RuleCodes.FILE_BYTES_IN_RESPONSE_RULE_CODE;
    }

    @Override
    public String description() {
        return "Сканирует код и ищет файлы, которые отдаются в HTTP-ответе массивом байт, а не потоком";
    }

    @Override
    public List<Violation> checkProject(List<SourceFile> sourceFiles) {
        ProjectClasses classes = ProjectClasses.of(sourceFiles);
        List<Violation> violations = new ArrayList<>();
        for (SourceFile sourceFile : sourceFiles) {
            for (MethodDeclaration method : sourceFile.unit().findAll(MethodDeclaration.class)) {
                if (method.getBody().isPresent() && !TestClasses.isInside(method)) {
                    check(sourceFile, method, classes, violations);
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
        return ErrorType.PERFORMANCE;
    }

    private void check(SourceFile sourceFile, MethodDeclaration method, ProjectClasses classes,
                       List<Violation> violations) {
        boolean fileResponse = FILE_RESPONSE.matcher(method.toString()).find();
        List<Expression> sites = sites(method);
        for (Expression site : sites) {
            Node place = site instanceof MethodCallExpr call ? call.getName() : site;
            // Заголовки файла могут задаваться в другом методе - тогда судим по названию самих данных
            if (fileResponse) {
                violations.add(violation(sourceFile, place, message("'" + shown(site) + "'")));
            } else if (InMemoryFiles.hasFileWord(shown(site))) {
                violations.add(violation(sourceFile, place, message("'" + shown(site) + "'"))
                        .withConfidence(Confidence.SUSPICION));
            }
        }
        if (sites.isEmpty() && BYTES_RETURN.matcher(method.getType().asString()).matches()
                && isDownloadHandler(method, classes, fileResponse)) {
            violations.add(violation(sourceFile, method.getName(),
                    message("Обработчик '" + method.getNameAsString() + "' возвращает " + method.getType().asString())));
        }
    }

    // Обработчик возвращает byte[]: Spring сам запишет его в ответ. Файл это или короткое значение,
    // видно по заголовкам, типу содержимого, имени метода и адресу
    private boolean isDownloadHandler(MethodDeclaration method, ProjectClasses classes, boolean fileResponse) {
        Optional<Handlers.Handler> handler = Handlers.of(method, classes);
        if (handler.isEmpty()) {
            return false;
        }
        return fileResponse
                || FILE_RESPONSE.matcher(handler.get().declaration().toString()).find()
                || DOWNLOAD_NAME.matcher(method.getNameAsString()).find()
                || DOWNLOAD_NAME.matcher(handler.get().mapping().toString()).find();
    }

    private List<Expression> sites(MethodDeclaration method) {
        List<Expression> sites = new ArrayList<>();
        boolean respondsWithEntity = RESPONSE_TYPE.matcher(method.getType().asString()).find();
        for (MethodCallExpr call : method.findAll(MethodCallExpr.class)) {
            if (respondsWithEntity && isBytesBody(call) || writesToResponse(call, method)) {
                sites.add(call);
            }
        }
        if (respondsWithEntity) {
            sites.addAll(byteResources(method, List.copyOf(sites)));
        }
        return sites;
    }

    // ResponseEntity.ok().body(bytes)
    private boolean isBytesBody(MethodCallExpr call) {
        return BODY.equals(call.getNameAsString()) && call.getArguments().size() == 1
                && holdsBytes(call.getArgument(0)) && buildsResponse(call);
    }

    // return new ByteArrayResource(bytes) - без построителя ответа
    private List<Expression> byteResources(MethodDeclaration method, List<Expression> bodies) {
        List<Expression> resources = new ArrayList<>();
        for (ObjectCreationExpr creation : method.findAll(ObjectCreationExpr.class)) {
            if (isByteResource(creation) && !isInside(bodies, creation)) {
                resources.add(creation);
            }
        }
        return resources;
    }

    // ResponseEntity.ok().header(...).body(file) - а не restClient.post().body(payload), где тело уходит в запрос
    private boolean buildsResponse(MethodCallExpr body) {
        Expression root = body;
        while (root.isMethodCallExpr() && root.asMethodCallExpr().getScope().isPresent()) {
            root = Nodes.unwrap(root.asMethodCallExpr().getScope().get());
        }
        Expression start = root;
        return RESPONSE_ENTITY.equals(start.toString())
                || LocalTypes.typeOf(start).filter(type -> type.endsWith(BUILDER)).isPresent();
    }

    private static boolean isInside(List<Expression> sites, Node node) {
        for (Expression site : sites) {
            if (site.isAncestorOf(node)) {
                return true;
            }
        }
        return false;
    }

    private boolean holdsBytes(Expression argument) {
        Expression value = Nodes.unwrap(argument);
        if (value.isObjectCreationExpr()) {
            return isByteResource(value.asObjectCreationExpr());
        }
        return InMemoryFiles.isByteArray(value)
                || value.isMethodCallExpr() && BYTE_SOURCES.contains(value.asMethodCallExpr().getNameAsString());
    }

    // new ByteArrayResource(bytes), new InputStreamResource(new ByteArrayInputStream(bytes))
    private boolean isByteResource(ObjectCreationExpr creation) {
        String type = creation.getType().getNameAsString();
        if (BYTE_ARRAY_RESOURCE.equals(type)) {
            return true;
        }
        return INPUT_STREAM_RESOURCE.equals(type) && !creation.getArguments().isEmpty()
                && Optional.of(Nodes.unwrap(creation.getArgument(0)))
                .filter(Expression::isObjectCreationExpr)
                .map(inner -> inner.asObjectCreationExpr().getType().getNameAsString())
                .filter(BYTE_ARRAY_INPUT_STREAM::equals)
                .isPresent();
    }

    // response.getOutputStream().write(bytes); OutputStream out = response.getOutputStream(); out.write(bytes)
    private boolean writesToResponse(MethodCallExpr call, MethodDeclaration method) {
        if (!WRITE.equals(call.getNameAsString()) || call.getArguments().size() != 1
                || call.getScope().isEmpty() || !holdsBytes(call.getArgument(0))) {
            return false;
        }
        boolean servlet = method.getParameters().stream()
                .anyMatch(parameter -> SERVLET_RESPONSE.equals(LocalTypes.typeName(parameter.getType())));
        Expression scope = Nodes.unwrap(call.getScope().get());
        Expression stream = scope.isNameExpr() ? LocalTypes.findInitializer(scope).map(Nodes::unwrap).orElse(scope) : scope;
        return servlet && stream.isMethodCallExpr()
                && GET_OUTPUT_STREAM.equals(stream.asMethodCallExpr().getNameAsString());
    }

    private static String message(String subject) {
        return subject + ": файл отдается в ответе массивом байт - на каждый запрос он целиком лежит в памяти,"
                + " и несколько одновременных скачиваний умножают ее расход; отдавайте потоком:"
                + " StreamingResponseBody, который пишет прямо в ответ, либо Resource поверх файла"
                + " (FileSystemResource, InputStreamResource)";
    }

    private static String shown(Node site) {
        // От цепочки построителя ответа оставляем последний вызов: body(file)
        if (site instanceof MethodCallExpr call) {
            return call.getNameAsString() + "(" + call.getArguments().stream()
                    .map(Nodes::text).reduce((left, right) -> left + ", " + right).orElse("") + ")";
        }
        return Nodes.text(site);
    }
}
