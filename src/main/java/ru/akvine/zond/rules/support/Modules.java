package ru.akvine.zond.rules.support;

import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.Node;
import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration;
import com.github.javaparser.ast.body.TypeDeclaration;
import ru.akvine.zond.models.SourceFile;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Stream;

/**
 * Модули сборки проверяемого проекта и то, какие из них - отдельные приложения.
 * <p>
 * В одном хранилище часто лежит несколько приложений: микросервисы, примеры, учебные проекты. У каждого свои
 * адреса, свои таблицы и свои миграции, и сверять их между собой нельзя: адрес /login в двух сервисах - не
 * конфликт, а таблица users соседнего сервиса ничего не говорит о сущности User этого.
 * <p>
 * Модуль - ближайший вверх от файла каталог с pom.xml или build.gradle. Приложение - модуль, в котором есть
 * запускаемый класс: с {@code @SpringBootApplication}, с методом {@code main} либо наследник
 * {@code WebApplicationInitializer} (приложение в виде war без Spring Boot). Модуль без такого класса -
 * библиотека: она может войти в любое приложение, поэтому отдельной от них не считается. Когда файлов сборки
 * нет вовсе (проверяют один каталог исходников), все файлы считаются частью одного приложения
 */
public final class Modules {
    private static final Set<String> BUILD_FILES = Set.of("pom.xml", "build.gradle", "build.gradle.kts");
    private static final Set<String> APPLICATION_ANNOTATIONS = Set.of("SpringBootApplication", "EnableAutoConfiguration");
    private static final String MAIN = "main";
    private static final String INITIALIZER = "Initializer";

    // Каталог -> его модуль: файлы одного каталога спрашивают об этом много раз
    private final Map<Path, Optional<Path>> moduleByDirectory = new HashMap<>();
    private final Set<Path> applications = new HashSet<>();

    private Modules() {
    }

    public static Modules of(List<SourceFile> sources) {
        Modules modules = new Modules();
        for (SourceFile source : sources) {
            boolean application = source.unit().getTypes().stream().anyMatch(Modules::isApplicationEntry);
            if (application) {
                modules.moduleOf(source.path()).ifPresent(modules.applications::add);
            }
        }
        return modules;
    }

    private static boolean isApplicationEntry(TypeDeclaration<?> type) {
        if (Annotations.hasAny(type, APPLICATION_ANNOTATIONS)) {
            return true;
        }
        boolean hasMain = type.getMethodsByName(MAIN).stream()
                .anyMatch(method -> method.isStatic() && method.isPublic() && method.getParameters().size() == 1);
        // AbstractAnnotationConfigDispatcherServletInitializer, AbstractSecurityWebApplicationInitializer,
        // SpringBootServletInitializer: так запускается приложение, которое разворачивают как war
        boolean isInitializer = type instanceof ClassOrInterfaceDeclaration declaration
                && Stream.concat(declaration.getExtendedTypes().stream(), declaration.getImplementedTypes().stream())
                .anyMatch(parent -> parent.getNameAsString().endsWith(INITIALIZER));
        return hasMain || isInitializer;
    }

    /**
     * @return каталог модуля, в котором лежит файл; пусто, если над файлом нет ни одного файла сборки
     */
    public synchronized Optional<Path> moduleOf(Path file) {
        Path directory = file.toAbsolutePath().normalize().getParent();
        return directory == null ? Optional.empty() : moduleOfDirectory(directory);
    }

    /**
     * @return true, если файлы лежат в разных приложениях и вместе в одно приложение попасть не могут
     */
    public boolean areSeparate(Path first, Path second) {
        if (applications.size() < 2) {
            return false;
        }
        Optional<Path> one = moduleOf(first);
        Optional<Path> other = moduleOf(second);
        return one.isPresent() && other.isPresent() && !one.equals(other)
                && applications.contains(one.get()) && applications.contains(other.get());
    }

    /**
     * То же для узлов кода; узел без файла (код из строки в тесте) ни от чего не отделен
     */
    public boolean areSeparate(Node first, Node second) {
        Optional<Path> one = fileOf(first);
        Optional<Path> other = fileOf(second);
        return one.isPresent() && other.isPresent() && areSeparate(one.get(), other.get());
    }

    /**
     * @return true, если в проекте больше одного приложения: только тогда их и нужно различать
     */
    public boolean hasSeveralApplications() {
        return applications.size() > 1;
    }

    /**
     * @return файл, из которого разобран узел
     */
    public static Optional<Path> fileOf(Node node) {
        return node.findCompilationUnit().flatMap(CompilationUnit::getStorage).map(CompilationUnit.Storage::getPath);
    }

    private Optional<Path> moduleOfDirectory(Path directory) {
        Optional<Path> known = moduleByDirectory.get(directory);
        if (known != null) {
            return known;
        }
        Optional<Path> module;
        if (BUILD_FILES.stream().anyMatch(name -> Files.isRegularFile(directory.resolve(name)))) {
            module = Optional.of(directory);
        } else {
            module = directory.getParent() == null ? Optional.empty() : moduleOfDirectory(directory.getParent());
        }
        moduleByDirectory.put(directory, module);
        return module;
    }
}
