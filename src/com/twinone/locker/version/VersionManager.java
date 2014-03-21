package com.twinone.locker.version;

import java.io.BufferedInputStream;
import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.Map;

import android.annotation.SuppressLint;
import android.content.Context;
import android.content.SharedPreferences;
import android.content.pm.PackageManager.NameNotFoundException;
import android.net.Uri;
import android.os.AsyncTask;
import android.os.Build;
import android.util.Log;

import com.google.gson.Gson;

/**
 * Manages versions of your application.<br>
 * It uses versionCode in AndroidManifest.xml
 * 
 * <br>
 * Requires permissions:<br>
 * INTERNET<br>
 * 
 * @author twinone
 * 
 */
public class VersionManager {

	public static final String TAG = "VersionManager";
	private static final String PREFS_FILENAME = "com.twinone.update";

	private static final String PREFS_VERSION_MATCHED = "com.twinone.update.version";
	/** This version is OR WILL BE deprecated */
	private static final String PREFS_DEPRECATED = "com.twinone.update.deprecated";
	/** This version's deprecation time */
	private static final String PREFS_DEPRECATION_TIME = "com.twinone.update.deprecation_time";
	/** If server time >= warning time, the user should get warned */
	private static final String PREFS_WARN_TIME = "com.twinone.update.warn_before_time";
	/** Current server time */
	private static final String PREFS_SERVER_TIME = "com.twinone.update.server_time";
	/** The url to query for version info */
	private static final String PREFS_URL = "com.twinone.update.url";
	/** Custom prefix to be added to user-defined objects */
	private static final String PREFS_VALUES_PREFIX = "com.twinone.update.values.custom.";

	/** This indicates the old version for #isJustUpdated() */
	private static final String PREFS_OLD_VERSION = "com.twinone.update.values.old_version";

	/**
	 * Gets the versionCode from AndroidManifest.xml<br>
	 * This is the current version installed on the device.
	 * 
	 * @param context
	 * @return
	 */
	public static final int getManifestVersion(Context context) {
		int ver = 0;
		try {
			ver = context.getPackageManager().getPackageInfo(
					context.getPackageName(), 0).versionCode;
		} catch (NameNotFoundException e) {
		}
		return ver;
	}

	public static interface VersionListener {
		public void onServerResponse();
	}

	/**
	 * Primary method that should be called when you want to know something
	 * about the device's version (async, so a listener is needed)
	 */
	public static void queryServer(Context context, VersionListener listener) {
		String url = getUrl(context);
		if (url == null) {
			Log.w(TAG, "You should provide a URL with setUrlOnce()");
			return;
		}
		new LoadVersionsTask(context, listener).execute(url);
	}

	/**
	 * Call this method in a onCreate or so<br>
	 * 
	 * This will set the default url of your app if it's not yet set. The url
	 * can be also changed from the server without needing to update the app
	 * itself. use the new_url parameter for it<br>
	 * It will also update the url when this app's version is newer as the
	 * stored version Note: ?v=versionCode will be appended to the Url, so the
	 * PHP backend can return different items based on the version of this
	 * installation. Please configure your server accordingly
	 * 
	 */
	@SuppressLint("CommitPrefEdits")
	public static void setUrlOnce(Context c, String url) {
		// update when different manifest versions or when there is no url set
		// yet
		if (getPrefsVersion(c) != getManifestVersion(c) || getUrl(c) == null) {
			SharedPreferences.Editor editor = c.getSharedPreferences(
					PREFS_FILENAME, Context.MODE_PRIVATE).edit();
			editor.putString(PREFS_URL, url);
			applyCompat(editor);
		}
	}

	/**
	 * Return the current URL, or null if it was not yet set.<br>
	 * This will also append the ?v=versionCode to the URL
	 */
	private static String getUrl(Context c) {
		try {
			String url = c.getSharedPreferences(PREFS_FILENAME,
					Context.MODE_PRIVATE).getString(PREFS_URL, null);
			Uri.Builder ub = Uri.parse(url).buildUpon();
			int manifestVersion = getManifestVersion(c);
			String mVersion = String.valueOf(manifestVersion);
			ub.appendQueryParameter("v", mVersion);
			
			return ub.build().toString();
		} catch (Exception e) {
			Log.w(TAG, "Error parsing url");
			return null;
		}
	}

