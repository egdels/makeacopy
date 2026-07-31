/*
 * Copyright 2025 Christian Kierdorf
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 */
package de.schliweb.makeacopy.utils.ocr.paddle;

import android.graphics.Bitmap;
import android.graphics.RectF;
import android.util.Log;
import androidx.annotation.VisibleForTesting;
import de.schliweb.makeacopy.utils.ocr.OCRHelper;
import de.schliweb.makeacopy.utils.ocr.RecognizedWord;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Utility class for constructing OCR result objects from image processing outputs.
 *
 * <p>This class provides methods for assembling OCR recognition results using predicted
 * quadrilateral text regions and associated recognition data. It includes functionality
 * for grouping text regions into lines, handling sub-word detection in quadrilaterals,
 * and interpolating regions of quadrilateral shapes for accurate text localization.
 *
 * <p>The class is not instantiable and primarily operates through static methods, some
 * of which are exposed for testing purposes.
 */
final class PaddleResultBuilder {

    private static final String TAG = "PaddleResultBuilder";

    /**
     * Tolerance factor used for grouping quads into lines during OCR result processing.
     * This factor determines the maximum allowable vertical overlap between the
     * center Y-coordinates of quads for them to be considered part of the same line.
     * Specifically, the tolerance is calculated as {@code LINE_TOLERANCE_FACTOR × median quad height}.
     */
    @VisibleForTesting static final double LINE_TOLERANCE_FACTOR = 0.6;
    @VisibleForTesting static final int PARALLEL_RECOGNITION_MIN_QUADS = 8;
    @VisibleForTesting static final int PARALLEL_RECOGNITION_THREADS = 1;
    @VisibleForTesting static final boolean ENABLE_TALL_QUAD_SPLITTER = false;
    @VisibleForTesting static final boolean ENABLE_BITMAP_WORD_SPLITTER = true;
    @VisibleForTesting static final boolean ENABLE_WIDE_QUAD_SPLITTER = false;
    @VisibleForTesting static final boolean ENABLE_DEBUG_DUMPS = false;

    /**
     * Seitenverhältnis (Höhe/Breite der Quad-Bounding-Box), ab dem ein Det-Quad als
     * „hochkant" gilt. Entspricht der Referenz-Implementierung von PaddleOCR
     * (get_rotate_crop_image: {@code h/w >= 1.5} → Crop um 90° rotieren), die für
     * vertikale CJK-Textspalten benötigt wird.
     */
    @VisibleForTesting static final double VERTICAL_QUAD_ASPECT_RATIO = 1.5;

    /**
     * Mindestanzahl an Det-Quads, ab der ein Dokument überhaupt als vertikales Layout
     * eingestuft werden kann. Verhindert, dass ein einzelner schmaler Treffer (z.&nbsp;B.
     * eine Ziffer) das gesamte Seitenlayout umschaltet.
     */
    @VisibleForTesting static final int VERTICAL_LAYOUT_MIN_QUADS = 2;

    /**
     * Anteil hochkanter Quads, ab dem das Seitenlayout als vertikal (CJK top-to-bottom,
     * Spalten rechts→links) behandelt wird.
     */
    @VisibleForTesting static final double VERTICAL_LAYOUT_DOMINANCE = 0.5;

    /**
     * Weißer Rand um rotierte Hochkant-Crops vor der Recognition, als Anteil der
     * (rotierten) Crop-Höhe. Ohne diesen Kontext-Rand verschluckt PP-OCRv5 in
     * vertikalen CJK-Spalten systematisch kleine Glyphen (っ/ッ, 。): das Rec-Modell
     * braucht Größenkontext, um kleine Kana von ihren großen Pendants (つ) zu
     * unterscheiden. Empirisch kalibriert am Issue-#88-Eval (jpn vertikal):
     * 0.4 → っ 5/5 statt 1/5, ohne Regression bei horizontalem Layout.
     */
    @VisibleForTesting static final double VERTICAL_CROP_PAD_FRACTION = 0.4;

    /**
     * Weißrand-Anteil der primären Variante für kontrastarme Foto-Crops (Buchfotos
     * mit Grauschleier): starkes Downscaling der Glyphen im 48-px-Rec-Input wirkt bei
     * unscharfen Serifen-Fotos wie eine Rauschglättung und stabilisiert kleine Kana
     * im Zeilenkontext. Empirisch am Issue-#88-Eval kalibriert; saubere Crops (volle
     * Luminanzspanne) werden unverändert mit {@link #VERTICAL_CROP_PAD_FRACTION}
     * verarbeitet.
     */
    @VisibleForTesting static final double VERTICAL_CROP_PAD_FRACTION_PHOTO = 0.7;

    /**
     * Zweite Pad-Variante für kontrastarme Foto-Crops: kleiner Weißrand, sodass die
     * Glyphen nahezu die volle 48-px-Höhe des Rec-Inputs behalten. Blurry Fotos werden
     * pro Crop mit beiden Pad-Varianten (Downscaling vs. volle Skala) erkannt und die
     * Texte per {@link #mergeAlignedCandidateTexts(String, String, boolean)} vereinigt;
     * versetzte Interpunktion (、) überlebt meist nur in der Voll-Skala-Variante,
     * kleine Kana (っ) nur in der Downscale-Variante.
     */
    @VisibleForTesting static final double VERTICAL_CROP_PAD_FRACTION_PHOTO_FULL_SCALE = 0.15;

    /**
     * Schärfungs-σ pro effektivem Downscale-Faktor für Foto-Crops: das Unsharp-Masking
     * in {@link #enhanceLowContrastCrop(Bitmap, double)} muss auf der finalen
     * Rec-Skala (48-px-Inputhöhe) wirken, nicht auf der nativen Crop-Auflösung — sonst
     * mittelt das Downscale die geschärften Kanten wieder weg. σ = Faktor ×
     * (Crop-Breite × (1 + 2·Pad)) / 48; kalibriert am Issue-#88-Buchfoto-Eval.
     */
    @VisibleForTesting static final double PHOTO_SHARPEN_SIGMA_PER_DOWNSCALE = 1.7;

    /**
     * Mindest-Konfidenz beider Foto-Pad-Varianten, damit ihre Texte per Alignment
     * vereinigt werden. Verhindert, dass eine fehlerhafte Variante (z. B. falsche
     * Rotation, Garbage) Zeichen in das Ergebnis einschleust.
     */
    @VisibleForTesting static final float PHOTO_VARIANT_MIN_CONFIDENCE = 0.85f;

    /**
     * Schwellwert der Perzentil-Luminanzspanne (P98 − P2), unterhalb derer ein
     * Hochkant-Crop als kontrastarmes Foto gilt und per
     * {@link #enhanceLowContrastCrop(Bitmap)} aufbereitet wird. Gemessen: Buchfoto
     * ≈ 119, synthetisch/digital gerendert = 255.
     */
    @VisibleForTesting static final int LOW_CONTRAST_RANGE_THRESHOLD = 200;

    /**
     * Sicherheitsrand (relativ zur Spaltenbreite), um den hochkante Det-Quads vor dem
     * Crop in alle Richtungen aufgeweitet werden. Die DB-Detection liefert bei engem
     * Buchsatz zu knappe Boxen, die Randglyphen (rechts oben versetzte 、。, kleine
     * Kana am Spaltenrand) anschneiden oder ganz abschneiden; der Rand gibt dem
     * Rec-Modell die vollständigen Glyphen. An Bildgrenzen wird geklemmt.
     * Horizontal wird zusätzlich auf die halbe Lücke zum nächsten vertikal
     * überlappenden Nachbar-Quad begrenzt, damit bei engem Spaltensatz keine Glyphen
     * der Nachbarspalte in den Crop einbluten (Kalibrierung am Issue-#88-Eval).
     */
    @VisibleForTesting static final double TALL_QUAD_EXPAND_FRACTION = 0.15;

    /**
     * Maximale Größe (relativ zur medianen Spaltenbreite) eines Det-Quads, damit es als
     * „Fragment" gilt (einzelne kleine Glyphe wie 。 oder versetztes っ, die die
     * DB-Detection von der Spalte abgetrennt hat). Fragmente werden per
     * {@link #mergeVerticalFragments(List)} wieder in ihre Spalte integriert.
     */
    @VisibleForTesting static final double VERTICAL_FRAGMENT_MAX_SIZE_FACTOR = 1.5;

