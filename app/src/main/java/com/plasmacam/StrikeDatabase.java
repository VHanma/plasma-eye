package com.plasmacam;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;

public class StrikeDatabase extends SQLiteOpenHelper {

    private static final String DB_NAME = "strikes.db";
    private static final int DB_VERSION = 1;

    private static final String TABLE = "strikes";
    private static final String COL_ID = "id";
    private static final String COL_SPEED = "speed_ms";
    private static final String COL_POWER = "power_score";
    private static final String COL_TECHNIQUE = "technique_score";
    private static final String COL_TS = "timestamp_ms";
    private static final String COL_PERFECT = "is_perfect";

    public StrikeDatabase(Context ctx) {
        super(ctx, DB_NAME, null, DB_VERSION);
    }

    @Override
    public void onCreate(SQLiteDatabase db) {
        db.execSQL("CREATE TABLE " + TABLE + " (" +
                COL_ID + " INTEGER PRIMARY KEY AUTOINCREMENT, " +
                COL_SPEED + " REAL, " +
                COL_POWER + " REAL, " +
                COL_TECHNIQUE + " REAL, " +
                COL_TS + " INTEGER, " +
                COL_PERFECT + " INTEGER)");
    }

    @Override
    public void onUpgrade(SQLiteDatabase db, int oldVersion, int newVersion) {
        db.execSQL("DROP TABLE IF EXISTS " + TABLE);
        onCreate(db);
    }

    public void saveStrike(StrikeTracker.StrikeResult r) {
        ContentValues v = new ContentValues();
        v.put(COL_SPEED, r.speedMs);
        v.put(COL_POWER, r.powerScore);
        v.put(COL_TECHNIQUE, r.techniqueScore);
        v.put(COL_TS, r.timestampMs);
        v.put(COL_PERFECT, r.isPerfect ? 1 : 0);
        getWritableDatabase().insert(TABLE, null, v);
    }

    /** Returns personal best row for "speed" or "power" */
    public StrikeTracker.StrikeResult getPersonalBest(String metric) {
        String col = metric.equals("speed") ? COL_SPEED : COL_POWER;
        Cursor c = getReadableDatabase().query(
                TABLE, null, null, null, null, null, col + " DESC", "1");
        if (c == null) return null;
        StrikeTracker.StrikeResult r = null;
        if (c.moveToFirst()) {
            r = new StrikeTracker.StrikeResult();
            r.speedMs = c.getFloat(c.getColumnIndexOrThrow(COL_SPEED));
            r.powerScore = c.getFloat(c.getColumnIndexOrThrow(COL_POWER));
            r.techniqueScore = c.getFloat(c.getColumnIndexOrThrow(COL_TECHNIQUE));
            r.timestampMs = c.getLong(c.getColumnIndexOrThrow(COL_TS));
            r.isPerfect = c.getInt(c.getColumnIndexOrThrow(COL_PERFECT)) == 1;
        }
        c.close();
        return r;
    }

    public int getTotalStrikes() {
        Cursor c = getReadableDatabase().rawQuery("SELECT COUNT(*) FROM " + TABLE, null);
        int count = 0;
        if (c.moveToFirst()) count = c.getInt(0);
        c.close();
        return count;
    }

    public int getPerfectStrikes() {
        Cursor c = getReadableDatabase().rawQuery(
                "SELECT COUNT(*) FROM " + TABLE + " WHERE " + COL_PERFECT + "=1", null);
        int count = 0;
        if (c.moveToFirst()) count = c.getInt(0);
        c.close();
        return count;
    }

    /** Session summary: avg speed, avg power, total strikes in last N ms */
    public float[] getSessionStats(long sinceMs) {
        Cursor c = getReadableDatabase().rawQuery(
                "SELECT AVG(" + COL_SPEED + "), AVG(" + COL_POWER + "), COUNT(*) FROM " +
                TABLE + " WHERE " + COL_TS + " > ?",
                new String[]{String.valueOf(sinceMs)});
        float[] stats = {0, 0, 0};
        if (c.moveToFirst()) {
            stats[0] = c.getFloat(0);
            stats[1] = c.getFloat(1);
            stats[2] = c.getFloat(2);
        }
        c.close();
        return stats;
    }
}
