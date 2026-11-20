package ru.akvine.zond.services;

import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.ImportDeclaration;
import com.github.javaparser.ast.body.BodyDeclaration;
import com.github.javaparser.ast.body.TypeDeclaration;
import com.github.javaparser.ast.expr.AnnotationExpr;
import com.github.javaparser.ast.expr.NameExpr;
import com.github.javaparser.ast.type.ClassOrInterfaceType;
import ru.akvine.zond.models.ConfigFile;
import ru.akvine.zond.models.ScanContext;
import ru.akvine.zond.models.SourceFile;
import ru.akvine.zond.models.TextFile;
import ru.akvine.zond.models.Violation;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Независимые части проверяемого каталога.
 * <p>
 * В одном каталоге нередко лежит несколько проектов, которые друг о друге не знают: набор примеров, учебные
 * задания, несколько сервисов без общего кода, просто папка со своими проектами. Правилам, которые смотрят
 * проект целиком, такие соседи только мешают: сущность {@code Product} одного проекта путается с одноименным
 * классом другого, схема базы одного сверяется с сущностями другого, два контроллера с одним адресом
 * объявляются конфликтом. Поэтому каталог делится на части, и каждая проверяется как отдельный проект.
 * <p>
 * Модуль - ближайший вверх от файла каталог с pom.xml или build.gradle. Два модуля попадают в одну часть,
 * если связаны хоть чем-то: один импортирует класс другого, пользуется классом того же пакета из другого
 * модуля либо называет другой в зависимостях файла сборки; связь через третий модуль тоже считается.
 * Общий родительский pom.xml и общий settings.gradle связью не являются - так собирают и независимые примеры.
 * Модуль без кода (родительский с docker-compose.yml, модуль с одними миграциями) присоединяется к части,
 * в которую входят все модули с кодом внутри него; если они разошлись по разным частям, он остается сам по себе.
 * <p>
 * Делится только то, что наверняка независимо: при любой найденной связи модули остаются вместе, и проект
 * из одного модуля либо вовсе без файлов сборки проверяется, как раньше, целиком. Отключить деление можно
 * свойством JVM {@code -Dzond.scan.split-projects=false}.
 */
final class ProjectParts {
    private static final String SWITCH = "zond.scan.split-projects";
    private static final String POM = "pom.xml";
    private static final List<String> GRADLE_FILES = List.of("build.gradle", "build.gradle.kts");

    private static final Pattern XML_COMMENT = Pattern.compile("(?s)<!--.*?-->");
    // Блоки pom.xml, в которых artifactId называет не сам модуль и не его зависимости
    private static final Pattern NOT_OWN_BLOCKS = Pattern.compile(
            "(?s)<(parent|dependencies|dependencyManagement|build|profiles|reporting)>.*?</\\1>");
    private static final Pattern NOT_DEPENDENCY_BLOCKS =
            Pattern.compile("(?s)<(dependencyManagement|build|reporting)>.*?</\\1>");
    private static final Pattern DEPENDENCY = Pattern.compile("(?s)<dependency>.*?</dependency>");
    private static final Pattern ARTIFACT_ID = Pattern.compile("<artifactId>\\s*([^<\\s]+)\\s*</artifactId>");
    // implementation project(':common'), api(project(":libs:common")), project(path: ':common')
    private static final Pattern GRADLE_PROJECT =
            Pattern.compile("project\\(\\s*(?:path\\s*[:=]\\s*)?['\"]:?([^'\"]+)['\"]");
    // subprojects { dependencies { implementation project(':common') } }: зависимость получают все вложенные модули
    private static final Pattern GRADLE_FOR_NESTED = Pattern.compile("\\b(subprojects|allprojects|configure)\\b");
    private static final char GRADLE_SEPARATOR = ':';

    private final Path root;
    private final Map<Path, Path> moduleByDirectory = new HashMap<>();
    // Модуль -> представитель его части
    private final Map<Path, Path> parent = new HashMap<>();

    private ProjectParts(Path root) {
        this.root = root.toAbsolutePath().normalize();
    }

    /**
     * @return части каталога, каждая со своими файлами; если делить нечего - один и тот же исходный набор
     */
    static List<ScanContext> split(ScanContext context) {
        if ("false".equalsIgnoreCase(System.getProperty(SWITCH, "").trim())) {
            return List.of(context);
        }
        return new ProjectParts(context.root()).splitContext(context);
    }