    /**
     * Maximaler vertikaler Abstand (relativ zur medianen Spaltenbreite) zwischen einem
     * Fragment und einer Spalte, damit das Fragment in die Spalte gemerged wird.
     */
    @VisibleForTesting static final double VERTICAL_FRAGMENT_MAX_GAP_FACTOR = 1.5;

    /**
     * Horizontaler Toleranzbereich (relativ zur medianen Spaltenbreite), um den das
     * Center-X eines Fragments außerhalb der Spalten-X-Range liegen darf.
     */
    @VisibleForTesting static final double VERTICAL_FRAGMENT_X_SLACK_FACTOR = 0.5;

    /**
     * Aufweitung hochkanter Det-Quads entlang der Spaltenachse (oben/unten), relativ
     * zur Spaltenbreite. Deutlich größer als die horizontale Aufweitung
     * ({@link #TALL_QUAD_EXPAND_FRACTION}), weil hängende Interpunktion (。 、) am
     * Spaltenende bei engem Buchsatz von der DB-Detection regelmäßig komplett
     * außerhalb der Box liegt (~eine halbe Zeichenzelle). Gegen Überlappungen mit
     * gestapelten Nachbar-Quads derselben Spalte wird auf die halbe Lücke gekappt.
     */
    @VisibleForTesting static final double TALL_QUAD_EXPAND_FRACTION_COLUMN_AXIS = 0.5;

    /**
     * Maximale vertikale Lücke (relativ zur medianen Spaltenbreite), bis zu der zwei
     * übereinanderliegende, stark x-überlappende Spalten-Quads als Detection-Split
     * derselben Spalte gelten und vereint werden. Bewusst klein: echte Splits
     * berühren/überlappen sich typischerweise; Absätze mit sichtbarem Abstand
     * (≥ Zeichengröße) bleiben getrennt.
     */
    @VisibleForTesting static final double VERTICAL_STACKED_COLUMN_MAX_GAP_FACTOR = 0.15;

    private PaddleResultBuilder() {}

    private static final class QuadRecognition {
        final String text;
        final List<RecognizedWord> words;

        QuadRecognition(String text, List<RecognizedWord> words) {
            this.text = text;
            this.words = words;
        }
    }

    /**
     * A functional interface that defines a method for cropping a region of interest from an image.
     * It extracts a specified quadrilateral region from the provided image bitmap.
     */
    @FunctionalInterface
    interface Cropper {
        Bitmap crop(Bitmap full, Quad quad);
    }

    static OCRHelper.OcrResultWords build(
            Bitmap full, List<Quad> quads, PaddleRecOrtRunner rec) throws Exception {
        return build(full, quads, rec, PaddleQuadCropper::crop);
    }

    @VisibleForTesting
    static OCRHelper.OcrResultWords build(
            Bitmap full, List<Quad> quads, PaddleRecOrtRunner rec, Cropper cropper)
            throws Exception {
        if (quads == null || quads.isEmpty()) {
            return new OCRHelper.OcrResultWords("", null, new ArrayList<>());
        }
        // Schritt 2 (Layout-Rekonstruktion v2): zu hohe Det-Quads vor Recognition per
        // horizontalem Projection Profile in Zeilen-Sub-Quads zerlegen. Im normalen
        // Paddle-Pfad bleibt das deaktiviert, weil reale Messungen gezeigt haben, dass
        // zusätzliche Crops die serielle Recognition deutlich verlangsamen können.
        List<Quad> effectiveQuads = ENABLE_TALL_QUAD_SPLITTER ? LineSplitter.splitTallQuads(full, quads) : quads;
        if (ENABLE_WIDE_QUAD_SPLITTER && effectiveQuads.size() > 1) {
            effectiveQuads = LineSplitter.splitWideQuads(effectiveQuads);
        }
        if (effectiveQuads.size() != quads.size()) {
            Log.i(TAG, "effectiveQuads original=" + quads.size() + " effective=" + effectiveQuads.size());
        }

        // Debug-Dumps (nur wenn aktiv und Bitmap registriert): Originalbild mit Det-Quads.
        if (ENABLE_DEBUG_DUMPS) {
            PaddleDebugDumper.dumpOriginalWithQuads(full, effectiveQuads);
        }

        // Vertikales CJK-Layout (Spalten top-to-bottom, rechts→links) erkennen: dominieren
        // hochkante Det-Quads, wird statt der Zeilen- eine Spalten-Gruppierung verwendet.
        boolean verticalLayout = isVerticalLayout(effectiveQuads);
        if (verticalLayout) {
            Log.i(TAG, "vertical text layout detected (quads=" + effectiveQuads.size() + ")");
            // Kleine, von der Detection abgetrennte Einzel-Glyphen (typisch: 。 oder
            // versetztes っ) zurück in ihre Spalte mergen. Isoliert erkannt würden sie
            // vom Rec-Modell als „o"/„0" fehlgedeutet; im Spaltenkontext stimmen sie.
            List<Quad> mergedQuads = mergeVerticalFragments(effectiveQuads);
            if (mergedQuads.size() != effectiveQuads.size()) {
                Log.i(
                        TAG,
                        "vertical fragment merge: "
                                + effectiveQuads.size()
                                + " -> "
                                + mergedQuads.size()
                                + " quads");
                effectiveQuads = mergedQuads;
            }
        }

        // Reading-Order via Zeilen-Gruppierung: vertikales Center-Y-Overlap mit Toleranz
        // = LINE_TOLERANCE_FACTOR * mediane Quad-Höhe. Innerhalb der Zeile nach Center-X
        // sortiert. Zwischen Zeilen '\n', innerhalb single-space. Bei vertikalem Layout
        // stattdessen Spalten-Gruppierung (Center-X-Overlap, Spalten rechts→links, innerhalb
        // der Spalte top-to-bottom).
        List<List<Quad>> lines =
                verticalLayout
                        ? groupQuadsIntoColumns(effectiveQuads)
                        : groupQuadsIntoLines(effectiveQuads);

        List<RecognizedWord> words = new ArrayList<>();
        StringBuilder textBuilder = new StringBuilder();
        double confSum = 0.0;
        int confCount = 0;

        AtomicInteger globalCropIndex = new AtomicInteger();
        int totalQuads = 0;
        List<Quad> allQuads = new ArrayList<>();
        for (List<Quad> line : lines) {
            totalQuads += line.size();
            allQuads.addAll(line);
        }
        ExecutorService recognitionExecutor =
                full != null
                                && PARALLEL_RECOGNITION_THREADS > 1
                                && totalQuads >= PARALLEL_RECOGNITION_MIN_QUADS
                        ? Executors.newFixedThreadPool(PARALLEL_RECOGNITION_THREADS)
                        : null;
        try {
        for (int li = 0; li < lines.size(); li++) {
            List<Quad> line = lines.get(li);
            if (li > 0) {
                textBuilder.append('\n');
            }

            // Phase 1: Recognition pro Quad in visueller Reihenfolge (links→rechts).
            // Wir sammeln pro Quad den emittierten Text-Snippet sowie alle Sub-Words.
            // Erst nach Abschluss der Zeile entscheiden wir per Codepoint-Heuristik
            // (RTL vs. LTR) über die logische Reihenfolge — ohne sprachspezifisches
            // Routing, konsistent mit PdfTextUtils.isRtlText/isRtlLine im PdfCreator.
            int n = line.size();
            String[] perQuadText = new String[n];
            List<List<RecognizedWord>> perQuadWords = new ArrayList<>(n);
            if (recognitionExecutor != null && n >= 2) {
                List<Future<QuadRecognition>> futures = new ArrayList<>(n);
                for (int qi = 0; qi < n; qi++) {
                    final Quad q = line.get(qi);
                    futures.add(
                            recognitionExecutor.submit(
                                    (Callable<QuadRecognition>)
                                            () -> recognizeQuad(full, q, rec, cropper, globalCropIndex, allQuads)));
                }
                for (int qi = 0; qi < n; qi++) {
                    QuadRecognition recognized = futures.get(qi).get();
                    perQuadText[qi] = recognized.text;
                    perQuadWords.add(recognized.words);
                }
            } else {
                for (int qi = 0; qi < n; qi++) {
                    QuadRecognition recognized =
                            recognizeQuad(full, line.get(qi), rec, cropper, globalCropIndex, allQuads);
                    perQuadText[qi] = recognized.text;
                    perQuadWords.add(recognized.words);
                }
            }

            // Phase 2: RTL-Detection für die Zeile anhand der erkannten Texte.
            // Eine Zeile gilt als RTL, wenn mehr ihrer Wort-Codepoints RTL als LTR
            // sind. Bei RTL kehren wir die Reihenfolge der Quads beim Aufbau des
            // konkatenierten Textes um — die linke (visuell erste) Quad-Box ist
            // logisch das letzte Wort der Zeile, die rechte das erste.
            boolean rtl = isRtlLineByText(perQuadText);
            for (int qi = 0; qi < n; qi++) {
                int idx = rtl ? (n - 1 - qi) : qi;
                String emittedText = perQuadText[idx];
                if (emittedText == null) continue;
                // Bei RTL liefert Paddle die Glyphen pro Crop in visueller (links→rechts)
                // Reihenfolge — das ist die Spiegelung der logischen Codepoint-Reihenfolge.
                // Für korrekten TXT-/BiDi-Output drehen wir jeden Snippet per Codepoints um.
                if (rtl) {
                    emittedText = reverseByCodepoints(emittedText);
                }
                // In vertikalen CJK-Spalten sind Spaces zwischen Quad-Segmenten nicht
                // sinnvoll — Segmente derselben Spalte werden direkt konkateniert.
                if (!verticalLayout && qi > 0 && textBuilder.length() > 0) {
                    char last = textBuilder.charAt(textBuilder.length() - 1);
                    if (last != ' ' && last != '\n' && !emittedText.isEmpty()
                            && emittedText.charAt(0) != ' ') {
                        textBuilder.append(' ');
                    }
                }
                textBuilder.append(emittedText);
            }

            // Sub-Words werden in visueller Reihenfolge der Zeile gesammelt: der
            // PdfCreator macht seine eigene RTL-Sortierung anhand der Bounding-Boxes
            // (siehe PdfTextUtils.isRtlLine + Sortierung in PdfCreator), daher hier
            // bewusst keine Umkehrung. Bei RTL müssen aber auch die Wort-Texte selbst
            // in dieselbe logische Codepoint-Reihenfolge gebracht werden wie der oben
            // aufgebaute Gesamttext; sonst zeigen word-basierte Views (OCRReview) die
            // einzelnen Wörter gespiegelt an.
            for (List<RecognizedWord> subWords : perQuadWords) {
                for (RecognizedWord sw : subWords) {
                    if (sw == null) continue;
                    if (rtl && isRtlText(sw.getText())) {
                        sw.setText(reverseByCodepoints(sw.getText()));
                    }
                    words.add(sw);
                    confSum += sw.getConfidence();
                    confCount++;
                }
            }
        }
        } finally {
            if (recognitionExecutor != null) {
                recognitionExecutor.shutdownNow();
            }
        }

        Integer meanConf =
                (confCount == 0) ? null : Integer.valueOf((int) Math.round(confSum / confCount));
        // Debug-Dump abschließen (nur wenn aktiv und für full registriert).
        if (ENABLE_DEBUG_DUMPS) {
            PaddleDebugDumper.finishSample(full, textBuilder.toString(), meanConf);
        }
        return new OCRHelper.OcrResultWords(textBuilder.toString(), meanConf, words);
    }

