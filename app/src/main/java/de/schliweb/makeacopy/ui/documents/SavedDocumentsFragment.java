/*
 * Copyright 2025 Christian Kierdorf
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 */
package de.schliweb.makeacopy.ui.documents;

import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.TextView;
import android.widget.Toast;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;
import androidx.lifecycle.ViewModelProvider;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;
import de.schliweb.makeacopy.R;
import de.schliweb.makeacopy.data.CompletedScansRegistry;
import de.schliweb.makeacopy.data.DocumentSession;
import de.schliweb.makeacopy.data.DocumentSessionRepository;
import de.schliweb.makeacopy.ui.export.session.CompletedScan;
import de.schliweb.makeacopy.ui.export.session.ExportSessionViewModel;
import de.schliweb.makeacopy.utils.ui.DialogUtils;
import de.schliweb.makeacopy.utils.ui.UIUtils;
import java.util.List;

/**
 * Session 4: minimal browser for persisted {@link DocumentSession}s ("Saved documents").
 *
 * <p>Responsibilities are deliberately limited to: load sessions from {@link
 * DocumentSessionRepository} (sorted by updatedAt descending, active session marked), open a
 * document (activate it, replace the runtime {@link ExportSessionViewModel} completely — pages of
 * different documents are never mixed — and navigate to the export/document screen) and optionally
 * discard a document via long-press. No OCR/export/edit logic lives here; no Room entity, no
 * migration — the list is fed directly from the JSON-backed repository.
 */
public class SavedDocumentsFragment extends Fragment implements SavedDocumentsAdapter.Callbacks {

  private RecyclerView recyclerView;
  private TextView emptyView;
  private SavedDocumentsAdapter adapter;
  private boolean opening;

  @Nullable
  @Override
  public View onCreateView(
      @NonNull LayoutInflater inflater,
      @Nullable ViewGroup container,
      @Nullable Bundle savedInstanceState) {
    View root = inflater.inflate(R.layout.fragment_saved_documents, container, false);
    recyclerView = root.findViewById(R.id.recycler);
    emptyView = root.findViewById(R.id.empty);
    Button buttonBack = root.findViewById(R.id.button_back);

    // Edge-to-edge insets: pad title with status bar, move bottom container above the nav bar.
    final View titleView = root.findViewById(R.id.title);
    final View bottomContainer = root.findViewById(R.id.button_container);
    final int titleOrigTop = titleView != null ? titleView.getPaddingTop() : 0;
    final int bottomOrigBottom = bottomContainer != null ? bottomContainer.getPaddingBottom() : 0;
    androidx.core.view.ViewCompat.setOnApplyWindowInsetsListener(
        root,
        (v, insets) -> {
          androidx.core.graphics.Insets sb =
              insets.getInsets(androidx.core.view.WindowInsetsCompat.Type.systemBars());
          if (titleView != null) {
            titleView.setPadding(
                titleView.getPaddingLeft(),
                titleOrigTop + sb.top,
                titleView.getPaddingRight(),
                titleView.getPaddingBottom());
          }
          if (bottomContainer != null) {
            ViewGroup.LayoutParams lp = bottomContainer.getLayoutParams();
            if (lp instanceof ViewGroup.MarginLayoutParams mlp) {
              mlp.bottomMargin = bottomOrigBottom + sb.bottom;
              bottomContainer.setLayoutParams(mlp);
            }
          }
          return insets;
        });

    recyclerView.setLayoutManager(new LinearLayoutManager(requireContext()));
    adapter = new SavedDocumentsAdapter(this);
    recyclerView.setAdapter(adapter);
    buttonBack.setOnClickListener(v -> navigateBack());

    loadItems();
    return root;
  }

  /** Loads all valid sessions (empty sessions are cleaned up first) sorted by updatedAt DESC. */
  private void loadItems() {
    final android.content.Context app = requireContext().getApplicationContext();
    new Thread(
            () -> {
              DocumentSessionRepository repo = DocumentSessionRepository.get(app);
              // Session 4 cleanup policy: empty sessions never accumulate in the list.
              try {
                repo.cleanupEmptySessions();
              } catch (Throwable ignore) {
                // Best-effort; failure is non-critical
              }
              final List<DocumentSession> sessions = repo.listSessions();
              final String activeId = repo.getActiveDocumentId();
              new Handler(Looper.getMainLooper())
                  .post(
                      () -> {
                        if (!isAdded()) return;
                        adapter.submitList(sessions, activeId);
                        boolean empty = sessions.isEmpty();
                        emptyView.setVisibility(empty ? View.VISIBLE : View.GONE);
                        recyclerView.setVisibility(empty ? View.GONE : View.VISIBLE);
                      });
            })
        .start();
  }

