package ru.akvine.zond.rules.support;

import ru.akvine.zond.models.SourceFile;

import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;

/**
 * Значение, которое считается один раз на набор файлов: граф вызовов, список классов, сводки методов.
 * <p>
 * Набор узнается по самому списку, а не по содержимому: правила одной проверки получают один и тот же список.
 * Наборов бывает несколько - независимые части проекта проверяются каждая со своим списком, и правила разных
 * потоков обращаются к ним вперемешку, поэтому помнить только последний набор нельзя. Значение живет, пока жив
 * список: ссылка на него слабая, и после проверки память освобождается.
 */
public final class PerSources<T> {
    private final List<Entry<T>> entries = new ArrayList<>();

    private record Entry<T>(WeakReference<List<SourceFile>> sources, T value) {
    }

    /**
     * @param builder считает значение для набора, которого еще не было; вызывается под блокировкой, так что
     *                два потока одно и то же значение дважды не посчитают
     */
    public synchronized T get(List<SourceFile> sources, Function<List<SourceFile>, T> builder) {
        entries.removeIf(entry -> entry.sources().get() == null);
        for (Entry<T> entry : entries) {
            if (entry.sources().get() == sources) {
                return entry.value();
            }
        }
        T value = builder.apply(sources);
        entries.add(new Entry<>(new WeakReference<>(sources), value));
        return value;
    }
}
