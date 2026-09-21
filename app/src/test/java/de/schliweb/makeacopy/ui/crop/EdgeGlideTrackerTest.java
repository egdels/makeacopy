/*
 * Copyright 2025 Christian Kierdorf
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 */
package de.schliweb.makeacopy.ui.crop;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/** Unit tests for {@link EdgeGlideTracker}. Image spans x in [100, 500]. */
public class EdgeGlideTrackerTest {
  private static final float LEFT = 100f;
  private static final float RIGHT = 500f;
  private static final float ENTER = 6f;
  private static final float EXIT = 12f;
  private static final int DELAY_MS = 120;

  private final EdgeGlideTracker tracker = new EdgeGlideTracker();

  private float[] move(float x, float y, float rawY, long nowMs, float cornerY) {
    float[] target = {x, y};
    tracker.onMove(x, rawY, LEFT, RIGHT, ENTER, EXIT, nowMs, DELAY_MS, cornerY, target);
    return target;
  }

  @Test
  public void insideTheImage_targetFollowsThePointer() {
    tracker.start(300f, 200f);
    assertArrayEquals(new float[] {250f, 210f}, move(250f, 210f, 310f, 0L, 200f), 0f);
    assertFalse(tracker.isGliding());
  }

  @Test
  public void barelyOutside_withinEnterEps_doesNotBecomeEligible() {
    tracker.start(300f, 200f);
    move(LEFT - ENTER, 200f, 300f, 0L, 200f);
    move(LEFT - ENTER, 200f, 300f, 1000L, 200f);
    assertFalse(tracker.isGliding());
  }

  @Test
  public void outsideLongerThanDelay_locksToThatEdgeAtCornerY() {
    tracker.start(300f, 200f);
    assertArrayEquals(new float[] {80f, 205f}, move(80f, 205f, 305f, 1000L, 200f), 0f);
    assertFalse(tracker.isGliding());
    assertArrayEquals(new float[] {LEFT, 207f}, move(70f, 230f, 330f, 1000L + DELAY_MS, 207f), 0f);
    assertTrue(tracker.isGliding());
  }

  @Test
  public void whileGliding_yFollowsRawYDeltas_andXStaysLocked() {
    tracker.start(300f, 200f);
    move(520f, 200f, 300f, 0L, 200f);
    move(520f, 200f, 300f, DELAY_MS, 200f); // engages on the right edge, lastRawY = 300
    assertArrayEquals(new float[] {RIGHT, 215f}, move(540f, 999f, 315f, 200L, 200f), 0f);
    assertArrayEquals(new float[] {RIGHT, 190f}, move(530f, 999f, 290f, 210L, 200f), 0f);
  }

  @Test
  public void returningInsideBeforeTheDelay_cancelsThePendingEngage() {
    tracker.start(300f, 200f);
    move(80f, 200f, 300f, 0L, 200f);
    move(200f, 200f, 300f, 50L, 200f); // back inside -> pending cancelled
    move(80f, 200f, 300f, 100L, 200f); // timer restarts here
    move(80f, 200f, 300f, 100L + DELAY_MS - 1, 200f);
    assertFalse(tracker.isGliding());
    move(80f, 200f, 300f, 100L + DELAY_MS, 200f);
    assertTrue(tracker.isGliding());
  }

  @Test
  public void glidingEndsOnlyBeyondTheExitHysteresis() {
    tracker.start(300f, 200f);
    move(80f, 200f, 300f, 0L, 200f);
    move(80f, 200f, 300f, DELAY_MS, 200f);
    move(LEFT + EXIT - 1f, 200f, 300f, 200L, 200f); // inside, but not clearly
    assertTrue(tracker.isGliding());
    // The move that crosses the hysteresis is still locked; the next one is free again.
    assertArrayEquals(new float[] {LEFT, 200f}, move(LEFT + EXIT, 200f, 300f, 210L, 200f), 0f);
    assertFalse(tracker.isGliding());
    assertArrayEquals(new float[] {150f, 222f}, move(150f, 222f, 322f, 220L, 200f), 0f);
  }

  @Test
  public void reset_stopsGliding() {
    tracker.start(300f, 200f);
    move(80f, 200f, 300f, 0L, 200f);
    move(80f, 200f, 300f, DELAY_MS, 200f);
    tracker.reset();
    assertFalse(tracker.isGliding());
  }
}
