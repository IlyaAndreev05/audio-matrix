package io.github.ilyaandreev05.audiomatrix.ui;

import io.github.ilyaandreev05.audiomatrix.audio.AudioNode;
import javafx.scene.control.Button;
import javafx.scene.control.CheckBox;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.control.TextField;
import javafx.scene.layout.VBox;

import java.nio.file.Path;

/** Shared controls used while assembling the calibration screen. */
final class CalibrationPageState {
    VBox page;
    VBox test;
    VBox observations;
    VBox observationFrequencyField;
    VBox observationNoteField;
    VBox measurement;
    CheckBox hardwareChecked;
    CheckBox sensorChecked;
    ComboBox<AudioNode> outputs;
    ComboBox<AudioNode> sensorSource;
    ComboBox<String> sensorMethod;
    ComboBox<String> phase;
    ComboBox<Integer> sensorChannel;
    TextField frequency;
    TextField duration;
    TextField sweepStart;
    TextField sweepEnd;
    TextField sweepSteps;
    TextField observationFrequency;
    TextField observationNote;
    TextField captureOffset;
    TextField sensorMount;
    TextField impulseOffset;
    TextField impulseWindow;
    Label recordingPath;
    Path[] recording;
    Button estimateOffset;
    Button chooseRecording;
    Button analyzeRecording;
    Button analyzeImpulse;
    Button recordSweep;
    Button cancelCapture;
    final Button[] estimateOffsetRef = new Button[1];
    final Button[] chooseRecordingRef = new Button[1];
    final Button[] analyzeButtonRef = new Button[1];
    final Button[] analyzeImpulseRef = new Button[1];
    final Button[] recordSweepRef = new Button[1];
    final Button[] cancelCaptureRef = new Button[1];
}
