/*
 * Copyright 2025 Christian Kierdorf
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 */
package de.schliweb.makeacopy.ml.corners;

import android.graphics.Bitmap;
import android.util.Log;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import org.opencv.android.Utils;
import org.opencv.core.CvType;
import org.opencv.core.Mat;
import org.opencv.core.Size;
import org.opencv.imgproc.Imgproc;

/**
 * Kanten-basiertes Refinement eines grob erkannten Dokument-Quads (Edge-Snapping).
 *
 * <p>Motivation: Der ML-Detektor (DocQuadNet-256) arbeitet auf einem 256×256-Letterbox-Bild mit
 * 64×64-Heatmaps. Bei der Rückprojektion auf das Originalbild (z. B. 3024×4032) wird jeder
 * Modellfehler mit Faktor ~16 skaliert, sodass die Ecken sichtbar neben den echten Dokumentecken
 * liegen können. Dieses Refinement nutzt die ML-Ecken nur als Startwert und "snappt" die vier
 * Quad-Kanten im hochaufgelösten Bild auf die stärksten Bildgradienten.
 *
 * <p>Algorithmus (pro Kante):
 *
 * <ol>
 *   <li>Entlang der Kante werden Stützpunkte abgetastet (Randbereiche nahe der Ecken werden
 *       ausgespart, da dort Rundungen/Finger/Schatten stören).
 *   <li>Für jeden Stützpunkt wird entlang der Kantennormalen im Sobel-Gradientenbild der stärkste
 *       Gradient gesucht (mit parabolischer Subpixel-Verfeinerung).
 *   <li>Durch die gefundenen Punkte wird eine Gerade robust gefittet (Total-Least-Squares mit einem
 *       Ausreißer-Reweighting-Pass).
 *   <li>Die neuen Ecken ergeben sich als Schnittpunkte benachbarter Geraden.
 * </ol>
 *
 * <p>Guards: Liefert eine Kante zu wenige valide Gradientenpunkte, wird die ursprüngliche Kante
 * beibehalten. Verschiebt sich eine Ecke weiter als ein kleiner Bruchteil der Bilddiagonale oder
 * wird das Quad nicht-konvex, bleibt die jeweilige Original-Ecke bzw. das gesamte Original-Quad
 * erhalten. Das Refinement kann das Ergebnis also nie deutlich verschlechtern.
 *
 * <p>Implementierungen dürfen niemals Exceptions nach außen werfen; im Fehlerfall wird das
 * Eingabe-Quad unverändert zurückgegeben.
 */
public final class EdgeSnapCornerRefiner {

  private static final String TAG = "EdgeSnapCornerRefiner";

  /** Maximale Arbeitskantenlänge; begrenzt Speicher/Laufzeit bei sehr großen Fotos. */
  private static final int WORK_MAX_EDGE = 1600;

  /** Anzahl Stützpunkte je Kante für die Gradientensuche. */
  private static final int SAMPLES_PER_EDGE = 48;

  /** Anteil der Kantenlänge nahe der Ecken, der nicht abgetastet wird (Rundungen, Verdeckung). */
  private static final double EDGE_END_MARGIN = 0.10;

  /** Mindestanteil valider Gradientenpunkte, sonst wird die Original-Kante beibehalten. */
  private static final double MIN_VALID_FRACTION = 0.5;

  /**
   * Minimale Gradientenstärke (Sobel 3×3, CV_32F auf 8-Bit-Graustufen), damit ein Punkt als Kante
   * zählt. Echte Papierkanten erzeugen typischerweise Werte von mehreren Hundert; 10 war praktisch
   * wirkungslos und ließ Rauschen/Texturen als "Kante" durchgehen.
   */
  private static final double MIN_GRADIENT_MAGNITUDE = 40.0;

