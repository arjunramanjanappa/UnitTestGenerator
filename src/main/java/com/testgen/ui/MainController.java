package com.testgen.ui;

import com.testgen.generator.NamingConvention;
import com.testgen.generator.TestOrchestrator;
import com.testgen.report.GenerationReport;
import javafx.application.Platform;
import javafx.collections.FXCollections;
import javafx.concurrent.Task;
import javafx.fxml.FXML;
import javafx.fxml.FXMLLoader;
import javafx.fxml.Initializable;
import javafx.scene.Parent;
import javafx.scene.Scene;
import javafx.scene.control.*;
import javafx.scene.control.cell.CheckBoxTreeCell;
import javafx.stage.DirectoryChooser;
import javafx.stage.FileChooser;
import javafx.stage.Stage;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.io.File;
import java.io.IOException;
import java.net.URL;
import java.nio.file.*;
import java.util.*;
import java.util.stream.Stream;

@Slf4j
@Component
@RequiredArgsConstructor
public class MainController implements Initializable {

    // ── Inputs ───────────────────────────────────────────────────────────────
    @FXML private TextField sourcePathField;
    @FXML private TextField targetPathField;
    @FXML private TextField includePatternField;
    @FXML private TextField excludePatternField;
    @FXML private ComboBox<NamingConvention> namingConventionCombo;
    @FXML private Spinner<Integer> inheritanceDepthSpinner;
    @FXML private CheckBox overwriteCheckBox;
    @FXML private CheckBox dryRunCheckBox;

    // ── Action buttons ───────────────────────────────────────────────────────
    @FXML private Button browseSourceBtn;
    @FXML private Button browseTargetBtn;
    @FXML private Button scanBtn;
    @FXML private Button generateBtn;
    @FXML private Button exportReportBtn;

    // ── Progress & log ───────────────────────────────────────────────────────
    @FXML private ProgressBar progressBar;
    @FXML private Label progressLabel;
    @FXML private TextArea logArea;

    // ── Class tree & preview ─────────────────────────────────────────────────
    @FXML private TreeView<String> classTreeView;
    @FXML private TextArea previewArea;
    @FXML private Label previewLabel;

    // ── Report area ──────────────────────────────────────────────────────────
    @FXML private TextArea reportArea;

    private final TestOrchestrator orchestrator;
    private GenerationReport lastReport;

    // Scanned files cached for tree display
    private List<Path> scannedFiles = new ArrayList<>();

    @Override
    public void initialize(URL url, ResourceBundle rb) {
        namingConventionCombo.setItems(FXCollections.observableArrayList(NamingConvention.values()));
        namingConventionCombo.setValue(NamingConvention.TEST_METHOD_SCENARIO);

        inheritanceDepthSpinner.setValueFactory(
                new SpinnerValueFactory.IntegerSpinnerValueFactory(0, 10, 1));

        overwriteCheckBox.setSelected(true);
        generateBtn.setDisable(true);
        exportReportBtn.setDisable(true);
        progressBar.setProgress(0);

        // Auto-fill target when source is set
        sourcePathField.textProperty().addListener((obs, oldVal, newVal) -> {
            if (newVal != null && newVal.contains("src/main/java")) {
                targetPathField.setText(newVal.replace("src/main/java", "src/test/java")
                        .replace("src\\main\\java", "src\\test\\java"));
            }
        });
    }

    @FXML
    void onBrowseSource() {
        File dir = chooseDirectory("Select src/main/java");
        if (dir != null) sourcePathField.setText(dir.getAbsolutePath());
    }

    @FXML
    void onBrowseTarget() {
        File dir = chooseDirectory("Select src/test/java");
        if (dir != null) targetPathField.setText(dir.getAbsolutePath());
    }

