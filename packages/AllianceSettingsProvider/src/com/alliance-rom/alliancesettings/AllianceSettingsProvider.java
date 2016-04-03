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

import android.app.ActivityManager;
import android.content.BroadcastReceiver;
import android.content.ContentProvider;
import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.content.UriMatcher;
import android.content.pm.PackageManager;
import android.content.pm.UserInfo;
import android.database.AbstractCursor;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteException;
import android.database.sqlite.SQLiteQueryBuilder;
import android.net.Uri;
import android.os.Binder;
import android.os.Bundle;
import android.os.SystemProperties;
import android.os.UserHandle;
import android.os.UserManager;
import android.provider.Settings;
import android.text.TextUtils;
import android.util.Log;
import android.util.SparseArray;

import alliancerom.providers.AllianceSettings;

public class AllianceSettingsProvicer extends ContentProvider {

	private static final String TAG = "AllianceSettingsProvider";
	private static final String PREF_FILE_NAME = TAG;
	private static final String PREF_HAS_MIGRATED_ALLIANCE_SETTINGS = "has_migrated_alliance_settings";
	private static final String ITEM_MATCHER = "/*";
	private static final String NAME_SELECTION = Settings.NameValueTable.NAME + " = ?";

	private static final boolean LOCAL_LOGV = false;
	private static final boolean USER_CHECK_THROWS = true;

	private static final Bundle NULL_SETTING = Bundle.forPair("value", null);

	private static final UriMatcher sUriMatcher = new UriMatcher(UriMatcher.NO_MATCH);

	static {
		sUriMatcher.addURI(AllianceSettings.AUTHORITY,
				AllianceDatabaseHelper.AllianceTableNames.TABLE_SYSTEM, SYSTEM);
		sUriMatcher.addURI(AllianceSettings.AUTHORITY,
				AllianceDatabaseHelper.AllianceTableNames.TABLE_SECURE, SECURE);
		sUriMatcher.addURI(AllianceSettings.AUTHORITY,
				AllianceDatabaseHelper.AllianceTableNames.TABLE_GLOBAL, GLOBAL);
		sUriMatcher.addURI(AllianceSettings.AUTHORITY,
				AllianceDatabaseHelper.AllianceTableNames.TABLE_SYSTEM + ITEM_MATCHER, SYSTEM_ITEM_NAME);
		sUriMatcher.addURI(AllianceSettings.AUTHORITY,
				AllianceDatabaseHelper.AllianceTableNames.TABLE_SECURE + ITEM_MATCHER, SECURE_ITEM_NAME);
		sUriMatcher.addURI(AllianceSettings.AUTHORITY,
				AllianceDatabaseHelper.AllianceTableNames.TABLE_GLOBAL + ITEM_MATCHER, GLOBAL_ITEM_NAME);
	}

	protected final SparseArray<AllianceDatabaseHelper> mDbHelpers = new SparseArray<>();

	private static final int SYSTEM = 1;
	private static final int SECURE = 2;
	private static final int GLOBAL = 3;
	private static final int SYSTEM_ITEM_NAME = 4;
	private static final int SECURE_ITEM_NAME = 5;
	private static final int GLOBAL_ITEM_NAME = 6;

	private UserManager mUserManager;
	private Uri.Builder mUriBuilder;
	private SharedPreferences mPrefs;

	@Override
	public boolean onCreate() {
		if (LOCAL_LOGV) Log.d(TAG, "Creating AllianceSettingsProvider");
		mUserManager = UserManager.get(getContext());
		establishDbTracking(UserHandle.USER_OWNER);
		mUriBuilder = new Uri.Builder();
		mUriBuilder.scheme(ContentResolver.SCHEME_CONTENT);
		mUriBuilder.authority(AllianceSettings.AUTHORITY);
		mPrefs = getContext().getSharedPreferences(PREF_FILE_NAME, Context.MODE_PRIVATE);
		IntentFilter userFilter = new IntentFilter();
		userFilter.addAction(Intent.ACTION_USER_REMOVED);
		getContext().registerReceiver(new BroadcastReceiver() {
			@Override
			public void onReceive(Context context, Intent intent) {
				final int userId = intent.getIntExtra(Intent.EXTRA_USER_HANDLE, UserHandle.USER_OWNER);
				String action = intent.getAction();
				if (LOCAL_LOGV) Log.d(TAG, "Received intent: " + action + " for user: " + userId);
				if (action.equals(Intent.ACTION_USER_REMOVED)) {
					onUserRemoved(userId);
				}
			}
		}, userFilter);
		return true;
	}

