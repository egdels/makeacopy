package de.schliweb.makeacopy.ui.crop;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assume.assumeTrue;

import android.graphics.Bitmap;
import android.os.SystemClock;
import android.view.MotionEvent;
import android.view.ViewGroup;
import androidx.test.core.app.ActivityScenario;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import de.schliweb.makeacopy.BuildConfig;
import de.schliweb.makeacopy.HiltTestActivity;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.opencv.core.Point;

/**
 * Multi-touch gestures on the crop overlay, which {@code adb input} cannot produce: pinch zoom,
 * double tap and a second finger landing while a corner is being dragged. The view is hosted
 * directly so the test does not depend on a camera or an image source.
 */
@RunWith(AndroidJUnit4.class)
public class TrapezoidMultiTouchInstrumentedTest {

  private static final int W = 900;
  private static final int H = 1200;
  private static final int POINTER_1_DOWN =
      MotionEvent.ACTION_POINTER_DOWN | (1 << MotionEvent.ACTION_POINTER_INDEX_SHIFT);
  private static final int POINTER_1_UP =
      MotionEvent.ACTION_POINTER_UP | (1 << MotionEvent.ACTION_POINTER_INDEX_SHIFT);

  private ActivityScenario<HiltTestActivity> scenario;
  private final AtomicReference<TrapezoidSelectionView> viewRef = new AtomicReference<>();

  private TrapezoidSelectionView host() {
    scenario = ActivityScenario.launch(HiltTestActivity.class);
    scenario.onActivity(
        a -> {
          TrapezoidSelectionView v = new TrapezoidSelectionView(a);
          Bitmap bmp = Bitmap.createBitmap(W, H, Bitmap.Config.ARGB_8888);
          bmp.eraseColor(0xFFFFFFFF);
          a.setContentView(v, new ViewGroup.LayoutParams(W, H));
          v.setImageBitmap(bmp);
          viewRef.set(v);
        });
    InstrumentationRegistry.getInstrumentation().waitForIdleSync();
    SystemClock.sleep(800); // corner initialization is posted
    TrapezoidSelectionView v = viewRef.get();
    scenario.onActivity(
        a ->
            v.setCornersFromImageCoordinates(
                new Point[] {
                  new Point(100, 100),
                  new Point(800, 100),
                  new Point(800, 1100),
                  new Point(100, 1100)
                }));
    InstrumentationRegistry.getInstrumentation().waitForIdleSync();
    return v;
  }

  /** Two fingers move apart symmetrically around the given centre. */
  private void pinchOut(float cx, float cy, float startGap, float endGap, int steps) {
    long down = SystemClock.uptimeMillis();
    float g0 = startGap / 2, g1 = endGap / 2;
    inject(obtain(down, down, MotionEvent.ACTION_DOWN, cx - g0, cy, cx + g0, cy, 1));
    inject(obtain(down, down + 20, POINTER_1_DOWN, cx - g0, cy, cx + g0, cy, 2));
    for (int i = 1; i <= steps; i++) {
      float g = g0 + (g1 - g0) * i / steps;
      inject(obtain(down, down + 20 + i * 16L, MotionEvent.ACTION_MOVE, cx - g, cy, cx + g, cy, 2));
    }
    long t = down + 40 + steps * 16L;
    inject(obtain(down, t, POINTER_1_UP, cx - g1, cy, cx + g1, cy, 2));
    inject(obtain(down, t + 10, MotionEvent.ACTION_UP, cx - g1, cy, cx + g1, cy, 1));
  }

  private static MotionEvent obtain(
      long down, long t, int action, float x0, float y0, float x1, float y1, int pointerCount) {
    MotionEvent.PointerProperties[] props = new MotionEvent.PointerProperties[pointerCount];
    MotionEvent.PointerCoords[] coords = new MotionEvent.PointerCoords[pointerCount];
    float[][] xy = {{x0, y0}, {x1, y1}};
    for (int i = 0; i < pointerCount; i++) {
      props[i] = new MotionEvent.PointerProperties();
      props[i].id = i;
      props[i].toolType = MotionEvent.TOOL_TYPE_FINGER;
      coords[i] = new MotionEvent.PointerCoords();
      coords[i].x = xy[i][0];
      coords[i].y = xy[i][1];
      coords[i].pressure = 1f;
      coords[i].size = 1f;
    }
    return MotionEvent.obtain(
        down, t, action, pointerCount, props, coords, 0, 0, 1f, 1f, 0, 0, 0, 0);
  }