    private static QuadRecognition recognizeQuad(
            Bitmap full,
            Quad q,
            PaddleRecOrtRunner rec,
            Cropper cropper,
            AtomicInteger globalCropIndex,
            List<Quad> allQuads)
            throws Exception {
        Bitmap crop = null;
        try {
            Quad cropQuad =
                    (full != null)
                            ? expandTallQuad(q, full.getWidth(), full.getHeight(), allQuads)
                            : q;
            crop = cropper.crop(full, cropQuad);

            // Hochkante Crops (vertikale CJK-Textspalten): das Rec-Modell erwartet
            // horizontale Textzeilen. Analog zur PaddleOCR-Referenz wird der Crop um 90°
            // rotiert; die 0°/180°-Ambiguität (CCW vs. CW) wird per Konfidenzvergleich
            // aufgelöst. Der unrotierte Crop bleibt als Kandidat, um Regressionen bei
            // schmalen horizontalen Crops (z. B. Einzelzeichen) zu vermeiden.
            PaddleRecOrtRunner.RecOutput out;
            boolean rotatedVertical = false;
            String overrideText = null;
            if (isTallCrop(crop)) {
                // Kontrastarme Foto-Crops (Buchfotos): Kontrast spreizen + nachschärfen
                // und mit größerem Weißrand erkennen. Kontrastreiche Crops werden auf
                // die Tinten-Bounding-Box zurückgetrimmt (Weißraum der Quad-Aufweitung
                // entfernen, Rec-Skalierung stabil halten).
                double padFraction = VERTICAL_CROP_PAD_FRACTION;
                Bitmap variantB = null;
                Bitmap enhanced =
                        enhanceLowContrastCrop(
                                crop, photoSharpenSigma(crop, VERTICAL_CROP_PAD_FRACTION_PHOTO));
                if (enhanced != crop) {
                    // Zweite Foto-Variante: kleiner Weißrand (volle Glyphenskala) mit
                    // an diese Skala angepasster Schärfung. Blurry Serifen-Fotos liefern
                    // je nach Variante komplementäre Ergebnisse (Downscale rettet kleine
                    // Kana im Zeilenkontext, volle Skala rettet Interpunktion); die Texte
                    // beider Varianten werden per Alignment vereinigt.
                    variantB =
                            enhanceLowContrastCrop(
                                    crop,
                                    photoSharpenSigma(
                                            crop, VERTICAL_CROP_PAD_FRACTION_PHOTO_FULL_SCALE));
                    if (variantB == crop) variantB = null;
                    crop.recycle();
                    crop = enhanced;
                    padFraction = VERTICAL_CROP_PAD_FRACTION_PHOTO;
                } else {
                    Bitmap trimmed = trimToInk(crop);
                    if (trimmed != crop) {
                        crop.recycle();
                        crop = trimmed;
                    }
                }
                Bitmap ccw = padWhiteVertical(rotateBitmap(crop, 270), padFraction);
                Bitmap cw = padWhiteVertical(rotateBitmap(crop, 90), padFraction);
                try {
                    PaddleRecOrtRunner.RecOutput out0 = rec.recognize(crop);
                    PaddleRecOrtRunner.RecOutput outCcw = rec.recognize(ccw);
                    PaddleRecOrtRunner.RecOutput outCw = rec.recognize(cw);
                    int chosen =
                            chooseRotationCandidate(
                                    new String[] {out0.text(), outCcw.text(), outCw.text()},
                                    new float[] {
                                        out0.confidence(), outCcw.confidence(), outCw.confidence()
                                    });
                    out = (chosen == 1) ? outCcw : (chosen == 2) ? outCw : out0;
                    rotatedVertical = chosen != 0;
                    if (rotatedVertical) {
                        Log.d(
                                TAG,
                                "tall crop rotated "
                                        + (chosen == 1 ? "90° CCW" : "90° CW")
                                        + " conf0="
                                        + out0.confidence()
                                        + " confCcw="
                                        + outCcw.confidence()
                                        + " confCw="
                                        + outCw.confidence());
                    }
                    if (rotatedVertical && variantB != null) {
                        Bitmap rotB =
                                padWhiteVertical(
                                        rotateBitmap(variantB, chosen == 1 ? 270 : 90),
                                        VERTICAL_CROP_PAD_FRACTION_PHOTO_FULL_SCALE);
                        try {
                            PaddleRecOrtRunner.RecOutput outB = rec.recognize(rotB);
                            if (outB.text() != null
                                    && !outB.text().isEmpty()
                                    && outB.confidence() >= PHOTO_VARIANT_MIN_CONFIDENCE
                                    && out.confidence() >= PHOTO_VARIANT_MIN_CONFIDENCE) {
                                overrideText =
                                        mergeAlignedCandidateTexts(
                                                out.text(),
                                                outB.text(),
                                                out.confidence() >= outB.confidence());
                                Log.d(
                                        TAG,
                                        "photo variant union: a='"
                                                + out.text()
                                                + "' b='"
                                                + outB.text()
                                                + "' -> '"
                                                + overrideText
                                                + "'");
                            }
                        } finally {
                            if (!rotB.isRecycled()) rotB.recycle();
                        }
                    }
                } finally {
                    if (ccw != null && ccw != crop && !ccw.isRecycled()) ccw.recycle();
                    if (cw != null && cw != crop && !cw.isRecycled()) cw.recycle();
                    if (variantB != null && !variantB.isRecycled()) variantB.recycle();
                }
            } else {
                out = rec.recognize(crop);
            }
            String text =
                    overrideText != null ? overrideText : (out.text() != null ? out.text() : "");

            // Debug-Dump pro Crop (nur wenn aktiv).
            if (ENABLE_DEBUG_DUMPS) {
                PaddleDebugDumper.dumpCrop(full, globalCropIndex.getAndIncrement(), q, crop, out);
            }

            // Space-/Token-Rekonstruktion: Recognition liefert keine Spaces.
            // Versuche, den Crop per vertikalem Projection-Profile in Wort-Segmente
            // zu trennen und den erkannten Text proportional zu verteilen.
            //
            // Bei RTL-Texten (Arabisch, Persisch, Hebräisch, …) ist diese Pixel-zu-
            // Codepoint-Verteilung nicht zuverlässig: WordSplitter schneidet den rec-
            // Output linear nach Pixel-Spalten in feste Substring-Bereiche, was die
            // Codepoints an falschen Positionen kappt und zu Buchstabensalat in
            // einzelnen Wörtern führt (zerrissene End-/Mittel-Formen). Wir skippen
            // den Splitter daher bei RTL-Crops und nehmen den rec-Text als ein Snippet
            // pro Quad; Sub-Word-Geometrie für den PDF-Layer kommt aus der CTC-Frame-
            // basierten buildSubWords (geometrisch korrekt unabhängig von Schriftrichtung).
            float aggConf100 = Math.max(0f, Math.min(100f, out.confidence() * 100f));

            // Bei rotierten Vertikal-Crops sind weder die Pixel-Spalten des Crops noch die
            // CTC-Frame-Geometrie auf die (unrotierte) Quad-Geometrie abbildbar: WordSplitter
            // und buildSubWords würden falsche Boxen liefern. Der gesamte Quad wird daher als
            // ein Wort mit der vollen Det-Box emittiert.
            if (rotatedVertical) {
                List<RecognizedWord> vWords = new ArrayList<>(1);
                if (!text.isEmpty()) {
                    vWords.add(new RecognizedWord(text, quadBBox(q), aggConf100));
                }
                return new QuadRecognition(text, vWords);
            }

            boolean cropIsRtl = isRtlText(text);
            List<RecognizedWord> subWords =
                    (!ENABLE_BITMAP_WORD_SPLITTER || cropIsRtl)
                            ? null
                            : WordSplitter.split(q, crop, text, aggConf100);
            String emittedText;
            if (subWords != null && subWords.size() >= 2) {
                StringBuilder sb = new StringBuilder();
                for (int wi = 0; wi < subWords.size(); wi++) {
                    if (wi > 0) sb.append(' ');
                    sb.append(subWords.get(wi).getText());
                }
                emittedText = sb.toString();
            } else {
                subWords = buildSubWords(q, out);
                emittedText = text;
            }
            return new QuadRecognition(emittedText, subWords);
        } finally {
            if (crop != null && crop != full && !crop.isRecycled()) {
                crop.recycle();
            }
        }
    }

