package com.ecl.ui;

import com.ecl.launcher.CrashAnalyzer;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.TextArea;
import javafx.scene.image.Image;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.stage.Modality;
import javafx.stage.Stage;

import java.io.File;
import java.net.URL;
import java.util.List;
import java.util.function.Consumer;

public final class CrashDiagnosticDialog {
    private CrashDiagnosticDialog() {
    }

    public static void show(Stage owner, CrashAnalyzer.Report report, File modsDir, Consumer<File> folderOpener) {
        Stage dialog = new Stage();
        dialog.initOwner(owner);
        dialog.initModality(Modality.NONE);
        dialog.setTitle("崩溃诊断");
        applyIcon(dialog);

        Label title = new Label("崩溃诊断");
        title.getStyleClass().add("section-title");
        Label reportTitle = new Label(report.getTitle());
        reportTitle.getStyleClass().add("status-title");
        reportTitle.setWrapText(true);
        Label explanation = new Label(report.getExplanation());
        explanation.getStyleClass().add("status-detail");
        explanation.setWrapText(true);
        Label suggestions = new Label(toBulletText(report.getSuggestions()));
        suggestions.getStyleClass().add("diagnostic-text");
        suggestions.setWrapText(true);
        TextArea evidence = new TextArea(toBulletText(report.getEvidence()));
        evidence.getStyleClass().add("diagnostic-log");
        evidence.setEditable(false);
        evidence.setWrapText(true);
        evidence.setPrefRowCount(10);

        Button crashDir = button("打开崩溃日志", "secondary-button");
        crashDir.setDisable(report.getCrashReportFile() == null);
        crashDir.setOnAction(event -> {
            File reportFile = report.getCrashReportFile();
            if (reportFile != null) folderOpener.accept(reportFile.getParentFile());
        });
        Button mods = button("打开 mods", "secondary-button");
        mods.setOnAction(event -> folderOpener.accept(modsDir));
        Button close = button("关闭", "ghost-button");
        close.setOnAction(e -> dialog.close());
        HBox actions = new HBox(10, crashDir, mods, close);
        actions.setAlignment(Pos.CENTER_RIGHT);

        VBox contentBox = new VBox(14,
                surface("解释", null, reportTitle, explanation),
                surface("修复建议", null, suggestions),
                surface("证据", "从原始崩溃报告中提取的关键行", evidence),
                actions);
        contentBox.getStyleClass().add("root-pane");
        contentBox.setPadding(new Insets(18));

        StackPane root = new StackPane(contentBox,
                createCornerBracket("corner-bracket-tl", Pos.TOP_LEFT),
                createCornerBracket("corner-bracket-tr", Pos.TOP_RIGHT),
                createCornerBracket("corner-bracket-bl", Pos.BOTTOM_LEFT),
                createCornerBracket("corner-bracket-br", Pos.BOTTOM_RIGHT));
        root.setPadding(new Insets(8));
        root.getStyleClass().add("root-pane");

        ScrollPane scroll = new ScrollPane(root);
        scroll.getStyleClass().add("main-scroll");
        scroll.setFitToWidth(true);
        Scene scene = new Scene(scroll, 760, 620);
        URL stylesheet = CrashDiagnosticDialog.class.getResource("/css/launcher.css");
        if (stylesheet != null) scene.getStylesheets().add(stylesheet.toExternalForm());
        dialog.setScene(scene);
        dialog.show();
    }

    private static Region createCornerBracket(String styleClass, Pos alignment) {
        Region corner = new Region();
        corner.getStyleClass().addAll("corner-bracket", styleClass);
        corner.setMouseTransparent(true);
        StackPane.setAlignment(corner, alignment);
        return corner;
    }

    private static VBox surface(String heading, String subtitle, Node... nodes) {
        VBox box = new VBox(8);
        box.getStyleClass().add("surface");
        Label title = new Label(heading);
        title.getStyleClass().add("section-title");
        box.getChildren().add(title);
        if (subtitle != null) {
            Label detail = new Label(subtitle);
            detail.getStyleClass().add("section-subtitle");
            box.getChildren().add(detail);
        }
        box.getChildren().addAll(nodes);
        return box;
    }

    private static Button button(String text, String style) {
        Button button = new Button(text);
        button.getStyleClass().addAll("app-button", style);
        return button;
    }

    private static String toBulletText(List<String> items) {
        if (items == null || items.isEmpty()) return "（无）";
        return items.stream().filter(item -> item != null && !item.isBlank())
                .map(item -> "- " + item.trim()).reduce((left, right) -> left + System.lineSeparator() + right)
                .orElse("（无）");
    }

    private static void applyIcon(Stage stage) {
        URL icon = CrashDiagnosticDialog.class.getResource("/icons/ecl-icon.png");
        if (icon != null) stage.getIcons().add(new Image(icon.toExternalForm()));
    }
}
