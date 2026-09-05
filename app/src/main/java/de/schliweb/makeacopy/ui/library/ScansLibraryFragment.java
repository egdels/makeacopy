/*
 * Copyright 2025 Christian Kierdorf
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 */
package de.schliweb.makeacopy.ui.library;

import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;
import androidx.navigation.Navigation;
import androidx.recyclerview.widget.RecyclerView;
import dagger.hilt.android.AndroidEntryPoint;
import de.schliweb.makeacopy.R;
import de.schliweb.makeacopy.data.library.CollectionsRepository;
import de.schliweb.makeacopy.data.library.ExistingScansIndexer;
import de.schliweb.makeacopy.data.library.ScanEntity;
import de.schliweb.makeacopy.data.library.ScansRepository;
import de.schliweb.makeacopy.utils.infra.FeatureFlags;
import de.schliweb.makeacopy.utils.ui.A11yUtils;
import de.schliweb.makeacopy.utils.ui.DialogUtils;
import de.schliweb.makeacopy.utils.ui.UIUtils;
import java.util.List;
import javax.inject.Inject;

/**
 * A Fragment that displays a library of scans. Provides functionality to manage and view scanned
 * entities. It includes features like displaying the list of scans, indexing existing scans, and
 * navigating to scan details or other collections.
 *
 * <p>This fragment relies on the {@link ScansAdapter} for displaying scan data and interacts with
 * the scan repository to fetch or index scan data.
 *
 * <p>Features: - Displays a list of scans with details. - Indicates empty state when no scans are
 * available. - Provides navigation to other collections. - Supports re-indexing of existing scans
 * to populate the library dynamically.
 *
 * <p>Lifecycle: - Initializes views and adapters in `onCreateView`. - Retrieves collection data
 * during runtime using `loadDataAsync`.
 *
 * <p>Navigation: - Navigates to scan details when an item in the list is clicked. - Provides a
 * button to navigate to other collections.
 *
 * <p>Data Handling: - Loads scan data asynchronously. - Uses injected repositories to fetch scan
 * data. - Dynamically updates the RecyclerView through `ScansAdapter`.
 *
 * <p>Configuration Dependencies: - Requires `BuildConfig.FEATURE_SCAN_LIBRARY` to enable scan
 * library functionality. - Provides feedback to users via Toast messages when features are
 * unavailable or actions complete.
 */
@AndroidEntryPoint
public class ScansLibraryFragment extends Fragment {

  @Inject ScansRepository scansRepository;
  @Inject CollectionsRepository collectionsRepository;

  private RecyclerView list;
  private View progress;
  private View emptyText;
  private View buttonOpenCollections;
  private View buttonContainer;
  private View backButton;
  private View buttonIndexExistingIcon;
  private View buttonCleanupSettings;
  private View buttonSelectMode;
  private View selectionToolbar;
  private View buttonSelectAllToggle;
  private android.widget.TextView textSelectedCount;
  private View buttonExitSelection;
  private android.widget.Button buttonDeleteSelected;
  private android.widget.TextView titleCollection;
  private ScansAdapter adapter;
  private String collectionIdArg;
  private String collectionNameArg;