    /**
     * Groups a list of {@link Quad} objects into lines based on their vertical positions and dimensions.
     * The method sorts the provided quads by their vertical and horizontal centroids, calculates a
     * tolerance value based on the median height of the quads, and clusters the quads within this
     * tolerance into distinct lines. Each line is further sorted by horizontal positions of the quads.
     *
     * @param quads the list of {@link Quad} objects to be grouped into lines
     * @return a list of lines, where each line is a list of {@link Quad} objects grouped vertically
     */
    @VisibleForTesting
    static List<List<Quad>> groupQuadsIntoLines(List<Quad> quads) {
        List<Quad> sortedByY = new ArrayList<>(quads);
        // Primärsortierung nach Center-Y, Tie-Break Center-X – stabile Vorordnung.
        sortedByY.sort(
                java.util.Comparator.<Quad>comparingDouble(PaddleResultBuilder::centerY)
                        .thenComparingDouble(PaddleResultBuilder::centerX));

        // Mediane Höhe als Skalenreferenz für die Toleranz.
        double[] heights = new double[sortedByY.size()];
        for (int i = 0; i < sortedByY.size(); i++) {
            heights[i] = Math.max(1.0, sortedByY.get(i).maxY() - sortedByY.get(i).minY());
        }
        double[] sortedHeights = heights.clone();
        java.util.Arrays.sort(sortedHeights);
        double medianHeight = sortedHeights[sortedHeights.length / 2];
        double tol = LINE_TOLERANCE_FACTOR * medianHeight;

        List<List<Quad>> lines = new ArrayList<>();
        List<Quad> current = new ArrayList<>();
        double currentRefY = Double.NaN;
        for (Quad q : sortedByY) {
            double cy = centerY(q);
            if (current.isEmpty()) {
                current.add(q);
                currentRefY = cy;
            } else if (Math.abs(cy - currentRefY) <= tol) {
                current.add(q);
                // Referenz als laufender Mittelwert aktualisieren, robustifiziert gegen Drift.
                currentRefY = (currentRefY * (current.size() - 1) + cy) / current.size();
            } else {
                current.sort(java.util.Comparator.comparingDouble(PaddleResultBuilder::centerX));
                lines.add(current);
                current = new ArrayList<>();
                current.add(q);
                currentRefY = cy;
            }
        }
        if (!current.isEmpty()) {
            current.sort(java.util.Comparator.comparingDouble(PaddleResultBuilder::centerX));
            lines.add(current);
        }
        return lines;
    }

    /**
     * Prüft, ob die Bounding-Box eines Det-Quads „hochkant" ist
     * (Höhe/Breite ≥ {@link #VERTICAL_QUAD_ASPECT_RATIO}).
     */
    @VisibleForTesting
    static boolean isTallQuad(Quad q) {
        double w = Math.max(1.0, q.maxX() - q.minX());
        double h = Math.max(1.0, q.maxY() - q.minY());
        return h / w >= VERTICAL_QUAD_ASPECT_RATIO;
    }

    private static boolean isTallCrop(Bitmap crop) {
        if (crop == null) return false;
        int w = Math.max(1, crop.getWidth());
        int h = Math.max(1, crop.getHeight());
        return ((double) h) / w >= VERTICAL_QUAD_ASPECT_RATIO;
    }

    /**
     * Erkennt ein vertikales Seitenlayout (CJK top-to-bottom): mindestens
     * {@link #VERTICAL_LAYOUT_MIN_QUADS} Quads und mehr als
     * {@link #VERTICAL_LAYOUT_DOMINANCE} Anteil hochkanter Quads.
     */
    @VisibleForTesting
    static boolean isVerticalLayout(List<Quad> quads) {
        if (quads == null || quads.size() < VERTICAL_LAYOUT_MIN_QUADS) return false;
        int tall = 0;
        for (Quad q : quads) {
            if (isTallQuad(q)) tall++;
        }
        return tall > quads.size() * VERTICAL_LAYOUT_DOMINANCE;
    }

    /**
     * Wählt den besten Rotationskandidaten für einen hochkanten Crop.
     * Kandidatenreihenfolge: 0 = unrotiert, 1 = 90° CCW, 2 = 90° CW.
     * Primärkriterium: nicht-leerer Text schlägt leeren; sekundär höhere Konfidenz;
     * Tie-Break: mehr Codepoints, dann der frühere Kandidat (bevorzugt unrotiert,
     * dann CCW wie in der PaddleOCR-Referenz {@code np.rot90}).
     */
    @VisibleForTesting
    static int chooseRotationCandidate(String[] texts, float[] confs) {
        int best = 0;
        for (int i = 1; i < texts.length; i++) {
            boolean bestHasText = texts[best] != null && !texts[best].trim().isEmpty();
            boolean hasText = texts[i] != null && !texts[i].trim().isEmpty();
            if (hasText != bestHasText) {
                if (hasText) best = i;
                continue;
            }
            if (confs[i] > confs[best] + 1e-4f) {
                best = i;
            } else if (Math.abs(confs[i] - confs[best]) <= 1e-4f) {
                int len = texts[i] == null ? 0 : texts[i].codePointCount(0, texts[i].length());
                int bestLen =
                        texts[best] == null
                                ? 0
                                : texts[best].codePointCount(0, texts[best].length());
                if (len > bestLen) best = i;
            }
        }
        return best;
    }