	private void migrateAllianceSettingsForExistingUsersIfNeeded() {
		boolean hasMigratedAllianceSettings = mPrefs.getBoolean(PREF_HAS_MIGRATED_ALLIANCE_SETTINGS, false);
		if (!hasMigratedAllianceSettings) {
			long startTime = System.currentTimeMillis();
			for (UserInfo user : mUserManager.getUsers()) {
				migrateAllianceSettingsForUser(user.id);
			}
			mPrefs.edit().putBoolean(PREF_HAS_MIGRATED_ALLIANCE_SETTINGS, true).commit();
			long timeDiffMillis = System.currentTimeMillis() - startTime;
			if (LOCAL_LOGV) Log.d(TAG, "Migration finished in " + timeDiffMillis + " milliseconds");
		}
	}

	private void migrateAllianceSettingsForUser(int userId) {
		synchronized (this) {
			if (LOCAL_LOGV) Log.d(TAG, "Alliance settings will be migrated for user id: " + userId);
			int rowsMigrated = migrateAllianceSettingsForTable(userId, AllianceDatabaseHelper.AllianceTableNames.TABLE_SYSTEM,
				AllianceSettings.System.LEGACY_SYSTEM_SETTINGS);
			if (LOCAL_LOGV) Log.d(TAG, "Migrated " + rowsMigrated + " to Alliance system table");
			rowsMigrated = migrateAllianceSettingsForTable(userId, AllianceDatabaseHelper.AllianceTableNames.TABLE_SECURE,
				AllianceSettings.Secure.LEGACY_SECURE_SETTINGS);
			if (LOCAL_LOGV) Log.d(TAG, "Migrated " + rowsMigrated + " to Alliance secure table");
			rowsMigrated = migrateAllianceSettingsForTable(userId, AllianceDatabaseHelper.AllianceTableNames.TABLE_GLOBAL,
				AllianceSettings.Global.LEGACY_GLOBAL_SETTINGS);
			if (LOCAL_LOGV) Log.d(TAG, "Migrated " + rowsMigrated + " to Alliance global table");
		}
	}

	private int migrateAllianceSettingsForTable(int userId, String tableName, String[] settings) {
		ContentResolver resolver = getContext().getContentResolver();
		ContentValues[] contentValues = new ContentValues[settings.length];
		int migrateSettingsCount = 0;
		for (String settingsKey : settings) {
			String settingsValue = null;
			if (tableName.equals(AllianceDatabaseHelper.AllianceTableNames.TABLE_SYSTEM)) {
				settingsValue = Settings.System.getStringForUser(resolver, settingsKey, userId);
			} else if (tableName.equals(AllianceDatabaseHelper.AllianceTableNames.TABLE_SECURE)) {
				settingsValue = Settings.Secure.getStringForUser(resolver, settingsKey, userId);
			} else if (tableName.equals(AllianceDatabaseHelper.AllianceTableNames.TABLE_GLOBAL)) {
				settingsValue = Settings.Global.getStringForUser(resolver, settingsKey, userId);
			}
			if (LOCAL_LOGV) Log.d(TAG, "Table: " + tableName + ", Key: " + settingsKey + ", Value: " + settingsValue);
			ContentValues contentValue = new ContentValues();
			contentValue.put(Settings.NameValueTable.NAME, settingsKey);
			contentValue.put(Settings.NameValueTable.VALUE, settingsValue);
			contentValues[migrateSettingsCount++] = contentValue;
		}

		int rowsInserted = 0;
		if (contentValues.length > 0) {
			Uri uri = mUriBuilder.build();
			uri = uri.buildUpon().appendPath(tableName).build();
			rowsInserted = bulkInsertForUser(userId, uri, contentValues);
		}
		return rowsInserted;
	}