    private List<ScanContext> splitContext(ScanContext context) {
        Map<Path, List<SourceFile>> sources = new LinkedHashMap<>();
        for (SourceFile source : context.sources()) {
            sources.computeIfAbsent(moduleOf(source.path()), module -> new ArrayList<>()).add(source);
        }
        // Один модуль с кодом: независимых проектов здесь быть не может
        if (sources.size() < 2) {
            return List.of(context);
        }
        Set<Path> modules = new HashSet<>(sources.keySet());
        context.configFiles().forEach(config -> modules.add(moduleOf(config.path())));
        context.textFiles().forEach(text -> modules.add(moduleOf(text.path())));
        modules.forEach(module -> parent.put(module, module));

        linkByCode(sources);
        try {
            linkByBuildFiles(modules);
        } catch (UncheckedIOException exception) {
            // Файл сборки есть, но не прочитан: какие модули он связывает, неизвестно. Делить папку вслепую
            // нельзя - проверяем ее целиком, как раньше
            return List.of(context);
        }
        // Модуль без кода -> части, которым видны его файлы, если частей под ним несколько
        Map<Path, Set<Path>> shared = attachModulesWithoutCode(modules, sources.keySet());

        // Части идут по порядку путей, чтобы отчет от запуска к запуску не менялся
        Map<Path, Part> parts = new TreeMap<>();
        modules.stream().sorted().forEach(module -> parts.computeIfAbsent(find(module), part -> new Part()));
        if (parts.size() < 2) {
            return List.of(context);
        }
        // Порядок файлов внутри части - тот же, что был в общем наборе
        for (SourceFile source : context.sources()) {
            parts.get(find(moduleOf(source.path()))).sources.add(source);
        }
        for (ConfigFile config : context.configFiles()) {
            for (Path part : partsOf(moduleOf(config.path()), shared)) {
                parts.get(part).configs.add(config);
            }
        }
        for (TextFile text : context.textFiles()) {
            for (Path part : partsOf(moduleOf(text.path()), shared)) {
                parts.get(part).texts.add(text);
            }
        }
        List<ScanContext> result = new ArrayList<>();
        for (Part part : parts.values()) {
            // Общий модуль, файлы которого розданы частям под ним, своей части не образует
            if (!part.sources.isEmpty() || !part.configs.isEmpty() || !part.texts.isEmpty()) {
                result.add(new ScanContext(context.root(), List.copyOf(part.sources), List.copyOf(part.configs),
                        List.copyOf(part.texts), context.folderFiles()));
            }
        }
        return result.size() < 2 ? List.of(context) : result;
    }

    // Часть, в которую входит модуль, либо, для общего модуля без кода, все части под ним
    private Set<Path> partsOf(Path module, Map<Path, Set<Path>> shared) {
        Set<Path> nested = shared.get(module);
        return nested == null ? Set.of(find(module)) : nested;
    }

    private static final class Part {
        private final List<SourceFile> sources = new ArrayList<>();
        private final List<ConfigFile> configs = new ArrayList<>();
        private final List<TextFile> texts = new ArrayList<>();
    }

    // Файлы вне всякого модуля относятся к самому проверяемому каталогу
    private Path moduleOf(Path file) {
        Path directory = file.toAbsolutePath().normalize().getParent();
        return directory == null || !directory.startsWith(root) ? root : moduleOfDirectory(directory);
    }

    private Path moduleOfDirectory(Path directory) {
        Path known = moduleByDirectory.get(directory);
        if (known != null) {
            return known;
        }
        Path module;
        if (hasBuildFile(directory) || directory.equals(root) || directory.getParent() == null) {
            module = directory;
        } else {
            module = moduleOfDirectory(directory.getParent());
        }
        moduleByDirectory.put(directory, module);
        return module;
    }

    private boolean hasBuildFile(Path directory) {
        return Files.isRegularFile(directory.resolve(POM))
                || GRADLE_FILES.stream().anyMatch(name -> Files.isRegularFile(directory.resolve(name)));
    }

    /**
     * Связь по коду: файл импортирует класс или пакет, объявленный только в другом модуле, либо без импорта
     * пользуется классом своего пакета, который лежит в другом модуле
     */
    private void linkByCode(Map<Path, List<SourceFile>> sources) {
        Map<String, Set<Path>> typeModules = new HashMap<>();
        Map<String, Set<Path>> packageModules = new HashMap<>();
        sources.forEach((module, files) -> {
            for (SourceFile file : files) {
                CompilationUnit unit = file.unit();
                packageModules.computeIfAbsent(packageOf(unit), name -> new HashSet<>()).add(module);
                for (TypeDeclaration<?> type : unit.getTypes()) {
                    collectTypes(type, module, typeModules);
                }
            }
        });

        sources.forEach((module, files) -> {
            for (SourceFile file : files) {
                CompilationUnit unit = file.unit();
                for (ImportDeclaration declaration : unit.getImports()) {
                    linkImport(module, declaration, typeModules, packageModules);
                }
                String ownPackage = packageOf(unit);
                if (packageModules.getOrDefault(ownPackage, Set.of()).size() > 1) {
                    for (String name : usedTypeNames(file)) {
                        link(module, typeModules.get(qualified(ownPackage, name)));
                    }
                }
            }
        });
    }

