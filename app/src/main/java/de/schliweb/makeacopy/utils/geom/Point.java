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

/** A mutable point with double coordinates, in image or view pixels. */
public final class Point implements Cloneable {
  public double x;
  public double y;

  public Point() {}

  public Point(double x, double y) {
    this.x = x;
    this.y = y;
  }

  @Override
  public Point clone() {
    return new Point(x, y);
  }

  @Override
  public boolean equals(Object o) {
    return o instanceof Point p && Double.compare(x, p.x) == 0 && Double.compare(y, p.y) == 0;
  }

  @Override
  public int hashCode() {
    return 31 * Double.hashCode(x) + Double.hashCode(y);
  }

  @Override
  public String toString() {
    return String.format(Locale.ROOT, "{%s, %s}", x, y);
  }
}