	private void onUserRemoved(int userId) {
		synchronized (this) {
			mDbHelpers.delete(userId);
			if (LOCAL_LOGV) Log.d(TAG, "User " + userId + " is removed");
		}
	}

	@Override
	public Bundle call(String method, String request, Bundle args) {
		if (LOCAL_LOGV) Log.d(TAG, "Call method: " + method);
		int callingUserId = UserHandle.getCallingUserId();
		if (args != null) {
			int reqUser = args.getInt(AllianceSettings.CALL_METHOD_USER_KEY, callingUserId);
			if (reqUser != callingUserId) {
				callingUserId = ActivityManager.handleIncomingUser(Binder.getCallingPid(),
						Binder.getCallingUid(), reqUser, false, true, "get/set setting for user", null);
				if (LOCAL_LOGV) Log.v(TAG, "   access setting for user " + callingUserId);
			}
		}

		if (AllianceSettings.CALL_METHOD_MIGRATE_SETTINGS.equals(method)) {
			migrateAllianceSettingsForExistingUsersIfNeeded();
			return null;
		} else if (AllianceSettings.CALL_METHOD_MIGRATE_SETTINGS_FOR_USER.equals(method)) {
			migrateAllianceSettingsForUser(callingUserId);
			return null;
		}

		if (AllianceSettings.CALL_METHOD_GET_SYSTEM.equals(method)) {
			return lookupSingleValue(callingUserId, AllianceSettings.System.CONTENT_URI, request);
        } else if (AllianceSettings.CALL_METHOD_GET_SECURE.equals(method)) {
            return lookupSingleValue(callingUserId, AllianceSettings.Secure.CONTENT_URI, request);
        } else if (AllianceSettings.CALL_METHOD_GET_GLOBAL.equals(method)) {
            return lookupSingleValue(callingUserId, AllianceSettings.Global.CONTENT_URI, request);
        }

        final String newValue = (args == null) ? null : args.getString(Settings.NameValueTable.VALUE);

        if (getContext().checkCallingOrSelfPermission("android.permission.WRITE_ALLIANCE_SETTINGS") != PackageManager.PERMISSION_GRANTED) {
            throw new SecurityException(String.format("Permission denial: writing to settings requires %1$s",
                            "android.permission.WRITE_ALLIANCE_SETTINGS"));
        }

        final ContentValues values = new ContentValues();
        values.put(Settings.NameValueTable.NAME, request);
        values.put(Settings.NameValueTable.VALUE, newValue);

        if (AllianceSettings.CALL_METHOD_PUT_SYSTEM.equals(method)) {
            insertForUser(callingUserId, AllianceSettings.System.CONTENT_URI, values);
        } else if (AllianceSettings.CALL_METHOD_PUT_SECURE.equals(method)) {
            insertForUser(callingUserId, AllianceSettings.Secure.CONTENT_URI, values);
        } else if (AllianceSettings.CALL_METHOD_PUT_GLOBAL.equals(method)) {
            insertForUser(callingUserId, AllianceSettings.Global.CONTENT_URI, values);
        }
        return null;
    }

    private Bundle lookupSingleValue(int userId, Uri uri, String key) {
        Cursor cursor = null;
        try {
            cursor = queryForUser(userId, uri, new String[]{ Settings.NameValueTable.VALUE },
                    Settings.NameValueTable.NAME + " = ?", new String[]{ key }, null);

            if (cursor != null && cursor.getCount() == 1) {
                cursor.moveToFirst();
                String value = cursor.getString(0);
                return value == null ? NULL_SETTING : Bundle.forPair(Settings.NameValueTable.VALUE, value);
            }
        } catch (SQLiteException e) {
            Log.w(TAG, "settings lookup error", e);
            return null;
        } finally {
            if (cursor != null) {
                cursor.close();
            }
        }
        return NULL_SETTING;
    }

    @Override
    public Cursor query(Uri uri, String[] projection, String selection, String[] selectionArgs, String sortOrder) {
        return queryForUser(UserHandle.getCallingUserId(), uri, projection, selection, selectionArgs, sortOrder);
    }

