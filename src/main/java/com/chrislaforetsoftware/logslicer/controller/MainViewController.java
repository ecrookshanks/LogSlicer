package com.chrislaforetsoftware.logslicer.controller;

import com.chrislaforetsoftware.logslicer.LogSlicer;
import com.chrislaforetsoftware.logslicer.display.ArrowFactory;
import com.chrislaforetsoftware.logslicer.display.ButtonFactory;
import com.chrislaforetsoftware.logslicer.display.LogContentTask;
import com.chrislaforetsoftware.logslicer.log.LogContent;
import com.chrislaforetsoftware.logslicer.parser.IMarkupContent;
import com.chrislaforetsoftware.logslicer.parser.JSONContent;
import com.chrislaforetsoftware.logslicer.parser.JSONExtractor;
import com.chrislaforetsoftware.logslicer.parser.XMLExtractor;
import com.chrislaforetsoftware.logslicer.parser.XMLMarkupContent;
import com.chrislaforetsoftware.logslicer.search.Location;
import com.chrislaforetsoftware.logslicer.search.TextSearch;
import javafx.application.Platform;
import javafx.concurrent.Task;
import javafx.event.ActionEvent;
import javafx.fxml.FXML;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.*;
import javafx.scene.layout.HBox;
import javafx.scene.layout.StackPane;
import javafx.stage.FileChooser;
import org.fxmisc.flowless.VirtualizedScrollPane;
import org.fxmisc.richtext.CodeArea;
import org.fxmisc.richtext.InlineCssTextArea;
import org.fxmisc.richtext.LineNumberFactory;

import java.io.*;
import java.nio.file.Files;
import java.util.Collections;
import java.util.Optional;
import java.util.concurrent.ExecutionException;
import java.util.function.IntFunction;
import java.util.stream.Stream;

public class MainViewController {

    @FXML
    private StackPane anchorPane;

    @FXML
    private Label statusMessage;

    @FXML
    private Label totalLines;

    @FXML
    private ProgressBar progressStatus;

    private LogContent logContent;

    private VirtualizedScrollPane virtualizedScrollPane;

    //private CodeArea codeArea;
    private InlineCssTextArea codeArea;

    private Optional<TextSearch> search = Optional.empty();

    private Optional<Location> lastIndex = Optional.empty();

    public LogContent getLogContent() {
        return this.logContent;
    }

    @FXML
    public void initialize() {
        codeArea = new InlineCssTextArea();
        //codeArea = new CodeArea();
        codeArea.setParagraphGraphicFactory(LineNumberFactory.get(codeArea));
        codeArea.clear();
        codeArea.setEditable(false);

        final IntFunction<Node> numberFactory = LineNumberFactory.get(codeArea);
        final ButtonFactory buttonFactory = new ButtonFactory(codeArea.currentParagraphProperty(), this);
        final IntFunction<Node> arrowFactory = new ArrowFactory(codeArea.currentParagraphProperty(), this);

        IntFunction<Node> graphicFactory = line -> {
            HBox hbox = new HBox(
                    numberFactory.apply(line),
                    arrowFactory.apply(line),
                    buttonFactory.apply(logContent, line)
            );
            hbox.setAlignment(Pos.CENTER_LEFT);
            return hbox;
        };
        codeArea.setParagraphGraphicFactory(graphicFactory);

// TODO: CML - set monospaced font
//codeArea.setParagraphStyle(0, );
// see https://github.com/FXMisc/RichTextFX/wiki/RichTextFX-CSS-Reference-Guide

        // codeArea.setContextMenu( new DefaultContextMenu() );

        virtualizedScrollPane = new VirtualizedScrollPane<>(codeArea);
        virtualizedScrollPane.autosize();

        anchorPane.getChildren().add(virtualizedScrollPane);
    }

    public void handleSearch(ActionEvent actionEvent) {
        if (logContent == null) {
            return;
        }

        final TextInputDialog dialog = new TextInputDialog("");
        dialog.setTitle("Find text");
        dialog.setHeaderText("Search for text within this log file");
        dialog.setContentText("Search for:");

        Optional<String> result = dialog.showAndWait();
        result.ifPresent(searchString -> {
            search = Optional.of(new TextSearch(logContent, searchString, true));
            var toUnmark = lastIndex;
            lastIndex = search.get().getFirstMatch();
            jumpToMatch(toUnmark);
        });
    }

    public void handleSearchNext(ActionEvent actionEvent) {
        if (search.isEmpty() || lastIndex.isEmpty()) {
            handleSearch(actionEvent);
        } else {
            var toUnmark = lastIndex;
            lastIndex = search.get().getNextMatchTo(lastIndex.get());
            jumpToMatch(toUnmark);
        }
    }

    public void handleSearchPrevious(ActionEvent actionEvent) {
        if (search.isEmpty() || lastIndex.isEmpty()) {
            handleSearch(actionEvent);
        } else {
            var toUnmark = lastIndex;
            lastIndex = search.get().getPreviousMatchTo(lastIndex.get());
            jumpToMatch(toUnmark);
        }
    }