  /** Dispatches to the hosted view on the main thread; coordinates are view-relative. */
  private void inject(MotionEvent e) {
    TrapezoidSelectionView v = viewRef.get();
    InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> v.dispatchTouchEvent(e));
    e.recycle();
  }

  @Test
  public void pinchOut_zoomsTheOverlay_andDoubleTapResets() {
    assumeTrue("pan/zoom is compiled out", BuildConfig.FEATURE_CROP_PAN_ZOOM);
    TrapezoidSelectionView v = host();
    assertEquals(1f, v.getViewScale(), 0.001f);
    int[] o = {0, 0}; // events are dispatched to the view, coordinates are view-relative
    float cx = o[0] + W / 2f, cy = o[1] + H / 2f;

    pinchOut(cx, cy, 150, 750, 20);
    InstrumentationRegistry.getInstrumentation().waitForIdleSync();
    float zoomed = v.getViewScale();
    assertTrue("pinch must zoom in, scale=" + zoomed, zoomed > 1.3f);

    // double tap toggles back to identity
    long down = SystemClock.uptimeMillis();
    inject(obtain(down, down, MotionEvent.ACTION_DOWN, cx, cy, 0, 0, 1));
    inject(obtain(down, down + 30, MotionEvent.ACTION_UP, cx, cy, 0, 0, 1));
    long down2 = down + 120;
    inject(obtain(down2, down2, MotionEvent.ACTION_DOWN, cx, cy, 0, 0, 1));
    inject(obtain(down2, down2 + 30, MotionEvent.ACTION_UP, cx, cy, 0, 0, 1));
    InstrumentationRegistry.getInstrumentation().waitForIdleSync();
    SystemClock.sleep(400);
    float after = v.getViewScale();
    assertTrue(
        "double tap must change the zoom state (was " + zoomed + ", now " + after + ")",
        Math.abs(after - zoomed) > 0.2f);
  }

  @Test
  public void secondFingerDuringCornerDrag_doesNotMakeTheCornerJump() {
    TrapezoidSelectionView v = host();
    int[] o = {0, 0}; // events are dispatched to the view, coordinates are view-relative
    Point[] before = v.getCorners();
    // corners are in view coordinates: TL is the one closest to the origin
    Point tl = before[0];
    for (Point p : before) if (p.x + p.y < tl.x + tl.y) tl = p;
    float sx = o[0] + (float) tl.x, sy = o[1] + (float) tl.y;

    long down = SystemClock.uptimeMillis();
    inject(obtain(down, down, MotionEvent.ACTION_DOWN, sx, sy, 0, 0, 1));
    for (int i = 1; i <= 6; i++) {
      inject(
          obtain(down, down + i * 16L, MotionEvent.ACTION_MOVE, sx + i * 10, sy + i * 10, 0, 0, 1));
    }
    float dx = sx + 60, dy = sy + 60;
    // second finger lands far away while the corner is held
    inject(obtain(down, down + 120, POINTER_1_DOWN, dx, dy, 700f, 900f, 2));
    inject(obtain(down, down + 140, MotionEvent.ACTION_MOVE, dx, dy, 720f, 920f, 2));
    inject(obtain(down, down + 160, POINTER_1_UP, dx, dy, 720f, 920f, 2));
    inject(obtain(down, down + 180, MotionEvent.ACTION_UP, dx, dy, 0, 0, 1));
    InstrumentationRegistry.getInstrumentation().waitForIdleSync();

    Point[] after = v.getCorners();
    Point tlAfter = after[0];
    for (Point p : after) if (p.x + p.y < tlAfter.x + tlAfter.y) tlAfter = p;
    double movedX = tlAfter.x - tl.x, movedY = tlAfter.y - tl.y;
    assertTrue(
        "dragged corner must end near the finger (+60,+60), moved " + movedX + "," + movedY,
        movedX > 30 && movedX < 90 && movedY > 30 && movedY < 90);
    // the corner where the second finger landed must not have moved onto that finger
    for (Point p : after) {
      assertTrue(
          "no corner may jump to the second finger: " + p,
          Math.hypot(p.x - 720, p.y - 920) > 60);
    }
  }
}