  /**
   * Anteil des stärksten Gradienten im Suchfenster, den ein näher an der Startkante liegendes
   * lokales Maximum mindestens erreichen muss, um bevorzugt zu werden. Dadurch snappt der Refiner
   * auf die *nächstgelegene* deutliche Kante (Papierkante) statt auf den global stärksten
   * Gradienten (oft Textzeilen oder harte Schattenkanten weiter innen/außen).
   */
  private static final double NEAREST_PEAK_ACCEPT_FRACTION = 0.5;

  /**
   * Maximal zulässiges Median-Residuum (px, Arbeitsmaßstab) des Geradenfits. Höhere Werte deuten
   * darauf hin, dass die Stützpunkte auf unterschiedliche Strukturen (Text, Schatten) gesnappt
   * sind; die Kante wird dann verworfen und die Original-Kante beibehalten.
   */
  private static final double MAX_MEDIAN_RESIDUAL = 3.0;

  /** Suchradius entlang der Normalen als Anteil der Bilddiagonale (im Arbeitsmaßstab). */
  private static final double SEARCH_RADIUS_FRACTION = 0.02;

  /** Maximal zulässige Ecken-Verschiebung als Anteil der Bilddiagonale (Guard). */
  private static final double MAX_CORNER_SHIFT_FRACTION = 0.03;

  private EdgeSnapCornerRefiner() {}

  /**
   * Verfeinert ein Quad in {@link org.opencv.core.Point}-Darstellung (TL,TR,BR,BL). Gibt bei
   * Fehlern oder verworfenem Refinement das Eingabe-Array unverändert zurück.
   */
  @Nullable
  public static org.opencv.core.Point[] refine(
      @Nullable Bitmap src, @Nullable org.opencv.core.Point[] quadTLTRBRBL) {
    if (src == null || quadTLTRBRBL == null || quadTLTRBRBL.length != 4) return quadTLTRBRBL;
    double[][] q = new double[4][2];
    for (int i = 0; i < 4; i++) {
      org.opencv.core.Point p = quadTLTRBRBL[i];
      if (p == null) return quadTLTRBRBL;
      q[i][0] = p.x;
      q[i][1] = p.y;
    }
    double[][] refined = refine(src, q);
    if (refined == q) return quadTLTRBRBL;
    org.opencv.core.Point[] out = new org.opencv.core.Point[4];
    for (int i = 0; i < 4; i++) out[i] = new org.opencv.core.Point(refined[i][0], refined[i][1]);
    return out;
  }