    /**
     * Gruppiert Quads in vertikale Spalten (analog zu {@link #groupQuadsIntoLines(List)},
     * mit vertauschten Achsen): Cluster über Center-X-Overlap mit Toleranz
     * = {@link #LINE_TOLERANCE_FACTOR} × mediane Quad-Breite. Spalten werden in
     * Leserichtung rechts→links geliefert, innerhalb einer Spalte top-to-bottom.
     */
    @VisibleForTesting
    static List<List<Quad>> groupQuadsIntoColumns(List<Quad> quads) {
        List<Quad> sortedByX = new ArrayList<>(quads);
        // Primärsortierung nach Center-X absteigend (rechts→links), Tie-Break Center-Y.
        sortedByX.sort(
                java.util.Comparator.<Quad>comparingDouble(PaddleResultBuilder::centerX)
                        .reversed()
                        .thenComparingDouble(PaddleResultBuilder::centerY));

        // Mediane Breite als Skalenreferenz für die Toleranz.
        double[] widths = new double[sortedByX.size()];
        for (int i = 0; i < sortedByX.size(); i++) {
            widths[i] = Math.max(1.0, sortedByX.get(i).maxX() - sortedByX.get(i).minX());
        }
        double[] sortedWidths = widths.clone();
        java.util.Arrays.sort(sortedWidths);
        double medianWidth = sortedWidths[sortedWidths.length / 2];
        double tol = LINE_TOLERANCE_FACTOR * medianWidth;

        List<List<Quad>> columns = new ArrayList<>();
        List<Quad> current = new ArrayList<>();
        double currentRefX = Double.NaN;
        for (Quad q : sortedByX) {
            double cx = centerX(q);
            if (current.isEmpty()) {
                current.add(q);
                currentRefX = cx;
            } else if (Math.abs(cx - currentRefX) <= tol) {
                current.add(q);
                // Referenz als laufender Mittelwert aktualisieren, robustifiziert gegen Drift.
                currentRefX = (currentRefX * (current.size() - 1) + cx) / current.size();
            } else {
                current.sort(java.util.Comparator.comparingDouble(PaddleResultBuilder::centerY));
                columns.add(current);
                current = new ArrayList<>();
                current.add(q);
                currentRefX = cx;
            }
        }
        if (!current.isEmpty()) {
            current.sort(java.util.Comparator.comparingDouble(PaddleResultBuilder::centerY));
            columns.add(current);
        }
        return columns;
    }

    /**
     * Merged kleine Fragment-Quads (einzelne Glyphen wie 。 oder versetztes っ, die die
     * DB-Detection von ihrer Textspalte abgetrennt hat) in die überlappende Spalte.
     * Ein Quad gilt als Fragment, wenn Breite und Höhe kleiner als
     * {@link #VERTICAL_FRAGMENT_MAX_SIZE_FACTOR} × mediane Spaltenbreite sind. Es wird
     * in die Spalte gemerged, deren X-Range (± {@link #VERTICAL_FRAGMENT_X_SLACK_FACTOR}
     * × mediane Breite) sein Center-X enthält und deren vertikaler Abstand am kleinsten
     * ist (max. {@link #VERTICAL_FRAGMENT_MAX_GAP_FACTOR} × mediane Breite). Merge =
     * achsenparallele Bounding-Box-Union; der Score der Spalte bleibt erhalten.
     * Fragmente ohne passende Spalte bleiben eigenständig.
     */
    @VisibleForTesting
    static List<Quad> mergeVerticalFragments(List<Quad> quads) {
        List<Quad> tallQuads = new ArrayList<>();
        for (Quad q : quads) {
            if (isTallQuad(q)) tallQuads.add(q);
        }
        if (tallQuads.isEmpty()) return quads;

        double[] widths = new double[tallQuads.size()];
        for (int i = 0; i < tallQuads.size(); i++) {
            widths[i] = Math.max(1.0, tallQuads.get(i).maxX() - tallQuads.get(i).minX());
        }
        java.util.Arrays.sort(widths);
        double medianWidth = widths[widths.length / 2];
        double maxFragmentSize = VERTICAL_FRAGMENT_MAX_SIZE_FACTOR * medianWidth;
        double maxGap = VERTICAL_FRAGMENT_MAX_GAP_FACTOR * medianWidth;
        double xSlack = VERTICAL_FRAGMENT_X_SLACK_FACTOR * medianWidth;

        // Spalten als mutable Bounding-Boxen [minX, minY, maxX, maxY, score].
        List<double[]> columns = new ArrayList<>();
        List<Quad> fragments = new ArrayList<>();
        for (Quad q : quads) {
            double w = q.maxX() - q.minX();
            double h = q.maxY() - q.minY();
            if (w < maxFragmentSize && h < maxFragmentSize) {
                fragments.add(q);
            } else {
                columns.add(new double[] {q.minX(), q.minY(), q.maxX(), q.maxY(), q.score});
            }
        }
        boolean anyFragmentMerged = false;
        List<Quad> standalone = new ArrayList<>();
        for (Quad f : fragments) {
            double cx = centerX(f);
            double[] best = null;
            double bestGap = Double.MAX_VALUE;
            for (double[] col : columns) {
                if (cx < col[0] - xSlack || cx > col[2] + xSlack) continue;
                double gap = Math.max(0.0, Math.max(col[1] - f.maxY(), f.minY() - col[3]));
                if (gap < bestGap) {
                    bestGap = gap;
                    best = col;
                }
            }
            if (best != null && bestGap < maxGap) {
                best[0] = Math.min(best[0], f.minX());
                best[1] = Math.min(best[1], f.minY());
                best[2] = Math.max(best[2], f.maxX());
                best[3] = Math.max(best[3], f.maxY());
                anyFragmentMerged = true;
            } else {
                standalone.add(f);
            }
        }

        // Gestapelte Spaltenteile vereinen: Die DB-Detection zerlegt sehr lange
        // Spalten gelegentlich in mehrere übereinanderliegende Quads; der Schnitt kann
        // mitten durch eine Zeichenzelle (inkl. versetzter Interpunktion) laufen.
        // Quads mit deutlicher X-Überlappung, die sich berühren/überlappen (Lücke ≤
        // VERTICAL_STACKED_COLUMN_MAX_GAP_FACTOR × mediane Breite), werden daher zu
        // einer Spalte vereint (Bounding-Box-Union, max. Score). Segmente mit echtem
        // Abstand bleiben getrennt.
        double stackedMaxGap = VERTICAL_STACKED_COLUMN_MAX_GAP_FACTOR * medianWidth;
        boolean anyColumnMerged = false;
        boolean mergedAny = true;
        while (mergedAny) {
            mergedAny = false;
            outer:
            for (int i = 0; i < columns.size(); i++) {
                double[] a = columns.get(i);
                for (int k = i + 1; k < columns.size(); k++) {
                    double[] b = columns.get(k);
                    double xOverlap = Math.min(a[2], b[2]) - Math.max(a[0], b[0]);
                    double minW = Math.min(a[2] - a[0], b[2] - b[0]);
                    double yGap = Math.max(b[1] - a[3], a[1] - b[3]);
                    if (xOverlap >= 0.5 * minW && yGap <= stackedMaxGap) {
                        a[0] = Math.min(a[0], b[0]);
                        a[1] = Math.min(a[1], b[1]);
                        a[2] = Math.max(a[2], b[2]);
                        a[3] = Math.max(a[3], b[3]);
                        a[4] = Math.max(a[4], b[4]);
                        columns.remove(k);
                        mergedAny = true;
                        anyColumnMerged = true;
                        break outer;
                    }
                }
            }
        }

        if (!anyFragmentMerged && !anyColumnMerged) return quads;

        List<Quad> result = new ArrayList<>(columns.size() + standalone.size());
        for (double[] col : columns) {
            result.add(
                    new Quad(
                            new double[] {col[0], col[2], col[2], col[0]},
                            new double[] {col[1], col[1], col[3], col[3]},
                            col[4]));
        }
        result.addAll(standalone);
        return result;
    }