  @Nullable
  @Override
  public View onCreateView(
      @NonNull LayoutInflater inflater,
      @Nullable ViewGroup container,
      @Nullable Bundle savedInstanceState) {
    View root = inflater.inflate(R.layout.fragment_scans_library, container, false);
    list = root.findViewById(R.id.list);
    progress = root.findViewById(R.id.progress);
    emptyText = root.findViewById(R.id.emptyText);
    buttonOpenCollections = root.findViewById(R.id.buttonOpenCollections);
    buttonContainer = root.findViewById(R.id.button_container);
    backButton = root.findViewById(R.id.button_back);
    titleCollection = root.findViewById(R.id.titleCollection);
    buttonIndexExistingIcon = root.findViewById(R.id.buttonIndexExistingIcon);
    buttonCleanupSettings = root.findViewById(R.id.buttonCleanupSettings);
    buttonSelectMode = root.findViewById(R.id.buttonSelectMode);
    selectionToolbar = root.findViewById(R.id.selectionToolbar);
    buttonSelectAllToggle = root.findViewById(R.id.buttonSelectAllToggle);
    textSelectedCount = root.findViewById(R.id.textSelectedCount);
    buttonExitSelection = root.findViewById(R.id.buttonExitSelection);
    buttonDeleteSelected = root.findViewById(R.id.buttonDeleteSelected);

    // Apply system insets: the AppBarLayout (fitsSystemWindows) handles the status bar; the
    // bottom button container gets the nav bar inset added to its base margin.
    de.schliweb.makeacopy.utils.ui.UIUtils.adjustMarginForSystemInsets(buttonContainer, 8);
    androidx.core.view.ViewCompat.setOnApplyWindowInsetsListener(
        root,
        (v, insets) -> {
          de.schliweb.makeacopy.utils.ui.UIUtils.adjustMarginForSystemInsets(buttonContainer, 8);
          return insets;
        });
    root.addOnAttachStateChangeListener(
        new View.OnAttachStateChangeListener() {
          @Override
          public void onViewAttachedToWindow(@NonNull View v) {
            de.schliweb.makeacopy.utils.ui.UIUtils.adjustMarginForSystemInsets(buttonContainer, 8);
            androidx.core.view.ViewCompat.requestApplyInsets(v);
          }

          @Override
          public void onViewDetachedFromWindow(@NonNull View v) {}
        });

    adapter =
        new ScansAdapter(
            item -> {
              try {
                android.os.Bundle args = new android.os.Bundle();
                args.putString("scanId", item.id);
                androidx.navigation.Navigation.findNavController(requireView())
                    .navigate(R.id.navigation_scan_details, args);
              } catch (Throwable t) {
                UIUtils.showToast(
                    requireContext(),
                    R.string.navigation_failed,
                    android.widget.Toast.LENGTH_SHORT);
              }
            });
    adapter.setOnSelectionChangedListener(this::onSelectionChanged);

    if (buttonSelectMode != null) {
      buttonSelectMode.setOnClickListener(v -> setSelectionModeActive(!adapter.isSelectionMode()));
    }
    if (buttonExitSelection != null) {
      buttonExitSelection.setOnClickListener(v -> setSelectionModeActive(false));
    }
    if (buttonSelectAllToggle != null) {
      buttonSelectAllToggle.setOnClickListener(
          v -> {
            if (adapter.getSelectedCount() > 0) {
              adapter.clearSelection();
            } else {
              adapter.selectAll();
            }
          });
    }
    if (buttonDeleteSelected != null) {
      buttonDeleteSelected.setOnClickListener(v -> confirmDeleteSelected());
    }

    // Back button action
    if (backButton != null) {
      backButton.setOnClickListener(
          v -> {
            try {
              Navigation.findNavController(requireView()).navigateUp();
            } catch (Throwable t) {
              requireActivity().getOnBackPressedDispatcher().onBackPressed();
            }
          });
    }
    list.setAdapter(adapter);
    // Adaptive grid: one column per ~360dp of available width (cards carry their own margins)
    int spanCount = Math.max(1, (int) (getResources().getConfiguration().screenWidthDp / 360f));
    list.setLayoutManager(
        new androidx.recyclerview.widget.GridLayoutManager(requireContext(), spanCount));

    // Material SearchBar + SearchView: filter loaded metadata and augment it with OCR FTS matches
    com.google.android.material.search.SearchBar searchBar = root.findViewById(R.id.search_bar);
    com.google.android.material.search.SearchView searchView = root.findViewById(R.id.search_view);
    RecyclerView searchResults = root.findViewById(R.id.search_results);
    if (searchBar != null && searchView != null && searchResults != null) {
      searchView.setupWithSearchBar(searchBar);
      searchResults.setLayoutManager(
          new androidx.recyclerview.widget.LinearLayoutManager(requireContext()));
      // Share the adapter so results stay in sync without duplicating data
      searchResults.setAdapter(adapter);
      searchView
          .getEditText()
          .addTextChangedListener(
              new android.text.TextWatcher() {
                @Override
                public void beforeTextChanged(CharSequence s, int st, int c, int a) {}

                @Override
                public void onTextChanged(CharSequence s, int st, int b, int c) {}

                @Override
                public void afterTextChanged(android.text.Editable s) {
                  String query = s == null ? null : s.toString();
                  adapter.filter(query);
                  loadOcrMatchesAsync(query);
                }
              });
      searchView.addTransitionListener(
          (sv, previousState, newState) -> {
            if (newState == com.google.android.material.search.SearchView.TransitionState.HIDDEN) {
              adapter.filter(null);
              adapter.setOcrMatches(null);
            }
          });
    }

    // Empty-state call to action: jump straight to the camera to create the first scan
    View scanFirst = root.findViewById(R.id.buttonScanFirst);
    if (scanFirst != null) {
      scanFirst.setOnClickListener(
          v -> {
            try {
              Navigation.findNavController(requireView()).navigate(R.id.navigation_camera);
            } catch (Throwable t) {
              UIUtils.showToast(
                  requireContext(), R.string.navigation_failed, android.widget.Toast.LENGTH_SHORT);
            }
          });
    }

    if (!FeatureFlags.isScanLibraryEnable()) {
      UIUtils.showToast(
          requireContext(),
          R.string.feature_scan_library_disabled,
          android.widget.Toast.LENGTH_SHORT);
      // Optionally navigate up if embedded in backstack
      requireActivity().getOnBackPressedDispatcher().onBackPressed();
      return root;
    }

    if (getArguments() != null) {
      collectionIdArg = getArguments().getString("collectionId");
      collectionNameArg = getArguments().getString("collectionName");
    }
    // If no collection specified, show all completed documents (no collection title)
    if (collectionIdArg == null) {
      if (titleCollection != null) {
        titleCollection.setVisibility(View.GONE);
      }
      loadDataAsync();
    } else {
      // Show collection name as title when provided
      if (titleCollection != null) {
        if (collectionNameArg != null && !collectionNameArg.trim().isEmpty()) {
          titleCollection.setText(collectionNameArg);
          titleCollection.setVisibility(View.VISIBLE);
        } else {
          titleCollection.setVisibility(View.GONE);
        }
      }
      loadDataAsync();
    }
    // When viewing a specific collection, enable long-press to remove an item from that collection
    if (collectionIdArg != null) {
      adapter.setOnItemLongClickListener(
          item -> {
            String display =
                (item.title != null && !item.title.trim().isEmpty()) ? item.title : item.id;
            final androidx.appcompat.app.AlertDialog dialog =
                new com.google.android.material.dialog.MaterialAlertDialogBuilder(requireContext())
                    .setTitle(display)
                    .setMessage(R.string.remove_from_collection_question)
                    .setPositiveButton(
                        R.string.ok,
                        (d, w) -> {
                          showLoading(true);
                          new Thread(
                                  () -> {
                                    try {
                                      collectionsRepository.removeScanFromCollection(
                                          requireContext(), item.id, collectionIdArg);
                                    } catch (Throwable ignore) {
                                      // Best-effort; failure is non-critical
                                    }
                                    if (!isAdded()) return;
                                    requireActivity()
                                        .runOnUiThread(
                                            () -> {
                                              UIUtils.showToast(
                                                  requireContext(),
                                                  R.string.removed_from_collection,
                                                  android.widget.Toast.LENGTH_SHORT);
                                              loadDataAsync();
                                            });
                                  })
                              .start();
                        })
                    .setNegativeButton(android.R.string.cancel, null)
                    .create();
            dialog.setOnShowListener(
                d -> {
                  try {
                    DialogUtils.improveAlertDialogButtonContrastForNight(dialog, requireContext());
                  } catch (Throwable ignore) {
                    // Best-effort; failure is non-critical
                  }
                });
            dialog.show();
          });
    }

    buttonOpenCollections.setOnClickListener(
        v -> {
          try {
            Navigation.findNavController(requireView()).navigate(R.id.navigation_collections);
          } catch (Throwable t) {
            UIUtils.showToast(
                requireContext(), R.string.navigation_failed, android.widget.Toast.LENGTH_SHORT);
          }
        });
    if (buttonIndexExistingIcon != null) {
      buttonIndexExistingIcon.setOnClickListener(
          v -> {
            showLoading(true);
            new Thread(
                    () -> {
                      int n = 0;
                      try {
                        n =
                            ExistingScansIndexer.runIncremental(
                                requireContext(), scansRepository, collectionsRepository);
                      } catch (Throwable ignore) {
                        // Best-effort; failure is non-critical
                      }
                      final int finalN = n;
                      if (!isAdded()) return;
                      requireActivity()
                          .runOnUiThread(
                              () -> {
                                // Build a short user message and provide both Toast and A11y
                                // announcement
                                CharSequence msg;
                                if (finalN > 0) {
                                  msg = getString(R.string.indexed_new_items, finalN);
                                  UIUtils.showToast(
                                      requireContext(),
                                      msg.toString(),
                                      android.widget.Toast.LENGTH_SHORT);
                                } else {
                                  msg = getString(R.string.nothing_new_to_index);
                                  UIUtils.showToast(
                                      requireContext(),
                                      msg.toString(),
                                      android.widget.Toast.LENGTH_SHORT);
                                }
                                // Announce for accessibility so screen reader users receive a
                                // concise summary
                                announceText(msg);
                                loadDataAsync();
                              });
                    })
                .start();
          });
    }

    if (buttonCleanupSettings != null) {
      buttonCleanupSettings.setOnClickListener(v -> showCleanupSettingsDialog());
    }

    // loadDataAsync() already called above depending on collection presence
    return root;
  }