  /**
   * Verfeinert ein Quad {@code {TL,TR,BR,BL}} in Original-Bitmap-Koordinaten. Gibt bei Fehlern oder
   * verworfenem Refinement das Eingabe-Array (identische Referenz) zurück.
   */
  @NonNull
  public static double[][] refine(@Nullable Bitmap src, @NonNull double[][] quadTLTRBRBL) {
    if (src == null || !isPlausibleQuad(quadTLTRBRBL)) {
      Log.d(
          TAG,
          "refine: skipped (src="
              + (src != null)
              + ", plausibleQuad="
              + isPlausibleQuad(quadTLTRBRBL)
              + ")");
      return quadTLTRBRBL;
    }
    long t0 = android.os.SystemClock.uptimeMillis();
    Mat rgba = null;
    Mat gray = null;
    Mat gx = null;
    Mat gy = null;
    try {
      int srcW = src.getWidth();
      int srcH = src.getHeight();
      if (srcW <= 2 || srcH <= 2) return quadTLTRBRBL;

      double scale = Math.min(1.0, WORK_MAX_EDGE / (double) Math.max(srcW, srcH));

      rgba = new Mat();
      Utils.bitmapToMat(src, rgba);
      gray = new Mat();
      Imgproc.cvtColor(rgba, gray, Imgproc.COLOR_RGBA2GRAY);
      rgba.release();
      rgba = null;

      if (scale < 1.0) {
        int w = Math.max(2, (int) Math.round(srcW * scale));
        int h = Math.max(2, (int) Math.round(srcH * scale));
        Imgproc.resize(gray, gray, new Size(w, h), 0, 0, Imgproc.INTER_AREA);
      }
      Imgproc.GaussianBlur(gray, gray, new Size(3, 3), 0);

      int w = gray.cols();
      int h = gray.rows();
      gx = new Mat();
      gy = new Mat();
      Imgproc.Sobel(gray, gx, CvType.CV_32F, 1, 0, 3);
      Imgproc.Sobel(gray, gy, CvType.CV_32F, 0, 1, 3);
      gray.release();
      gray = null;

      float[] gxArr = new float[w * h];
      float[] gyArr = new float[w * h];
      gx.get(0, 0, gxArr);
      gy.get(0, 0, gyArr);
      gx.release();
      gx = null;
      gy.release();
      gy = null;

      // Quad in den Arbeitsmaßstab bringen
      double[][] q = new double[4][2];
      for (int i = 0; i < 4; i++) {
        q[i][0] = quadTLTRBRBL[i][0] * scale;
        q[i][1] = quadTLTRBRBL[i][1] * scale;
      }

      double diag = Math.hypot(w, h);
      double searchRadius = clamp(diag * SEARCH_RADIUS_FRACTION, 4.0, 48.0);
      double maxShift = diag * MAX_CORNER_SHIFT_FRACTION;
      Log.d(
          TAG,
          "refine: src="
              + srcW
              + "x"
              + srcH
              + ", work="
              + w
              + "x"
              + h
              + " (scale="
              + String.format(java.util.Locale.US, "%.4f", scale)
              + "), searchRadius="
              + String.format(java.util.Locale.US, "%.1f", searchRadius)
              + "px, maxShift="
              + String.format(java.util.Locale.US, "%.1f", maxShift)
              + "px (work scale)");

      // Pro Kante eine Gerade fitten; Fallback ist die Original-Kante.
      double[][] lines = new double[4][]; // {px, py, dx, dy}
      for (int i = 0; i < 4; i++) {
        double[] a = q[i];
        double[] b = q[(i + 1) % 4];
        double[] fitted = snapEdgeLine(gxArr, gyArr, w, h, a, b, searchRadius, i);
        if (fitted != null) {
          lines[i] = fitted;
        } else {
          Log.d(TAG, "refine: edge " + edgeName(i) + " not snapped — keeping original edge");
          double ex = b[0] - a[0];
          double ey = b[1] - a[1];
          double len = Math.hypot(ex, ey);
          if (len < 1e-6) return quadTLTRBRBL;
          lines[i] = new double[] {a[0], a[1], ex / len, ey / len};
        }
      }

      // Ecke i = Schnittpunkt der eingehenden Kante (i-1) mit der ausgehenden Kante (i)
      double[][] refined = new double[4][2];
      for (int i = 0; i < 4; i++) {
        double[] p = intersectLines(lines[(i + 3) % 4], lines[i]);
        double shift = p != null ? Math.hypot(p[0] - q[i][0], p[1] - q[i][1]) : Double.NaN;
        if (p == null || shift > maxShift) {
          Log.d(
              TAG,
              "refine: corner "
                  + cornerName(i)
                  + (p == null
                      ? " — no intersection (parallel lines), keeping original"
                      : " — shift "
                          + String.format(java.util.Locale.US, "%.1f", shift)
                          + "px > maxShift "
                          + String.format(java.util.Locale.US, "%.1f", maxShift)
                          + "px, keeping original"));
          refined[i][0] = q[i][0];
          refined[i][1] = q[i][1];
        } else {
          Log.d(
              TAG,
              "refine: corner "
                  + cornerName(i)
                  + " snapped, shift="
                  + String.format(java.util.Locale.US, "%.2f", shift)
                  + "px (work scale, ~"
                  + String.format(java.util.Locale.US, "%.1f", shift / scale)
                  + "px original)");
          refined[i][0] = p[0];
          refined[i][1] = p[1];
        }
      }

      if (!DocQuadDetector.isConvexTLTRBRBL(refined)) {
        Log.w(
            TAG, "refine: refined quad not convex — discarding refinement, keeping original quad");
        return quadTLTRBRBL;
      }

      // Zurück in Original-Koordinaten
      double inv = 1.0 / scale;
      double[][] out = new double[4][2];
      double totalShiftOrig = 0.0;
      for (int i = 0; i < 4; i++) {
        out[i][0] = refined[i][0] * inv;
        out[i][1] = refined[i][1] * inv;
        totalShiftOrig +=
            Math.hypot(out[i][0] - quadTLTRBRBL[i][0], out[i][1] - quadTLTRBRBL[i][1]);
      }
      Log.i(
          TAG,
          "refine: done in "
              + (android.os.SystemClock.uptimeMillis() - t0)
              + "ms, mean corner shift="
              + String.format(java.util.Locale.US, "%.1f", totalShiftOrig / 4.0)
              + "px (original scale)");
      return out;
    } catch (Throwable t) {
      Log.w(TAG, "refine failed, keeping original quad: " + t);
      return quadTLTRBRBL;
    } finally {
      releaseQuietly(rgba);
      releaseQuietly(gray);
      releaseQuietly(gx);
      releaseQuietly(gy);
    }
  }