    private void collectTypes(TypeDeclaration<?> type, Path module, Map<String, Set<Path>> typeModules) {
        type.getFullyQualifiedName()
                .ifPresent(name -> typeModules.computeIfAbsent(name, key -> new HashSet<>()).add(module));
        for (BodyDeclaration<?> member : type.getMembers()) {
            if (member instanceof TypeDeclaration<?> nested) {
                collectTypes(nested, module, typeModules);
            }
        }
    }

    private void linkImport(Path module, ImportDeclaration declaration, Map<String, Set<Path>> typeModules,
                            Map<String, Set<Path>> packageModules) {
        String name = declaration.getNameAsString();
        if (declaration.isAsterisk()) {
            // import ru.shop.dto.*; у статического импорта со звездочкой имя - это класс
            link(module, declaration.isStatic() ? typeModules.get(name) : packageModules.get(name));
            return;
        }
        // import static ru.shop.Limits.MAX: класс - все, что до последней точки
        int memberStart = name.lastIndexOf('.');
        String type = declaration.isStatic() && memberStart > 0 ? name.substring(0, memberStart) : name;
        link(module, typeModules.get(type));
    }

    // Имена типов, которыми пользуется файл: в объявлениях, в обращениях Limits.MAX и в аннотациях
    private Set<String> usedTypeNames(SourceFile file) {
        Set<String> names = new HashSet<>();
        for (ClassOrInterfaceType type : file.findAll(ClassOrInterfaceType.class)) {
            if (type.getScope().isEmpty()) {
                names.add(type.getNameAsString());
            }
        }
        for (NameExpr name : file.findAll(NameExpr.class)) {
            if (Character.isUpperCase(name.getNameAsString().charAt(0))) {
                names.add(name.getNameAsString());
            }
        }
        for (AnnotationExpr annotation : file.findAll(AnnotationExpr.class)) {
            names.add(annotation.getName().getIdentifier());
        }
        return names;
    }

    /**
     * Модуль связывается с тем, где объявлено нужное ему имя. Если имя есть и в нем самом, связи нет: он
     * пользуется своим. Если то же имя объявлено сразу в нескольких чужих модулях, неясно, какой из них имеется
     * в виду: так выглядят независимые примеры с одинаковыми пакетами, и связывать их нельзя
     */
    private void link(Path module, Set<Path> declaring) {
        if (declaring == null || declaring.contains(module) || declaring.size() != 1) {
            return;
        }
        union(module, declaring.iterator().next());
    }

    private void linkByBuildFiles(Set<Path> modules) {
        Map<String, List<Path>> byArtifact = new HashMap<>();
        Map<Path, String> poms = new HashMap<>();
        for (Path module : modules) {
            read(module.resolve(POM)).ifPresent(text -> {
                String pom = XML_COMMENT.matcher(text).replaceAll("");
                poms.put(module, pom);
                Matcher own = ARTIFACT_ID.matcher(NOT_OWN_BLOCKS.matcher(pom).replaceAll(""));
                if (own.find()) {
                    byArtifact.computeIfAbsent(own.group(1), name -> new ArrayList<>()).add(module);
                }
            });
        }
        poms.forEach((module, pom) -> {
            Matcher dependency = DEPENDENCY.matcher(NOT_DEPENDENCY_BLOCKS.matcher(pom).replaceAll(""));
            while (dependency.find()) {
                Matcher artifact = ARTIFACT_ID.matcher(dependency.group());
                List<Path> found = artifact.find() ? byArtifact.getOrDefault(artifact.group(1), List.of()) : List.of();
                // Зависимости родительского pom.xml получают и все вложенные модули
                if (found.size() == 1) {
                    linkWithNested(module, found.get(0), modules, true);
                }
            }
        });

        for (Path module : modules) {
            for (String file : GRADLE_FILES) {
                read(module.resolve(file)).ifPresent(text -> {
                    boolean forNested = GRADLE_FOR_NESTED.matcher(text).find();
                    Matcher project = GRADLE_PROJECT.matcher(text);
                    while (project.find()) {
                        gradleModule(module, project.group(1), modules)
                                .ifPresent(target -> linkWithNested(module, target, modules, forNested));
                    }
                });
            }
        }
    }

    private void linkWithNested(Path module, Path target, Set<Path> modules, boolean withNested) {
        union(module, target);
        if (withNested) {
            modules.stream()
                    .filter(nested -> !nested.equals(module) && nested.startsWith(module))
                    .forEach(nested -> union(nested, target));
        }
    }

