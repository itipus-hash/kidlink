package com.kidlink.beacon.data;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;

import java.util.ArrayList;
import java.util.List;

/**
 * SQLite 数据库帮助类
 * 纯本地存储，所有数据不上云
 */
public class BeaconDbHelper extends SQLiteOpenHelper {

    private static final String DB_NAME = "kidlink_beacon.db";
    private static final int DB_VERSION = 3;

    public static final String TABLE_BEACONS = "beacons";

    public static final String COL_ID = "id";
    public static final String COL_NAME = "name";
    public static final String COL_REMARK = "remark";
    public static final String COL_UUID = "uuid";
    public static final String COL_MAJOR = "major";
    public static final String COL_MINOR = "minor";
    public static final String COL_RSSI_1M = "rssi_1m";
    public static final String COL_CREATE_TIME = "create_time";
    public static final String COL_SORT_ORDER = "sort_order";
    public static final String COL_QR_CONTENT = "qr_content";
    /** 设备蓝牙地址：三件套撞车的两个信标靠它区分 */
    public static final String COL_MAC = "mac";

    private static final String CREATE_TABLE =
            "CREATE TABLE " + TABLE_BEACONS + " (" +
            COL_ID + " INTEGER PRIMARY KEY AUTOINCREMENT, " +
            COL_NAME + " TEXT NOT NULL, " +
            COL_REMARK + " TEXT, " +
            COL_UUID + " TEXT NOT NULL, " +
            COL_MAJOR + " INTEGER NOT NULL, " +
            COL_MINOR + " INTEGER NOT NULL, " +
            COL_RSSI_1M + " INTEGER DEFAULT -59, " +
            COL_CREATE_TIME + " INTEGER DEFAULT 0, " +
            COL_SORT_ORDER + " INTEGER DEFAULT 0, " +
            COL_MAC + " TEXT DEFAULT '', " +
            COL_QR_CONTENT + " TEXT DEFAULT '')";

    public BeaconDbHelper(Context context) {
        super(context, DB_NAME, null, DB_VERSION);
    }

    @Override
    public void onCreate(SQLiteDatabase db) {
        db.execSQL(CREATE_TABLE);
    }

    @Override
    public void onUpgrade(SQLiteDatabase db, int oldVersion, int newVersion) {
        // 只增量加列，绝不删表——避免用户已有的信标丢失
        if (oldVersion < 2) {
            try {
                db.execSQL("ALTER TABLE " + TABLE_BEACONS + " ADD COLUMN " +
                        COL_SORT_ORDER + " INTEGER DEFAULT 0");
            } catch (Exception ignored) {}
            // calib_distance / calib_rssi 两列已随标定功能一起移除。
            // 老库里残留这两列不影响读写（新代码不再引用），也不需要 DROP。
            try {
                db.execSQL("ALTER TABLE " + TABLE_BEACONS + " ADD COLUMN " +
                        COL_QR_CONTENT + " TEXT DEFAULT ''");
            } catch (Exception ignored) {}
        }
        if (oldVersion < 3) {
            try {
                db.execSQL("ALTER TABLE " + TABLE_BEACONS + " ADD COLUMN " +
                        COL_MAC + " TEXT DEFAULT ''");
            } catch (Exception ignored) {}
        }
    }

    // === CRUD ===

    public long insertBeacon(BeaconEntity beacon) {
        SQLiteDatabase db = getWritableDatabase();
        if (beacon.getSortOrder() <= 0) {
            beacon.setSortOrder(getNextSortOrder());
        }
        ContentValues cv = beaconToValues(beacon);
        return db.insert(TABLE_BEACONS, null, cv);
    }

    /** 新信标排在最末（占位格之上） */
    public int getNextSortOrder() {
        SQLiteDatabase db = getReadableDatabase();
        Cursor cursor = db.rawQuery(
                "SELECT IFNULL(MAX(" + COL_SORT_ORDER + "), 0) + 1 FROM " + TABLE_BEACONS, null);
        int next = 1;
        if (cursor != null) {
            if (cursor.moveToFirst()) next = cursor.getInt(0);
            cursor.close();
        }
        return next;
    }

