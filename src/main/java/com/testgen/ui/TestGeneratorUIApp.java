package com.testgen.ui;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableAsync;

@SpringBootApplication(scanBasePackages = "com.testgen")
@EnableAsync
public class TestGeneratorUIApp {

    public static void main(String[] args) {
        SpringApplication.run(TestGeneratorUIApp.class, args);
        System.out.println("\n========================================");
        System.out.println("  Unit Test Generator started.");
        System.out.println("  Open: http://localhost:8080");
        System.out.println("========================================\n");
    }
}
