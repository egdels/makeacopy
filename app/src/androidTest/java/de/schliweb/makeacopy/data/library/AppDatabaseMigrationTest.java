/*
 * Copyright 2025 Christian Kierdorf
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 */
package de.schliweb.makeacopy.data.library;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import androidx.room.Room;
import androidx.test.core.app.ApplicationProvider;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import java.util.UUID;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

@RunWith(AndroidJUnit4.class)
public class AppDatabaseMigrationTest {
  private static final String SCAN_ID = "scan-1";
  private static final int PAGE_INDEX = 0;

  private Context context;
  private String databaseName;
  private AppDatabase database;

  @Before
  public void setUp() {
    context = ApplicationProvider.getApplicationContext();
    databaseName = "migration-test-" + UUID.randomUUID() + ".db";
  }

  @After
  public void tearDown() {
    if (database != null) database.close();
    context.deleteDatabase(databaseName);
  }

  @Test
  public void migration1To2CreatesSearchTablesAndFtsTriggers() {
    createVersion1DatabaseWithScan();

    database =
        Room.databaseBuilder(context, AppDatabase.class, databaseName)
            .addMigrations(AppDatabase.MIGRATION_1_2)
            .allowMainThreadQueries()
            .build();
    assertNotNull(database.scanPageTextDao());
    // Room opens the database lazily; force the open so MIGRATION_1_2 actually runs,
    // then close Room before accessing the file with the framework SQLite API.
    database.getOpenHelper().getWritableDatabase();
    database.close();
    database = null;

    try (SQLiteDatabase migrated =
        SQLiteDatabase.openDatabase(
            context.getDatabasePath(databaseName).getPath(), null, SQLiteDatabase.OPEN_READWRITE)) {
      insertPageText(migrated);
      assertEquals(1, queryFtsCount(migrated, "alpha"));

      updatePageText(migrated);
      assertEquals(0, queryFtsCount(migrated, "alpha"));
      assertEquals(1, queryFtsCount(migrated, "beta"));

      migrated.delete(
          "scan_page_text",
          "scanId = ? AND pageIndex = ?",
          new String[] {SCAN_ID, String.valueOf(PAGE_INDEX)});
      assertEquals(0, queryFtsCount(migrated, "beta"));
    }
  }

  private void createVersion1DatabaseWithScan() {
    try (SQLiteDatabase db =
        SQLiteDatabase.openOrCreateDatabase(context.getDatabasePath(databaseName), null)) {
      db.execSQL("PRAGMA user_version = 1");
      db.execSQL(
          "CREATE TABLE scans (id TEXT NOT NULL PRIMARY KEY, title TEXT, createdAt INTEGER NOT NULL, pageCount INTEGER NOT NULL, coverPath TEXT, exportPathsJson TEXT, sourceMetaJson TEXT)");
      db.execSQL(
          "CREATE TABLE collections (id TEXT NOT NULL PRIMARY KEY, name TEXT NOT NULL, sortOrder INTEGER NOT NULL, createdAt INTEGER NOT NULL)");
      db.execSQL(
          "CREATE TABLE scan_collection_join (scanId TEXT NOT NULL, collectionId TEXT NOT NULL, addedAt INTEGER NOT NULL, PRIMARY KEY(scanId, collectionId))");
      db.execSQL(
          "CREATE INDEX index_scan_collection_join_collectionId ON scan_collection_join(collectionId)");

      ContentValues values = new ContentValues();
      values.put("id", SCAN_ID);
      values.put("title", "Invoice 2025");
      values.put("createdAt", 1L);
      values.put("pageCount", 1);
      db.insertOrThrow("scans", null, values);
    }
  }

  private void insertPageText(SQLiteDatabase db) {
    ContentValues values = new ContentValues();
    values.put("scanId", SCAN_ID);
    values.put("pageIndex", PAGE_INDEX);
    values.put("text", "alpha searchable text");
    values.put("indexedAt", 10L);
    values.put("updatedAt", 10L);
    db.insertOrThrow("scan_page_text", null, values);
  }

  private void updatePageText(SQLiteDatabase db) {
    ContentValues values = new ContentValues();
    values.put("text", "beta searchable text");
    db.update(
        "scan_page_text",
        values,
        "scanId = ? AND pageIndex = ?",
        new String[] {SCAN_ID, String.valueOf(PAGE_INDEX)});
  }

  private int queryFtsCount(SQLiteDatabase db, String query) {
    try (Cursor cursor =
        db.rawQuery(
            "SELECT COUNT(*) FROM scan_page_text_fts WHERE scan_page_text_fts MATCH ?",
            new String[] {query})) {
      cursor.moveToFirst();
      return cursor.getInt(0);
    }
  }
}