    public int updateBeacon(BeaconEntity beacon) {
        SQLiteDatabase db = getWritableDatabase();
        ContentValues cv = beaconToValues(beacon);
        return db.update(TABLE_BEACONS, cv, COL_ID + " = ?",
                new String[]{String.valueOf(beacon.getId())});
    }

    public int deleteBeacon(long id) {
        SQLiteDatabase db = getWritableDatabase();
        return db.delete(TABLE_BEACONS, COL_ID + " = ?", new String[]{String.valueOf(id)});
    }

    public BeaconEntity getBeaconById(long id) {
        if (id <= 0) return null;
        SQLiteDatabase db = getReadableDatabase();
        Cursor cursor = db.query(TABLE_BEACONS, null, COL_ID + " = ?",
                new String[]{String.valueOf(id)}, null, null, null);
        if (cursor != null && cursor.moveToFirst()) {
            BeaconEntity beacon = cursorToBeacon(cursor);
            cursor.close();
            return beacon;
        }
        if (cursor != null) cursor.close();
        return null;
    }

    public List<BeaconEntity> getAllBeacons() {
        List<BeaconEntity> list = new ArrayList<>();
        SQLiteDatabase db = getReadableDatabase();
        Cursor cursor = db.query(TABLE_BEACONS, null, null, null, null, null,
                COL_SORT_ORDER + " ASC, " + COL_ID + " ASC");
        if (cursor != null && cursor.moveToFirst()) {
            do {
                list.add(cursorToBeacon(cursor));
            } while (cursor.moveToNext());
        }
        if (cursor != null) cursor.close();
        return list;
    }

    /**
     * 批量保存排序（拖动结束后调用）
     */
    public void updateSortOrders(List<BeaconEntity> ordered) {
        if (ordered == null || ordered.isEmpty()) return;
        SQLiteDatabase db = getWritableDatabase();
        db.beginTransaction();
        try {
            for (int i = 0; i < ordered.size(); i++) {
                BeaconEntity b = ordered.get(i);
                b.setSortOrder(i + 1);
                ContentValues cv = new ContentValues();
                cv.put(COL_SORT_ORDER, i + 1);
                db.update(TABLE_BEACONS, cv, COL_ID + " = ?",
                        new String[]{String.valueOf(b.getId())});
            }
            db.setTransactionSuccessful();
        } finally {
            db.endTransaction();
        }
    }

    /**
     * 搜索信标（名称/UUID/Major/Minor）
     */
    public List<BeaconEntity> searchBeacons(String keyword) {
        List<BeaconEntity> list = new ArrayList<>();
        if (keyword == null || keyword.trim().isEmpty()) {
            return getAllBeacons();
        }
        String kw = "%" + keyword.trim() + "%";
        SQLiteDatabase db = getReadableDatabase();
        Cursor cursor = db.query(TABLE_BEACONS, null,
                COL_NAME + " LIKE ? OR " + COL_UUID + " LIKE ? OR " +
                "CAST(" + COL_MAJOR + " AS TEXT) LIKE ? OR " +
                "CAST(" + COL_MINOR + " AS TEXT) LIKE ?",
                new String[]{kw, kw, kw, kw}, null, null,
                COL_SORT_ORDER + " ASC, " + COL_ID + " ASC");
        if (cursor != null && cursor.moveToFirst()) {
            do {
                list.add(cursorToBeacon(cursor));
            } while (cursor.moveToNext());
        }
        if (cursor != null) cursor.close();
        return list;
    }

