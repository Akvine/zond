package ru.akvine.zond.rules.logical;

import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration;
import com.github.javaparser.ast.body.FieldDeclaration;
import com.github.javaparser.ast.type.ClassOrInterfaceType;
import org.springframework.stereotype.Component;
import ru.akvine.zond.enums.ErrorLevel;
import ru.akvine.zond.enums.ErrorType;
import ru.akvine.zond.models.SourceFile;
import ru.akvine.zond.models.Violation;
import ru.akvine.zond.rules.AbstractRule;
import ru.akvine.zond.rules.ProjectRule;
import ru.akvine.zond.rules.RuleCodes;
import ru.akvine.zond.rules.support.ProjectClasses;
import ru.akvine.zond.rules.support.TestClasses;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

@Component
public class NonSerializableFieldRule extends AbstractRule implements ProjectRule {
    private static final String SERIALIZABLE = "Serializable";
    // Типы JDK и распространенных библиотек, которые не сериализуются
    private static final Set<String> NOT_SERIALIZABLE = Set.of(
            "Thread", "InputStream", "OutputStream", "Reader", "Writer", "Socket", "Connection", "Statement",
            "ResultSet", "ExecutorService", "Executor", "Lock", "ReentrantLock", "Stream", "Optional", "Logger",
            "DataSource", "EntityManager", "RestTemplate", "WebClient", "ObjectMapper", "Process", "ClassLoader");

    @Override
    public String code() {
        return RuleCodes.NON_SERIALIZABLE_FIELD_RULE_CODE;
    }

    @Override
    public String description() {
        return "Сканирует проект и ищет в классах Serializable поля, тип которых сериализовать нельзя";
    }

    @Override
    public List<Violation> checkProject(List<SourceFile> sourceFiles) {
        ProjectClasses classes = ProjectClasses.of(sourceFiles);
        List<Violation> violations = new ArrayList<>();
        for (SourceFile sourceFile : sourceFiles) {
            for (ClassOrInterfaceDeclaration type : sourceFile.unit().findAll(ClassOrInterfaceDeclaration.class)) {
                if (type.isInterface() || TestClasses.isInside(type)
                        || !isSerializable(type, classes, new HashSet<>()).orElse(false)) {
                    continue;
                }
                for (FieldDeclaration field : type.getFields()) {
                    if (field.isStatic() || field.isTransient()) {
                        continue;
                    }
                    findProblemType(field, classes).ifPresent(problem -> violations.add(violation(sourceFile, field,
                            "Поле '" + field.getVariable(0).getNameAsString() + "' класса Serializable '"
                                    + type.getNameAsString() + "' имеет тип '" + problem + "', который сериализовать"
                                    + " нельзя: запись объекта упадет с NotSerializableException; сделайте тип"
                                    + " Serializable либо пометьте поле transient")));
                }
            }
        }
        return violations;
    }

    @Override
    public ErrorLevel errorLevel() {
        return ErrorLevel.MINOR;
    }

    @Override
    public ErrorType errorType() {
        return ErrorType.LOGICAL;
    }

    // Сам тип поля либо тип элементов коллекции
    private Optional<String> findProblemType(FieldDeclaration field, ProjectClasses classes) {
        if (!field.getElementType().isClassOrInterfaceType()) {
            return Optional.empty();
        }
        ClassOrInterfaceType declared = field.getElementType().asClassOrInterfaceType();
        List<String> names = new ArrayList<>(List.of(declared.getNameAsString()));
        declared.getTypeArguments().ifPresent(arguments -> arguments.stream()
                .filter(argument -> argument.isClassOrInterfaceType())
                .forEach(argument -> names.add(argument.asClassOrInterfaceType().getNameAsString())));
        return names.stream()
                .filter(name -> NOT_SERIALIZABLE.contains(name) || classes.find(name)
                        .filter(found -> !found.isInterface())
                        .flatMap(found -> isSerializable(found, classes, new HashSet<>()))
                        .filter(serializable -> !serializable)
                        .isPresent())
                .findFirst();
    }

    /**
     * @return пусто, если судить нельзя: часть предков или интерфейсов лежит вне проекта и может быть Serializable
     */
    private Optional<Boolean> isSerializable(
            ClassOrInterfaceDeclaration type, ProjectClasses classes, Set<ClassOrInterfaceDeclaration> seen) {
        if (!seen.add(type)) {
            return Optional.of(false);
        }
        boolean unknown = false;
        List<ClassOrInterfaceType> parents = new ArrayList<>(type.getImplementedTypes());
        parents.addAll(type.getExtendedTypes());
        for (ClassOrInterfaceType parent : parents) {
            if (SERIALIZABLE.equals(parent.getNameAsString())) {
                return Optional.of(true);
            }
            Optional<ClassOrInterfaceDeclaration> declared = classes.find(parent.getNameAsString());
            if (declared.isEmpty()) {
                unknown = true;
                continue;
            }
            Optional<Boolean> inherited = isSerializable(declared.get(), classes, seen);
            if (inherited.isEmpty()) {
                unknown = true;
            } else if (inherited.get()) {
                return Optional.of(true);
            }
        }
        return unknown ? Optional.empty() : Optional.of(false);
    }
}
