package ru.akvine.zond.rules.support;

import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.DataKey;
import com.github.javaparser.ast.ImportDeclaration;
import com.github.javaparser.ast.body.TypeDeclaration;
import com.github.javaparser.ast.expr.Expression;
import com.github.javaparser.ast.expr.MethodCallExpr;
import lombok.experimental.UtilityClass;
import ru.akvine.zond.loaders.ProjectSubtypes;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;

@UtilityClass
public class Repositories {
    private final static String REPOSITORY_ANNOTATION = "Repository";

    // Имя объекта либо типа: userRepository, orderDao, JpaRepository
    private final static Pattern REPOSITORY = Pattern.compile(".*(repository|repo|dao)$", Pattern.CASE_INSENSITIVE);

    // USER_REPOSITORY - константа, а не внедренный бин
    private final static Pattern CONSTANT_NAME = Pattern.compile("^[A-Z][A-Z0-9_]*$");
    private final static String PACKAGE_SEPARATOR = ".";
    private final static DataKey<Boolean> PERSISTENCE_CODE = new DataKey<>() {
    };
    private final static DataKey<Boolean> OWN_NON_STORAGE = new DataKey<>() {
    };
    private final static Object PERSISTENCE_LOCK = new Object();

    // Признаки хранилища у своего типа: предок из Spring Data или MyBatis, аннотации, импорты, клиенты БД в полях
    private final static Pattern STORAGE_PARENT = Pattern.compile(".*(Repository|Repo|Dao|DAO|Mapper)$");
    private final static Pattern DAO_NAME = Pattern.compile(".*(Dao|DAO)$");
    // Поле-репозиторий, которому адаптер отдает вызовы. ObjectMapper из Jackson сюда не подходит
    private final static Pattern DELEGATE = Pattern.compile(".*(Repository|Dao|DAO)$");
    // Сколько наследников и реализаций просматривать в глубину
    private final static int MAX_SUBTYPES = 50;
    private final static Set<String> STORAGE_ANNOTATIONS = Set.of(
            "Repository", "RepositoryDefinition", "Mapper", "Transactional", "Query", "Modifying", "Select", "Insert",
            "Update", "Delete", "Procedure", "SqlQuery", "SqlUpdate");
    private final static List<String> STORAGE_PACKAGES = List.of(
            "jakarta.persistence", "javax.persistence", "org.springframework.data", "org.springframework.jdbc",
            "org.springframework.r2dbc", "org.springframework.orm", "org.hibernate", "java.sql", "javax.sql",
            "org.jooq", "org.apache.ibatis", "org.mybatis", "io.r2dbc", "org.jdbi", "com.mongodb", "io.ebean",
            "com.querydsl", "io.vertx.sqlclient", "com.datastax");
    private final static Set<String> STORAGE_CLIENTS = Set.of(
            "JdbcTemplate", "NamedParameterJdbcTemplate", "JdbcClient", "EntityManager", "Session", "SessionFactory",
            "DataSource", "Connection", "DSLContext", "MongoTemplate", "ReactiveMongoTemplate", "SqlSession",
            "SqlSessionTemplate", "R2dbcEntityTemplate", "DatabaseClient", "Jdbi", "Handle", "JPAQueryFactory");
    // findByEmail, countAllByStatus, deleteById, save: так называют методы запросов
    private final static Pattern QUERY_METHOD = Pattern.compile(
            "(find|get|read|query|search|stream|count|exists|delete|remove)(All|Distinct|First|Top\\d*)?By[A-Z].*"
                    + "|save|saveAll|saveAndFlush|findById|findAll|findAllById|getById|getReferenceById|existsById"
                    + "|deleteById|deleteAll|insert|persist|merge|upsert");

    public boolean isRepository(String receiver) {
        return REPOSITORY.matcher(receiver).matches() && !CONSTANT_NAME.matcher(receiver).matches();
    }

    /**
     * @return true для вызова вида userRepository.findById(...)
     */
    public boolean isRepositoryCall(MethodCallExpr call) {
        return call.getScope().filter(Repositories::isRepositoryObject).isPresent();
    }

