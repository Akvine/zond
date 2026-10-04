package ru.akvine.zond.rules;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import ru.akvine.zond.enums.TextFileType;
import ru.akvine.zond.loaders.FileSystemConfigLoader;
import ru.akvine.zond.loaders.FileSystemTextFileLoader;
import ru.akvine.zond.models.ConfigFile;
import ru.akvine.zond.models.ScanContext;
import ru.akvine.zond.models.TextFile;
import ru.akvine.zond.models.Violation;
import ru.akvine.zond.rules.logical.CheckCiTestsSkippedRule;
import ru.akvine.zond.rules.logical.CheckComposeUnpinnedImageRule;
import ru.akvine.zond.rules.logical.CheckKubernetesNoProbesRule;
import ru.akvine.zond.rules.logical.CheckKubernetesUnpinnedImageRule;
import ru.akvine.zond.rules.logical.CheckLogUnusedAppenderRule;
import ru.akvine.zond.rules.logical.CheckSqlChangeWithoutWhereRule;
import ru.akvine.zond.rules.logical.CheckSqlDestructiveStatementRule;
import ru.akvine.zond.rules.logical.CheckSqlNotNullWithoutDefaultRule;
import ru.akvine.zond.rules.logical.CheckUnstableDependencyVersionRule;
import ru.akvine.zond.rules.performance.CheckLogCallerDataInPatternRule;
import ru.akvine.zond.rules.performance.CheckLogRootLevelDebugRule;
import ru.akvine.zond.rules.performance.CheckSqlForeignKeyWithoutIndexRule;
import ru.akvine.zond.rules.resources.CheckKubernetesNoResourceLimitsRule;
import ru.akvine.zond.rules.resources.CheckLogFileWithoutRotationRule;
import ru.akvine.zond.rules.security.CheckCiSecretInVariablesRule;
import ru.akvine.zond.rules.security.CheckCiUnpinnedReferenceRule;
import ru.akvine.zond.rules.security.CheckComposeDatabasePortExposedRule;
import ru.akvine.zond.rules.security.CheckComposePrivilegedServiceRule;
import ru.akvine.zond.rules.security.CheckComposeSecretInEnvironmentRule;
import ru.akvine.zond.rules.security.CheckKubernetesPrivilegedContainerRule;
import ru.akvine.zond.rules.security.CheckKubernetesSecretInManifestRule;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Журналы Liquibase, разбор pom.xml и YAML настоящими парсерами и правила jr:307 - jr:322:
 * настройки логирования, docker-compose, манифесты Kubernetes, файлы CI
 */
class ProjectFilesRulesTest {
    @TempDir
    Path dir;

    @Test
    void liquibaseChangelogsAreCheckedAsMigrations() throws IOException {
        write("db/changelog/changelog.xml", """
                <?xml version="1.0" encoding="UTF-8"?>
                <databaseChangeLog xmlns="http://www.liquibase.org/xml/ns/dbchangelog">
                  <changeSet id="1" author="a">
                    <createTable tableName="orders">
                      <column name="id" type="bigint"><constraints primaryKey="true"/></column>
                      <column name="customer_id" type="bigint"><constraints references="customers(id)" foreignKeyName="fk_c"/></column>
                      <column name="manager_id" type="bigint"/>
                    </createTable>
                    <addForeignKeyConstraint baseTableName="orders" baseColumnNames="manager_id" constraintName="fk_m" referencedTableName="managers" referencedColumnNames="id"/>
                    <createIndex tableName="orders" indexName="idx_m"><column name="manager_id"/></createIndex>
                  </changeSet>
                  <changeSet id="2" author="a">
                    <addColumn tableName="orders">
                      <column name="status" type="varchar(20)"><constraints nullable="false"/></column>
                      <column name="kind" type="varchar(20)" defaultValue="NEW"><constraints nullable="false"/></column>
                    </addColumn>
                    <dropColumn tableName="orders" columnName="total"/>
                    <dropTable tableName="legacy"/>
                    <update tableName="orders"><column name="status" value="NEW"/></update>
                    <delete tableName="orders"><where>id = 1</where></delete>
                    <sql>truncate table audit;
                         delete from logs;</sql>
                    <rollback><dropTable tableName="orders"/></rollback>
                  </changeSet>
                </databaseChangeLog>
                """);
        write("db/changelog/changes.yaml", """
                databaseChangeLog:
                  - changeSet:
                      id: 3
                      author: a
                      changes:
                        - addColumn:
                            tableName: orders
                            columns:
                              - column:
                                  name: note
                                  type: varchar(50)
                                  constraints:
                                    nullable: false
                        - dropTable:
                            tableName: old_orders
                        - sql:
                            sql: |
                              update orders set note = 'x';
                              delete from orders where id = 2;
                      rollback:
                        - dropTable:
                            tableName: orders
                """);
        // XML и YAML, которые не являются ни журналом, ни чем-то еще известным, не читаются вовсе
        write("data/export.xml", "<rows><row id=\"1\"/></rows>\n");
        write("data/settings.yml", "drop: table\n");
        ScanContext context = context();

        assertThat(context.textFiles()).extracting(TextFile::type)
                .containsExactly(TextFileType.LIQUIBASE_XML, TextFileType.LIQUIBASE_YAML);
        // Откат (rollback) миграцией не считается
        assertThat(check(new CheckSqlDestructiveStatementRule(), context))
                .containsExactly("changelog.xml:17", "changelog.xml:18", "changelog.xml:21", "changes.yaml:14");
        assertThat(check(new CheckSqlNotNullWithoutDefaultRule(), context))
                .containsExactly("changelog.xml:14", "changes.yaml:9");
        // Индекс по manager_id создан отдельным изменением
        assertThat(check(new CheckSqlForeignKeyWithoutIndexRule(), context)).containsExactly("changelog.xml:4");
        assertThat(check(new CheckSqlChangeWithoutWhereRule(), context))
                .containsExactly("changelog.xml:19", "changelog.xml:22", "changes.yaml:18");
    }