    private Cursor queryForUser(int userId, Uri uri, String[] projection, String selection, String[] selectionArgs, String sortOrder) {
        if (uri == null) {
            throw new IllegalArgumentException("Uri cannot be null");
        }

        int code = sUriMatcher.match(uri);
        String tableName = getTableNameFromUriMatchCode(code);
        AllianceDatabaseHelper dbHelper = getOrEstablishDatabase(getUserIdForTable(tableName, userId));
        SQLiteDatabase db = dbHelper.getReadableDatabase();
        SQLiteQueryBuilder queryBuilder = new SQLiteQueryBuilder();
        queryBuilder.setTables(tableName);
        Cursor returnCursor;
        if (isItemUri(code)) {
            returnCursor = queryBuilder.query(db, projection, NAME_SELECTION, new String[] { uri.getLastPathSegment() }, null, null, sortOrder);
        } else {
            returnCursor = queryBuilder.query(db, projection, selection, selectionArgs, null, null, sortOrder);
        }

        try {
            AbstractCursor abstractCursor = (AbstractCursor) returnCursor;
            abstractCursor.setNotificationUri(getContext().getContentResolver(), uri, userId);
        } catch (ClassCastException e) {
            Log.wtf(TAG, "Incompatible cursor derivation");
            throw e;
        }
        return returnCursor;
    }

    @Override
    public String getType(Uri uri) {
        int code = sUriMatcher.match(uri);
        String tableName = getTableNameFromUriMatchCode(code);

        if (isItemUri(code)) {
            return "vnd.android.cursor.item/" + tableName;
        } else {
            return "vnd.android.cursor.dir/" + tableName;
        }
    }

    @Override
    public int bulkInsert(Uri uri, ContentValues[] values) {
        return bulkInsertForUser(UserHandle.getCallingUserId(), uri, values);
    }

    int bulkInsertForUser(int userId, Uri uri, ContentValues[] values) {
        if (uri == null) {
            throw new IllegalArgumentException("Uri cannot be null");
        }
        if (values == null) {
            throw new IllegalArgumentException("ContentValues cannot be null");
        }
        int numRowsAffected = 0;
        String tableName = getTableNameFromUri(uri);
        checkWritePermissions(tableName);
        AllianceDatabaseHelper dbHelper = getOrEstablishDatabase(getUserIdForTable(tableName, userId));
        SQLiteDatabase db = dbHelper.getWritableDatabase();

        db.beginTransaction();
        try {
            for (ContentValues value : values) {
                if (value == null) {
                    continue;
                }
                long rowId = db.insert(tableName, null, value);
                if (rowId >= 0) {
                    numRowsAffected++;
                } else {
                    return 0;
                }
            }
            db.setTransactionSuccessful();
        } finally {
            db.endTransaction();
        }

        if (numRowsAffected > 0) {
            notifyChange(uri, tableName, userId);
            if (LOCAL_LOGV) Log.d(TAG, tableName + ": " + numRowsAffected + " row(s) inserted");
        }

        return numRowsAffected;
    }

    @Override
    public Uri insert(Uri uri, ContentValues values) {
        return insertForUser(UserHandle.getCallingUserId(), uri, values);
    }

    private Uri insertForUser(int userId, Uri uri, ContentValues values) {
        if (uri == null) {
            throw new IllegalArgumentException("Uri cannot be null");
        }
        if (values == null) {
            throw new IllegalArgumentException("ContentValues cannot be null");
        }

        String tableName = getTableNameFromUri(uri);
        checkWritePermissions(tableName);

        AllianceDatabaseHelper dbHelper = getOrEstablishDatabase(getUserIdForTable(tableName, userId));

        final String name = values.getAsString(Settings.NameValueTable.NAME);
        if (AllianceDatabaseHelper.AllianceTableNames.TABLE_SYSTEM.equals(tableName)) {
            final String value = values.getAsString(Settings.NameValueTable.VALUE);
            validateSystemSettingNameValue(name, value);
        }

        SQLiteDatabase db = dbHelper.getWritableDatabase();
        long rowId = db.insert(tableName, null, values);

        Uri returnUri = null;
        if (rowId > -1) {
            returnUri = Uri.withAppendedPath(uri, name);
            notifyChange(returnUri, tableName, userId);
            if (LOCAL_LOGV) Log.d(TAG, "Inserted row id: " + rowId + " into tableName: " + tableName);
        }
        return returnUri;
    }