    // По типу: сам он либо его предок - репозиторий (interface Users extends JpaRepository), либо на нем @Repository.
    // Если тип неизвестен - по имени объекта
    private boolean isRepositoryObject(Expression scope) {
        if (Types.annotations(scope).contains(REPOSITORY_ANNOTATION)) {
            return true;
        }
        boolean looksLikeRepository = Types.matches(scope, type -> REPOSITORY.matcher(type).matches())
                .orElseGet(() -> !isLibraryType(scope) && isRepository(MethodCalls.receiverName(scope))
                        && !isDeclaredAsOtherType(scope)
                        && (!Nodes.unwrap(scope).isMethodCallExpr() || isPersistenceFile(scope)));
        // Дорогие проверки - только для того, что уже похоже на репозиторий
        return looksLikeRepository && !isTypeReference(scope) && !isOwnNonStorageType(scope);
    }

    // Repository.assertSnapshotMetaThread(): статический метод типа, а не обращение к бину
    private boolean isTypeReference(Expression scope) {
        return scope.isNameExpr() && Character.isUpperCase(scope.asNameExpr().getNameAsString().charAt(0))
                && !CONSTANT_NAME.matcher(scope.asNameExpr().getNameAsString()).matches()
                && LocalTypes.findDeclaration(scope).isEmpty();
    }

    /**
     * Тип объявлен в проекте, и ни он, ни его предки с хранилищем не связаны: Repository снапшотов в
     * Elasticsearch, record ProjectRepo, IWorkflowRepository с кешем в памяти. Порт гексагональной архитектуры
     * (interface UserRepository { Optional<User> findById(Long id); }) узнаем по методам запросов либо по реализации
     */
    private boolean isOwnNonStorageType(Expression scope) {
        Optional<TypeDeclaration<?>> type = Types.projectType(scope);
        if (type.isEmpty()) {
            return false;
        }
        // Ответ один на тип: иерархию не разбираем заново для каждого вызова
        synchronized (PERSISTENCE_LOCK) {
            if (type.get().containsData(OWN_NON_STORAGE)) {
                return type.get().getData(OWN_NON_STORAGE);
            }
        }
        boolean storageParent = Types.libraryHierarchy(scope).stream()
                .anyMatch(name -> STORAGE_PARENT.matcher(name).matches());
        boolean nonStorage = !storageParent
                && Types.projectHierarchy(scope).stream().noneMatch(Repositories::looksLikeStorage)
                && !hasStorageImplementation(type.get());
        synchronized (PERSISTENCE_LOCK) {
            type.get().setData(OWN_NON_STORAGE, nonStorage);
        }
        return nonStorage;
    }

    private boolean looksLikeStorage(TypeDeclaration<?> type) {
        // DAO, в отличие от "репозитория", ничего другого не означает
        if (DAO_NAME.matcher(type.getNameAsString()).matches()) {
            return true;
        }
        boolean annotated = TestClasses.annotationNames(type).stream().anyMatch(STORAGE_ANNOTATIONS::contains)
                || type.getMethods().stream()
                .flatMap(method -> TestClasses.annotationNames(method).stream())
                .anyMatch(STORAGE_ANNOTATIONS::contains);
        boolean importsStorage = type.findCompilationUnit().stream()
                .flatMap(unit -> unit.getImports().stream())
                .map(ImportDeclaration::getNameAsString)
                .anyMatch(name -> STORAGE_PACKAGES.stream().anyMatch(name::startsWith));
        boolean storageClient = type.getFields().stream()
                .map(field -> LocalTypes.typeName(field.getElementType()))
                .anyMatch(STORAGE_CLIENTS::contains);
        boolean queries = type.getMethods().stream()
                .anyMatch(method -> QUERY_METHOD.matcher(method.getNameAsString()).matches());
        return annotated || importsStorage || storageClient || queries;
    }