    /**
     * 是否已经存过同一个信标。
     *
     * 有 MAC 就按 MAC 判重 —— 同品牌的信标出厂三件套可能完全一样，
     * 只看 UUID+Major+Minor 会把第二台当成第一台的重复。
     * 没有 MAC（旧数据 / 扫码导入）才退回三件套比对。
     */
    public boolean isDuplicate(String uuid, int major, int minor, String mac) {
        SQLiteDatabase db = getReadableDatabase();
        String m = mac == null ? "" : mac.trim().toUpperCase();
        Cursor cursor;
        if (!m.isEmpty()) {
            cursor = db.query(TABLE_BEACONS, new String[]{COL_ID},
                    "UPPER(" + COL_MAC + ") = ?", new String[]{m},
                    null, null, null);
        } else {
            cursor = db.query(TABLE_BEACONS, new String[]{COL_ID},
                    COL_UUID + " LIKE ? AND " + COL_MAJOR + " = ? AND " + COL_MINOR + " = ?",
                    new String[]{"%" + uuid.replace("-", "") + "%",
                            String.valueOf(major), String.valueOf(minor)},
                    null, null, null);
        }
        boolean exists = cursor != null && cursor.getCount() > 0;
        if (cursor != null) cursor.close();
        return exists;
    }

    /**
     * 把探测到的 MAC 补写进已有记录（传空串 = 解绑，下次收到广播重新学）。
     * 旧版本存下来的信标没有 MAC，第一次收到广播时自动绑定，之后就靠它区分。
     */
    public void updateMac(long id, String mac) {
        if (id <= 0) return;
        ContentValues cv = new ContentValues();
        cv.put(COL_MAC, mac == null ? "" : mac.trim().toUpperCase());
        getWritableDatabase().update(TABLE_BEACONS, cv, COL_ID + " = ?",
                new String[]{String.valueOf(id)});
    }

    private ContentValues beaconToValues(BeaconEntity beacon) {
        ContentValues cv = new ContentValues();
        cv.put(COL_NAME, beacon.getName());
        cv.put(COL_REMARK, beacon.getRemark() != null ? beacon.getRemark() : "");
        cv.put(COL_UUID, beacon.getUuidRaw());
        cv.put(COL_MAJOR, beacon.getMajor());
        cv.put(COL_MINOR, beacon.getMinor());
        cv.put(COL_RSSI_1M, beacon.getRssiAt1m());
        cv.put(COL_CREATE_TIME, beacon.getCreateTime());
        cv.put(COL_SORT_ORDER, beacon.getSortOrder());
        cv.put(COL_MAC, beacon.getMac());
        cv.put(COL_QR_CONTENT, beacon.getQrContent());
        return cv;
    }

    private BeaconEntity cursorToBeacon(Cursor cursor) {
        BeaconEntity beacon = new BeaconEntity();
        beacon.setId(cursor.getLong(cursor.getColumnIndexOrThrow(COL_ID)));
        beacon.setName(cursor.getString(cursor.getColumnIndexOrThrow(COL_NAME)));
        beacon.setRemark(cursor.getString(cursor.getColumnIndexOrThrow(COL_REMARK)));
        String rawUuid = cursor.getString(cursor.getColumnIndexOrThrow(COL_UUID));
        beacon.setUuid(rawUuid);
        beacon.setMajor(cursor.getInt(cursor.getColumnIndexOrThrow(COL_MAJOR)));
        beacon.setMinor(cursor.getInt(cursor.getColumnIndexOrThrow(COL_MINOR)));
        beacon.setRssiAt1m(cursor.getInt(cursor.getColumnIndexOrThrow(COL_RSSI_1M)));
        beacon.setCreateTime(cursor.getLong(cursor.getColumnIndexOrThrow(COL_CREATE_TIME)));
        // 新列采用容错读取，兼容极端情况下未完成 ALTER 的旧库
        int idxSort = cursor.getColumnIndex(COL_SORT_ORDER);
        beacon.setSortOrder(idxSort >= 0 ? cursor.getInt(idxSort) : 0);
        int idxQr = cursor.getColumnIndex(COL_QR_CONTENT);
        beacon.setQrContent(idxQr >= 0 ? cursor.getString(idxQr) : "");
        int idxMac = cursor.getColumnIndex(COL_MAC);
        beacon.setMac(idxMac >= 0 ? cursor.getString(idxMac) : "");
        return beacon;
    }
}
