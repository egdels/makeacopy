/*
 * Copyright 2026 Christian Kierdorf
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 */
package de.schliweb.makeacopy.ui;

import android.content.Context;
import android.content.Intent;
import android.graphics.Typeface;
import android.os.Bundle;
import android.text.SpannableStringBuilder;
import android.text.Spanned;
import android.text.method.LinkMovementMethod;
import android.text.style.BulletSpan;
import android.text.style.StyleSpan;
import android.text.style.TypefaceSpan;
import android.text.style.URLSpan;
import android.text.util.Linkify;
import android.util.TypedValue;
import android.widget.LinearLayout;
import android.widget.TextView;
import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowCompat;
import androidx.core.view.WindowInsetsCompat;
import de.schliweb.makeacopy.databinding.ActivityDocumentBinding;
import de.schliweb.makeacopy.utils.docs.MarkdownDocument;
import de.schliweb.makeacopy.utils.docs.MarkdownDocument.Block;
import de.schliweb.makeacopy.utils.docs.MarkdownDocument.Span;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.regex.Pattern;

/**
 * Shows one Markdown file from assets/docs/: the help in the app's language or the privacy policy.
 * app/build.gradle copies them from help/ and docs/_includes/ (copyDocs), so the text is maintained
 * in one place for the web page and the app. Google Play wants the privacy policy reachable from
 * inside the app; as text it keeps the app offline.
 */
public class DocumentActivity extends AppCompatActivity {

  private static final String DOCS_DIR = "docs";
  private static final String PRIVACY_POLICY = "PRIVACY.md";
  private static final String HELP = "HELP.md";

  private static final String EXTRA_ASSET = "asset";

  /**
   * A web address with a path, such as github.com/egdels/makeacopy, without punctuation that ends
   * the sentence. Not Patterns.WEB_URL: that takes file names like CHANGELOG.md for a domain (.md
   * is Moldova's) and keeps a full stop after the address.
   */
  private static final Pattern WEB_ADDRESS =
      Pattern.compile("(https://)?[\\w-]+(\\.[\\w-]+)+/[^\\s]*[^\\s.,;:)]");

  public static void showPrivacyPolicy(@NonNull Context context) {
    start(context, PRIVACY_POLICY);
  }

  /**
   * Opens the help in the app's current language (help/HELP.de.md for German), or the English
   * HELP.md when there is no translation for that language.
   */
  public static void showHelp(@NonNull Context context) {
    String language = context.getResources().getConfiguration().getLocales().get(0).getLanguage();
    String localized = "HELP." + language + ".md";
    String asset = HELP;
    try {
      String[] files = context.getAssets().list(DOCS_DIR);
      if (files != null && Arrays.asList(files).contains(localized)) asset = localized;
    } catch (IOException ignore) {
      // Fall back to the English help
    }
    start(context, asset);
  }

  private static void start(Context context, String asset) {
    context.startActivity(new Intent(context, DocumentActivity.class).putExtra(EXTRA_ASSET, asset));
  }

  @Override
  protected void onCreate(Bundle savedInstanceState) {
    super.onCreate(savedInstanceState);
    // Edge-to-edge like MainActivity: the toolbar takes the status bar, the text the rest
    WindowCompat.setDecorFitsSystemWindows(getWindow(), false);
    ActivityDocumentBinding binding = ActivityDocumentBinding.inflate(getLayoutInflater());
    setContentView(binding.getRoot());
    ViewCompat.setOnApplyWindowInsetsListener(
        binding.getRoot(),
        (v, insets) -> {
          Insets bars =
              insets.getInsets(
                  WindowInsetsCompat.Type.systemBars() | WindowInsetsCompat.Type.displayCutout());
          binding.toolbar.setPadding(bars.left, bars.top, bars.right, 0);
          binding.documentScroll.setPadding(bars.left, 0, bars.right, bars.bottom);
          return insets;
        });
    setSupportActionBar(binding.toolbar);
    if (getSupportActionBar() != null) {
      getSupportActionBar().setDisplayHomeAsUpEnabled(true);
    }

    String asset = getIntent().getStringExtra(EXTRA_ASSET);
    MarkdownDocument document =
        MarkdownDocument.parse(readAsset(asset != null ? asset : HELP), "MakeACopy");
    setTitle(document.title());
    for (Block block : document.blocks()) {
      binding.documentContent.addView(blockView(block));
    }
  }

  @Override
  public boolean onSupportNavigateUp() {
    finish();
    return true;
  }

  private String readAsset(String asset) {
    // Not InputStream.readAllBytes: that needs API 33, minSdk is 29
    try (InputStream in = getAssets().open(DOCS_DIR + "/" + asset)) {
      ByteArrayOutputStream bytes = new ByteArrayOutputStream();
      byte[] buffer = new byte[8192];
      for (int n; (n = in.read(buffer)) != -1; ) {
        bytes.write(buffer, 0, n);
      }
      return bytes.toString(StandardCharsets.UTF_8.name());
    } catch (IOException e) {
      // The files are part of the APK (see copyDocs in app/build.gradle); missing means a broken
      // build.
      throw new UncheckedIOException(e);
    }
  }

  private TextView blockView(Block block) {
    TextView view = new TextView(this);
    SpannableStringBuilder text = styled(block);
    LinearLayout.LayoutParams params =
        new LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
    switch (block.type()) {
      case HEADING -> {
        view.setTextAppearance(
            com.google.android.material.R.style.TextAppearance_Material3_TitleMedium);
        ViewCompat.setAccessibilityHeading(view, true);
        params.topMargin = dp(20);
        params.bottomMargin = dp(4);
      }
      case BULLET -> {
        view.setTextAppearance(
            com.google.android.material.R.style.TextAppearance_Material3_BodyMedium);
        text.setSpan(new BulletSpan(dp(10)), 0, text.length(), Spanned.SPAN_INCLUSIVE_EXCLUSIVE);
        params.setMarginStart(dp(4));
        params.topMargin = dp(6);
      }
      default -> {
        view.setTextAppearance(
            com.google.android.material.R.style.TextAppearance_Material3_BodyMedium);
        params.topMargin = dp(10);
      }
    }
    view.setLayoutParams(params);
    // The files name their addresses as plain text; make them tappable. The mask variant of
    // addLinks drops existing links, so it has to run before the web address pattern.
    Linkify.addLinks(text, Linkify.EMAIL_ADDRESSES);
    Linkify.addLinks(text, WEB_ADDRESS, "https://");
    if (text.getSpans(0, text.length(), URLSpan.class).length > 0) {
      view.setMovementMethod(LinkMovementMethod.getInstance());
    }
    view.setText(text);
    return view;
  }

  private static SpannableStringBuilder styled(Block block) {
    SpannableStringBuilder text = new SpannableStringBuilder();
    for (Span span : block.spans()) {
      int start = text.length();
      text.append(span.text());
      int end = text.length();
      int style = (span.bold() ? Typeface.BOLD : 0) | (span.italic() ? Typeface.ITALIC : 0);
      if (style != 0) {
        text.setSpan(new StyleSpan(style), start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
      }
      if (span.code()) {
        text.setSpan(new TypefaceSpan("monospace"), start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
      }
    }
    return text;
  }

  private int dp(int value) {
    return Math.round(
        TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_DIP, value, getResources().getDisplayMetrics()));
  }
}