    private void jumpToMatch(Optional<Location> toUnmark) {
        if (lastIndex.isEmpty()) {
            Platform.runLater(() -> {
                toUnmark.ifPresent(location ->
                        codeArea.setStyle(location.line(), location.column(), location.column() + location.length(), "-rtfx-background-color: white;"));
            });
            return;
        }
        lastIndex.ifPresent(index -> {
            Platform.runLater(() -> {
                toUnmark.ifPresent(location ->
                        codeArea.setStyle(location.line(), location.column(), location.column() + location.length(), "-rtfx-background-color: white;"));

                codeArea.moveTo(index.line(), index.column());
                codeArea.requestFollowCaret();

                codeArea.setStyle(index.line(), index.column(), index.column() + index.length(),"-rtfx-background-color: yellow;");
                //codeArea.setStyleClass(index.column(), index.column() + index.length(), "-rtfx-background-color: red;");
//                codeArea.setStyle(0,
//                                index.column(),
//                            index.column() + index.length(),
//                                Collections.singleton("-rtfx-background-color: yellow;"));
            });
        });
    }

    public void handleOpenLog(ActionEvent actionEvent) {
        progressStatus.setProgress(0.0);

        final FileChooser fileChooser = new FileChooser();
        fileChooser.setTitle("Open log file");
        final File file = fileChooser.showOpenDialog(LogSlicer.getStage());
        if (file != null) {
            Task<LogContent> loadFileTask = loadLogContent(file);
            progressStatus.progressProperty().bind(loadFileTask.progressProperty());
            loadFileTask.run();
            progressStatus.progressProperty().unbind();
        }
    }

    public void handlePasteLog(ActionEvent actionEvent) {
        progressStatus.setProgress(0.0);

        final PasteLogViewDialog dialog = new PasteLogViewDialog(LogSlicer.getStage());
        dialog.showAndWait().ifPresent(text -> {
            Task<LogContent> loadFileTask = loadLogContent(text);
            progressStatus.progressProperty().bind(loadFileTask.progressProperty());
            loadFileTask.run();
        });
    }

    public void handleGoToLine(ActionEvent actionEvent) {
        if (logContent == null) {
            return;
        }

        final TextInputDialog dialog = new TextInputDialog("");
        dialog.setTitle("Go to line");
        dialog.setHeaderText("Jump to the specified line number (1 to " + logContent.lineCount() + ")");
        dialog.setContentText("Line number:");

        Optional<String> result = dialog.showAndWait();
        result.ifPresent(lineString -> {
            try {
                int lineNumber = Integer.parseInt(lineString.trim());
                if (lineNumber > 0 && lineNumber <= logContent.lineCount()) {

                    Platform.runLater(() -> {
                        codeArea.moveTo(lineNumber - 1, 0);
                        codeArea.requestFollowCaret();
                    });
                }
            } catch (Exception e) {
                // handles error on parsing
            }
        });
    }

    public void handleXmlButton(ActionEvent actionEvent, int lineNumber) {
        if (logContent == null) {
            return;
        }

        final var content = logContent.getXmlContentFor(lineNumber);
        if (content != null) {
            System.err.println(content.getContent());

            final ContentViewDialog dialog = new ContentViewDialog(LogSlicer.getStage(), lineNumber, content);
            dialog.showAndWait();
        }
    }

    public void handleJsonButton(ActionEvent actionEvent, int lineNumber) {
        if (logContent == null) {
            return;
        }

        final var content = logContent.getJsonContentFor(lineNumber);
        if (content != null) {
            System.err.println(content.getContent());

            final ContentViewDialog dialog = new ContentViewDialog(LogSlicer.getStage(), lineNumber, content);
            dialog.showAndWait();
        }
    }