  /**
   * Sucht entlang der Kante a→b für mehrere Stützpunkte den stärksten Gradienten entlang der
   * Kantennormalen und fittet eine Gerade durch die gefundenen Punkte.
   *
   * @return {@code {px, py, dx, dy}} oder {@code null}, wenn zu wenige valide Punkte vorliegen.
   */
  @Nullable
  private static double[] snapEdgeLine(
      float[] gxArr,
      float[] gyArr,
      int w,
      int h,
      double[] a,
      double[] b,
      double searchRadius,
      int edgeIndex) {
    double ex = b[0] - a[0];
    double ey = b[1] - a[1];
    double len = Math.hypot(ex, ey);
    if (len < 8.0) {
      Log.d(
          TAG,
          "snapEdgeLine["
              + edgeName(edgeIndex)
              + "]: edge too short ("
              + String.format(java.util.Locale.US, "%.1f", len)
              + "px)");
      return null;
    }
    double dx = ex / len;
    double dy = ey / len;
    // Normale (Richtung egal, es wird in beide Richtungen gesucht)
    double nx = -dy;
    double ny = dx;

    int r = (int) Math.ceil(searchRadius);
    double[] xs = new double[SAMPLES_PER_EDGE];
    double[] ys = new double[SAMPLES_PER_EDGE];
    int count = 0;

    for (int s = 0; s < SAMPLES_PER_EDGE; s++) {
      double t =
          EDGE_END_MARGIN + (1.0 - 2.0 * EDGE_END_MARGIN) * s / (double) (SAMPLES_PER_EDGE - 1);
      double px = a[0] + t * ex;
      double py = a[1] + t * ey;

      // Gradienten-Profil entlang der Normalen aufnehmen
      int n = 2 * r + 1;
      double[] prof = new double[n];
      double maxResp = -1.0;
      for (int u = -r; u <= r; u++) {
        int ix = (int) Math.round(px + u * nx);
        int iy = (int) Math.round(py + u * ny);
        double resp;
        if (ix < 1 || iy < 1 || ix >= w - 1 || iy >= h - 1) {
          resp = -1.0;
        } else {
          int idx = iy * w + ix;
          // Projektion des Gradienten auf die Normale: reagiert nur auf Kanten, die parallel
          // zur erwarteten Dokumentkante verlaufen.
          resp = Math.abs(gxArr[idx] * nx + gyArr[idx] * ny);
        }
        prof[u + r] = resp;
        if (resp > maxResp) maxResp = resp;
      }

      if (maxResp < MIN_GRADIENT_MAGNITUDE) continue;

      // Nächstgelegenes ausreichend starkes lokales Maximum wählen (statt globalem Maximum):
      // die Startkante liegt bereits nahe der Papierkante, stärkere Gradienten weiter weg
      // stammen meist von Text oder Schatten.
      double acceptThr = Math.max(MIN_GRADIENT_MAGNITUDE, NEAREST_PEAK_ACCEPT_FRACTION * maxResp);
      int bestU = Integer.MIN_VALUE;
      int bestDist = Integer.MAX_VALUE;
      for (int k = 1; k < n - 1; k++) {
        double c = prof[k];
        if (c < acceptThr) continue;
        if (c < prof[k - 1] || c < prof[k + 1]) continue; // kein lokales Maximum
        int u = k - r;
        int dist = Math.abs(u);
        if (dist < bestDist) {
          bestDist = dist;
          bestU = u;
        }
      }
      if (bestU == Integer.MIN_VALUE) continue;

      // Parabolische Subpixel-Verfeinerung um das Maximum
      double uRefined = bestU;
      int k = bestU + r;
      double respAtBestMinus1 = prof[k - 1];
      double bestResp = prof[k];
      double respAtBestPlus1 = prof[k + 1];
      if (respAtBestMinus1 >= 0 && respAtBestPlus1 >= 0) {
        double denom = respAtBestMinus1 - 2.0 * bestResp + respAtBestPlus1;
        if (Math.abs(denom) > 1e-9) {
          double offset = 0.5 * (respAtBestMinus1 - respAtBestPlus1) / denom;
          if (offset >= -1.0 && offset <= 1.0) uRefined = bestU + offset;
        }
      }

      xs[count] = px + uRefined * nx;
      ys[count] = py + uRefined * ny;
      count++;
    }

    if (count < (int) Math.ceil(MIN_VALID_FRACTION * SAMPLES_PER_EDGE)) {
      Log.d(
          TAG,
          "snapEdgeLine["
              + edgeName(edgeIndex)
              + "]: too few valid gradient points ("
              + count
              + "/"
              + SAMPLES_PER_EDGE
              + ")");
      return null;
    }

    double[] line = fitLineTLS(xs, ys, count);
    if (line == null) {
      Log.d(TAG, "snapEdgeLine[" + edgeName(edgeIndex) + "]: TLS fit degenerate");
      return null;
    }

    // Ein Reweighting-Pass: Ausreißer (z. B. Textzeilen, Schattenkanten) verwerfen und neu fitten.
    double[] res = new double[count];
    for (int i = 0; i < count; i++) res[i] = Math.abs(pointLineDistance(line, xs[i], ys[i]));
    double medAbs = median(res, count);
    if (medAbs > MAX_MEDIAN_RESIDUAL) {
      Log.d(
          TAG,
          "snapEdgeLine["
              + edgeName(edgeIndex)
              + "]: median residual too high ("
              + String.format(java.util.Locale.US, "%.2f", medAbs)
              + "px > "
              + String.format(java.util.Locale.US, "%.2f", MAX_MEDIAN_RESIDUAL)
              + "px) — rejecting edge fit");
      return null;
    }
    double thr = Math.max(1.5, 3.0 * medAbs);
    double[] xs2 = new double[count];
    double[] ys2 = new double[count];
    int kept = 0;
    for (int i = 0; i < count; i++) {
      if (res[i] <= thr) {
        xs2[kept] = xs[i];
        ys2[kept] = ys[i];
        kept++;
      }
    }
    if (kept < (int) Math.ceil(MIN_VALID_FRACTION * SAMPLES_PER_EDGE)) {
      Log.d(
          TAG,
          "snapEdgeLine["
              + edgeName(edgeIndex)
              + "]: too few inliers after reweighting ("
              + kept
              + "/"
              + count
              + ", medAbsResidual="
              + String.format(java.util.Locale.US, "%.2f", medAbs)
              + ")");
      return null;
    }
    Log.d(
        TAG,
        "snapEdgeLine["
            + edgeName(edgeIndex)
            + "]: fitted (valid="
            + count
            + "/"
            + SAMPLES_PER_EDGE
            + ", inliers="
            + kept
            + ", medAbsResidual="
            + String.format(java.util.Locale.US, "%.2f", medAbs)
            + ")");
    double[] line2 = fitLineTLS(xs2, ys2, kept);
    return line2 != null ? line2 : line;
  }