    @Override
    public int delete(Uri uri, String selection, String[] selectionArgs) {
        if (uri == null) {
            throw new IllegalArgumentException("Uri cannot be null");
        }
        int numRowsAffected = 0;

        if (!TextUtils.isEmpty(selection) && selectionArgs.length > 0) {
            String tableName = getTableNameFromUri(uri);
            checkWritePermissions(tableName);

            int callingUserId = UserHandle.getCallingUserId();
            AllianceDatabaseHelper dbHelper = getOrEstablishDatabase(getUserIdForTable(tableName, callingUserId));
            SQLiteDatabase db = dbHelper.getWritableDatabase();
            numRowsAffected = db.delete(tableName, selection, selectionArgs);

            if (numRowsAffected > 0) {
                notifyChange(uri, tableName, callingUserId);
                if (LOCAL_LOGV) Log.d(TAG, tableName + ": " + numRowsAffected + " row(s) deleted");
            }
        }
        return numRowsAffected;
    }

    @Override
    public int update(Uri uri, ContentValues values, String selection, String[] selectionArgs) {
        if (uri == null) {
            throw new IllegalArgumentException("Uri cannot be null");
        }
        if (values == null) {
            throw new IllegalArgumentException("ContentValues cannot be null");
        }
        String tableName = getTableNameFromUri(uri);
        checkWritePermissions(tableName);

        final String name = values.getAsString(Settings.NameValueTable.NAME);
        if (AllianceDatabaseHelper.AllianceTableNames.TABLE_SYSTEM.equals(tableName)) {
            final String value = values.getAsString(Settings.NameValueTable.VALUE);
            validateSystemSettingNameValue(name, value);
        }

        int callingUserId = UserHandle.getCallingUserId();
        AllianceDatabaseHelper dbHelper = getOrEstablishDatabase(getUserIdForTable(tableName, callingUserId));

        SQLiteDatabase db = dbHelper.getWritableDatabase();
        int numRowsAffected = db.update(tableName, values, selection, selectionArgs);

        if (numRowsAffected > 0) {
            notifyChange(uri, tableName, callingUserId);
            if (LOCAL_LOGV) Log.d(TAG, tableName + ": " + numRowsAffected + " row(s) updated");
        }
        return numRowsAffected;
    }

    private AllianceDatabaseHelper getOrEstablishDatabase(int callingUser) {
        if (callingUser >= android.os.Process.SYSTEM_UID) {
            if (USER_CHECK_THROWS) {
                throw new IllegalArgumentException("Uid rather than user handle: " + callingUser);
            } else {
                Log.wtf(TAG, "Establish db for uid rather than user: " + callingUser);
            }
        }

        long oldId = Binder.clearCallingIdentity();
        try {
            AllianceDatabaseHelper dbHelper;
            synchronized (this) {
                dbHelper = mDbHelpers.get(callingUser);
            }
            if (null == dbHelper) {
                establishDbTracking(callingUser);
                synchronized (this) {
                    dbHelper = mDbHelpers.get(callingUser);
                }
            }
            return dbHelper;
        } finally {
            Binder.restoreCallingIdentity(oldId);
        }
    }

    private void establishDbTracking(int userId) {
        AllianceDatabaseHelper dbHelper;

        synchronized (this) {
            dbHelper = mDbHelpers.get(userId);
            if (LOCAL_LOGV) {
                Log.i(TAG, "Checking nameless settings db helper for user " + userId);
            }
            if (dbHelper == null) {
                if (LOCAL_LOGV) {
                    Log.i(TAG, "Installing new nameless settings db helper for user " + userId);
                }
                dbHelper = new AllianceDatabaseHelper(getContext(), userId);
                mDbHelpers.append(userId, dbHelper);
            }
        }
        dbHelper.getWritableDatabase();
    }