    /**
     * Weitet hochkante Det-Quads (vertikale Textspalten, Höhe ≥ 2 × Breite) um
     * {@link #TALL_QUAD_EXPAND_FRACTION} × Spaltenbreite auf,
     * geklemmt an die Bildgrenzen. Nicht-hochkante Quads werden unverändert
     * zurückgegeben. Entlang der Spaltenachse wird um
     * {@link #TALL_QUAD_EXPAND_FRACTION_COLUMN_AXIS} × Breite aufgeweitet (hängende
     * Interpunktion am Spaltenanfang/-ende); gegen gestapelte Nachbar-Quads derselben
     * Spalte (x-Überlappung) wird auf die halbe Lücke gekappt.
     */
    @VisibleForTesting
    static Quad expandTallQuad(Quad q, int imgW, int imgH, List<Quad> allQuads) {
        double bw = q.maxX() - q.minX();
        double bh = q.maxY() - q.minY();
        if (bw <= 0 || bh < 2 * bw) return q;
        double m = TALL_QUAD_EXPAND_FRACTION * bw;
        double mv = TALL_QUAD_EXPAND_FRACTION_COLUMN_AXIS * bw;
        // Horizontale Aufweitung je Seite auf die halbe Lücke zum nächsten vertikal
        // überlappenden Nachbar-Quad begrenzen (enger Spaltensatz); entlang der
        // Spaltenachse analog zum nächsten horizontal überlappenden Nachbarn.
        double mxLeft = m;
        double mxRight = m;
        double myTop = mv;
        double myBottom = mv;
        if (allQuads != null) {
            for (Quad other : allQuads) {
                if (other == q) continue;
                double overlapY =
                        Math.min(q.maxY(), other.maxY()) - Math.max(q.minY(), other.minY());
                if (overlapY > 0) {
                    if (other.maxX() <= q.minX()) {
                        mxLeft = Math.min(mxLeft, Math.max(0, (q.minX() - other.maxX()) / 2));
                    } else if (other.minX() >= q.maxX()) {
                        mxRight = Math.min(mxRight, Math.max(0, (other.minX() - q.maxX()) / 2));
                    }
                }
                double overlapX =
                        Math.min(q.maxX(), other.maxX()) - Math.max(q.minX(), other.minX());
                if (overlapX > 0) {
                    if (other.maxY() <= q.minY()) {
                        myTop = Math.min(myTop, Math.max(0, (q.minY() - other.maxY()) / 2));
                    } else if (other.minY() >= q.maxY()) {
                        myBottom = Math.min(myBottom, Math.max(0, (other.minY() - q.maxY()) / 2));
                    }
                }
            }
        }
        // TL=0, TR=1, BR=2, BL=3: links/rechts bzw. oben/unten nach außen schieben.
        double[] xs = {q.x[0] - mxLeft, q.x[1] + mxRight, q.x[2] + mxRight, q.x[3] - mxLeft};
        double[] ys = {q.y[0] - myTop, q.y[1] - myTop, q.y[2] + myBottom, q.y[3] + myBottom};
        for (int i = 0; i < 4; i++) {
            xs[i] = Math.min(imgW, Math.max(0, xs[i]));
            ys[i] = Math.min(imgH, Math.max(0, ys[i]));
        }
        return new Quad(xs, ys, q.score);
    }

    /**
     * Trimmt einen (aufgeweiteten) Hochkant-Crop auf die Tinten-Bounding-Box plus
     * 5&nbsp;% Spaltenbreite Rand. Zusammen mit {@link #expandTallQuad} stellt das
     * sicher, dass von der Detection abgeschnittene Randglyphen (、。, kleine Kana)
     * im Crop landen, ohne dass bereits enge Crops unnötig Weißraum (und damit eine
     * veränderte Rec-Skalierung) erhalten. „Tinte" = Luminanz unter der Mitte des
     * Crop-Luminanzbereichs (robust gegen Foto-Grauschleier).
     */
    @VisibleForTesting
    static Bitmap trimToInk(Bitmap src) {
        int w = src.getWidth();
        int h = src.getHeight();
        int[] px = new int[w * h];
        src.getPixels(px, 0, w, 0, 0, w, h);
        int minLum = 255;
        int maxLum = 0;
        int[] lum = new int[w * h];
        for (int i = 0; i < px.length; i++) {
            int c = px[i];
            int l = (299 * ((c >> 16) & 0xFF) + 587 * ((c >> 8) & 0xFF) + 114 * (c & 0xFF)) / 1000;
            lum[i] = l;
            if (l < minLum) minLum = l;
            if (l > maxLum) maxLum = l;
        }
        if (maxLum - minLum < 32) return src; // leerer/uniformer Crop
        int threshold = (minLum + maxLum) / 2;
        int x0 = w;
        int x1 = -1;
        int y0 = h;
        int y1 = -1;
        for (int y = 0; y < h; y++) {
            int row = y * w;
            for (int x = 0; x < w; x++) {
                if (lum[row + x] < threshold) {
                    if (x < x0) x0 = x;
                    if (x > x1) x1 = x;
                    if (y < y0) y0 = y;
                    if (y > y1) y1 = y;
                }
            }
        }
        if (x1 < x0 || y1 < y0) return src;
        int margin = Math.max(1, (int) Math.round(0.05 * w));
        x0 = Math.max(0, x0 - margin);
        y0 = Math.max(0, y0 - margin);
        x1 = Math.min(w - 1, x1 + margin);
        y1 = Math.min(h - 1, y1 + margin);
        if (x0 == 0 && y0 == 0 && x1 == w - 1 && y1 == h - 1) return src;
        return Bitmap.createBitmap(src, x0, y0, x1 - x0 + 1, y1 - y0 + 1);
    }

    /**
     * Aufbereitung kontrastarmer Foto-Crops (Buchfotos): Perzentil-Kontrast-Stretch
     * (P2→0, P98→255) plus Unsharp-Masking (Gauß σ=2, 150&nbsp;%, Schwelle 2), beides in
     * Graustufen. Crops mit voller Luminanzspanne (P98 − P2 ≥
     * {@link #LOW_CONTRAST_RANGE_THRESHOLD}, typisch digital gerenderte Scans) werden
     * unverändert (identische Instanz) zurückgegeben. Empirisch am Issue-#88-Eval
     * validiert: Buchfoto-CER 0.16 → 0.05, keine Regression bei sauberen Vorlagen.
     */
    @VisibleForTesting
    static Bitmap enhanceLowContrastCrop(Bitmap src) {
        return enhanceLowContrastCrop(src, 2.0);
    }

    /** Variante mit skalenangepasstem Unsharp-σ, siehe {@link #photoSharpenSigma}. */
    @VisibleForTesting
    static Bitmap enhanceLowContrastCrop(Bitmap src, double sigma) {
        int w = src.getWidth();
        int h = src.getHeight();
        int[] px = new int[w * h];
        src.getPixels(px, 0, w, 0, 0, w, h);
        int[] lum = new int[w * h];
        int[] hist = new int[256];
        for (int i = 0; i < px.length; i++) {
            int c = px[i];
            int l = (299 * ((c >> 16) & 0xFF) + 587 * ((c >> 8) & 0xFF) + 114 * (c & 0xFF)) / 1000;
            lum[i] = l;
            hist[l]++;
        }
        int total = px.length;
        int loTarget = (int) (total * 0.02);
        int hiTarget = (int) (total * 0.98);
        int cum = 0;
        int lo = 0;
        int hi = 255;
        for (int i = 0; i < 256; i++) {
            cum += hist[i];
            if (cum <= loTarget) lo = i;
            if (cum < hiTarget) hi = i;
        }
        if (hi <= lo || hi - lo >= LOW_CONTRAST_RANGE_THRESHOLD) return src;

        // Kontrast-Stretch auf 0..255.
        float scale = 255f / (hi - lo);
        float[] stretched = new float[w * h];
        for (int i = 0; i < lum.length; i++) {
            float v = (lum[i] - lo) * scale;
            stretched[i] = v < 0f ? 0f : Math.min(v, 255f);
        }

        // Unsharp-Mask: separierbarer Gauß-Blur (σ skalenangepasst, s. photoSharpenSigma),
        // sharp = orig + 1.5·(orig − blur) nur wenn |orig − blur| ≥ 2 (Rausch-Schwelle).
        float[] blurred = gaussianBlur(stretched, w, h, sigma);
        int[] outPx = new int[w * h];
        for (int i = 0; i < stretched.length; i++) {
            float diff = stretched[i] - blurred[i];
            float v = Math.abs(diff) >= 2f ? stretched[i] + 1.5f * diff : stretched[i];
            int iv = Math.round(v);
            if (iv < 0) iv = 0;
            if (iv > 255) iv = 255;
            outPx[i] = 0xFF000000 | (iv << 16) | (iv << 8) | iv;
        }
        Bitmap out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
        out.setPixels(outPx, 0, w, 0, 0, w, h);
        return out;
    }

    /**
     * Unsharp-σ für einen Foto-Crop, angepasst an den effektiven Downscale-Faktor bis
     * zur 48-px-Rec-Inputhöhe: nach Rotation+Pad wird der Crop um den Faktor
     * (minDim × (1 + 2·padFraction)) / 48 verkleinert; die Schärfung muss auf dieser
     * Zielskala wirken, sonst wird sie beim Downscale wieder weggemittelt.
     */
    @VisibleForTesting
    static double photoSharpenSigma(Bitmap crop, double padFraction) {
        int minDim = Math.min(crop.getWidth(), crop.getHeight());
        double downscale = (minDim * (1 + 2 * padFraction)) / 48.0;
        return Math.max(2.0, PHOTO_SHARPEN_SIGMA_PER_DOWNSCALE * downscale);
    }