    @Test
    void pomVersionsAreResolvedFromPropertiesAndParent() throws IOException {
        write("pom.xml", """
                <project>
                  <groupId>com.example</groupId>
                  <artifactId>parent</artifactId>
                  <version>1.0</version>
                  <properties>
                    <core.version>2.0-SNAPSHOT</core.version>
                    <api.version>3.1</api.version>
                  </properties>
                </project>
                """);
        write("service/pom.xml", """
                <project>
                  <parent>
                    <groupId>com.example</groupId>
                    <artifactId>parent</artifactId>
                    <version>1.0</version>
                  </parent>
                  <artifactId>service</artifactId>
                  <properties>
                    <api.version>${revision}-SNAPSHOT</api.version>
                    <revision>4.0</revision>
                  </properties>
                  <dependencies>
                    <!-- <dependency><groupId>x</groupId><artifactId>old</artifactId><version>LATEST</version></dependency> -->
                    <dependency><groupId>com.example</groupId><artifactId>core</artifactId><version>${core.version}</version></dependency>
                    <dependency><groupId>com.example</groupId><artifactId>api</artifactId><version>${api.version}</version></dependency>
                    <dependency><groupId>com.example</groupId><artifactId>self</artifactId><version>${project.version}</version></dependency>
                    <dependency><groupId>com.example</groupId><artifactId>other</artifactId><version>${missing.version}</version></dependency>
                  </dependencies>
                  <build><plugins><plugin><dependencies>
                    <dependency><groupId>p</groupId><artifactId>tool</artifactId><version>1.0-SNAPSHOT</version></dependency>
                  </dependencies></plugin></plugins></build>
                </project>
                """);
        ScanContext context = context();

        // core.version задана в родителе, api.version переопределена в модуле и сама ссылается на revision;
        // версия модуля унаследована от родителя; зависимости плагина сборки в приложение не попадают
        List<Violation> violations = new CheckUnstableDependencyVersionRule().checkContext(context);
        assertThat(violations).extracting(this::place).containsExactly("pom.xml:14", "pom.xml:15");
        assertThat(violations).extracting(Violation::message)
                .anyMatch(message -> message.contains("com.example:core:2.0-SNAPSHOT"))
                .anyMatch(message -> message.contains("com.example:api:4.0-SNAPSHOT"));
    }

    @Test
    void yamlSettingsAreReadWithListsAndBlocks() throws IOException {
        write("src/main/resources/application.yml", """
                app:
                  servers:
                    - host: a.example
                      port: 8080
                    - host: b.example
                  names: [x, y]
                  text: |
                    line one
                    line two
                  quoted: "a: b # не комментарий"
                """);
        // Подстановка сборки делает файл некорректным YAML - тогда ключи читаются построчно
        write("src/main/resources/application-build.yml", """
                app:
                  version: @project.version@
                  name: shop
                """);
        List<ConfigFile> files = new FileSystemConfigLoader().load(dir, file -> true);

        ConfigFile filtered = files.get(0);
        assertThat(filtered.find("app.name")).map(property -> property.value()).contains("shop");

        ConfigFile main = files.get(1);
        assertThat(main.properties()).extracting(property -> property.key() + "=" + property.line()).containsExactly(
                "app.servers[0].host=3", "app.servers[0].port=4", "app.servers[1].host=5",
                "app.names[0]=6", "app.names[1]=6", "app.text=7", "app.quoted=10");
        assertThat(main.find("app.quoted")).map(property -> property.value()).contains("a: b # не комментарий");
        assertThat(main.find("app.text")).map(property -> property.value()).contains("line one\nline two\n");
    }