    private Task<LogContent> loadLogContent(String text) {
        final LogContentTask loaderTask = new LogContentTask() {

            protected LogContent doCall() throws Exception {
                long lineCount = text.codePoints().filter(ch -> ch == '\n').count();
                try (BufferedReader reader = new BufferedReader(new StringReader(text))) {
                    return parseLogContent(this, reader, (int)lineCount);
                }
            }
        };
        loaderTask.setOnSucceeded(workerStateEvent -> {
            try {
                logContent = loaderTask.get();
                statusMessage.setText("Loaded pasted log file");
                codeArea.clear();
                codeArea.replaceText(0, 0, logContent.getText());
                totalLines.setText(logContent.lineCount() + " lines");

                progressStatus.progressProperty().unbind();

                Platform.runLater(() -> {
                    for (var paragraph = 0; paragraph < logContent.lineCount(); paragraph++) {
                        codeArea.setParagraphStyle(paragraph, "-fx-font: ui-monospace,SFMono-Regular,SF Mono,Menlo,Consolas,Liberation Mono,monospace; -fx-font-size: 12px; -fx-text-fill: black");
                    }
                    //                   codeArea.setStyle(0, logContent.getText().length(), "-fx-font: ui-monospace,SFMono-Regular,SF Mono,Menlo,Consolas,Liberation Mono,monospace; -fx-font-size: 50px; -fx-text-fill: black");
                    codeArea.moveTo(0);
                    codeArea.requestFollowCaret();
                });
            } catch (InterruptedException | ExecutionException e) {
                codeArea.clear();
                codeArea.replaceText(0, 0, "Error showing pasted log file");
                statusMessage.setText("Error showing pasted log file");
            }
        });

        loaderTask.setOnFailed(workerStateEvent -> {
            codeArea.clear();
            codeArea.replaceText(0, 0, "Could not parse pasted log file");
            statusMessage.setText("Failed to parse pasted log file");
            totalLines.setText("");

            progressStatus.progressProperty().unbind();

            final Alert alert = new Alert(Alert.AlertType.ERROR);
            alert.setTitle("Error");
            alert.setHeaderText("Error parsing");
            alert.setContentText("A problem occurred while parsing pasted log file text");
            alert.showAndWait();
        });
        return loaderTask;
    }

    private Task<LogContent> loadLogContent(File file) {
        final LogContentTask loaderTask = new LogContentTask() {
            @Override
            protected LogContent doCall() throws Exception {
                long lineCount;
                try (Stream<String> stream = Files.lines(file.toPath())) {
                    lineCount = stream.count();
                }

                try (BufferedReader reader = new BufferedReader(new FileReader(file))) {
                    return parseLogContent(this, reader, (int)lineCount);
                }
            }
        };

        loaderTask.setOnSucceeded(workerStateEvent -> {
            try {
                logContent = loaderTask.get();
                statusMessage.setText("Loaded " + file.getName());
                codeArea.clear();
                codeArea.replaceText(0, 0, logContent.getText());

                progressStatus.progressProperty().unbind();

                Platform.runLater(() -> {
                    for (var paragraph = 0; paragraph < logContent.lineCount(); paragraph++) {
                        codeArea.setParagraphStyle(paragraph, "-fx-font: ui-monospace,SFMono-Regular,SF Mono,Menlo,Consolas,Liberation Mono,monospace; -fx-font-size: 12px; -fx-text-fill: black");
                    }
                    //codeArea.setStyle(0, logContent.getText().length(), "-fx-font-family:Courier;-fx-font-size: 12px; -fx-text-fill: black");
                    codeArea.moveTo(0);
                    codeArea.requestFollowCaret();
                });
            } catch (InterruptedException | ExecutionException e) {
                codeArea.clear();
                codeArea.replaceText(0, 0, "Could not load file from: " + file.getAbsolutePath());
                statusMessage.setText("Error showing file");
            }
        });

        loaderTask.setOnFailed(workerStateEvent -> {
            codeArea.clear();
            codeArea.replaceText(0, 0, "Could not load file from: " + file.getAbsolutePath());
            statusMessage.setText("Failed to load file");
            totalLines.setText("");

            progressStatus.progressProperty().unbind();

            final Alert alert = new Alert(Alert.AlertType.ERROR);
            alert.setTitle("Error");
            alert.setHeaderText("Error loading file " + file.getName());
            alert.setContentText("A problem occurred while loading file " + file.getName() +
                    "\r\nPath to file:" + file.getAbsolutePath());
            alert.showAndWait();
        });
        return loaderTask;
    }

    private LogContent parseLogContent(LogContentTask loaderTask, BufferedReader reader, int lineCount) throws IOException {
        final LogContent content = new LogContent();
        String line;
        int linesLoaded = 0;
        while ((line = reader.readLine()) != null) {
            content.addLine(linesLoaded, line);
            ++linesLoaded;
            if (linesLoaded % 100 == 0) {
                loaderTask.showProgress(linesLoaded, lineCount);
            }
        }

        int currentLine = 0;
        while (currentLine < content.lineCount()) {
            IMarkupContent xml = XMLExtractor.testAndExtractFrom(content, currentLine);
            if (xml != null) {
                for (int lineNumber = xml.getStartLine(); lineNumber <= xml.getEndLine(); lineNumber++) {
                    content.setXml(lineNumber, (XMLMarkupContent)xml);
                }
                currentLine = xml.getEndLine() + 1;
            } else {
                IMarkupContent json = JSONExtractor.testAndExtractFrom(content, currentLine);
                if (json != null) {
                    for (int lineNumber = json.getStartLine(); lineNumber <= json.getEndLine(); lineNumber++) {
                        content.setJson(lineNumber, (JSONContent)json);
                    }
                    currentLine = json.getEndLine() + 1;
                } else {
                    ++currentLine;
                }
            }

        }


        loaderTask.showProgress(lineCount, lineCount);
        return content;
    }

    public void handleClose(ActionEvent actionEvent) {
        Platform.exit();
    }
}
