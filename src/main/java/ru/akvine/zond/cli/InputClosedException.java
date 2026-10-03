package ru.akvine.zond.cli;

/**
 * Ввод закончился: консоль закрыта либо приложению передали готовый текст и он исчерпан.
 * Меню на любом уровне вложенности нужно завершить.
 */
public class InputClosedException extends RuntimeException {

    public InputClosedException() {
        super("Ввод закрыт");
    }
}