    @Test
    void loggingSettings() throws IOException {
        write("src/main/resources/logback-spring.xml", """
                <configuration>
                  <appender name="CONSOLE" class="ch.qos.logback.core.ConsoleAppender">
                    <encoder><pattern>%d %-5level %logger{36} %M:%L - %msg%n</pattern></encoder>
                  </appender>
                  <appender name="FILE" class="ch.qos.logback.core.FileAppender">
                    <file>app.log</file>
                    <encoder><pattern>%d %-5level %logger - %msg%n</pattern></encoder>
                  </appender>
                  <appender name="ROLLING" class="ch.qos.logback.core.rolling.RollingFileAppender">
                    <rollingPolicy class="ch.qos.logback.core.rolling.TimeBasedRollingPolicy">
                      <fileNamePattern>app.%d.log</fileNamePattern>
                    </rollingPolicy>
                  </appender>
                  <appender name="LIMITED" class="ch.qos.logback.core.rolling.RollingFileAppender">
                    <rollingPolicy class="ch.qos.logback.core.rolling.TimeBasedRollingPolicy">
                      <maxHistory>30</maxHistory>
                    </rollingPolicy>
                  </appender>
                  <springProfile name="dev">
                    <root level="TRACE"><appender-ref ref="CONSOLE"/></root>
                  </springProfile>
                  <root level="DEBUG">
                    <appender-ref ref="CONSOLE"/>
                    <appender-ref ref="FILE"/>
                    <appender-ref ref="LIMITED"/>
                  </root>
                </configuration>
                """);
        write("src/main/resources/log4j2.xml", """
                <Configuration>
                  <Appenders>
                    <Console name="Console"><PatternLayout pattern="%d %p %c - %m%n"/></Console>
                    <File name="Plain" fileName="app.log"><PatternLayout pattern="%d %l %m%n"/></File>
                  </Appenders>
                  <Loggers>
                    <Root level="trace"><AppenderRef ref="Console"/></Root>
                  </Loggers>
                </Configuration>
                """);
        // Настройки для тестов не проверяются
        write("src/test/resources/logback-test.xml", """
                <configuration>
                  <appender name="FILE" class="ch.qos.logback.core.FileAppender"/>
                  <root level="DEBUG"/>
                </configuration>
                """);
        ScanContext context = context();

        // Профиль dev в рабочую среду не попадает
        assertThat(check(new CheckLogRootLevelDebugRule(), context))
                .containsExactly("log4j2.xml:7", "logback-spring.xml:22");
        assertThat(check(new CheckLogFileWithoutRotationRule(), context))
                .containsExactly("log4j2.xml:4", "logback-spring.xml:5", "logback-spring.xml:9");
        assertThat(check(new CheckLogUnusedAppenderRule(), context))
                .containsExactly("log4j2.xml:4", "logback-spring.xml:9");
        // %logger, %level, %c и %m места вызова не требуют
        assertThat(check(new CheckLogCallerDataInPatternRule(), context))
                .containsExactly("log4j2.xml:4", "logback-spring.xml:3");
    }

    @Test
    void dockerCompose() throws IOException {
        write("docker-compose.yml", """
                services:
                  db:
                    image: postgres
                    environment:
                      POSTGRES_PASSWORD: s3cr3tPass
                      POSTGRES_USER: app
                      POSTGRES_DB: ${DB_NAME}
                    ports:
                      - "5432:5432"
                  cache:
                    image: redis:7.2
                    ports:
                      - "127.0.0.1:6379:6379"
                  agent:
                    image: example/agent:latest
                    privileged: true
                    network_mode: host
                    environment:
                      - API_TOKEN=abc123def
                      - API_URL=http://x
                      - DB_PASSWORD=${DB_PASSWORD}
                    volumes:
                      - /var/run/docker.sock:/var/run/docker.sock
                    ports:
                      - "8080:8080"
                """);
        ScanContext context = context();

        assertThat(check(new CheckComposeUnpinnedImageRule(), context))
                .containsExactly("docker-compose.yml:15", "docker-compose.yml:3");
        // Значение из окружения (${...}) секретом в файле не является
        assertThat(check(new CheckComposeSecretInEnvironmentRule(), context))
                .containsExactly("docker-compose.yml:19", "docker-compose.yml:5");
        assertThat(check(new CheckComposePrivilegedServiceRule(), context))
                .containsExactly("docker-compose.yml:16", "docker-compose.yml:17", "docker-compose.yml:23");
        // Порт, привязанный к 127.0.0.1, снаружи недоступен; 8080 - порт самого приложения
        assertThat(check(new CheckComposeDatabasePortExposedRule(), context)).containsExactly("docker-compose.yml:9");
    }

