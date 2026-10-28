/*
 * Copyright 2026 Christian Kierdorf
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 */
package de.schliweb.makeacopy.di;

import android.content.Context;
import dagger.Module;
import dagger.Provides;
import dagger.hilt.InstallIn;
import dagger.hilt.android.qualifiers.ApplicationContext;
import dagger.hilt.components.SingletonComponent;
import de.schliweb.makeacopy.data.library.AppDatabase;
import de.schliweb.makeacopy.data.library.CollectionsDao;
import de.schliweb.makeacopy.data.library.CollectionsRepository;
import de.schliweb.makeacopy.data.library.DefaultCollectionsRepository;
import de.schliweb.makeacopy.data.library.DefaultScansRepository;
import de.schliweb.makeacopy.data.library.OcrSearchIndexer;
import de.schliweb.makeacopy.data.library.ScanCollectionJoinDao;
import de.schliweb.makeacopy.data.library.ScanPageTextDao;
import de.schliweb.makeacopy.data.library.ScansDao;
import de.schliweb.makeacopy.data.library.ScansRepository;
import javax.inject.Singleton;

/** Hilt module that provides database-related dependencies as singletons. */
@Module
@InstallIn(SingletonComponent.class)
public class DatabaseModule {

  @Provides
  @Singleton
  AppDatabase provideAppDatabase(@ApplicationContext Context context) {
    return AppDatabase.getInstance(context);
  }

  @Provides
  ScansDao provideScansDao(AppDatabase db) {
    return db.scansDao();
  }

  @Provides
  CollectionsDao provideCollectionsDao(AppDatabase db) {
    return db.collectionsDao();
  }

  @Provides
  ScanCollectionJoinDao provideScanCollectionJoinDao(AppDatabase db) {
    return db.scanCollectionJoinDao();
  }

  @Provides
  ScanPageTextDao provideScanPageTextDao(AppDatabase db) {
    return db.scanPageTextDao();
  }

  @Provides
  @Singleton
  ScansRepository provideScansRepository(
      ScansDao scansDao,
      ScanCollectionJoinDao joinDao,
      ScanPageTextDao scanPageTextDao,
      OcrSearchIndexer ocrSearchIndexer) {
    return new DefaultScansRepository(scansDao, joinDao, scanPageTextDao, ocrSearchIndexer);
  }

  @Provides
  @Singleton
  CollectionsRepository provideCollectionsRepository(
      CollectionsDao collectionsDao, ScanCollectionJoinDao joinDao, ScansDao scansDao) {
    return new DefaultCollectionsRepository(collectionsDao, joinDao, scansDao);
  }
}