  /**
   * "Open document": activates the selected session (stable documentId, no copy), resolves its
   * ordered pages (missing pages are skipped and the session repaired) and replaces the runtime
   * session completely before navigating to the export/document screen.
   */
  @Override
  public void onOpen(@NonNull DocumentSession session) {
    if (opening) return;
    opening = true;
    final android.content.Context app = requireContext().getApplicationContext();
    final String docId = session.documentId();
    new Thread(
            () -> {
              DocumentSessionRepository repo = DocumentSessionRepository.get(app);
              DocumentSession activated = repo.setActive(docId);
              final List<CompletedScan> resolved =
                  repo.resolvePages(activated, CompletedScansRegistry.get(app));
              new Handler(Looper.getMainLooper())
                  .post(
                      () -> {
                        opening = false;
                        if (!isAdded()) return;
                        if (resolved.isEmpty()) {
                          // Broken session without a single valid page: never open it; the
                          // repaired-empty session is cleaned up and the list refreshed.
                          new Thread(
                                  () -> {
                                    try {
                                      repo.cleanupEmptySessions();
                                    } catch (Throwable ignore) {
                                      // Best-effort; failure is non-critical
                                    }
                                  })
                              .start();
                          UIUtils.showToast(
                              requireContext(),
                              R.string.saved_documents_open_failed,
                              Toast.LENGTH_SHORT);
                          loadItems();
                          return;
                        }
                        // Replace the runtime session completely — never mix documents.
                        ExportSessionViewModel sessionVm =
                            new ViewModelProvider(requireActivity())
                                .get(ExportSessionViewModel.class);
                        sessionVm.setInitial(null);
                        sessionVm.setDocumentId(docId);
                        sessionVm.addAll(resolved);
                        try {
                          androidx.navigation.Navigation.findNavController(requireView())
                              .navigate(R.id.navigation_export);
                        } catch (IllegalArgumentException | IllegalStateException ignored) {
                          // Best-effort; failure is non-critical
                        }
                      });
            })
        .start();
  }

  /** Long-press: explicit discard (Session 4 §18) — the session is deleted, pages are kept. */
  @Override
  public void onLongPress(@NonNull DocumentSession session) {
    final String docId = session.documentId();
    androidx.appcompat.app.AlertDialog dialog =
        new com.google.android.material.dialog.MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.saved_documents_discard_title)
            .setMessage(R.string.saved_documents_discard_message)
            .setPositiveButton(
                R.string.remove,
                (d, w) -> {
                  final android.content.Context app = requireContext().getApplicationContext();
                  // If the discarded document is the one currently loaded in the runtime
                  // session, clear the runtime state as well so no stale pages linger.
                  ExportSessionViewModel sessionVm =
                      new ViewModelProvider(requireActivity()).get(ExportSessionViewModel.class);
                  if (docId.equals(sessionVm.getDocumentId())) {
                    sessionVm.setDocumentId(null);
                    sessionVm.setInitial(null);
                  }
                  new Thread(
                          () -> {
                            try {
                              DocumentSessionRepository.get(app).delete(docId);
                            } catch (Throwable ignore) {
                              // Best-effort; failure is non-critical
                            }
                            new Handler(Looper.getMainLooper())
                                .post(
                                    () -> {
                                      if (!isAdded()) return;
                                      UIUtils.showToast(
                                          requireContext(),
                                          R.string.saved_documents_discarded_toast,
                                          Toast.LENGTH_SHORT);
                                      loadItems();
                                    });
                          })
                      .start();
                })
            .setNegativeButton(R.string.cancel, (d, w) -> d.dismiss())
            .create();
    dialog.setOnShowListener(
        dlg -> DialogUtils.improveAlertDialogButtonContrastForNight(dialog, requireContext()));
    dialog.show();
  }

  private void navigateBack() {
    try {
      androidx.navigation.Navigation.findNavController(requireView()).popBackStack();
    } catch (Throwable t) {
      getParentFragmentManager().popBackStack();
    }
  }
}