  private void showCleanupSettingsDialog() {
    DialogUtils.showCleanupSettingsDialog(requireContext());
  }

  /**
   * Announces a short message for accessibility users via the root view, centralized through
   * A11yUtils to avoid deprecated direct calls.
   */
  private void announceText(@NonNull CharSequence text) {
    View root = getView();
    if (root == null) return;
    root.setContentDescription(text);
    A11yUtils.announce(root, text);
  }

  private void loadDataAsync() {
    showLoading(true);
    final android.content.Context appCtx = requireContext().getApplicationContext();
    new Thread(
            () -> {
              List<ScanEntity> data;
              try {
                if (collectionIdArg == null) {
                  data = scansRepository.getAllScans(appCtx);
                  // On home screen: hide single-page CompletedScanEntry items from the registry.
                  // We detect these by a lightweight marker in sourceMetaJson:
                  // {"kind":"CompletedScanEntry"}
                  if (data != null && !data.isEmpty()) {
                    java.util.ArrayList<ScanEntity> filtered =
                        new java.util.ArrayList<>(data.size());
                    for (ScanEntity se : data) {
                      if (se == null) continue;
                      String sm = se.sourceMetaJson;
                      // Keep if not explicitly marked as CompletedScanEntry
                      if (sm == null || sm.isEmpty() || !sm.contains("\"CompletedScanEntry\"")) {
                        filtered.add(se);
                      }
                    }
                    data = filtered;
                  }
                } else {
                  data = scansRepository.getScansForCollection(appCtx, collectionIdArg);
                }
              } catch (Throwable t) {
                data = java.util.Collections.emptyList();
              }
              final List<ScanEntity> finalData = data;
              // Build memberships: scanId -> list of collection names
              final java.util.Map<String, java.util.List<String>> memberships =
                  new java.util.LinkedHashMap<>();
              try {
                de.schliweb.makeacopy.data.library.CollectionsRepository cr = collectionsRepository;
                if (finalData != null) {
                  for (ScanEntity se : finalData) {
                    if (se == null) continue;
                    java.util.List<de.schliweb.makeacopy.data.library.CollectionEntity> cols =
                        cr.getCollectionsForScan(appCtx, se.id);
                    if (cols != null && !cols.isEmpty()) {
                      java.util.ArrayList<String> names = new java.util.ArrayList<>(cols.size());
                      for (de.schliweb.makeacopy.data.library.CollectionEntity c : cols) {
                        if (c != null && c.name != null && !c.name.trim().isEmpty())
                          names.add(c.name);
                      }
                      if (!names.isEmpty()) memberships.put(se.id, names);
                    }
                  }
                }
              } catch (Throwable ignore) {
                // Best-effort; failure is non-critical
              }
              final boolean finalShowIndexBtn = FeatureFlags.isScanLibraryEnable();
              if (!isAdded()) return;
              requireActivity()
                  .runOnUiThread(
                      () -> {
                        adapter.setMemberships(memberships);
                        adapter.submitList(finalData);
                        showLoading(false);
                        boolean isEmpty = (finalData == null || finalData.isEmpty());
                        emptyText.setVisibility(isEmpty ? View.VISIBLE : View.GONE);
                        // Hide the (empty) RecyclerView so it does not overlap the empty-state
                        // call-to-action and swallow its touch events
                        list.setVisibility(isEmpty ? View.GONE : View.VISIBLE);
                        if (buttonIndexExistingIcon != null) {
                          buttonIndexExistingIcon.setVisibility(
                              finalShowIndexBtn ? View.VISIBLE : View.GONE);
                        }
                      });
            })
        .start();
  }