    /**
     * Vereinigt die Texte zweier Rec-Varianten desselben Crops per Edit-Distanz-
     * Alignment (Codepoints): Matches bleiben, bei Substitutionen gewinnt die
     * bevorzugte (konfidenzstärkere) Variante, einseitig fehlende Zeichen (CTC-Drops
     * der jeweils anderen Variante) werden übernommen. So ergänzen sich die
     * Downscale-Variante (kleine Kana im Zeilenkontext) und die Voll-Skala-Variante
     * (Interpunktion) generisch, ohne sprach- oder zeichen-spezifische Regeln.
     */
    @VisibleForTesting
    static String mergeAlignedCandidateTexts(String a, String b, boolean preferA) {
        int[] ca = codePoints(a);
        int[] cb = codePoints(b);
        int n = ca.length;
        int m = cb.length;
        int[][] d = new int[n + 1][m + 1];
        for (int i = 0; i <= n; i++) d[i][0] = i;
        for (int j = 0; j <= m; j++) d[0][j] = j;
        for (int i = 1; i <= n; i++) {
            for (int j = 1; j <= m; j++) {
                int cost = ca[i - 1] == cb[j - 1] ? 0 : 1;
                d[i][j] =
                        Math.min(
                                Math.min(d[i - 1][j] + 1, d[i][j - 1] + 1),
                                d[i - 1][j - 1] + cost);
            }
        }
        StringBuilder sb = new StringBuilder(Math.max(n, m));
        int i = n;
        int j = m;
        while (i > 0 || j > 0) {
            if (i > 0 && j > 0 && d[i][j] == d[i - 1][j - 1] + (ca[i - 1] == cb[j - 1] ? 0 : 1)) {
                sb.appendCodePoint(
                        ca[i - 1] == cb[j - 1] ? ca[i - 1] : (preferA ? ca[i - 1] : cb[j - 1]));
                i--;
                j--;
            } else if (i > 0 && d[i][j] == d[i - 1][j] + 1) {
                sb.appendCodePoint(ca[i - 1]); // nur in A vorhanden → übernehmen
                i--;
            } else {
                sb.appendCodePoint(cb[j - 1]); // nur in B vorhanden → übernehmen
                j--;
            }
        }
        return sb.reverse().toString();
    }

    private static int[] codePoints(String s) {
        return s == null ? new int[0] : s.codePoints().toArray();
    }

