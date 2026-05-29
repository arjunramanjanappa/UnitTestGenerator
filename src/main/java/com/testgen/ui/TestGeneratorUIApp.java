package com.testgen.ui;

import javafx.application.Application;
import javafx.fxml.FXMLLoader;
import javafx.scene.Scene;
import javafx.stage.Stage;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.ConfigurableApplicationContext;

@SpringBootApplication(scanBasePackages = "com.testgen")
public class TestGeneratorUIApp extends Application {

    private static ConfigurableApplicationContext springContext;
    private static String[] appArgs;

    public static void main(String[] args) {
        appArgs = args;
        Application.launch(TestGeneratorUIApp.class, args);
    }

    @Override
    public void init() {
        springContext = SpringApplication.run(TestGeneratorUIApp.class, appArgs);
    }

    @Override
    public void start(Stage stage) throws Exception {
        FXMLLoader loader = new FXMLLoader(
                getClass().getResource("/com/testgen/ui/main-view.fxml"));
        loader.setControllerFactory(springContext::getBean);

        Scene scene = new Scene(loader.load(), 1200, 800);
        scene.getStylesheets().add(
                getClass().getResource("/com/testgen/ui/style.css") != null
                ? getClass().getResource("/com/testgen/ui/style.css").toExternalForm()
                : "");

        stage.setTitle("Unit Test Generator — UFW Model");
        stage.setScene(scene);
        stage.setMinWidth(900);
        stage.setMinHeight(600);
        stage.show();
    }

    @Override
    public void stop() {
        if (springContext != null) {
            springContext.close();
        }
    }
}