    // project(':libs:common') - каталог libs/common от корня сборки; корень - этот модуль либо один из каталогов выше
    private Optional<Path> gradleModule(Path module, String projectPath, Set<Path> modules) {
        String relative = projectPath.replace(GRADLE_SEPARATOR, '/');
        for (Path base = module; base != null && base.startsWith(root); base = base.getParent()) {
            Path candidate = base.resolve(relative).normalize();
            if (modules.contains(candidate)) {
                return Optional.of(candidate);
            }
        }
        // Каталог модуля переназначен в settings.gradle: ищем по имени, если оно одно такое
        String name = relative.substring(relative.lastIndexOf('/') + 1);
        List<Path> named = modules.stream()
                .filter(candidate -> candidate.getFileName() != null && candidate.getFileName().toString().equals(name))
                .toList();
        return named.size() == 1 ? Optional.of(named.get(0)) : Optional.empty();
    }

    /**
     * Модуль без кода - родительский каталог с общими файлами (docker-compose.yml, CI, скрипты базы) либо модуль
     * с одними ресурсами. Он относится к тому проекту, чьи модули в нем лежат. Если проектов под ним несколько,
     * его файлы видны каждому из них: общая схема базы нужна тому проекту, чьи сущности в ней описаны, а какой
     * это из проектов, по каталогам не понять. Находка в самом общем файле при этом остается одна
     *
     * @return общие модули вместе с частями, которым видны их файлы
     */
    private Map<Path, Set<Path>> attachModulesWithoutCode(Set<Path> modules, Set<Path> withCode) {
        Map<Path, Set<Path>> shared = new HashMap<>();
        for (Path module : modules) {
            if (withCode.contains(module)) {
                continue;
            }
            Set<Path> nestedParts = new HashSet<>();
            for (Path other : withCode) {
                if (other.startsWith(module)) {
                    nestedParts.add(find(other));
                }
            }
            if (nestedParts.size() == 1) {
                union(module, nestedParts.iterator().next());
            } else if (nestedParts.size() > 1) {
                shared.put(module, nestedParts);
            }
        }
        // Объединения выше могли сменить представителей частей
        shared.replaceAll((module, nestedParts) -> {
            Set<Path> current = new HashSet<>();
            nestedParts.forEach(part -> current.add(find(part)));
            return current;
        });
        return shared;
    }

    private Optional<String> read(Path file) {
        if (!Files.isRegularFile(file)) {
            return Optional.empty();
        }
        try {
            return Optional.of(Files.readString(file, StandardCharsets.UTF_8));
        } catch (IOException exception) {
            throw new UncheckedIOException(exception);
        }
    }

    private String packageOf(CompilationUnit unit) {
        return unit.getPackageDeclaration().map(declaration -> declaration.getNameAsString()).orElse("");
    }

    private String qualified(String packageName, String simpleName) {
        return packageName.isEmpty() ? simpleName : packageName + "." + simpleName;
    }

    private Path find(Path module) {
        Path current = module;
        while (!parent.get(current).equals(current)) {
            current = parent.get(current);
        }
        // Сжатие пути: следующий поиск пройдет за один шаг
        Path representative = current;
        current = module;
        while (!parent.get(current).equals(current)) {
            Path next = parent.get(current);
            parent.put(current, representative);
            current = next;
        }
        return representative;
    }

    private void union(Path first, Path second) {
        Path one = find(first);
        Path other = find(second);
        if (!one.equals(other)) {
            // Представителем становится путь, который идет раньше: результат не зависит от порядка обхода
            if (one.compareTo(other) <= 0) {
                parent.put(other, one);
            } else {
                parent.put(one, other);
            }
        }
    }

    /**
     * @return находки, которые дала каждая часть проекта, получившая файл: у общего файла - все его части, у
     * файла одной части - она сама
     */
    static List<Violation> confirmedByEveryPart(List<Violation> found, List<ScanContext> parts) {
        Map<Path, Integer> owners = new HashMap<>();
        for (ScanContext part : parts) {
            part.configFiles().forEach(file -> owners.merge(file.path(), 1, Integer::sum));
            part.textFiles().forEach(file -> owners.merge(file.path(), 1, Integer::sum));
        }
        Map<Violation, Integer> reported = new LinkedHashMap<>();
        found.forEach(violation -> reported.merge(violation, 1, Integer::sum));
        return reported.entrySet().stream()
                .filter(entry -> entry.getValue() >= owners.getOrDefault(entry.getKey().file(), 1))
                .map(Map.Entry::getKey)
                .toList();
    }
}
