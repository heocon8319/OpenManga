package org.nv95.openmanga.utils;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;
import android.preference.PreferenceManager;
import android.support.annotation.MainThread;
import android.support.annotation.WorkerThread;

import org.nv95.openmanga.helpers.DirRemoveHelper;
import org.nv95.openmanga.helpers.StorageHelper;
import org.nv95.openmanga.items.DownloadInfo;
import org.nv95.openmanga.items.MangaChapter;
import org.nv95.openmanga.items.MangaPage;
import org.nv95.openmanga.items.SimpleDownload;

import java.io.File;
import java.util.Date;
import java.util.concurrent.CopyOnWriteArraySet;

/**
 * Created by nv95 on 04.06.16.
 * Use this instead of #StorageHelper only for saved manga
 */

public class MangaStore {

    public static final String TABLE_MANGAS = "mangas";
    public static final String TABLE_CHAPTERS = "chapters";
    public static final String TABLE_PAGES = "pages";

    private static DatabaseHelper mDatabaseHelper = null;
    private final Context mContext;

    public MangaStore(Context context) {
        mContext = context;
        if (mDatabaseHelper == null) {
            mDatabaseHelper = new DatabaseHelper(mContext);
        }
    }

    /**
     * Add manga to database, create dir for it, download preview
     * @param manga - what save to db
     * @return manga id
     */
    @WorkerThread
    public int pushManga(DownloadInfo manga) {
        SQLiteDatabase database = null;
        int id = 0;
        try {
            database = mDatabaseHelper.getWritableDatabase();
            final ContentValues cv = new ContentValues();
            id = manga.readLink.hashCode();
            cv.put("id", id);
            cv.put("name", manga.name);
            cv.put("subtitle", manga.subtitle);
            cv.put("summary", manga.genres);
            final File dest = getMangaDir(mContext, id);
            cv.put("dir", dest.getPath());
            new SimpleDownload(manga.preview, new File(dest, "cover")).run();
            cv.put("description", manga.description);
            cv.put("timestamp", new Date().getTime());
            cv.put("provider", manga.provider.getName());
            cv.put("source", manga.path);
            if (database.update(TABLE_MANGAS,cv, "id=" + id, null) == 0) {
                database.insert(TABLE_MANGAS, null, cv);
            }
        } catch (Exception e) {
            FileLogger.getInstance().report(e);
        } finally {
            if (database != null) {
                database.close();
            }
        }
        return id;
    }

    /**
     * Add chapter to database and nothing more
     * @param chapter what to add
     * @param mangaId id of manga
     * @return id of chapter
     */
    @WorkerThread
    public int pushChapter(MangaChapter chapter, int mangaId) {
        SQLiteDatabase database = null;
        int id = 0;
        try {
            database = mDatabaseHelper.getWritableDatabase();
            final ContentValues cv = new ContentValues();
            id = chapter.readLink.hashCode();
            cv.put("id", id);
            cv.put("mangaid", mangaId);
            cv.put("name", chapter.name);
            cv.put("number", chapter.number == -1 ?
                    StorageHelper.getColumnCount(database, TABLE_CHAPTERS, "mangaid=" + mangaId)
                    : chapter.number);
            if (database.update(TABLE_CHAPTERS,cv, "id=" + id, null) == 0) {
                database.insert(TABLE_CHAPTERS, null, cv);
            }
        } catch (Exception e) {
            FileLogger.getInstance().report(e);
        } finally {
            if (database != null) {
                database.close();
            }
        }
        return id;
    }

    /**
     * Download page, save it to manga dir and add to database
     * @param page page to save
     * @param mangaId id of manga
     * @param chapterId id of chapter
     * @return page id;
     */
    @WorkerThread
    public int pushPage(MangaPage page, int mangaId, int chapterId) {
        SQLiteDatabase database = null;
        int id = 0;
        try {
            database = mDatabaseHelper.getWritableDatabase();
            final ContentValues cv = new ContentValues();
            id = page.path.hashCode();
            cv.put("id", id);
            cv.put("chapterid", chapterId);
            cv.put("mangaid", mangaId);
            File dest = new File(getMangaDir(mContext, mangaId), chapterId + "_" + id);
            SimpleDownload sd =new SimpleDownload(page.path, dest);
            sd.run();
            if (!sd.isSuccess()) {
                return 0;
            }
            cv.put("file", dest.getName());
            cv.put("number", StorageHelper.getColumnCount(database, TABLE_PAGES, "chapterid=" + chapterId));
            if (database.update(TABLE_PAGES,cv, "id=" + id, null) == 0) {
                database.insert(TABLE_PAGES, null, cv);
            }
        } catch (Exception e) {
            FileLogger.getInstance().report(e);
            id = 0;
        } finally {
            if (database != null) {
                database.close();
            }
        }
        return id;
    }