    /**
     * Интерфейс AdministrationDAO о базе ничего не говорит, а его реализация HibernateAdministrationDAO работает
     * с Hibernate; адаптер порта UserRepository отдает вызовы репозиторию Spring Data в своем поле.
     * Обертка того же типа (FilterRepository с полем Repository in) хранилищем от этого не становится
     */
    private boolean hasStorageImplementation(TypeDeclaration<?> type) {
        ProjectSubtypes subtypes = type.findCompilationUnit().map(ProjectSubtypes::of).orElse(null);
        if (subtypes == null) {
            return false;
        }
        Set<String> visited = new HashSet<>();
        Deque<String> names = new ArrayDeque<>(List.of(type.getNameAsString()));
        while (!names.isEmpty() && visited.size() < MAX_SUBTYPES) {
            String name = names.poll();
            if (!visited.add(name)) {
                continue;
            }
            for (TypeDeclaration<?> subtype : subtypes.directSubtypes(name)) {
                if (looksLikeStorage(subtype) || delegatesToStorage(subtype, visited)) {
                    return true;
                }
                names.add(subtype.getNameAsString());
            }
        }
        return false;
    }

    private boolean delegatesToStorage(TypeDeclaration<?> type, Set<String> wrapped) {
        return type.getFields().stream()
                .map(field -> LocalTypes.typeName(field.getElementType()))
                .filter(name -> !wrapped.contains(name) && !name.equals(type.getNameAsString()))
                .anyMatch(name -> DELEGATE.matcher(name).matches());
    }

    // RepositoryName repository: тип объявлен, импортирован явно и на репозиторий не похож - имя ничего не решает.
    // Неизвестный тип без импорта не в счет: о нем судим по имени
    private boolean isDeclaredAsOtherType(Expression scope) {
        Optional<String> declared = LocalTypes.findDeclaration(scope)
                .flatMap(LocalTypes::declaredType)
                .filter(type -> !REPOSITORY.matcher(type).matches());
        Optional<CompilationUnit> unit = scope.findCompilationUnit();
        return declared.isPresent() && unit.isPresent() && unit.get().getImports().stream()
                .filter(declaration -> !declaration.isAsterisk() && !declaration.isStatic())
                .map(ImportDeclaration::getNameAsString)
                .anyMatch(name -> name.endsWith(PACKAGE_SEPARATOR + declared.get()));
    }

    // label.getRepository().getName() в Bazel - внешний репозиторий кода, а не таблица. Результат вызова
    // getRepository() считается репозиторием, только если файл вообще работает с хранилищем: JPA, Spring Data,
    // тип *Repository или *Dao, @Transactional
    private boolean isPersistenceFile(Expression scope) {
        Optional<CompilationUnit> unit = scope.findCompilationUnit();
        if (unit.isEmpty()) {
            return true;
        }
        synchronized (PERSISTENCE_LOCK) {
            if (unit.get().containsData(PERSISTENCE_CODE)) {
                return unit.get().getData(PERSISTENCE_CODE);
            }
        }
        boolean persistence = JpaEntities.isPersistenceCode(unit.get());
        synchronized (PERSISTENCE_LOCK) {
            unit.get().setData(PERSISTENCE_CODE, persistence);
        }
        return persistence;
    }

    // ClassOrInterfaceDeclaration repository: тип объявлен в коде и импортирован из библиотеки (не из пакетов
    // проекта), а по имени на репозиторий не похож - имя переменной тут ничего не говорит
    private boolean isLibraryType(Expression scope) {
        Optional<String> declared = LocalTypes.typeOf(scope);
        Optional<CompilationUnit> unit = scope.findCompilationUnit();
        if (declared.isEmpty() || unit.isEmpty() || REPOSITORY.matcher(declared.get()).matches()) {
            return false;
        }
        String own = unit.get().getPackageDeclaration().map(declaration -> rootOf(declaration.getNameAsString())).orElse("");
        return !own.isEmpty() && unit.get().getImports().stream()
                .filter(declaration -> !declaration.isAsterisk() && !declaration.isStatic())
                .map(ImportDeclaration::getNameAsString)
                .filter(name -> name.endsWith(PACKAGE_SEPARATOR + declared.get()))
                .anyMatch(name -> !name.startsWith(own + PACKAGE_SEPARATOR));
    }

    // ru.akvine.zond.rules -> ru.akvine: первые две части имени пакета принадлежат проекту
    private String rootOf(String packageName) {
        String[] parts = packageName.split("\\.");
        return parts.length < 2 ? "" : parts[0] + PACKAGE_SEPARATOR + parts[1];
    }
}