    @FXML
    void onScan() {
        String sourcePath = sourcePathField.getText().trim();
        if (sourcePath.isEmpty()) {
            showAlert("Validation", "Please enter a source folder path.");
            return;
        }
        Path sourceRoot = Path.of(sourcePath);
        if (!Files.isDirectory(sourceRoot)) {
            showAlert("Not Found", "Source directory does not exist: " + sourcePath);
            return;
        }

        log("Scanning: " + sourcePath);
        generateBtn.setDisable(true);
        scannedFiles.clear();

        Task<List<Path>> task = new Task<>() {
            @Override
            protected List<Path> call() throws Exception {
                List<String> includes = parsePatterns(includePatternField.getText());
                List<String> excludes = parsePatterns(excludePatternField.getText());
                try (Stream<Path> stream = Files.walk(sourceRoot)) {
                    return stream
                            .filter(p -> p.toString().endsWith(".java"))
                            .filter(p -> !p.getFileName().toString().equals("package-info.java"))
                            .filter(p -> includes.isEmpty() || matchesAny(p, includes))
                            .filter(p -> !matchesAny(p, excludes))
                            .sorted()
                            .toList();
                }
            }
        };

        task.setOnSucceeded(e -> {
            scannedFiles = task.getValue();
            buildClassTree(sourceRoot, scannedFiles);
            log("Found " + scannedFiles.size() + " Java class(es).");
            generateBtn.setDisable(scannedFiles.isEmpty());
        });

        task.setOnFailed(e -> log("Scan failed: " + task.getException().getMessage()));
        new Thread(task, "scanner").start();
    }

    @FXML
    void onGenerate() {
        String sourcePath = sourcePathField.getText().trim();
        String targetPath = targetPathField.getText().trim();

        if (sourcePath.isEmpty() || targetPath.isEmpty()) {
            showAlert("Validation", "Source and Target paths are required.");
            return;
        }

        boolean isDryRun = dryRunCheckBox.isSelected();
        boolean overwrite = overwriteCheckBox.isSelected();
        NamingConvention convention = namingConventionCombo.getValue();
        int depth = inheritanceDepthSpinner.getValue();
        List<String> includes = parsePatterns(includePatternField.getText());
        List<String> excludes = parsePatterns(excludePatternField.getText());

        log(isDryRun ? "Dry-run started..." : "Generation started...");
        generateBtn.setDisable(true);
        progressBar.setProgress(ProgressBar.INDETERMINATE_PROGRESS);

        Task<GenerationReport> task = new Task<>() {
            @Override
            protected GenerationReport call() {
                return orchestrator.generate(
                        Path.of(sourcePath), Path.of(targetPath),
                        overwrite, includes, excludes,
                        convention, isDryRun, depth,
                        (current, total) -> Platform.runLater(() -> {
                            progressBar.setProgress((double) current / total);
                            progressLabel.setText(current + " / " + total);
                            log("Processing " + current + "/" + total);
                        })
                );
            }
        };

        task.setOnSucceeded(e -> {
            lastReport = task.getValue();
            progressBar.setProgress(1.0);
            progressLabel.setText("Done");
            reportArea.setText(lastReport.toSummaryText());
            exportReportBtn.setDisable(false);
            generateBtn.setDisable(false);
            log(isDryRun ? "Dry-run complete." : "Generation complete.");
            log("Generated: " + lastReport.getTotalGenerated()
                    + "  Skipped: " + lastReport.getTotalSkipped()
                    + "  Failed: " + lastReport.getTotalFailed());
        });

        task.setOnFailed(e -> {
            log("Generation failed: " + task.getException().getMessage());
            progressBar.setProgress(0);
            generateBtn.setDisable(false);
        });

        new Thread(task, "generator").start();
    }

    @FXML
    void onExportReport() {
        if (lastReport == null) return;
        FileChooser fc = new FileChooser();
        fc.setTitle("Export Report");
        fc.setInitialFileName("test-gen-report.txt");
        fc.getExtensionFilters().add(new FileChooser.ExtensionFilter("Text files", "*.txt"));
        File file = fc.showSaveDialog(exportReportBtn.getScene().getWindow());
        if (file != null) {
            try {
                lastReport.exportToFile(file.toPath());
                log("Report exported to: " + file.getAbsolutePath());
            } catch (IOException ex) {
                log("Export failed: " + ex.getMessage());
            }
        }
    }

