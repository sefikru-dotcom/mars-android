package ru.mars.mobile.local;

/**
 * Заглушка {@code Context} для JVM-тестов каталога инструментов.
 *
 * <p>Зачем свой класс, а не Robolectric: каталог проверяется на СОСТАВ, ПОРЯДОК и
 * ФЛАГИ схем, а не на работу с системой. Тянуть Android-рантайм ради девяти
 * объектов, которые при создании к платформе не обращаются, несоразмерно.
 *
 * <p>Почему Java, а не Kotlin: у платформенных методов типы помечены как non-null,
 * и Kotlin-реализация обязана возвращать значение, которого у заглушки нет.
 *
 * <p>Каждый метод бросает {@link UnsupportedOperationException}: тест, случайно
 * задевший платформу, обязан упасть громко и с именем метода. Единственное
 * исключение — {@link #getApplicationContext()}: его каталог реально зовёт, и
 * {@code null} там означает «своего контекста нет, работай с переданным».
 *
 * <p>Сигнатуры сняты {@code javap} с {@code android.jar} API 34.
 */
final class FakeContext extends android.content.Context {

    @Override public android.content.res.AssetManager getAssets() { throw new UnsupportedOperationException("getAssets"); }
    @Override public android.content.res.Resources getResources() { throw new UnsupportedOperationException("getResources"); }
    @Override public android.content.pm.PackageManager getPackageManager() { throw new UnsupportedOperationException("getPackageManager"); }
    @Override public android.content.ContentResolver getContentResolver() { throw new UnsupportedOperationException("getContentResolver"); }
    @Override public android.os.Looper getMainLooper() { throw new UnsupportedOperationException("getMainLooper"); }
    @Override public android.content.Context getApplicationContext() { return null; }
    @Override public void setTheme(int a0) { throw new UnsupportedOperationException("setTheme"); }
    @Override public android.content.res.Resources.Theme getTheme() { throw new UnsupportedOperationException("getTheme"); }
    @Override public java.lang.ClassLoader getClassLoader() { throw new UnsupportedOperationException("getClassLoader"); }
    @Override public java.lang.String getPackageName() { throw new UnsupportedOperationException("getPackageName"); }
    @Override public android.content.pm.ApplicationInfo getApplicationInfo() { throw new UnsupportedOperationException("getApplicationInfo"); }
    @Override public java.lang.String getPackageResourcePath() { throw new UnsupportedOperationException("getPackageResourcePath"); }
    @Override public java.lang.String getPackageCodePath() { throw new UnsupportedOperationException("getPackageCodePath"); }
    @Override public android.content.SharedPreferences getSharedPreferences(java.lang.String a0, int a1) { throw new UnsupportedOperationException("getSharedPreferences"); }
    @Override public boolean moveSharedPreferencesFrom(android.content.Context a0, java.lang.String a1) { throw new UnsupportedOperationException("moveSharedPreferencesFrom"); }
    @Override public boolean deleteSharedPreferences(java.lang.String a0) { throw new UnsupportedOperationException("deleteSharedPreferences"); }
    @Override public java.io.FileInputStream openFileInput(java.lang.String a0) { throw new UnsupportedOperationException("openFileInput"); }
    @Override public java.io.FileOutputStream openFileOutput(java.lang.String a0, int a1) { throw new UnsupportedOperationException("openFileOutput"); }
    @Override public boolean deleteFile(java.lang.String a0) { throw new UnsupportedOperationException("deleteFile"); }
    @Override public java.io.File getFileStreamPath(java.lang.String a0) { throw new UnsupportedOperationException("getFileStreamPath"); }
    @Override public java.io.File getDataDir() { throw new UnsupportedOperationException("getDataDir"); }
    @Override public java.io.File getFilesDir() { throw new UnsupportedOperationException("getFilesDir"); }
    @Override public java.io.File getNoBackupFilesDir() { throw new UnsupportedOperationException("getNoBackupFilesDir"); }
    @Override public java.io.File getExternalFilesDir(java.lang.String a0) { throw new UnsupportedOperationException("getExternalFilesDir"); }
    @Override public java.io.File[] getExternalFilesDirs(java.lang.String a0) { throw new UnsupportedOperationException("getExternalFilesDirs"); }
    @Override public java.io.File getObbDir() { throw new UnsupportedOperationException("getObbDir"); }
    @Override public java.io.File[] getObbDirs() { throw new UnsupportedOperationException("getObbDirs"); }
    @Override public java.io.File getCacheDir() { throw new UnsupportedOperationException("getCacheDir"); }
    @Override public java.io.File getCodeCacheDir() { throw new UnsupportedOperationException("getCodeCacheDir"); }
    @Override public java.io.File getExternalCacheDir() { throw new UnsupportedOperationException("getExternalCacheDir"); }
    @Override public java.io.File[] getExternalCacheDirs() { throw new UnsupportedOperationException("getExternalCacheDirs"); }
    @Override public java.io.File[] getExternalMediaDirs() { throw new UnsupportedOperationException("getExternalMediaDirs"); }
    @Override public java.lang.String[] fileList() { throw new UnsupportedOperationException("fileList"); }
    @Override public java.io.File getDir(java.lang.String a0, int a1) { throw new UnsupportedOperationException("getDir"); }
    @Override public android.database.sqlite.SQLiteDatabase openOrCreateDatabase(java.lang.String a0, int a1, android.database.sqlite.SQLiteDatabase.CursorFactory a2) { throw new UnsupportedOperationException("openOrCreateDatabase"); }
    @Override public android.database.sqlite.SQLiteDatabase openOrCreateDatabase(java.lang.String a0, int a1, android.database.sqlite.SQLiteDatabase.CursorFactory a2, android.database.DatabaseErrorHandler a3) { throw new UnsupportedOperationException("openOrCreateDatabase"); }
    @Override public boolean moveDatabaseFrom(android.content.Context a0, java.lang.String a1) { throw new UnsupportedOperationException("moveDatabaseFrom"); }
    @Override public boolean deleteDatabase(java.lang.String a0) { throw new UnsupportedOperationException("deleteDatabase"); }
    @Override public java.io.File getDatabasePath(java.lang.String a0) { throw new UnsupportedOperationException("getDatabasePath"); }
    @Override public java.lang.String[] databaseList() { throw new UnsupportedOperationException("databaseList"); }
    @Override public android.graphics.drawable.Drawable getWallpaper() { throw new UnsupportedOperationException("getWallpaper"); }
    @Override public android.graphics.drawable.Drawable peekWallpaper() { throw new UnsupportedOperationException("peekWallpaper"); }
    @Override public int getWallpaperDesiredMinimumWidth() { throw new UnsupportedOperationException("getWallpaperDesiredMinimumWidth"); }
    @Override public int getWallpaperDesiredMinimumHeight() { throw new UnsupportedOperationException("getWallpaperDesiredMinimumHeight"); }
    @Override public void setWallpaper(android.graphics.Bitmap a0) { throw new UnsupportedOperationException("setWallpaper"); }
    @Override public void setWallpaper(java.io.InputStream a0) { throw new UnsupportedOperationException("setWallpaper"); }
    @Override public void clearWallpaper() { throw new UnsupportedOperationException("clearWallpaper"); }
    @Override public void startActivity(android.content.Intent a0) { throw new UnsupportedOperationException("startActivity"); }
    @Override public void startActivity(android.content.Intent a0, android.os.Bundle a1) { throw new UnsupportedOperationException("startActivity"); }
    @Override public void startActivities(android.content.Intent[] a0) { throw new UnsupportedOperationException("startActivities"); }
    @Override public void startActivities(android.content.Intent[] a0, android.os.Bundle a1) { throw new UnsupportedOperationException("startActivities"); }
    @Override public void startIntentSender(android.content.IntentSender a0, android.content.Intent a1, int a2, int a3, int a4) { throw new UnsupportedOperationException("startIntentSender"); }
    @Override public void startIntentSender(android.content.IntentSender a0, android.content.Intent a1, int a2, int a3, int a4, android.os.Bundle a5) { throw new UnsupportedOperationException("startIntentSender"); }
    @Override public void sendBroadcast(android.content.Intent a0) { throw new UnsupportedOperationException("sendBroadcast"); }
    @Override public void sendBroadcast(android.content.Intent a0, java.lang.String a1) { throw new UnsupportedOperationException("sendBroadcast"); }
    @Override public void sendOrderedBroadcast(android.content.Intent a0, java.lang.String a1) { throw new UnsupportedOperationException("sendOrderedBroadcast"); }
    @Override public void sendOrderedBroadcast(android.content.Intent a0, java.lang.String a1, android.content.BroadcastReceiver a2, android.os.Handler a3, int a4, java.lang.String a5, android.os.Bundle a6) { throw new UnsupportedOperationException("sendOrderedBroadcast"); }
    @Override public void sendBroadcastAsUser(android.content.Intent a0, android.os.UserHandle a1) { throw new UnsupportedOperationException("sendBroadcastAsUser"); }
    @Override public void sendBroadcastAsUser(android.content.Intent a0, android.os.UserHandle a1, java.lang.String a2) { throw new UnsupportedOperationException("sendBroadcastAsUser"); }
    @Override public void sendOrderedBroadcastAsUser(android.content.Intent a0, android.os.UserHandle a1, java.lang.String a2, android.content.BroadcastReceiver a3, android.os.Handler a4, int a5, java.lang.String a6, android.os.Bundle a7) { throw new UnsupportedOperationException("sendOrderedBroadcastAsUser"); }
    @Override public void sendStickyBroadcast(android.content.Intent a0) { throw new UnsupportedOperationException("sendStickyBroadcast"); }
    @Override public void sendStickyOrderedBroadcast(android.content.Intent a0, android.content.BroadcastReceiver a1, android.os.Handler a2, int a3, java.lang.String a4, android.os.Bundle a5) { throw new UnsupportedOperationException("sendStickyOrderedBroadcast"); }
    @Override public void removeStickyBroadcast(android.content.Intent a0) { throw new UnsupportedOperationException("removeStickyBroadcast"); }
    @Override public void sendStickyBroadcastAsUser(android.content.Intent a0, android.os.UserHandle a1) { throw new UnsupportedOperationException("sendStickyBroadcastAsUser"); }
    @Override public void sendStickyOrderedBroadcastAsUser(android.content.Intent a0, android.os.UserHandle a1, android.content.BroadcastReceiver a2, android.os.Handler a3, int a4, java.lang.String a5, android.os.Bundle a6) { throw new UnsupportedOperationException("sendStickyOrderedBroadcastAsUser"); }
    @Override public void removeStickyBroadcastAsUser(android.content.Intent a0, android.os.UserHandle a1) { throw new UnsupportedOperationException("removeStickyBroadcastAsUser"); }
    @Override public android.content.Intent registerReceiver(android.content.BroadcastReceiver a0, android.content.IntentFilter a1) { throw new UnsupportedOperationException("registerReceiver"); }
    @Override public android.content.Intent registerReceiver(android.content.BroadcastReceiver a0, android.content.IntentFilter a1, int a2) { throw new UnsupportedOperationException("registerReceiver"); }
    @Override public android.content.Intent registerReceiver(android.content.BroadcastReceiver a0, android.content.IntentFilter a1, java.lang.String a2, android.os.Handler a3) { throw new UnsupportedOperationException("registerReceiver"); }
    @Override public android.content.Intent registerReceiver(android.content.BroadcastReceiver a0, android.content.IntentFilter a1, java.lang.String a2, android.os.Handler a3, int a4) { throw new UnsupportedOperationException("registerReceiver"); }
    @Override public void unregisterReceiver(android.content.BroadcastReceiver a0) { throw new UnsupportedOperationException("unregisterReceiver"); }
    @Override public android.content.ComponentName startService(android.content.Intent a0) { throw new UnsupportedOperationException("startService"); }
    @Override public android.content.ComponentName startForegroundService(android.content.Intent a0) { throw new UnsupportedOperationException("startForegroundService"); }
    @Override public boolean stopService(android.content.Intent a0) { throw new UnsupportedOperationException("stopService"); }
    @Override public boolean bindService(android.content.Intent a0, android.content.ServiceConnection a1, int a2) { throw new UnsupportedOperationException("bindService"); }
    @Override public void unbindService(android.content.ServiceConnection a0) { throw new UnsupportedOperationException("unbindService"); }
    @Override public boolean startInstrumentation(android.content.ComponentName a0, java.lang.String a1, android.os.Bundle a2) { throw new UnsupportedOperationException("startInstrumentation"); }
    @Override public java.lang.Object getSystemService(java.lang.String a0) { throw new UnsupportedOperationException("getSystemService"); }
    @Override public java.lang.String getSystemServiceName(java.lang.Class<?> a0) { throw new UnsupportedOperationException("getSystemServiceName"); }
    @Override public int checkPermission(java.lang.String a0, int a1, int a2) { throw new UnsupportedOperationException("checkPermission"); }
    @Override public int checkCallingPermission(java.lang.String a0) { throw new UnsupportedOperationException("checkCallingPermission"); }
    @Override public int checkCallingOrSelfPermission(java.lang.String a0) { throw new UnsupportedOperationException("checkCallingOrSelfPermission"); }
    @Override public int checkSelfPermission(java.lang.String a0) { throw new UnsupportedOperationException("checkSelfPermission"); }
    @Override public void enforcePermission(java.lang.String a0, int a1, int a2, java.lang.String a3) { throw new UnsupportedOperationException("enforcePermission"); }
    @Override public void enforceCallingPermission(java.lang.String a0, java.lang.String a1) { throw new UnsupportedOperationException("enforceCallingPermission"); }
    @Override public void enforceCallingOrSelfPermission(java.lang.String a0, java.lang.String a1) { throw new UnsupportedOperationException("enforceCallingOrSelfPermission"); }
    @Override public void grantUriPermission(java.lang.String a0, android.net.Uri a1, int a2) { throw new UnsupportedOperationException("grantUriPermission"); }
    @Override public void revokeUriPermission(android.net.Uri a0, int a1) { throw new UnsupportedOperationException("revokeUriPermission"); }
    @Override public void revokeUriPermission(java.lang.String a0, android.net.Uri a1, int a2) { throw new UnsupportedOperationException("revokeUriPermission"); }
    @Override public int checkUriPermission(android.net.Uri a0, int a1, int a2, int a3) { throw new UnsupportedOperationException("checkUriPermission"); }
    @Override public int checkCallingUriPermission(android.net.Uri a0, int a1) { throw new UnsupportedOperationException("checkCallingUriPermission"); }
    @Override public int checkCallingOrSelfUriPermission(android.net.Uri a0, int a1) { throw new UnsupportedOperationException("checkCallingOrSelfUriPermission"); }
    @Override public int checkUriPermission(android.net.Uri a0, java.lang.String a1, java.lang.String a2, int a3, int a4, int a5) { throw new UnsupportedOperationException("checkUriPermission"); }
    @Override public void enforceUriPermission(android.net.Uri a0, int a1, int a2, int a3, java.lang.String a4) { throw new UnsupportedOperationException("enforceUriPermission"); }
    @Override public void enforceCallingUriPermission(android.net.Uri a0, int a1, java.lang.String a2) { throw new UnsupportedOperationException("enforceCallingUriPermission"); }
    @Override public void enforceCallingOrSelfUriPermission(android.net.Uri a0, int a1, java.lang.String a2) { throw new UnsupportedOperationException("enforceCallingOrSelfUriPermission"); }
    @Override public void enforceUriPermission(android.net.Uri a0, java.lang.String a1, java.lang.String a2, int a3, int a4, int a5, java.lang.String a6) { throw new UnsupportedOperationException("enforceUriPermission"); }
    @Override public android.content.Context createPackageContext(java.lang.String a0, int a1) { throw new UnsupportedOperationException("createPackageContext"); }
    @Override public android.content.Context createContextForSplit(java.lang.String a0) { throw new UnsupportedOperationException("createContextForSplit"); }
    @Override public android.content.Context createConfigurationContext(android.content.res.Configuration a0) { throw new UnsupportedOperationException("createConfigurationContext"); }
    @Override public android.content.Context createDisplayContext(android.view.Display a0) { throw new UnsupportedOperationException("createDisplayContext"); }
    @Override public android.content.Context createDeviceProtectedStorageContext() { throw new UnsupportedOperationException("createDeviceProtectedStorageContext"); }
    @Override public boolean isDeviceProtectedStorage() { throw new UnsupportedOperationException("isDeviceProtectedStorage"); }
}