    /** Separierbarer Gauß-Blur (Kernel-Radius = ⌈3σ⌉) für ein Graustufen-Float-Array. */
    private static float[] gaussianBlur(float[] src, int w, int h, double sigma) {
        int radius = (int) Math.ceil(3 * sigma);
        float[] kernel = new float[2 * radius + 1];
        double sum = 0;
        for (int i = -radius; i <= radius; i++) {
            double v = Math.exp(-(i * i) / (2 * sigma * sigma));
            kernel[i + radius] = (float) v;
            sum += v;
        }
        for (int i = 0; i < kernel.length; i++) kernel[i] /= (float) sum;

        float[] tmp = new float[w * h];
        for (int y = 0; y < h; y++) {
            int row = y * w;
            for (int x = 0; x < w; x++) {
                float acc = 0f;
                for (int k = -radius; k <= radius; k++) {
                    int xx = Math.min(w - 1, Math.max(0, x + k));
                    acc += src[row + xx] * kernel[k + radius];
                }
                tmp[row + x] = acc;
            }
        }
        float[] dst = new float[w * h];
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                float acc = 0f;
                for (int k = -radius; k <= radius; k++) {
                    int yy = Math.min(h - 1, Math.max(0, y + k));
                    acc += tmp[yy * w + x] * kernel[k + radius];
                }
                dst[y * w + x] = acc;
            }
        }
        return dst;
    }

    /**
     * Umgibt einen (bereits rotierten) Vertikal-Crop mit einem weißen Rand von
     * {@code padFraction} × Crop-Höhe. Der Rand gibt dem Rec-Modell den
     * nötigen Größenkontext, damit kleine Kana (っ/ッ) und Interpunktion (。) in langen
     * Spalten-Crops nicht verschluckt bzw. als große Pendants fehlgedeutet werden.
     * Die Quelle wird recycelt, sofern ein neues Bitmap erzeugt wurde.
     */
    private static Bitmap padWhiteVertical(Bitmap src, double padFraction) {
        int pad = (int) Math.round(padFraction * src.getHeight());
        if (pad <= 0) return src;
        Bitmap out =
                Bitmap.createBitmap(
                        src.getWidth() + 2 * pad,
                        src.getHeight() + 2 * pad,
                        Bitmap.Config.ARGB_8888);
        android.graphics.Canvas canvas = new android.graphics.Canvas(out);
        canvas.drawColor(android.graphics.Color.WHITE);
        canvas.drawBitmap(src, pad, pad, null);
        if (!src.isRecycled()) {
            src.recycle();
        }
        return out;
    }

    /**
     * Rotiert eine Bitmap um den angegebenen Winkel im Uhrzeigersinn.
     * Bei 0° (mod 360) wird die Original-Bitmap unverändert zurückgegeben.
     */
    private static Bitmap rotateBitmap(Bitmap src, int degreesCw) {
        int deg = ((degreesCw % 360) + 360) % 360;
        if (deg == 0) return src;
        android.graphics.Matrix m = new android.graphics.Matrix();
        m.postRotate(deg);
        return Bitmap.createBitmap(src, 0, 0, src.getWidth(), src.getHeight(), m, true);
    }

    private static double centerY(Quad q) {
        return (q.minY() + q.maxY()) * 0.5;
    }

    private static double centerX(Quad q) {
        return (q.minX() + q.maxX()) * 0.5;
    }

    /**
     * Codepoint-basierte RTL-Erkennung für einen einzelnen String. Konsistent mit
     * {@link #isRtlLineByText(String[])} und {@code PdfTextUtils.isRtlText} (im
     * :main-Sourceset, hier bewusst dupliziert um eine Modul-Abhängigkeit zu vermeiden).
     *
     * @param s der zu prüfende Text
     * @return {@code true}, wenn mehr RTL- als LTR-Codepoints vorkommen
     */
    @VisibleForTesting
    static boolean isRtlText(String s) {
        if (s == null || s.isEmpty()) return false;
        int rtlCount = 0;
        int ltrCount = 0;
        for (int i = 0; i < s.length(); ) {
            int cp = s.codePointAt(i);
            byte d = Character.getDirectionality(cp);
            if (d == Character.DIRECTIONALITY_RIGHT_TO_LEFT
                    || d == Character.DIRECTIONALITY_RIGHT_TO_LEFT_ARABIC
                    || d == Character.DIRECTIONALITY_RIGHT_TO_LEFT_EMBEDDING
                    || d == Character.DIRECTIONALITY_RIGHT_TO_LEFT_OVERRIDE) {
                rtlCount++;
            } else if (d == Character.DIRECTIONALITY_LEFT_TO_RIGHT
                    || d == Character.DIRECTIONALITY_LEFT_TO_RIGHT_EMBEDDING
                    || d == Character.DIRECTIONALITY_LEFT_TO_RIGHT_OVERRIDE) {
                ltrCount++;
            }
            i += Character.charCount(cp);
        }
        return rtlCount > ltrCount;
    }

    @VisibleForTesting
    static boolean isRtlLineByText(String[] perQuadText) {
        if (perQuadText == null || perQuadText.length == 0) return false;
        int rtlCount = 0;
        int ltrCount = 0;
        for (String s : perQuadText) {
            if (s == null || s.isEmpty()) continue;
            for (int i = 0; i < s.length(); ) {
                int cp = s.codePointAt(i);
                byte d = Character.getDirectionality(cp);
                if (d == Character.DIRECTIONALITY_RIGHT_TO_LEFT
                        || d == Character.DIRECTIONALITY_RIGHT_TO_LEFT_ARABIC
                        || d == Character.DIRECTIONALITY_RIGHT_TO_LEFT_EMBEDDING
                        || d == Character.DIRECTIONALITY_RIGHT_TO_LEFT_OVERRIDE) {
                    rtlCount++;
                } else if (d == Character.DIRECTIONALITY_LEFT_TO_RIGHT
                        || d == Character.DIRECTIONALITY_LEFT_TO_RIGHT_EMBEDDING
                        || d == Character.DIRECTIONALITY_LEFT_TO_RIGHT_OVERRIDE) {
                    ltrCount++;
                }
                i += Character.charCount(cp);
            }
        }
        return rtlCount > ltrCount;
    }

    /**
     * Dreht einen String per Codepoints um (Surrogate-Paar-sicher). Wird bei RTL-Zeilen im
     * TXT-Output verwendet, weil Paddle die Glyphen pro Crop in visueller (links→rechts)
     * Reihenfolge liefert — die logische Codepoint-Reihenfolge ist die Spiegelung davon.
     */
    @VisibleForTesting
    static String reverseByCodepoints(String s) {
        if (s == null || s.length() < 2) return s;
        int[] cps = s.codePoints().toArray();
        StringBuilder sb = new StringBuilder(s.length());
        for (int i = cps.length - 1; i >= 0; i--) {
            sb.appendCodePoint(cps[i]);
        }
        return sb.toString();
    }

    /**
     * Builds a list of sub-words represented as {@link RecognizedWord} objects, given a {@link Quad}
     * and the OCR output containing recognized text and associated metadata.
     *
     * The method determines whether geometric data is available from the OCR output to properly
     * split the recognized text into sub-words. When geometry data is insufficient or unavailable,
     * it falls back to using the bounding box of the entire quadrilateral.
     *
     * @param q the quadrilateral that defines the region of interest for the recognized text
     * @param out the OCR output containing recognized text, confidence levels, and geometry
     * @return a list of {@link RecognizedWord} objects representing the constructed sub-words,
     *         each with its own text, bounding box, and confidence score
     */
    private static List<RecognizedWord> buildSubWords(Quad q, PaddleRecOrtRunner.RecOutput out) {
        String fullText = out.text() != null ? out.text() : "";
        // Aggregat-Konfidenz der Engine, 0..1 → 0..100 (für Fallback und als Mittel).
        float aggConf100 = clampConf100(out.confidence() * 100f);

        List<CtcDecoder.RecognizedToken> tokens = out.tokens();
        boolean hasGeometry =
                tokens != null
                        && !tokens.isEmpty()
                        && out.frameCount() > 0
                        && out.scaledCropWidth() > 0
                        && out.paddedCropWidth() > 0;

        if (!hasGeometry || tokens.size() < 2) {
            // Fallback: gesamte Det-Box.
            RectF bbox = quadBBox(q);
            String text = (tokens != null && tokens.size() == 1) ? tokens.get(0).text() : fullText;
            float conf =
                    (tokens != null && tokens.size() == 1)
                            ? clampConf100(tokens.get(0).confidence() * 100f)
                            : aggConf100;
            List<RecognizedWord> result = new ArrayList<>(1);
            if (!text.isEmpty()) {
                result.add(new RecognizedWord(text, bbox, conf));
            }
            return result;
        }

        final int T = out.frameCount();
        final double paddedW = out.paddedCropWidth();
        final double scaledW = out.scaledCropWidth();
        // Inhaltsbreite in padded-Koordinaten ist scaledW (Padding mit weiß rechts).
        // Mapping Frame k (0..T-1) → x in padded = (k + 0.5) * paddedW / T,
        // anschließend clip & normalisieren auf [0..1] über scaledW.
        List<RecognizedWord> result = new ArrayList<>(tokens.size());
        for (CtcDecoder.RecognizedToken tok : tokens) {
            if (tok.text() == null || tok.text().isEmpty()) continue;
            double xStart = ((double) tok.frameStart()) * paddedW / T; // linke Frame-Kante
            double xEnd = ((double) (tok.frameEnd() + 1)) * paddedW / T; // rechte Frame-Kante
            double uStart = clamp01(xStart / scaledW);
            double uEnd = clamp01(xEnd / scaledW);
            if (uEnd <= uStart) {
                // Degenerierter Fall (z.B. Frame jenseits scaledW): minimale Spanne erzwingen.
                uEnd = Math.min(1.0, uStart + 1e-3);
            }
            RectF bbox = interpolateQuadStrip(q, uStart, uEnd);
            float conf = clampConf100(tok.confidence() * 100f);
            result.add(new RecognizedWord(tok.text(), bbox, conf));
        }
        if (result.isEmpty()) {
            // Defensive: keine nicht-leeren Tokens → Fallback.
            if (!fullText.isEmpty()) {
                result.add(new RecognizedWord(fullText, quadBBox(q), aggConf100));
            }
        }
        return result;
    }

    /**
     * Computes a rectangular bounding box for a segment of a quadrilateral strip
     * defined by a start and end interpolation parameter. The method utilizes the
     * vertices of a {@link Quad} to interpolate positions along the strip's top
     * and bottom edges and derives the minimum and maximum coordinates of the
     * resultant rectangle.
     *
     * @param q the quadrilateral representing the context for interpolation, assumed to
     *          have vertices ordered as top-left (TL), top-right (TR), bottom-right (BR),
     *          and bottom-left (BL)
     * @param uStart the starting interpolation parameter, typically between 0.0 and 1.0,
     *               where 0.0 indicates the left edge and 1.0 indicates the right edge
     *               of the quadrilateral
     * @param uEnd the ending interpolation parameter, typically between 0.0 and 1.0,
     *             where this parameter must be greater than or equal to uStart
     * @return a {@link RectF} containing the interpolated bounding box defined by the minimum
     *         and maximum x and y coordinates of the interpolated vertices
     */
    @VisibleForTesting
    static RectF interpolateQuadStrip(Quad q, double uStart, double uEnd) {
        float[] r = interpolateQuadStripFloats(q, uStart, uEnd);
        return new RectF(r[0], r[1], r[2], r[3]);
    }

    /**
     * Computes a rectangular bounding box as an array of floats for a segment of a quadrilateral
     * strip defined by a start and end interpolation parameter. The method interpolates positions
     * along the top and bottom edges of the quadrilateral using the given parameters and derives
     * the minimum and maximum coordinates.
     *
     * @param q the quadrilateral representing the context for interpolation, assumed to have
     *          vertices ordered as top-left (TL), top-right (TR), bottom-right (BR), and bottom-left (BL)
     * @param uStart the starting interpolation parameter, typically between 0.0 and 1.0,
     *               where 0.0 indicates the left edge and 1.0 indicates the right edge of the quadrilateral
     * @param uEnd the ending interpolation parameter, typically between 0.0 and 1.0,
     *             where this parameter must be greater than or equal to uStart
     * @return a float array of size 4 containing the interpolated bounding box defined by
     *         the minimum x, minimum y, maximum x, and maximum y coordinates, in that order
     */
    @VisibleForTesting
    static float[] interpolateQuadStripFloats(Quad q, double uStart, double uEnd) {
        // Quad-Konvention: TL=0, TR=1, BR=2, BL=3.
        double tlx = q.x[0], tly = q.y[0];
        double trx = q.x[1], try_ = q.y[1];
        double brx = q.x[2], bry = q.y[2];
        double blx = q.x[3], bly = q.y[3];

        double topX0 = tlx + uStart * (trx - tlx);
        double topY0 = tly + uStart * (try_ - tly);
        double topX1 = tlx + uEnd * (trx - tlx);
        double topY1 = tly + uEnd * (try_ - tly);
        double botX0 = blx + uStart * (brx - blx);
        double botY0 = bly + uStart * (bry - bly);
        double botX1 = blx + uEnd * (brx - blx);
        double botY1 = bly + uEnd * (bry - bly);

        float minX = (float) Math.min(Math.min(topX0, topX1), Math.min(botX0, botX1));
        float maxX = (float) Math.max(Math.max(topX0, topX1), Math.max(botX0, botX1));
        float minY = (float) Math.min(Math.min(topY0, topY1), Math.min(botY0, botY1));
        float maxY = (float) Math.max(Math.max(topY0, topY1), Math.max(botY0, botY1));
        return new float[] {minX, minY, maxX, maxY};
    }

    private static RectF quadBBox(Quad q) {
        return new RectF(
                (float) q.minX(), (float) q.minY(), (float) q.maxX(), (float) q.maxY());
    }

    private static float clampConf100(float v) {
        if (v < 0f) return 0f;
        if (v > 100f) return 100f;
        return v;
    }

    private static double clamp01(double v) {
        if (v < 0.0) return 0.0;
        if (v > 1.0) return 1.0;
        return v;
    }
}