  private void loadOcrMatchesAsync(String query) {
    String trimmed = query == null ? "" : query.trim();
    if (trimmed.isEmpty()) {
      adapter.setOcrMatches(null);
      return;
    }
    new Thread(
            () -> {
              List<de.schliweb.makeacopy.data.library.ScanSearchResult> results =
                  scansRepository.searchOcrText(requireContext(), trimmed, 50);
              if (!isAdded()) return;
              requireActivity().runOnUiThread(() -> adapter.setOcrMatches(results));
            })
        .start();
  }

  /**
   * Toggles multi-select mode on the adapter and swaps the bottom toolbar/icon row between the
   * normal actions (Back / Open collections / cleanup icons) and the selection actions (select
   * all, count, exit, delete selected).
   */
  private void setSelectionModeActive(boolean active) {
    adapter.setSelectionMode(active);
    if (selectionToolbar != null) {
      selectionToolbar.setVisibility(active ? View.VISIBLE : View.GONE);
    }
    if (buttonSelectMode != null) {
      buttonSelectMode.setVisibility(active ? View.GONE : View.VISIBLE);
    }
    if (buttonIndexExistingIcon != null) {
      buttonIndexExistingIcon.setVisibility(
          !active && FeatureFlags.isScanLibraryEnable() ? View.VISIBLE : View.GONE);
    }
    if (buttonCleanupSettings != null) {
      buttonCleanupSettings.setVisibility(active ? View.GONE : View.VISIBLE);
    }
    if (backButton != null) {
      backButton.setVisibility(active ? View.GONE : View.VISIBLE);
    }
    if (buttonOpenCollections != null) {
      buttonOpenCollections.setVisibility(active ? View.GONE : View.VISIBLE);
    }
    if (buttonDeleteSelected != null) {
      buttonDeleteSelected.setVisibility(active ? View.VISIBLE : View.GONE);
    }
    onSelectionChanged(adapter.getSelectedCount());
  }

