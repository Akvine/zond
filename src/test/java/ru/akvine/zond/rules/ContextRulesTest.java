package ru.akvine.zond.rules;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import ru.akvine.zond.loaders.FileSystemConfigLoader;
import ru.akvine.zond.loaders.FileSystemSourceLoader;
import ru.akvine.zond.loaders.FileSystemTextFileLoader;
import ru.akvine.zond.models.ScanContext;
import ru.akvine.zond.models.Violation;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Правила jr:290 - jr:306: SQL-миграции, файлы сборки, Dockerfile, сверка кода с настройками
 * и файлов настроек и сообщений между собой
 */
class ContextRulesTest {
    @TempDir
    Path dir;

    private ScanContext context;

    @BeforeEach
    void setUp() throws IOException {
        write("db/migration/V1__init.sql", """
                -- первая миграция
                create table customers (
                    id bigint primary key,
                    code varchar(20) unique
                );
                create table orders (
                    id bigint primary key,
                    customer_id bigint not null references customers(id),
                    manager_id bigint,
                    total numeric(10, 2),
                    constraint fk_manager foreign key (manager_id) references managers(id)
                );
                create index idx_orders_manager on orders (manager_id);
                """);
        write("db/migration/V2__changes.sql", """
                alter table orders add column status varchar(20) not null;
                alter table orders add column kind varchar(20) not null default 'NEW';
                alter table orders add constraint uq_code unique (code);
                drop table legacy_orders;
                alter table orders drop column total;
                alter table orders drop constraint fk_manager;
                update orders set status = 'NEW; still text';
                delete from orders where id = 1;
                /* delete from orders; */
                truncate table audit;
                """);
        write("pom.xml", """
                <project>
                  <version>1.0-SNAPSHOT</version>
                  <dependencyManagement>
                    <dependencies>
                      <dependency>
                        <groupId>org.junit</groupId>
                        <artifactId>junit-bom</artifactId>
                        <version>5.10.0</version>
                      </dependency>
                    </dependencies>
                  </dependencyManagement>
                  <dependencies>
                    <dependency>
                      <groupId>com.example</groupId>
                      <artifactId>core</artifactId>
                      <version>2.0-SNAPSHOT</version>
                    </dependency>
                    <dependency>
                      <groupId>org.mockito</groupId>
                      <artifactId>mockito-core</artifactId>
                      <exclusions>
                        <exclusion>
                          <groupId>net.bytebuddy</groupId>
                          <artifactId>byte-buddy</artifactId>
                        </exclusion>
                      </exclusions>
                    </dependency>
                    <dependency>
                      <groupId>org.assertj</groupId>
                      <artifactId>assertj-core</artifactId>
                      <scope>test</scope>
                    </dependency>
                    <dependency>
                      <groupId>com.example</groupId>
                      <artifactId>core</artifactId>
                      <version>1.5</version>
                    </dependency>
                  </dependencies>
                </project>
                """);
        write("build.gradle", """
                dependencies {
                    implementation 'org.springframework.boot:spring-boot-starter'
                    implementation 'com.example:core:1.+'
                    implementation("org.junit.jupiter:junit-jupiter:5.10.0")
                    testImplementation 'org.mockito:mockito-core:5.0.0'
                    compileOnly 'org.projectlombok:lombok'
                    annotationProcessor 'org.projectlombok:lombok'
                    implementation 'org.springframework.boot:spring-boot-starter'
                }
                """);
        write("Dockerfile", """
                FROM maven:3.9 AS build
                ADD . /src
                RUN mvn package
                FROM eclipse-temurin
                ARG APP_VERSION=1.0
                ENV DB_PASSWORD=secret123 \\
                    APP_MODE=prod
                ENV API_TOKEN ${TOKEN}
                ADD https://example.org/agent.jar /agent.jar
                COPY --from=build /src/target/app.jar /app.jar
                ENTRYPOINT ["java", "-jar", "/app.jar"]
                """);
        write("docker/Dockerfile.safe", """
                FROM eclipse-temurin:21-jre AS base
                FROM base
                USER app
                """);
        write("src/main/resources/application.properties", """
                app.name=shop
                app.unused-flag=true
                app.mail.host=localhost
                app.feature.enabled=true
                spring.application.name=shop
                app.limit=10
                app.limit=20
                """);
        write("src/main/resources/application.yml", """
                app:
                  name: store
                  mail:
                    host: localhost
                """);
        write("src/main/resources/application-dev.properties", "app.dev-only=1\napp.common=1\n");
        write("src/main/resources/application-prod.properties", "app.common=1\napp.prod-only=2\n");
        write("src/main/resources/messages.properties", """
                greeting=Hello, {0}!
                farewell=Bye, {0} and {1}
                only.default=x
                """);
        write("src/main/resources/messages_ru.properties", """
                greeting=Привет, {0}!
                farewell=Пока, {0}
                greeting=Здравствуйте, {0}
                """);
        write("src/main/java/Settings.java", """
                @Component
                class Settings {
                    @Value("${app.name}") String name;
                    @Value("${app.missing}") String missing;
                    @Value("${app.optional:none}") String optional;
                    @Value("${DB_HOST}") String host;
                    @Value("${app.limit:5}") int limit;
                }
                @ConfigurationProperties("app.mail")
                class MailProperties {}
                @ConditionalOnProperty(prefix = "app.feature", name = "enabled")
                class Feature {}
                """);

        context = new ScanContext(
                dir,
                new FileSystemSourceLoader().load(dir).sources(),
                new FileSystemConfigLoader().load(dir, file -> true),
                new FileSystemTextFileLoader().load(dir, file -> true));
    }