  /** Kantenname für Diagnose-Logging (Index i = Kante von Ecke i zu Ecke i+1). */
  private static String edgeName(int i) {
    switch (i) {
      case 0:
        return "top";
      case 1:
        return "right";
      case 2:
        return "bottom";
      case 3:
        return "left";
      default:
        return String.valueOf(i);
    }
  }

  /** Eckenname für Diagnose-Logging (TL,TR,BR,BL). */
  private static String cornerName(int i) {
    switch (i) {
      case 0:
        return "TL";
      case 1:
        return "TR";
      case 2:
        return "BR";
      case 3:
        return "BL";
      default:
        return String.valueOf(i);
    }
  }

  /**
   * Total-Least-Squares-Geradenfit (PCA-Hauptachse) durch {@code n} Punkte.
   *
   * @return {@code {px, py, dx, dy}} (Punkt + normierte Richtung) oder {@code null} bei
   *     degenerierten Eingaben. Sichtbar für Tests.
   */
  @Nullable
  static double[] fitLineTLS(double[] xs, double[] ys, int n) {
    if (xs == null || ys == null || n < 2 || xs.length < n || ys.length < n) return null;
    double mx = 0, my = 0;
    for (int i = 0; i < n; i++) {
      mx += xs[i];
      my += ys[i];
    }
    mx /= n;
    my /= n;
    double sxx = 0, sxy = 0, syy = 0;
    for (int i = 0; i < n; i++) {
      double cx = xs[i] - mx;
      double cy = ys[i] - my;
      sxx += cx * cx;
      sxy += cx * cy;
      syy += cy * cy;
    }
    if (sxx + syy < 1e-12) return null; // alle Punkte identisch
    double theta = 0.5 * Math.atan2(2.0 * sxy, sxx - syy);
    double dx = Math.cos(theta);
    double dy = Math.sin(theta);
    return new double[] {mx, my, dx, dy};
  }