    private void buildClassTree(Path sourceRoot, List<Path> files) {
        TreeItem<String> root = new TreeItem<>(sourceRoot.getFileName().toString());
        root.setExpanded(true);

        Map<String, TreeItem<String>> packageNodes = new LinkedHashMap<>();
        for (Path file : files) {
            String pkg = sourceRoot.relativize(file).toString()
                    .replace("\\", "/")
                    .replace("/", " › ")
                    .replace(".java", "");

            // Create package node if needed
            String dir = sourceRoot.relativize(file.getParent()).toString()
                    .replace("\\", ".");
            TreeItem<String> pkgNode = packageNodes.computeIfAbsent(dir, k -> {
                TreeItem<String> node = new TreeItem<>(k);
                root.getChildren().add(node);
                return node;
            });

            TreeItem<String> classNode = new TreeItem<>(file.getFileName().toString().replace(".java", ""));
            classNode.setExpanded(false);
            pkgNode.getChildren().add(classNode);
        }

        classTreeView.setRoot(root);

        // Click on class → show preview
        classTreeView.getSelectionModel().selectedItemProperty().addListener((obs, oldVal, selected) -> {
            if (selected != null && selected.isLeaf()) {
                showPreview(selected.getValue(), sourceRoot, files);
            }
        });
    }

    private void showPreview(String className, Path sourceRoot, List<Path> files) {
        Path classFile = files.stream()
                .filter(f -> f.getFileName().toString().replace(".java", "").equals(className))
                .findFirst().orElse(null);

        if (classFile == null) return;
        previewLabel.setText("Preview: " + className + "Test.java");

        // Generate preview without writing
        Task<String> task = new Task<>() {
            @Override
            protected String call() {
                try {
                    GenerationReport report = orchestrator.generate(
                            sourceRoot, Path.of(System.getProperty("java.io.tmpdir")),
                            false, List.of(className), List.of(),
                            namingConventionCombo.getValue(), true,
                            inheritanceDepthSpinner.getValue(), null);
                    return report.getGeneratedFiles().isEmpty()
                            ? "Could not generate preview."
                            : "Preview generated — " + report.getGeneratedFiles().size() + " file(s) would be created.";
                } catch (Exception e) {
                    return "Preview error: " + e.getMessage();
                }
            }
        };
        task.setOnSucceeded(e -> previewArea.setText(task.getValue()));
        new Thread(task, "preview").start();
    }

    // ── Utilities ────────────────────────────────────────────────────────────

    private void log(String message) {
        Platform.runLater(() -> {
            logArea.appendText("[" + java.time.LocalTime.now().toString().substring(0, 8) + "] " + message + "\n");
            logArea.setScrollTop(Double.MAX_VALUE);
        });
    }

    private File chooseDirectory(String title) {
        DirectoryChooser dc = new DirectoryChooser();
        dc.setTitle(title);
        return dc.showDialog(
                browseSourceBtn.getScene() != null ? browseSourceBtn.getScene().getWindow() : null);
    }

    private List<String> parsePatterns(String raw) {
        if (raw == null || raw.isBlank()) return List.of();
        return Arrays.stream(raw.split("[,;]")).map(String::trim).filter(s -> !s.isEmpty()).toList();
    }

    private boolean matchesAny(Path path, List<String> patterns) {
        String normalized = path.toString().replace("\\", "/");
        return patterns.stream().anyMatch(p -> normalized.contains(p) || normalized.matches(".*" + p.replace("*", ".*") + ".*"));
    }

    private void showAlert(String title, String message) {
        Platform.runLater(() -> {
            Alert alert = new Alert(Alert.AlertType.WARNING);
            alert.setTitle(title);
            alert.setHeaderText(null);
            alert.setContentText(message);
            alert.showAndWait();
        });
    }
}
