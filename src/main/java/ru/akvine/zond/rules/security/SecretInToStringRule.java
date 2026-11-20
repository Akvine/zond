package ru.akvine.zond.rules.security;

import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration;
import com.github.javaparser.ast.body.FieldDeclaration;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.body.VariableDeclarator;
import com.github.javaparser.ast.expr.AnnotationExpr;
import com.github.javaparser.ast.expr.Expression;
import com.github.javaparser.ast.expr.FieldAccessExpr;
import com.github.javaparser.ast.expr.MemberValuePair;
import com.github.javaparser.ast.expr.NameExpr;
import org.springframework.stereotype.Component;
import ru.akvine.zond.enums.Confidence;
import ru.akvine.zond.enums.ErrorLevel;
import ru.akvine.zond.enums.ErrorType;
import ru.akvine.zond.models.SourceFile;
import ru.akvine.zond.models.Violation;
import ru.akvine.zond.rules.AbstractRule;
import ru.akvine.zond.rules.ProjectRule;
import ru.akvine.zond.rules.RuleCodes;
import ru.akvine.zond.rules.support.Annotations;
import ru.akvine.zond.rules.support.ProtectedProperties;
import ru.akvine.zond.rules.support.Secrets;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

@Component
public class SecretInToStringRule extends AbstractRule implements ProjectRule {
    private static final String TO_STRING = "toString";
    private static final String TO_STRING_EXCLUDE = "ToString.Exclude";
    private static final String TO_STRING_INCLUDE = "ToString.Include";
    private static final String TO_STRING_ANNOTATION = "ToString";
    private static final String EXCLUDE = "exclude";
    private static final String OF = "of";
    private static final String ONLY_INCLUDED = "onlyExplicitlyIncluded";

    // Lombok включает в toString все поля класса
    private static final Set<String> TO_STRING_GENERATORS = Set.of("Data", "ToString", "Value");

    @Override
    public String code() {
        return RuleCodes.SECRET_IN_TO_STRING_RULE_CODE;
    }

    @Override
    public String description() {
        return "Сканирует код и ищет пароли и токены, которые попадают в toString()";
    }

    @Override
    public List<Violation> checkProject(List<SourceFile> sourceFiles) {
        ProtectedProperties properties = ProtectedProperties.of(sourceFiles);
        return sourceFiles.stream().flatMap(sourceFile -> check(sourceFile, properties).stream()).toList();
    }

    private List<Violation> check(SourceFile sourceFile, ProtectedProperties properties) {
        List<Violation> violations = new ArrayList<>();
        for (ClassOrInterfaceDeclaration type : sourceFile.findAll(ClassOrInterfaceDeclaration.class)) {
            // Написанный вручную toString() Lombok не заменяет: печатается только то, что в нем названо
            boolean written = !type.getMethodsByName(TO_STRING).isEmpty();
            boolean generated = !written && Annotations.hasAny(type, TO_STRING_GENERATORS);
            Set<String> printed = findFieldsUsedInToString(type);
            Optional<AnnotationExpr> settings = Annotations.find(type, TO_STRING_ANNOTATION);
            // @ToString(exclude = "secret"), @ToString(of = {"id", "name"}), @ToString(onlyExplicitlyIncluded = true)
            Set<String> excludedNames = settings.map(annotation -> memberTexts(annotation, EXCLUDE)).orElse(Set.of());
            Set<String> onlyNames = settings.map(annotation -> memberTexts(annotation, OF)).orElse(Set.of());
            boolean onlyIncluded = settings.map(annotation -> memberTexts(annotation, ONLY_INCLUDED).contains("true"))
                    .orElse(false);

            for (FieldDeclaration field : type.getFields()) {
                boolean excluded = field.getAnnotations().stream()
                        .anyMatch(annotation -> annotation.getNameAsString().equals(TO_STRING_EXCLUDE));
                boolean included = field.getAnnotations().stream()
                        .anyMatch(annotation -> annotation.getNameAsString().equals(TO_STRING_INCLUDE));
                for (VariableDeclarator variable : field.getVariables()) {
                    String name = variable.getNameAsString();
                    boolean byLombok = generated && !excluded && !field.isStatic() && !excludedNames.contains(name)
                            && (onlyNames.isEmpty() || onlyNames.contains(name)) && (!onlyIncluded || included);
                    boolean isPrinted = printed.contains(name) || byLombok;
                    // Поле, в которое кладут только хеш или шифртекст, в toString() секрет не раскроет
                    boolean isProtected = properties.isProtected(Optional.of(type.getNameAsString()), name);
                    // boolean hasSecrets, int passwordLength - флаг или число, а не сам секрет
                    boolean isFlag = variable.getType().isPrimitiveType()
                            || variable.getType().isClassOrInterfaceType() && variable.getType().asClassOrInterfaceType().isBoxedType();
                    if (isPrinted && Secrets.isSecretName(name) && !isProtected && !isFlag) {
                        violations.add(violation(sourceFile, variable,
                                "Секретное поле '" + name + "' попадает в toString() класса '"
                                        + type.getNameAsString() + "': первая же запись объекта в лог или в текст"
                                        + " исключения раскроет его; исключите поле: @ToString.Exclude"));
                    }
                }
            }
        }
        return violations;
    }

    // О том, что поле хранит секрет, говорит только его имя
    @Override
    public Confidence confidence() {
        return Confidence.SUSPICION;
    }

    @Override
    public ErrorLevel errorLevel() {
        return ErrorLevel.MAJOR;
    }

    @Override
    public ErrorType errorType() {
        return ErrorType.SECURITY;
    }

    // Значения параметра аннотации: одно ("secret", true) либо список ({"id", "name"})
    private Set<String> memberTexts(AnnotationExpr annotation, String member) {
        Set<String> texts = new HashSet<>();
        if (!annotation.isNormalAnnotationExpr()) {
            return texts;
        }
        for (MemberValuePair pair : annotation.asNormalAnnotationExpr().getPairs()) {
            if (!pair.getNameAsString().equals(member)) {
                continue;
            }
            List<Expression> values = pair.getValue().isArrayInitializerExpr()
                    ? pair.getValue().asArrayInitializerExpr().getValues()
                    : List.of(pair.getValue());
            for (Expression value : values) {
                texts.add(value.isStringLiteralExpr() ? value.asStringLiteralExpr().asString() : value.toString());
            }
        }
        return texts;
    }

    // Поля, на которые ссылается написанный вручную toString()
    private Set<String> findFieldsUsedInToString(ClassOrInterfaceDeclaration type) {
        Set<String> used = new HashSet<>();
        for (MethodDeclaration method : type.getMethodsByName(TO_STRING)) {
            method.findAll(NameExpr.class).forEach(name -> used.add(name.getNameAsString()));
            method.findAll(FieldAccessExpr.class).forEach(access -> used.add(access.getNameAsString()));
        }
        return used;
    }
}
