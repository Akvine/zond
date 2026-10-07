package ru.akvine.zond;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import ru.akvine.zond.config.ZondVersion;

import java.util.Arrays;

@SpringBootApplication
public class ZondApplication {
	private static final String VERSION_OPTION = "--version";

	public static void main(String[] args) {
		// Версию печатаем до запуска Spring: без баннера, настроек и сканирования
		if (Arrays.asList(args).contains(VERSION_OPTION)) {
			System.out.println(ZondVersion.title());
			return;
		}
		System.exit(SpringApplication.exit(SpringApplication.run(ZondApplication.class, args)));
	}

}
