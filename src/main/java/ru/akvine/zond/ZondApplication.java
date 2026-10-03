package ru.akvine.zond;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@SpringBootApplication
public class ZondApplication {

	public static void main(String[] args) {
		System.exit(SpringApplication.exit(SpringApplication.run(ZondApplication.class, args)));
	}

}