    @Test
    void kubernetesManifests() throws IOException {
        write("deploy/app.yaml", """
                apiVersion: apps/v1
                kind: Deployment
                metadata:
                  name: shop
                spec:
                  template:
                    spec:
                      hostNetwork: true
                      containers:
                        - name: app
                          image: registry.local:5000/shop
                          env:
                            - name: DB_PASSWORD
                              value: s3cr3tPass
                            - name: DB_HOST
                              value: db
                            - name: API_TOKEN
                              valueFrom:
                                secretKeyRef: {name: s, key: t}
                          securityContext:
                            privileged: true
                        - name: sidecar
                          image: envoy:1.29
                          resources:
                            requests: {cpu: 100m}
                            limits: {memory: 128Mi}
                          readinessProbe: {httpGet: {path: /, port: 80}}
                          livenessProbe: {httpGet: {path: /, port: 80}}
                ---
                apiVersion: v1
                kind: Secret
                metadata:
                  name: s
                stringData:
                  t: plain-token
                ---
                apiVersion: batch/v1
                kind: Job
                metadata:
                  name: migrate
                spec:
                  template:
                    spec:
                      containers:
                        - name: job
                          image: migrator:2.0
                          resources:
                            requests: {cpu: 1}
                            limits: {cpu: 1}
                """);
        // Шаблон Helm - не YAML: он пропускается, а не роняет проверку
        write("chart/templates/deployment.yaml", """
                apiVersion: apps/v1
                kind: Deployment
                spec:
                  replicas: {{ .Values.replicas }}
                """);
        ScanContext context = context();

        assertThat(check(new CheckKubernetesNoResourceLimitsRule(), context)).containsExactly("app.yaml:10");
        // Задаче (Job) проверки готовности не нужны
        assertThat(check(new CheckKubernetesNoProbesRule(), context)).containsExactly("app.yaml:10");
        // registry.local:5000 - порт реестра, а не тег
        assertThat(check(new CheckKubernetesUnpinnedImageRule(), context)).containsExactly("app.yaml:11");
        assertThat(check(new CheckKubernetesPrivilegedContainerRule(), context))
                .containsExactly("app.yaml:21", "app.yaml:8");
        assertThat(check(new CheckKubernetesSecretInManifestRule(), context))
                .containsExactly("app.yaml:14", "app.yaml:35");
    }

    @Test
    void ciFiles() throws IOException {
        write(".gitlab-ci.yml", """
                image: maven:latest
                variables:
                  DEPLOY_TOKEN: abc123def
                  MAVEN_OPTS: "-Xmx1g"
                  DB_PASSWORD: $CI_DB_PASSWORD
                build:
                  script:
                    - mvn package -DskipTests
                    - echo done
                """);
        write(".github/workflows/build.yml", """
                name: build
                on: push
                jobs:
                  build:
                    runs-on: ubuntu-latest
                    env:
                      API_KEY: ${{ secrets.API_KEY }}
                    steps:
                      - uses: actions/checkout@v4
                      - uses: some/action@main
                      - uses: ./local-action
                      - run: |
                          ./gradlew build -x test
                          ./gradlew test
                      - uses: docker/login-action@v3
                        with:
                          password: hunter2pass
                """);
        ScanContext context = context();

        assertThat(context.textFiles()).extracting(TextFile::type)
                .containsExactlyInAnyOrder(TextFileType.GITLAB_CI, TextFileType.GITHUB_WORKFLOW);
        assertThat(check(new CheckCiSecretInVariablesRule(), context))
                .containsExactly(".gitlab-ci.yml:3", "build.yml:17");
        assertThat(check(new CheckCiUnpinnedReferenceRule(), context))
                .containsExactly(".gitlab-ci.yml:1", "build.yml:10");
        // В build.yml тесты идут отдельной командой - сборка без них там допустима
        assertThat(check(new CheckCiTestsSkippedRule(), context)).containsExactly(".gitlab-ci.yml:8");
    }

    private ScanContext context() {
        return new ScanContext(
                dir,
                List.of(),
                new FileSystemConfigLoader().load(dir, file -> true),
                new FileSystemTextFileLoader().load(dir, file -> true));
    }

    private void write(String path, String content) throws IOException {
        Path file = dir.resolve(path);
        Files.createDirectories(file.getParent());
        Files.writeString(file, content);
    }

    // Находки в виде "файл:строка", по алфавиту
    private List<String> check(ContextRule rule, ScanContext context) {
        return rule.checkContext(context).stream().map(this::place).sorted().toList();
    }

    private String place(Violation violation) {
        return violation.file().getFileName() + ":" + violation.line();
    }
}
