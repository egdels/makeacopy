/*
 * Copyright 2026 Christian Kierdorf
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 */
package de.schliweb.makeacopy.ml.ncnn;

import android.content.res.AssetManager;
import androidx.annotation.NonNull;
import java.io.File;
import java.io.IOException;
import java.util.concurrent.locks.ReentrantReadWriteLock;

/**
 * One neural network loaded into NCNN, the inference library bundled as {@code
 * libmakeacopy_ncnn.so}.
 *
 * <p>A model consists of two files that share a base name: {@code <base>.ncnn.param} describes the
 * layers, {@code <base>.ncnn.bin} holds the weights. The network computes in single precision on
 * the CPU; that is fixed in the native code and not an option here.
 *
 * <p>Any number of threads may call {@link #run} at the same time. {@link #close} waits for runs in
 * flight and makes later runs fail.
 */
public final class NcnnNet implements AutoCloseable {

  /** Suffix of the file that describes the layers. */
  public static final String PARAM_SUFFIX = ".ncnn.param";

  /** Suffix of the file that holds the weights. */
  public static final String BIN_SUFFIX = ".ncnn.bin";

  /** Name NCNN's converter gives the first output of a model. */
  private static final String[] SINGLE_OUTPUT = {"out0"};

  static {
    System.loadLibrary("makeacopy_ncnn");
  }

  /**
   * One output tensor, flattened channel by channel, row by row.
   *
   * @param data {@code channels * height * width} values
   * @param channels number of channels; 1 for outputs with fewer than three dimensions
   * @param height number of rows; 1 for a one-dimensional output
   * @param width number of columns
   */
  public record Tensor(float[] data, int channels, int height, int width) {}

  private final ReentrantReadWriteLock lock = new ReentrantReadWriteLock();
  private long handle;

  private NcnnNet(long handle) {
    this.handle = handle;
  }

  /**
   * Loads a model from the app's assets.
   *
   * @param assets the asset manager that holds the model
   * @param basePath asset path without {@link #PARAM_SUFFIX} / {@link #BIN_SUFFIX}
   * @param threads worker threads one run may use, at least 1
   * @throws IOException if one of the two files is missing or not a valid model
   */
  @NonNull
  public static NcnnNet fromAssets(
      @NonNull AssetManager assets, @NonNull String basePath, int threads) throws IOException {
    long handle =
        nativeLoadAssets(
            assets, basePath + PARAM_SUFFIX, basePath + BIN_SUFFIX, Math.max(1, threads));
    if (handle == 0) {
      throw new IOException("Cannot load NCNN model from assets: " + basePath);
    }
    return new NcnnNet(handle);
  }

  /**
   * Loads a model from two files on disk.
   *
   * @param basePath file path without {@link #PARAM_SUFFIX} / {@link #BIN_SUFFIX}
   * @param threads worker threads one run may use, at least 1
   * @throws IOException if one of the two files is missing or not a valid model
   */
  @NonNull
  public static NcnnNet fromFiles(@NonNull File basePath, int threads) throws IOException {
    String base = basePath.getAbsolutePath();
    long handle = nativeLoadFiles(base + PARAM_SUFFIX, base + BIN_SUFFIX, Math.max(1, threads));
    if (handle == 0) {
      throw new IOException("Cannot load NCNN model from files: " + base);
    }
    return new NcnnNet(handle);
  }

  /**
   * Runs the network on one image-like tensor and returns its only output.
   *
   * @param chw input values, channel by channel, row by row
   * @throws IllegalStateException if the network is closed or produced no output
   */
  @NonNull
  public Tensor run(@NonNull float[] chw, int channels, int height, int width) {
    return run(chw, channels, height, width, SINGLE_OUTPUT)[0];
  }

  /**
   * Runs the network on one image-like tensor.
   *
   * @param chw input values, channel by channel, row by row
   * @param outputNames names of the outputs to fetch; the converter calls them {@code out0}, {@code
   *     out1}, ... in the order the original model declared them
   * @return one tensor per requested name, in the same order
   * @throws IllegalStateException if the network is closed or lacks one of the outputs
   */
  @NonNull
  public Tensor[] run(
      @NonNull float[] chw, int channels, int height, int width, @NonNull String[] outputNames) {
    if (channels <= 0 || height <= 0 || width <= 0 || chw.length != channels * height * width) {
      throw new IllegalArgumentException(
          "input has " + chw.length + " values, expected " + channels + "x" + height + "x" + width);
    }
    int[] dims = new int[outputNames.length * 3];
    float[][] data;
    lock.readLock().lock();
    try {
      if (handle == 0) {
        throw new IllegalStateException("NcnnNet is closed");
      }
      data = nativeRun(handle, chw, channels, height, width, outputNames, dims);
    } finally {
      lock.readLock().unlock();
    }
    if (data == null) {
      throw new IllegalStateException("NCNN returned no result");
    }
    Tensor[] out = new Tensor[outputNames.length];
    for (int i = 0; i < out.length; i++) {
      out[i] = new Tensor(data[i], dims[i * 3], dims[i * 3 + 1], dims[i * 3 + 2]);
    }
    return out;
  }

  /** Frees the native network. Waits for runs in flight; calling it twice is harmless. */
  @Override
  public void close() {
    lock.writeLock().lock();
    try {
      if (handle != 0) {
        nativeDestroy(handle);
        handle = 0;
      }
    } finally {
      lock.writeLock().unlock();
    }
  }

  private static native long nativeLoadAssets(
      AssetManager assets, String paramPath, String binPath, int threads);

  private static native long nativeLoadFiles(String paramPath, String binPath, int threads);

  private static native void nativeDestroy(long handle);

  private static native float[][] nativeRun(
      long handle,
      float[] input,
      int channels,
      int height,
      int width,
      String[] outputNames,
      int[] dims);
}