    @Test
    void sqlMigrations() {
        assertThat(check(new CheckSqlDestructiveStatementRule()))
                .containsExactly("V2__changes.sql:10", "V2__changes.sql:4", "V2__changes.sql:5");
        assertThat(check(new CheckSqlNotNullWithoutDefaultRule())).containsExactly("V2__changes.sql:1");
        // Индекс по manager_id создан отдельной командой, по customer_id его нет
        assertThat(check(new CheckSqlForeignKeyWithoutIndexRule())).containsExactly("V1__init.sql:6");
        // Точка с запятой внутри строки команду не завершает, а закомментированный delete не считается
        assertThat(check(new CheckSqlChangeWithoutWhereRule())).containsExactly("V2__changes.sql:7");
    }

    @Test
    void buildFiles() {
        assertThat(check(new CheckUnstableDependencyVersionRule())).containsExactly("build.gradle:3", "pom.xml:13");
        assertThat(check(new CheckDuplicateDependencyRule())).containsExactly("build.gradle:8", "pom.xml:33");
        // Исключения и dependencyManagement зависимостями не считаются
        assertThat(check(new CheckTestDependencyInMainScopeRule())).containsExactly("build.gradle:4", "pom.xml:18");
    }

    @Test
    void dockerfile() {
        assertThat(check(new CheckDockerRootUserRule())).containsExactly("Dockerfile:4");
        // FROM base во втором файле - имя этапа сборки, а не образ
        assertThat(check(new CheckDockerUnpinnedImageRule())).containsExactly("Dockerfile:4");
        assertThat(check(new CheckDockerAddInsteadOfCopyRule())).containsExactly("Dockerfile:2");
        assertThat(check(new CheckDockerSecretInImageRule())).containsExactly("Dockerfile:6");
    }

    @Test
    void codeAndSettings() {
        assertThat(check(new CheckMissingConfigPropertyRule())).containsExactly("Settings.java:4");
        // app.mail.* читает @ConfigurationProperties, app.feature.enabled - @ConditionalOnProperty
        assertThat(check(new CheckUnusedConfigPropertyRule())).containsExactly(
                "application-dev.properties:1", "application-dev.properties:2",
                "application-prod.properties:1", "application-prod.properties:2",
                "application.properties:2");
    }

    @Test
    void messagesAndSettingsFiles() {
        List<Violation> bundle = new CheckMessageBundleMismatchRule().checkContext(context);
        assertThat(bundle).extracting(this::place)
                .containsExactlyInAnyOrder("messages_ru.properties:1", "messages_ru.properties:2");
        assertThat(bundle).extracting(Violation::message)
                .anyMatch(message -> message.contains("only.default"))
                .anyMatch(message -> message.contains("[{0}]") && message.contains("[{0}, {1}]"));

        assertThat(check(new CheckDuplicatePropertyKeyRule()))
                .containsExactly("application.properties:7", "messages_ru.properties:3");

        List<Violation> profiles = new CheckProfilePropertyMissingRule().checkContext(context);
        assertThat(profiles).extracting(this::place)
                .containsExactlyInAnyOrder("application-dev.properties:1", "application-prod.properties:1");
        assertThat(profiles).extracting(Violation::message)
                .anyMatch(message -> message.contains("'dev'") && message.contains("app.prod-only"))
                .anyMatch(message -> message.contains("'prod'") && message.contains("app.dev-only"));

        // app.mail.host совпадает в обоих файлах, app.name - нет
        assertThat(check(new CheckConflictingConfigValuesRule())).containsExactly("application.yml:2");
    }

    @Test
    void emptyProjectHasNoFindings() throws IOException {
        Path empty = Files.createDirectories(dir.resolve("empty"));
        ScanContext nothing = new ScanContext(empty, List.of(), List.of(), List.of());

        assertThat(new CheckMissingConfigPropertyRule().checkContext(nothing)).isEmpty();
        assertThat(new CheckUnusedConfigPropertyRule().checkContext(nothing)).isEmpty();
        assertThat(new CheckSqlForeignKeyWithoutIndexRule().checkContext(nothing)).isEmpty();
        assertThat(new CheckMessageBundleMismatchRule().checkContext(nothing)).isEmpty();
    }

    private void write(String path, String content) throws IOException {
        Path file = dir.resolve(path);
        Files.createDirectories(file.getParent());
        Files.writeString(file, content);
    }

    // Находки в виде "файл:строка", по алфавиту
    private List<String> check(ContextRule rule) {
        return rule.checkContext(context).stream().map(this::place).sorted().toList();
    }

    private String place(Violation violation) {
        return violation.file().getFileName() + ":" + violation.line();
    }
}
