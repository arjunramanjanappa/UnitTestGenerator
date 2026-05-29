package com.testgen.ui;

import javafx.fxml.FXML;
import javafx.scene.control.TextArea;
import javafx.scene.control.Label;
import org.springframework.stereotype.Component;

/**
 * Controller for the standalone preview window (optional pop-out).
 */
@Component
public class PreviewController {

    @FXML private Label titleLabel;
    @FXML private TextArea contentArea;

    public void setContent(String title, String content) {
        titleLabel.setText(title);
        contentArea.setText(content);
    }
}
