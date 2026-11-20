package ru.akvine.zond.services;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import ru.akvine.zond.enums.ErrorLevel;
import ru.akvine.zond.enums.ErrorType;
import ru.akvine.zond.enums.TextFileType;
import ru.akvine.zond.loaders.FileSystemSourceLoader;
import ru.akvine.zond.loaders.FileSystemTextFileLoader;
import ru.akvine.zond.models.ConfigFile;
import ru.akvine.zond.models.ScanContext;
import ru.akvine.zond.models.SourceFile;
import ru.akvine.zond.models.TextFile;
import ru.akvine.zond.models.Violation;
import ru.akvine.zond.rules.logical.UnstableDependencyVersionRule;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class ProjectPartsTest {
    private static final String POM = """
            <project>
              <parent><groupId>shop</groupId><artifactId>parent</artifactId><version>1</version></parent>
              <artifactId>%s</artifactId>
              <dependencies>%s</dependencies>
            </project>
            """;
    private static final String DEPENDENCY =
            "<dependency><groupId>shop</groupId><artifactId>%s</artifactId></dependency>";

    @Test
    void projectWithoutBuildFilesIsNotSplit(@TempDir Path root) throws IOException {
        write(root, "first/src/Product.java", "package first; class Product {}");
        write(root, "second/src/Product.java", "package second; class Product {}");

        ScanContext context = context(root);

        assertThat(ProjectParts.split(context)).hasSize(1);
        assertThat(ProjectParts.split(context).get(0) == context).isTrue();
    }

    @Test
    void independentModulesAreSeparateParts(@TempDir Path root) throws IOException {
        // Общий родительский pom.xml модули не связывает: так собирают и наборы независимых примеров
        write(root, "pom.xml", "<project><artifactId>parent</artifactId></project>");
        write(root, "orders/pom.xml", POM.formatted("orders", ""));
        write(root, "orders/src/main/java/shop/Product.java", "package shop; class Product {}");
        write(root, "billing/pom.xml", POM.formatted("billing", ""));
        write(root, "billing/src/main/java/shop/Product.java", "package shop; class Product {}");
        write(root, "billing/src/main/java/shop/Invoice.java", "package shop; class Invoice { Product product; }");

        List<ScanContext> parts = ProjectParts.split(context(root));

        assertThat(parts).hasSize(2);
        assertThat(names(parts.get(0))).containsExactly("Invoice.java", "Product.java");
        assertThat(names(parts.get(1))).containsExactly("Product.java");
    }

    @Test
    void modulesLinkedByImportStayTogether(@TempDir Path root) throws IOException {
        write(root, "api/build.gradle", "plugins { id 'java' }");
        write(root, "api/src/main/java/shop/api/Order.java", "package shop.api; public class Order {}");
        write(root, "app/build.gradle", "plugins { id 'java' }");
        write(root, "app/src/main/java/shop/app/OrderService.java",
                "package shop.app; import shop.api.Order; class OrderService { Order find() { return null; } }");
        write(root, "tool/build.gradle", "plugins { id 'java' }");
        write(root, "tool/src/main/java/tool/Main.java", "package tool; class Main {}");

        List<ScanContext> parts = ProjectParts.split(context(root));

        assertThat(parts).hasSize(2);
        assertThat(names(parts.get(0))).containsExactly("Order.java", "OrderService.java");
        assertThat(names(parts.get(1))).containsExactly("Main.java");
    }

    @Test
    void modulesLinkedBySamePackageClassStayTogether(@TempDir Path root) throws IOException {
        // Класс того же пакета из другого модуля используется без импорта
        write(root, "core/build.gradle", "");
        write(root, "core/src/main/java/shop/Money.java", "package shop; public class Money {}");
        write(root, "web/build.gradle", "");
        write(root, "web/src/main/java/shop/PriceController.java",
                "package shop; class PriceController { Money price; }");

        assertThat(ProjectParts.split(context(root))).hasSize(1);
    }

    @Test
    void modulesLinkedByBuildFileStayTogether(@TempDir Path root) throws IOException {
        // Кода, который ссылался бы на другой модуль, нет: связь видна только в файле сборки
        write(root, "pom.xml", "<project><artifactId>parent</artifactId></project>");
        write(root, "app/pom.xml", POM.formatted("app", DEPENDENCY.formatted("migrations")));
        write(root, "app/src/main/java/shop/App.java", "package shop; class App {}");
        write(root, "migrations/pom.xml", POM.formatted("migrations", ""));
        write(root, "migrations/src/main/resources/db/migration/V1__init.sql", "create table product (id bigint);");
        write(root, "report/pom.xml", POM.formatted("report", ""));
        write(root, "report/src/main/java/report/Report.java", "package report; class Report {}");

        Path migration = root.resolve("migrations/src/main/resources/db/migration/V1__init.sql");
        ScanContext context = new ScanContext(root, sources(root), List.of(),
                List.of(new TextFile(migration, Files.readAllLines(migration), TextFileType.SQL)));
        List<ScanContext> parts = ProjectParts.split(context);

        assertThat(parts).hasSize(2);
        assertThat(names(parts.get(0))).containsExactly("App.java");
        assertThat(parts.get(0).textFiles()).hasSize(1);
        assertThat(names(parts.get(1))).containsExactly("Report.java");
        assertThat(parts.get(1).textFiles()).isEmpty();
    }

    @Test
    void filesOfParentWithoutCodeAreSharedByPartsBelow(@TempDir Path root) throws IOException {
        // Схема базы лежит в корне, а под ним два независимых проекта: чьи в ней таблицы, по каталогам не понять
        write(root, "pom.xml", "<project><artifactId>parent</artifactId></project>");
        write(root, "scripts/schema.sql", "create table product (id bigint);");
        write(root, "shop/pom.xml", POM.formatted("shop", ""));
        write(root, "shop/src/main/java/shop/Product.java", "package shop; class Product {}");
        write(root, "tool/pom.xml", POM.formatted("tool", ""));
        write(root, "tool/src/main/java/tool/Main.java", "package tool; class Main {}");

        Path schema = root.resolve("scripts/schema.sql");
        ScanContext context = new ScanContext(root, sources(root), List.of(),
                List.of(new TextFile(schema, Files.readAllLines(schema), TextFileType.SQL)));
        List<ScanContext> parts = ProjectParts.split(context);

        assertThat(parts).hasSize(2);
        assertThat(parts.get(0).textFiles()).hasSize(1);
        assertThat(parts.get(1).textFiles()).hasSize(1);
    }

    @Test
    void absenceInSharedFileMustBeConfirmedByEveryPart(@TempDir Path root) throws IOException {
        write(root, "pom.xml", "<project><artifactId>parent</artifactId></project>");
        write(root, "config/application.yml", "portal.title: Shop");
        write(root, "shop/pom.xml", POM.formatted("shop", ""));
        write(root, "shop/src/main/java/shop/Product.java", "package shop; class Product {}");
        write(root, "tool/pom.xml", POM.formatted("tool", ""));
        write(root, "tool/src/main/java/tool/Main.java", "package tool; class Main {}");

        Path shared = root.resolve("config/application.yml");
        Path own = root.resolve("shop/src/main/resources/application.yml");
        ScanContext context = new ScanContext(root, sources(root),
                List.of(new ConfigFile(shared, List.of()), new ConfigFile(own, List.of())), List.of());
        List<ScanContext> parts = ProjectParts.split(context);
        assertThat(parts).hasSize(2);

        Violation inShared = violation(shared, 1);
        Violation inSharedElsewhere = violation(shared, 2);
        Violation inOwn = violation(own, 1);
        // Первую находку в общем файле дали обе части, вторую - только одна: ей свойство не нужно, а другой нужно
        assertThat(ProjectParts.confirmedByEveryPart(List.of(inShared, inSharedElsewhere, inShared, inOwn), parts))
                .containsExactly(inShared, inOwn);
    }

    @Test
    void moduleOfAnotherPartIsStillOwnModule(@TempDir Path root) throws IOException {
        // Общий pom.xml объявляет версии своих модулей; одна из частей проекта самого модуля не содержит
        write(root, "pom.xml", """
                <project>
                  <groupId>shop</groupId><artifactId>parent</artifactId><version>1.0-SNAPSHOT</version>
                  <dependencyManagement><dependencies>
                    <dependency>
                      <groupId>shop</groupId><artifactId>tool</artifactId><version>1.0-SNAPSHOT</version>
                    </dependency>
                    <dependency>
                      <groupId>org.acme</groupId><artifactId>acme-core</artifactId><version>2.0-SNAPSHOT</version>
                    </dependency>
                  </dependencies></dependencyManagement>
                </project>
                """);
        write(root, "shop/pom.xml", POM.formatted("shop", ""));
        write(root, "shop/src/main/java/shop/Product.java", "package shop; class Product {}");
        write(root, "tool/pom.xml", POM.formatted("tool", ""));
        write(root, "tool/src/main/java/tool/Main.java", "package tool; class Main {}");

        ScanContext context = new ScanContext(root, sources(root), List.of(),
                new FileSystemTextFileLoader().load(root, file -> true));
        List<ScanContext> parts = ProjectParts.split(context);
        assertThat(parts).hasSize(2);

        // Свой модуль tool собирается вместе с проектом - и для той части, где его нет; чужой SNAPSHOT остается
        UnstableDependencyVersionRule rule = new UnstableDependencyVersionRule();
        for (ScanContext part : parts) {
            assertThat(rule.checkContext(part)).extracting(Violation::line).containsExactly(7);
        }
    }

    private Violation violation(Path file, int line) {
        return new Violation(
                ErrorLevel.INFO, ErrorType.CODE_SMELL, "jr:302", "UnusedConfigProperty", file, line, "лишнее");
    }

    @Test
    void gradleProjectDependencyLinksModules(@TempDir Path root) throws IOException {
        write(root, "settings.gradle", "include 'app', 'libs:common'");
        write(root, "app/build.gradle", "dependencies { implementation project(':libs:common') }");
        write(root, "app/src/main/java/app/App.java", "package app; class App {}");
        write(root, "libs/common/build.gradle", "");
        write(root, "libs/common/src/main/java/common/Texts.java", "package common; class Texts {}");

        assertThat(ProjectParts.split(context(root))).hasSize(1);
    }

    private ScanContext context(Path root) {
        return new ScanContext(root, sources(root), List.of(), List.of());
    }

    private List<SourceFile> sources(Path root) {
        return new FileSystemSourceLoader().load(root).sources();
    }

    private List<String> names(ScanContext part) {
        List<String> names = new ArrayList<>();
        part.sources().forEach(source -> names.add(source.path().getFileName().toString()));
        names.sort(String::compareTo);
        return names;
    }

    private void write(Path root, String file, String text) throws IOException {
        Path path = root.resolve(file);
        Files.createDirectories(path.getParent());
        Files.writeString(path, text);
    }
}
