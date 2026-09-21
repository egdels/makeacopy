package de.schliweb.makeacopy.testutil;

import android.content.Intent;
import android.os.Bundle;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;
import androidx.lifecycle.Lifecycle;
import androidx.test.core.app.ActivityScenario;
import de.schliweb.makeacopy.HiltTestActivity;

/**
 * Launches a Hilt-annotated {@link Fragment} inside {@link HiltTestActivity} so that Hilt injection
 * works correctly during instrumented tests.
 *
 * <p>Usage mirrors {@code FragmentScenario} but hosts the fragment in an {@code @AndroidEntryPoint}
 * activity.
 */
public final class HiltFragmentScenario<F extends Fragment> {

  private final ActivityScenario<HiltTestActivity> activityScenario;

  private HiltFragmentScenario(ActivityScenario<HiltTestActivity> activityScenario) {
    this.activityScenario = activityScenario;
  }

  /**
   * Launches the given fragment class inside a Hilt-compatible activity.
   *
   * @param fragmentClass the fragment to launch
   * @param args optional fragment arguments
   * @param themeResId theme resource id (ignored — activity theme is used)
   * @param initialState the desired lifecycle state
   * @param <F> fragment type
   * @return a {@link HiltFragmentScenario} that supports {@link #onFragment}
   */
  @NonNull
  public static <F extends Fragment> HiltFragmentScenario<F> launchInHiltContainer(
      @NonNull Class<F> fragmentClass,
      @Nullable Bundle args,
      int themeResId,
      @NonNull Lifecycle.State initialState) {
    // Fragments must not start from a draft that an earlier run or manual use left behind:
    // CameraFragment would cover itself with the "resume document" dialog.
    TestAppState.endActiveDocumentSession();
    Intent intent =
        Intent.makeMainActivity(
            new android.content.ComponentName(
                androidx.test.platform.app.InstrumentationRegistry.getInstrumentation()
                    .getTargetContext(),
                HiltTestActivity.class));
    ActivityScenario<HiltTestActivity> scenario = ActivityScenario.launch(intent);
    scenario.moveToState(Lifecycle.State.RESUMED);
    scenario.onActivity(
        activity -> {
          Fragment fragment;
          try {
            fragment = fragmentClass.getDeclaredConstructor().newInstance();
          } catch (Exception e) {
            throw new RuntimeException(e);
          }
          if (args != null) {
            fragment.setArguments(args);
          }
          activity
              .getSupportFragmentManager()
              .beginTransaction()
              .replace(android.R.id.content, fragment, "hilt_fragment")
              .commitNow();
        });
    if (initialState != Lifecycle.State.RESUMED) {
      scenario.moveToState(initialState);
    }
    return new HiltFragmentScenario<>(scenario);
  }

  /**
   * Runs the given action on the hosted fragment, similar to {@code
   * FragmentScenario#onFragment(FragmentAction)}.
   */
  @NonNull
  @SuppressWarnings("unchecked")
  public HiltFragmentScenario<F> onFragment(@NonNull FragmentAction<F> action) {
    activityScenario.onActivity(
        activity -> {
          Fragment f = activity.getSupportFragmentManager().findFragmentByTag("hilt_fragment");
          if (f == null) {
            throw new IllegalStateException("Fragment not found in HiltTestActivity");
          }
          action.perform((F) f);
        });
    return this;
  }

  /** Recreates the hosting activity (configuration change simulation). */
  @NonNull
  public HiltFragmentScenario<F> recreate() {
    activityScenario.recreate();
    return this;
  }

  /** Functional interface matching FragmentScenario.FragmentAction. */
  public interface FragmentAction<F extends Fragment> {
    void perform(@NonNull F fragment);
  }
}
