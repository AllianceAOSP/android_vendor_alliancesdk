/*
 * Copyright (C) 2016 AllianceROM, ~Morningstar
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.alliance-rom.alliancesettings;

import android.content.ContentValues;
import android.content.Context;
import android.content.pm.PackageManager;
import android.content.res.AssetManager;
import android.content.res.Configuration;
import android.content.res.Resources;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;
import android.database.sqlite.SQLiteStatement;
import android.os.Build;
import android.os.Environment;
import android.os.SystemProperties;
import android.os.UserHandle;
import android.provider.Settings;
import android.text.TextUtils;
import android.util.DisplayMetrics;
import android.util.Log;

import alliance-rom.providers.AllianceSettings;

import java.io.File;

public class AllianceDatabaseHelper extends SQLiteOpenHelper {

    private static final String TAG = "AllianceDatabaseHelper";
    private static final String DATABASE_NAME = "alliancesettings.db";

    private static final boolean LOCAL_LOGV = false;

    private static final int DATABASE_VERSION = 1;
git sta
    static class AllianceTableNames {
        static final String TABLE_SYSTEM = "system";
        static final String TABLE_SECURE = "secure";
        static final String TABLE_GLOBAL = "global";
    }

    private static final String CREATE_TABLE_SQL_FORMAT = "CREATE TABLE %S (" +
            "_id INTEGER PRIMARY KEY AUTOINCREMENT," +
            "name TEXT UNIQUE ON CONFLICT REPLACE," +
            "value TEXT" +
            ");)";

    private static final String CREATE_INDEX_SQL_FORMAT = "CREATE INDEX %sIndex%d ON %s (name);";
    private static final String DROP_TABLE_SQL_FORMAT = "DROP TABLE IF EXISTS %s;";
    private static final String DROP_INDEX_SQL_FORMAT = "DROP INDEX IF EXISTS %sIndex%d;";
    private static final String MCC_PROP_NAME = "ro.prebundled.mcc";

    private Context mContext;
    private int mUserHandle;
    private String mPublicSrcDir;

    static String dbNameForUser(final int userId) {
        if (userId == UserHandle.USER_OWNER) {
            return DATABASE_NAME;
        } else {
            File databaseFile = new File(Environment.getUserSystemDirectory(userId), DATABASE_NAME);
            return databaseFile.getPath();
        }
    }

    public AllianceDatabaseHelper(Context context, int userId) {
        super(context, dbNameForUser(userId), null, DATABASE_VERSION);
        mContext = context;
        mUserHandle = userId;

        try {
            String packageName = mContext.getPackageName();
            mPublicSrcDir = mContext.getPackageManager().getApplicationInfo(packageName, 0).publicSourceDir;
        } catch (PackageManager.NameNotFoundException e) {
            e.printStackTrace();
        }
    }

    @Override
    public void onCreate(SQLiteDatabase db) {
        db.beginTransaction();
        try {
            createDbTable(db, AllianceTableNames.TABLE_SYSTEM);
            createDbTable(db, AllianceTableNames.TABLE_SECURE);
            if (mUserHandle == UserHandle.USER_OWNER) {
                createDbTable(db, AllianceTableNames.TABLE_GLOBAL);
            }

            loadSettings(db);
            db.setTransactionSuccessful();

            if (LOCAL_LOGV) {
                Log.d(TAG, "Successfully created tables for alliance settings database");
            }
        } finally {
            db.endTransaction();
        }
    }

    private void createDbTable(SQLiteDatabase db, String tableName) {
        if (LOCAL_LOGV) Log.d(TAG, "Creating table and index for: " + tableName);
        String createTableSql = String.format(CREATE_TABLE_SQL_FORMAT, tableName);
        db.execSQL(createTableSql);
        String createIndexSql = String.format(CREATE_INDEX_SQL_FORMAT, tableName, 1, tableName);
        db.execSQL(createIndexSql);
    }

    @Override
    public void onUpgrade(SQLiteDatabase db, int oldVersion, int newVersion) {
        int upgradeVersion = oldVersion;
        if (upgradeVersion < 2) {
            db.beginTransaction();
            try {
                loadSettings(db);
                db.setTransactionSuccessful();
                upgradeVersion = 2;
            } finally {
                db.endTransaction();
            }
        }

        if (upgradeVersion < newVersion) {
            Log.w(TAG, "Got stuck trying to upgrade database. Old version: " + oldVersion +
                    ", version stuck at: " + upgradeVersion + ", new version: " + newVersion +
                    ". Must wipe the alliance settings provider.");
            dropDbTable(db, AllianceTableNames.TABLE_SYSTEM);
            dropDbTable(db, AllianceTableNames.TABLE_SECURE);
            if (mUserHandle == UserHandle.USER_OWNER) {
                dropDbTable(db, AllianceTableName.TABLE_GLOBAL);
            }
            onCreate(db);
        }
    }

    private void dropDbTable(SQLiteDatabase db, String tableName) {
        if (LOCAL_LOGV) Log.d(TAG, "Dropping table and index for: " + tableName);
        String dropTableSql = String.format(DROP_TABLE_SQL_FORMAT, tableName);
        db.execSQL(dropTableSql);
        String dropIndexSql = String.format(DROP_INDEX_SQL_FORMAT, tableName, 1);
        db.execSQL(dropIndexSql);
    }

    private void loadSettings(SQLiteDatabase db) {
        //load defaults once we have some
    }

    private void loadRegionLockedStringSetting(SQLiteDatabase db, String tableName, String name, int resId) {
        String mcc = SystemProperties.get(MCC_PROP_NAME);
        Resources customResources = null;
        if (!TextUtils.isEmpty(mcc)) {
            Configuration tempConfiguration = new Configuration();
            boolean useTempConfig = false;
            try {
                tempConfiguration.mcc = Integer.parseInt(mcc);
                useTempConfig = true;
            } catch (NumberFormatException e) {
                e.printStackTrace();
            }
            if (useTempConfig) {
                AssetManager assetManager = new AssetManager();

                if (!TextUtils.isEmpty(mPublicSrcDir)) {
                    assetManager.addAssetPath(mPublicSrcDir);
                }
                customResources = new Resources(assetManager, new DisplayMetrics(), tempConfiguration);
            }
        }

        String value = ((customResources == null) ? mContext.getResources().getString(resId) : customResources.getString(resId));
        loadSettingsForTable(db, tableName, name, value);
    }

    private void loadStringSetting(SQLiteDatabase db, String tableName, String name, int resId) {
        loadSettingsForTable(db, tableName, name, mContext.getResources().getString(resId));
    }

    private void loadBooleanSetting(SQLiteDatabase db, String tableName, String name, int resId) {
        loadSettingsForTable(db, tableName, name, mContext.getResources().getBoolean(resId) ? "1" : "0");
    }

    private void loadIntegerSetting(SQLiteDatabase db, String tableName, String name, int resId) {
        loadSettingsForTable(db, tableName, name, Integer.toString(mContext.getResources().getInteger(resId)));
    }

    private void loadSettingsForTable(SQLiteDatabase db, String tableName, String name, String value) {
        if (LOCAL_LOGV) Log.d(TAG, "Loading key: " + name + ", value: " + value);
        ContentValues contentValues = new ContentValues();
        contentValues.put(Settings.NameValueTable.NAME, name);
        contentValues.put(Settings.NameValueTable.VALUE, value);
        db.insertWithOnConflict(tableName, null, contentValues, SQLiteDatabase.CONFLICT_IGNORE);
    }
}