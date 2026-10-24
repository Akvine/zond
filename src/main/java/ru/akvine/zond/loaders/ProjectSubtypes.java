package ru.akvine.zond.loaders;

import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.DataKey;
import com.github.javaparser.ast.body.TypeDeclaration;
import com.github.javaparser.ast.nodeTypes.NodeWithExtends;
import com.github.javaparser.ast.nodeTypes.NodeWithImplements;
import com.github.javaparser.ast.type.ClassOrInterfaceType;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Наследники и реализации типов проекта: кто пишет его имя в extends или implements. Решатель типов отвечает
 * только на вопрос "кто предок", а правилам бывает нужен и обратный: интерфейс AdministrationDAO сам о базе
 * ничего не говорит, а его реализация HibernateAdministrationDAO работает с Hibernate.
 * <p>
 * Типы сопоставляются по простому имени, как они записаны в коде.
 */
public final class ProjectSubtypes {
    /**
     * Общий для всех файлов проекта указатель: загрузчик прикрепляет его к каждому файлу
     */
    public static final DataKey<ProjectSubtypes> KEY = new DataKey<>() {
    };

    private final Map<String, List<TypeDeclaration<?>>> byParent = new HashMap<>();

    private ProjectSubtypes() {
    }

    public static ProjectSubtypes of(List<CompilationUnit> units) {
        ProjectSubtypes subtypes = new ProjectSubtypes();
        for (CompilationUnit unit : units) {
            for (TypeDeclaration<?> type : unit.findAll(TypeDeclaration.class)) {
                for (ClassOrInterfaceType parent : declaredParents(type)) {
                    subtypes.byParent.computeIfAbsent(parent.getNameAsString(), name -> new ArrayList<>()).add(type);
                }
            }
        }
        return subtypes;
    }

    /**
     * @return индекс проекта, к которому относится файл; если файл разобран отдельно (без загрузчика) -
     * индекс по самому файлу
     */
    public static ProjectSubtypes of(CompilationUnit unit) {
        if (unit.containsData(KEY)) {
            return unit.getData(KEY);
        }
        return of(List.of(unit));
    }

    /**
     * @return типы, у которых в extends или implements стоит это простое имя
     */
    public List<TypeDeclaration<?>> directSubtypes(String parentName) {
        return byParent.getOrDefault(parentName, List.of());
    }

    private static List<ClassOrInterfaceType> declaredParents(TypeDeclaration<?> type) {
        List<ClassOrInterfaceType> parents = new ArrayList<>();
        if (type instanceof NodeWithExtends<?> withExtends) {
            parents.addAll(withExtends.getExtendedTypes());
        }
        if (type instanceof NodeWithImplements<?> withImplements) {
            parents.addAll(withImplements.getImplementedTypes());
        }
        return parents;
    }
}