  /**
   * Schnittpunkt zweier Geraden in Punkt-Richtungs-Form {@code {px, py, dx, dy}}.
   *
   * @return {@code {x, y}} oder {@code null}, wenn (nahezu) parallel. Sichtbar für Tests.
   */
  @Nullable
  static double[] intersectLines(double[] l1, double[] l2) {
    if (l1 == null || l2 == null || l1.length != 4 || l2.length != 4) return null;
    double cross = l1[2] * l2[3] - l1[3] * l2[2];
    if (Math.abs(cross) < 1e-9) return null;
    double qpx = l2[0] - l1[0];
    double qpy = l2[1] - l1[1];
    double t = (qpx * l2[3] - qpy * l2[2]) / cross;
    return new double[] {l1[0] + t * l1[2], l1[1] + t * l1[3]};
  }

  /** Senkrechter Abstand eines Punkts zur Geraden {@code {px, py, dx, dy}}. Sichtbar für Tests. */
  static double pointLineDistance(double[] line, double x, double y) {
    double rx = x - line[0];
    double ry = y - line[1];
    return rx * -line[3] + ry * line[2];
  }

  private static double median(double[] values, int n) {
    double[] copy = new double[n];
    System.arraycopy(values, 0, copy, 0, n);
    java.util.Arrays.sort(copy);
    return (n % 2 == 1) ? copy[n / 2] : 0.5 * (copy[n / 2 - 1] + copy[n / 2]);
  }

  private static boolean isPlausibleQuad(double[][] q) {
    if (q == null || q.length != 4) return false;
    for (double[] p : q) {
      if (p == null || p.length != 2) return false;
      if (Double.isNaN(p[0]) || Double.isNaN(p[1])) return false;
      if (Double.isInfinite(p[0]) || Double.isInfinite(p[1])) return false;
    }
    return true;
  }

  private static double clamp(double v, double lo, double hi) {
    return Math.max(lo, Math.min(hi, v));
  }

  private static void releaseQuietly(@Nullable Mat m) {
    try {
      if (m != null) m.release();
    } catch (Throwable ignore) {
      // Best-effort; failure is non-critical
    }
  }
}