    /**
     * Delete mangas from database and files
     * @param ids array of manga's id
     * @return #true if no errors
     */
    @MainThread
    public boolean dropMangas(long[] ids) {
        SQLiteDatabase database = null;
        boolean result = true;
        Cursor cursor = null;
        int id;
        final File[] dirs = new File[ids.length];
        try {
            database = mDatabaseHelper.getWritableDatabase();
            database.beginTransaction();
            for (int i = 0;i < ids.length;i++) {
                id = (int) ids[i];
                cursor = database.query(TABLE_MANGAS, new String[]{"dir"}, "id=?", new String[]{String.valueOf(id)}, null, null, null);
                if (cursor.moveToFirst()) {
                    dirs[i] = new File(cursor.getString(0));
                }
                cursor.close();
                cursor = null;
                database.delete(TABLE_PAGES, "mangaid=?", new String[]{String.valueOf(id)});
                database.delete(TABLE_CHAPTERS, "mangaid=?", new String[]{String.valueOf(id)});
                database.delete(TABLE_MANGAS, "id=?", new String[]{String.valueOf(id)});
            }
            database.setTransactionSuccessful();
            new DirRemoveHelper(dirs).runAsync();
        } catch (Exception e) {
            FileLogger.getInstance().report(e);
            result = false;
        } finally {
            if (cursor != null) {
                cursor.close();
            }
            if (database != null) {
                database.endTransaction();
                database.close();
            }
        }
        return result;
    }

    @MainThread
    public boolean dropChapters(int mangaId, long[] ids) {
        SQLiteDatabase database = null;
        boolean result = true;
        try {
            database = mDatabaseHelper.getWritableDatabase();
            database.beginTransaction();
            for (long id : ids) {
                database.delete(TABLE_PAGES, "chapterid=? AND mangaid=?", new String[]{String.valueOf(id), String.valueOf(mangaId)});
                database.delete(TABLE_CHAPTERS, "id=?", new String[]{String.valueOf(id)});
                new DirRemoveHelper(getMangaDir(mContext, mangaId), id + "_*").runAsync();
            }
            database.setTransactionSuccessful();
        } catch (Exception e) {
            FileLogger.getInstance().report(e);
            result = false;
        } finally {
            if (database != null) {
                database.endTransaction();
                database.close();
            }
        }
        return result;
    }

    public SQLiteDatabase getDatabase(boolean writable) {
        return writable ? mDatabaseHelper.getWritableDatabase() : mDatabaseHelper.getReadableDatabase();
    }

    public static File getMangasDir(Context context) {
        final String dir = PreferenceManager.getDefaultSharedPreferences(context).getString("mangadir", "");
        final File res = dir.length() == 0 ? context.getExternalFilesDir("saved") : new File(dir);
        assert res != null;
        if (!res.exists()) {
            //noinspection ResultOfMethodCallIgnored
            res.mkdirs();
        }
        return res;
    }

    public static File getMangaDir(Context context, int id) {
        final File res = new File(getMangasDir(context), String.valueOf(id));
        if (!res.exists()) {
            //noinspection ResultOfMethodCallIgnored
            res.mkdirs();
        }
        return res;
    }

    private static class DatabaseHelper extends SQLiteOpenHelper {

        private static final int DB_VERSION = 2;

        DatabaseHelper(Context context) {
            super(context, "mangastore", null, DB_VERSION);
        }

        @Override
        public void onCreate(SQLiteDatabase db) {
            db.execSQL("CREATE TABLE " + TABLE_MANGAS + " ("
                    + "id INTEGER PRIMARY KEY,"
                    + "name TEXT,"
                    + "subtitle TEXT,"
                    + "summary TEXT,"
                    + "description TEXT,"
                    + "dir TEXT,"             //каталог с файлами
                    + "timestamp INTEGER,"
                    + "source TEXT,"        //link to source manga
                    + "provider TEXT"       //source provider
                    + ");");

            db.execSQL("CREATE TABLE " + TABLE_CHAPTERS + " ("
                    + "id INTEGER PRIMARY KEY,"
                    + "mangaid INTEGER,"
                    + "name TEXT,"
                    + "number INTEGER"
                    + ");");

            db.execSQL("CREATE TABLE " + TABLE_PAGES + " ("
                    + "id INTEGER PRIMARY KEY,"
                    + "chapterid INTEGER,"
                    + "mangaid INTEGER,"
                    + "file TEXT,"           //name of file, without path
                    + "number INTEGER"     //use for true order
                    + ");");
        }

        @Override
        public void onUpgrade(SQLiteDatabase db, int oldVersion, int newVersion) {
            CopyOnWriteArraySet<String> tables = StorageHelper.getColumsNames(db, TABLE_MANGAS);
            if (!tables.contains("source")) {
                db.execSQL("ALTER TABLE " + TABLE_MANGAS + " ADD COLUMN source TEXT");
            }
            if (!tables.contains("provider")) {
                db.execSQL("ALTER TABLE " + TABLE_MANGAS + " ADD COLUMN provider TEXT");
            }
        }
    }
}