  private void onSelectionChanged(int count) {
    if (textSelectedCount != null) {
      textSelectedCount.setText(getString(R.string.selected_count, count));
    }
    if (buttonDeleteSelected != null) {
      buttonDeleteSelected.setEnabled(count > 0);
      buttonDeleteSelected.setText(getString(R.string.delete_selected, count));
    }
  }

  private void confirmDeleteSelected() {
    final List<String> ids = adapter.getSelectedIds();
    if (ids.isEmpty()) return;
    final int count = ids.size();
    String message =
        getResources().getQuantityString(R.plurals.delete_selected_warning, count, count);
    final androidx.appcompat.app.AlertDialog dialog =
        new com.google.android.material.dialog.MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.confirm)
            .setMessage(message)
            .setPositiveButton(R.string.delete, (d, w) -> doDeleteSelected(ids))
            .setNegativeButton(android.R.string.cancel, null)
            .create();
    dialog.setOnShowListener(
        d -> {
          try {
            DialogUtils.improveAlertDialogButtonContrastForNight(dialog, requireContext());
          } catch (Throwable ignore) {
            // Best-effort; failure is non-critical
          }
        });
    dialog.show();
  }

  private void doDeleteSelected(List<String> ids) {
    showLoading(true);
    final android.content.Context appCtx = requireContext().getApplicationContext();
    new Thread(
            () -> {
              try {
                scansRepository.deleteScans(appCtx, ids);
              } catch (Throwable ignore) {
                // Best-effort; failure is non-critical
              }
              if (!isAdded()) return;
              requireActivity()
                  .runOnUiThread(
                      () -> {
                        UIUtils.showToast(
                            requireContext(), R.string.deleted, android.widget.Toast.LENGTH_SHORT);
                        setSelectionModeActive(false);
                        loadDataAsync();
                      });
            })
        .start();
  }

  private void showLoading(boolean show) {
    progress.setVisibility(show ? View.VISIBLE : View.GONE);
    list.setVisibility(show ? View.GONE : View.VISIBLE);
    emptyText.setVisibility(View.GONE);
  }
}
