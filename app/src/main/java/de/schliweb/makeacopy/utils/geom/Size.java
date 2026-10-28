/*
 * Copyright 2026 Christian Kierdorf
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 */
package de.schliweb.makeacopy.utils.geom;

import java.util.Locale;

/** A width and height in pixels. */
public final class Size {
  public double width;
  public double height;

  public Size() {}

  public Size(double width, double height) {
    this.width = width;
    this.height = height;
  }

  @Override
  public boolean equals(Object o) {
    return o instanceof Size s
        && Double.compare(width, s.width) == 0
        && Double.compare(height, s.height) == 0;
  }

  @Override
  public int hashCode() {
    return 31 * Double.hashCode(width) + Double.hashCode(height);
  }

  @Override
  public String toString() {
    return String.format(Locale.ROOT, "%sx%s", width, height);
  }
}
