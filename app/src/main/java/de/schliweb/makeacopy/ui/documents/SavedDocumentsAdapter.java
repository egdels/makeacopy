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

import android.text.format.DateUtils;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;
import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;
import de.schliweb.makeacopy.R;
import de.schliweb.makeacopy.data.DocumentSession;
import java.util.ArrayList;
import java.util.List;

/**
 * Minimal list adapter for saved {@link DocumentSession}s (Session 4). Shows title (or a generic
 * default), page count, relative updatedAt and an "active" badge; deliberately no thumbnails and no
 * OCR/export logic — the saved documents list stays a small session browser.
 */
class SavedDocumentsAdapter extends RecyclerView.Adapter<SavedDocumentsAdapter.Holder> {

  /** Row callbacks: tap opens the document, long-press offers discard. */
  interface Callbacks {
    void onOpen(@NonNull DocumentSession session);

    void onLongPress(@NonNull DocumentSession session);
  }

  private final Callbacks callbacks;
  private final List<DocumentSession> items = new ArrayList<>();
  private String activeDocumentId;

  SavedDocumentsAdapter(Callbacks callbacks) {
    this.callbacks = callbacks;
  }

  void submitList(List<DocumentSession> sessions, String activeDocumentId) {
    items.clear();
    if (sessions != null) items.addAll(sessions);
    this.activeDocumentId = activeDocumentId;
    notifyDataSetChanged();
  }

  @NonNull
  @Override
  public Holder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
    View v =
        LayoutInflater.from(parent.getContext())
            .inflate(R.layout.item_saved_document, parent, false);
    return new Holder(v);
  }

  @Override
  public void onBindViewHolder(@NonNull Holder holder, int position) {
    DocumentSession s = items.get(position);
    android.content.Context ctx = holder.itemView.getContext();
    String title =
        (s.title() != null && !s.title().trim().isEmpty())
            ? s.title()
            : ctx.getString(R.string.saved_documents_default_title);
    holder.title.setText(title);
    String pages = ctx.getString(R.string.saved_documents_page_count, s.pageIds().size());
    CharSequence when =
        DateUtils.getRelativeTimeSpanString(
            s.updatedAt(), System.currentTimeMillis(), DateUtils.MINUTE_IN_MILLIS);
    holder.subtitle.setText(pages + " · " + when);
    boolean active = s.documentId() != null && s.documentId().equals(activeDocumentId);
    holder.activeBadge.setVisibility(active ? View.VISIBLE : View.GONE);
    holder.itemView.setOnClickListener(v -> callbacks.onOpen(s));
    holder.itemView.setOnLongClickListener(
        v -> {
          callbacks.onLongPress(s);
          return true;
        });
  }

  @Override
  public int getItemCount() {
    return items.size();
  }

  static class Holder extends RecyclerView.ViewHolder {
    final TextView title;
    final TextView subtitle;
    final TextView activeBadge;

    Holder(@NonNull View itemView) {
      super(itemView);
      title = itemView.findViewById(R.id.document_title);
      subtitle = itemView.findViewById(R.id.document_subtitle);
      activeBadge = itemView.findViewById(R.id.document_active_badge);
    }
  }
}