    private void checkWritePermissions(String tableName) {
        if ((AllianceDatabaseHelper.AllianceTableNames.TABLE_SECURE.equals(tableName) ||
                AllianceDatabaseHelper.AllianceTableNames.TABLE_GLOBAL.equals(tableName)) &&
                getContext().checkCallingOrSelfPermission(
                        "android.permission.WRITE_NAMELESS_SECURE_SETTINGS") !=
                        PackageManager.PERMISSION_GRANTED) {
            throw new SecurityException(
                    String.format("Permission denial: writing to Alliance secure settings requires %1$s",
                            "android.permission.WRITE_ALLIANCE_SECURE_SETTINGS"));
        }
    }

    private boolean isItemUri(int code) {
        switch (code) {
            case SYSTEM:
            case SECURE:
            case GLOBAL:
                return false;
            case SYSTEM_ITEM_NAME:
            case SECURE_ITEM_NAME:
            case GLOBAL_ITEM_NAME:
                return true;
            default:
                throw new IllegalArgumentException("Invalid uri match code: " + code);
        }
    }

    private String getTableNameFromUri(Uri uri) {
        int code = sUriMatcher.match(uri);
        return getTableNameFromUriMatchCode(code);
    }

    private String getTableNameFromUriMatchCode(int code) {
        switch (code) {
            case SYSTEM:
            case SYSTEM_ITEM_NAME:
                return AllianceDatabaseHelper.AllianceTableNames.TABLE_SYSTEM;
            case SECURE:
            case SECURE_ITEM_NAME:
                return AllianceDatabaseHelper.AllianceTableNames.TABLE_SECURE;
            case GLOBAL:
            case GLOBAL_ITEM_NAME:
                return AllianceDatabaseHelper.AllianceTableNames.TABLE_GLOBAL;
            default:
                throw new IllegalArgumentException("Invalid uri match code: " + code);
        }
    }

    private int getUserIdForTable(String tableName, int userId) {
        return AllianceDatabaseHelper.AllianceTableNames.TABLE_GLOBAL.equals(tableName) ? UserHandle.USER_OWNER : userId;
    }

    private void notifyChange(Uri uri, String tableName, int userId) {
        String property = null;
        final boolean isGlobal = tableName.equals(AllianceDatabaseHelper.AllianceTableNames.TABLE_GLOBAL);
        if (tableName.equals(AllianceDatabaseHelper.AllianceTableNames.TABLE_SYSTEM)) {
            property = AllianceSettings.System.SYS_PROP_ALLIANCE_SETTING_VERSION;
        } else if (tableName.equals(AllianceDatabaseHelper.AllianceTableNames.TABLE_SECURE)) {
            property = AllianceSettings.Secure.SYS_PROP_ALLIANCE_SETTING_VERSION;
        } else if (isGlobal) {
            property = AllianceSettings.Global.SYS_PROP_ALLIANCE_SETTING_VERSION;
        }

        if (property != null) {
            long version = SystemProperties.getLong(property, 0) + 1;
            if (LOCAL_LOGV) Log.v(TAG, "property: " + property + "=" + version);
            SystemProperties.set(property, Long.toString(version));
        }

        final int notifyTarget = isGlobal ? UserHandle.USER_ALL : userId;
        final long oldId = Binder.clearCallingIdentity();
        try {
            getContext().getContentResolver().notifyChange(uri, null, true, notifyTarget);
        } finally {
            Binder.restoreCallingIdentity(oldId);
        }
        if (LOCAL_LOGV) Log.v(TAG, "notifying for " + notifyTarget + ": " + uri);
    }

    private void validateSystemSettingNameValue(String name, String value) {
        AllianceSettings.System.Validator validator = AllianceSettings.System.VALIDATORS.get(name);
        if (validator == null) {
            throw new IllegalArgumentException("Invalid setting: " + name);
        }

        if (!validator.validate(value)) {
            throw new IllegalArgumentException("Invalid value: " + value
                    + " for setting: " + name);
        }
    }
}