package ru.akvine.zond.rules.support;

import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.PackageDeclaration;
import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration;
import com.github.javaparser.ast.expr.AnnotationExpr;
import com.github.javaparser.ast.expr.ClassExpr;
import com.github.javaparser.ast.expr.MemberValuePair;
import com.github.javaparser.ast.expr.StringLiteralExpr;
import ru.akvine.zond.models.SourceFile;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Пакеты, в которых приложения проекта ищут бины: пакет класса с {@code @SpringBootApplication}, его
 * scanBasePackages и пакеты из {@code @ComponentScan}. Класс с {@code @Service} вне них в приложении не
 * создается - разве что его подключают явно через {@code @Import}
 */
public final class ScanRoots {
    private static final String SPRING_BOOT_APPLICATION = "SpringBootApplication";
    private static final String COMPONENT_SCAN = "ComponentScan";
    private static final String IMPORT = "Import";
    private static final Set<String> PACKAGE_MEMBERS = Set.of("value", "basePackages", "scanBasePackages");
    private static final String PACKAGE_SEPARATOR = ".";

    private final Set<String> roots;
    private final Set<String> imported;

    private ScanRoots(Set<String> roots, Set<String> imported) {
        this.roots = roots;
        this.imported = imported;
    }

    public static ScanRoots of(List<SourceFile> sources) {
        Set<String> roots = new HashSet<>();
        Set<String> imported = new HashSet<>();
        for (SourceFile source : sources) {
            for (AnnotationExpr annotation : source.unit().findAll(AnnotationExpr.class)) {
                String name = annotation.getName().getIdentifier();
                if (SPRING_BOOT_APPLICATION.equals(name)) {
                    Set<String> packages = packagesOf(annotation);
                    roots.addAll(packages.isEmpty() ? Set.of(packageOf(source.unit())) : packages);
                } else if (COMPONENT_SCAN.equals(name)) {
                    roots.addAll(packagesOf(annotation));
                } else if (IMPORT.equals(name)) {
                    annotation.findAll(ClassExpr.class).forEach(type -> imported.add(LocalTypes.typeName(type.getType())));
                }
            }
        }
        return new ScanRoots(roots, imported);
    }

    /**
     * @return false, если приложения проекта известны и ни одно не сканирует пакет класса: бин не создается.
     * Без {@code @SpringBootApplication} (библиотека, часть проекта) ответ всегда true
     */
    public boolean isCreated(ClassOrInterfaceDeclaration type) {
        if (roots.isEmpty() || imported.contains(type.getNameAsString())) {
            return true;
        }
        String packageName = type.findCompilationUnit().map(ScanRoots::packageOf).orElse("");
        return roots.stream().anyMatch(root -> root.isEmpty() || packageName.equals(root)
                || packageName.startsWith(root + PACKAGE_SEPARATOR));
    }

    // @ComponentScan({"org.thingsboard.server", "org.thingsboard.script"}), @SpringBootApplication(scanBasePackages = "...")
    private static Set<String> packagesOf(AnnotationExpr annotation) {
        Set<String> packages = new HashSet<>();
        if (annotation.isSingleMemberAnnotationExpr()) {
            annotation.asSingleMemberAnnotationExpr().getMemberValue().findAll(StringLiteralExpr.class)
                    .forEach(literal -> packages.add(literal.asString()));
        } else if (annotation.isNormalAnnotationExpr()) {
            annotation.asNormalAnnotationExpr().getPairs().stream()
                    .filter(pair -> PACKAGE_MEMBERS.contains(pair.getNameAsString()))
                    .map(MemberValuePair::getValue)
                    .flatMap(value -> value.findAll(StringLiteralExpr.class).stream())
                    .forEach(literal -> packages.add(literal.asString()));
        }
        return packages;
    }

    private static String packageOf(CompilationUnit unit) {
        return unit.getPackageDeclaration().map(PackageDeclaration::getNameAsString).orElse("");
    }
}