	private static int getPrefsVersion(Context c) {
		return c.getSharedPreferences(PREFS_FILENAME, Context.MODE_PRIVATE)
				.getInt(PREFS_VERSION_MATCHED, 0);
	}

	private static class LoadVersionsTask extends
			AsyncTask<String, Void, VersionInfo> {

		private final VersionListener mListener;
		private final Context mContext;

		public LoadVersionsTask(Context context, VersionListener listener) {
			mListener = listener;
			mContext = context;
		}

		@Override
		protected VersionInfo doInBackground(String... params) {
			final String url = params[0];
			return queryServerImpl(url);
		}

		@Override
		protected void onPostExecute(VersionInfo result) {
			if (result != null) {
				saveToStorage(mContext, result);
				if (mListener != null) {
					mListener.onServerResponse();
				}
			}
		}
	}

	@SuppressLint("CommitPrefEdits")
	private static void saveToStorage(Context c, VersionInfo vi) {
		// Get if this version is deprecated, and if it is, the most restrictive
		// deprecation time
		SharedPreferences sp = c.getSharedPreferences(PREFS_FILENAME,
				Context.MODE_PRIVATE);
		SharedPreferences.Editor editor = sp.edit();

		// save the url!
		String url = sp.getString(PREFS_URL, null);
		editor.putString(PREFS_URL, url);
		editor.clear();

		editor.putInt(PREFS_VERSION_MATCHED, getManifestVersion(c));
		if (vi.deprecated != null)
			editor.putBoolean(PREFS_DEPRECATED, vi.deprecated);
		if (vi.deprecation_time != null)
			editor.putLong(PREFS_DEPRECATION_TIME, vi.deprecation_time);
		if (vi.warn_time != null)
			editor.putLong(PREFS_WARN_TIME, vi.warn_time);
		if (vi.new_url != null)
			editor.putString(PREFS_URL, vi.new_url);
		if (vi.server_time != null)
			editor.putLong(PREFS_SERVER_TIME, vi.server_time);
		if (vi.values != null) {
			for (Map.Entry<String, String> entry : vi.values.entrySet()) {
				String key = PREFS_VALUES_PREFIX + entry.getKey();
				String value = entry.getValue();
				if (key != null && value != null)
					editor.putString(key, value);
			}
		}

		applyCompat(editor);
	}

	/**
	 * If this is true, you should show a dialog warning the user that he has
	 * only some days left to update
	 */
	public static boolean shouldWarn(Context c) {
		if (getPrefsVersion(c) == getManifestVersion(c)) {
			if (isMarkedForDeprecation(c) && !isDeprecated(c)) {
				SharedPreferences sp = c.getSharedPreferences(PREFS_FILENAME,
						Context.MODE_PRIVATE);
				long warnTime = sp.getLong(PREFS_WARN_TIME, 0);
				long serverTime = sp.getLong(PREFS_SERVER_TIME, 0);
				if (warnTime != 0 && serverTime != 0 && serverTime >= warnTime) {
					return true;
				}
			}
		}
		return false;
	}

	/**
	 * Returns a custom value that was sent from the server in
	 * {@link VersionInfo#values} or defValue if the value was not received
	 * 
	 */
	public static final String getValue(Context c, String key, String defValue) {
		// don't interfeare with older versions
		if (getPrefsVersion(c) != getManifestVersion(c)) {
			return defValue;
		}
		return c.getSharedPreferences(PREFS_FILENAME, Context.MODE_PRIVATE)
				.getString(PREFS_VALUES_PREFIX + key, defValue);
	}

	@SuppressLint("NewApi")
	private static void applyCompat(SharedPreferences.Editor editor) {
		if (Build.VERSION.SDK_INT < Build.VERSION_CODES.GINGERBREAD) {
			editor.commit();
		} else {
			editor.apply();
		}
	}

	/**
	 * Returns true if this version is deprecated, independently of whether the
	 * deprecation time is before or after current server time
	 */
	public static boolean isMarkedForDeprecation(Context c) {
		if (getPrefsVersion(c) != getManifestVersion(c)) {
			return false;
		}
		SharedPreferences sp = c.getSharedPreferences(PREFS_FILENAME,
				Context.MODE_PRIVATE);
		boolean deprecated = sp.getBoolean(PREFS_DEPRECATED, false);
		return deprecated;
	}

	/**
	 * Number of full days the user can still use the app before it will become
	 * unusable<br>
	 * returns negative value if there are no days left, so check with
	 * {@link #getDeprecationStatus(Context)}<br>
	 * returns -1 if not applicable
	 * 
	 */
	public static int getDaysLeft(Context c) {
		if (getPrefsVersion(c) != getManifestVersion(c)) {
			return -1;
		}
		SharedPreferences sp = c.getSharedPreferences(PREFS_FILENAME,
				Context.MODE_PRIVATE);
		long server_time = sp.getLong(PREFS_SERVER_TIME, 0);
		long deprecation_time = sp.getLong(PREFS_DEPRECATION_TIME, 0);
		if (server_time == 0 || deprecation_time == 0)
			return -1;
		return (int) (deprecation_time - server_time) / 86400;
	}

	/**
	 * This app is marked for deprecation and the server time >= deprecation
	 * time
	 */
	public static final int STATUS_DEPRECATED = -1;
	/**
	 * This version has been marked for deprecation but there is still time left
	 * to use this version
	 */
	public static final int STATUS_MARKED_FOR_DEPRECATION = -2;
	/** This version is not marked for deprecation */
	public static final int STATUS_NOT_DEPRECATED = -3;

	/**
	 * Returns one of {@link #STATUS_DEPRECATED},
	 * {@link #STATUS_MARKED_FOR_DEPRECATION} or {@link #STATUS_NOT_DEPRECATED}
	 */
	public static int getDeprecationStatus(Context c) {
		SharedPreferences sp = c.getSharedPreferences(PREFS_FILENAME,
				Context.MODE_PRIVATE);
		int matchedVersion = sp.getInt(PREFS_VERSION_MATCHED, 0);
		int manifestVersion = getManifestVersion(c);

		// avoid false positives after updating the app
		if (matchedVersion == manifestVersion) {
			boolean deprecated = sp.getBoolean(PREFS_DEPRECATED, false);
			long server_time = sp.getLong(PREFS_SERVER_TIME, 0);
			long deprecation_time = sp.getLong(PREFS_DEPRECATION_TIME, 0);
			if (deprecated && server_time != 0 && deprecation_time != 0) {
				return server_time >= deprecation_time ? STATUS_DEPRECATED
						: STATUS_MARKED_FOR_DEPRECATION;
			}
		}
		return STATUS_NOT_DEPRECATED;
	}

	/**
	 * @return true if {@link #getDeprecationStatus(Context)} =
	 *         {@link #STATUS_DEPRECATED}
	 */
	public static boolean isDeprecated(Context c) {
		return getDeprecationStatus(c) == STATUS_DEPRECATED;
	}

	/**
	 * Does the loading (blocks until done)
	 * 
	 * @param link
	 * @return
	 */
	private static VersionInfo queryServerImpl(String link) {
		try {
			URL url = new URL(link);
			HttpURLConnection urlConnection = (HttpURLConnection) url
					.openConnection();

			InputStream in = new BufferedInputStream(
					urlConnection.getInputStream());
			BufferedReader br = new BufferedReader(new InputStreamReader(in));
			StringBuilder data = new StringBuilder();
			String tmp;
			while ((tmp = br.readLine()) != null) {
				data.append(tmp);
			}
			urlConnection.disconnect();
			Gson gson = new Gson();
			VersionInfo vi = gson.fromJson(data.toString(), VersionInfo.class);
			Log.d(TAG, "Succesful query to server");
			return vi;
		} catch (Exception e) {
			Log.w(TAG, "Query to server failed" + e.getMessage());
			return null;
		}
	}

	/**
	 * This will return true once after every upgrade, returns false at any
	 * other time
	 */
	@SuppressLint("CommitPrefEdits")
	public static boolean isJustUpgraded(Context context) {
		SharedPreferences sp = context.getSharedPreferences(PREFS_FILENAME,
				Context.MODE_PRIVATE);
		SharedPreferences.Editor editor = sp.edit();
		int manifestVersion = getManifestVersion(context);
		int storedVersion = sp.getInt(PREFS_OLD_VERSION, 0);
		editor.putInt(PREFS_OLD_VERSION, manifestVersion);
		applyCompat(editor);
		return storedVersion == manifestVersion;
	}
}
