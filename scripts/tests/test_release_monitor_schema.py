"""Exercise the real migrations and outbox SQL against SQLite, without a device."""
import re
import sqlite3
import unittest
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]


class ReleaseSchemaTest(unittest.TestCase):
    def database(self, anime):
        db = sqlite3.connect(":memory:")
        db.execute("PRAGMA foreign_keys=ON")
        parent, item = ("animes", "episodes") if anime else ("mangas", "chapters")
        fk, seen, pos = ("anime_id", "seen", "last_second_seen") if anime else ("manga_id", "read", "last_page_read")
        number = "episode_number" if anime else "chapter_number"
        db.execute(f"CREATE TABLE {parent} (_id INTEGER PRIMARY KEY, source INTEGER, title TEXT, favorite INTEGER, date_added INTEGER, thumbnail_url TEXT, cover_last_modified INTEGER, viewer INTEGER, parent_id INTEGER)")
        db.execute(f"CREATE TABLE {item} (_id INTEGER PRIMARY KEY, {fk} INTEGER REFERENCES {parent}(_id) ON DELETE CASCADE, name TEXT, scanlator TEXT, {seen} INTEGER, bookmark INTEGER, {pos} INTEGER, total_seconds INTEGER, fillermark INTEGER, date_fetch INTEGER, date_upload INTEGER, {number} REAL)")
        tracker, history, historyfk = ("anime_sync", "animehistory", "episode_id") if anime else ("manga_sync", "history", "chapter_id")
        db.execute(f"CREATE TABLE {tracker} ({fk} INTEGER)")
        db.execute(f"CREATE TABLE {history} ({historyfk} INTEGER, " + ("last_seen" if anime else "last_read") + " INTEGER)")
        view = "animeupdatesView" if anime else "updatesView"
        db.execute(f"CREATE VIEW {view} AS SELECT 1")
        db.execute(f"INSERT INTO {parent} VALUES (1,42,'Title',1,0,'',0,0,0)")
        db.execute(f"INSERT INTO {item} VALUES (11,1,'Item','',0,0,0,0,0,2000,2000,1)")
        root = "sqldelightanime" if anime else "sqldelight"
        migration = "141.sqm" if anime else "35.sqm"
        db.executescript((ROOT / f"data/src/main/{root}/migrations/{migration}").read_text(encoding="utf-8"))
        correction = "142.sqm" if anime else "36.sqm"
        db.executescript((ROOT / f"data/src/main/{root}/migrations/{correction}").read_text(encoding="utf-8"))
        query_file = ROOT / f"data/src/main/{root}/{'dataanime' if anime else 'data'}/releaseMonitor.sq"
        return db, query_file, parent, item, fk, seen

    def query(self, path, name):
        return re.search(rf"^{name}:\s*(.*?);", path.read_text(encoding="utf-8"), re.M | re.S)[1]

    def test_seasons_inherit_follow_and_parent_exclusion(self):
        db, path, parent, *_ = self.database(True)
        db.execute(f"INSERT INTO {parent}(_id,favorite,parent_id) VALUES (2,0,1)")
        query = self.query(path, "getMonitoredIds")
        self.assertEqual({1, 2}, {row[0] for row in db.execute(query)})
        db.execute(self.query(path, "setSubscription"), (1, "IGNORE", 1, 1))
        self.assertEqual([], db.execute(query).fetchall())
        db.execute(self.query(path, "setSubscription"), (2, "FOLLOW", 1, 1))
        self.assertEqual([(2,)], db.execute(query).fetchall())
        db.close()

    def test_follow_outside_library_uses_the_existing_updates_view(self):
        for anime in (False, True):
            db, path, parent, *_ = self.database(anime)
            db.execute(f"UPDATE {parent} SET favorite=0")
            view = "animeupdatesView" if anime else "updatesView"
            self.assertEqual([], db.execute(f"SELECT * FROM {view}").fetchall())
            db.execute(self.query(path, "queueNotice"), (11, 1, 2000, 0))
            self.assertEqual(1, len(db.execute(f"SELECT * FROM {view}").fetchall()))
            db.close()

    def test_outbox_commit_is_atomic_and_deduplicated(self):
        for anime in (False, True):
            db, path, _, item, *_ = self.database(anime)
            with self.assertRaises(RuntimeError):
                with db:
                    db.execute(f"INSERT INTO {item} SELECT 12," + "1,'New','',0,0,0,0,0,3000,3000,2")
                    db.execute(self.query(path, "queueNotice"), (12, 1, 3000, 0))
                    raise RuntimeError("interrupt transaction")
            self.assertEqual(0, db.execute("SELECT count(*) FROM release_notice").fetchone()[0])
            with db:
                db.execute(f"INSERT INTO {item} SELECT 12," + "1,'New','',0,0,0,0,0,3000,3000,2")
                for _ in range(2):
                    db.execute(self.query(path, "queueNotice"), (12, 1, 3000, 0))
            self.assertEqual(1, len(db.execute(self.query(path, "getPending")).fetchall()))
            db.execute("UPDATE release_notice SET delivered_at=4000")
            self.assertEqual([], db.execute(self.query(path, "getPending")).fetchall())
            db.close()

    def test_explicit_follow_and_ignore_override_automatic_membership(self):
        for anime in (False, True):
            db, path, parent, *_ = self.database(anime)
            db.execute(f"UPDATE {parent} SET favorite=0")
            self.assertEqual([], db.execute(self.query(path, "getMonitoredIds")).fetchall())
            db.execute(self.query(path, "setSubscription"), (1, "FOLLOW", 1, 1))
            self.assertEqual([(1,)], db.execute(self.query(path, "getMonitoredIds")).fetchall())
            db.execute(f"UPDATE {parent} SET favorite=1")
            db.execute(self.query(path, "setSubscription"), (1, "IGNORE", 1, 1))
            self.assertEqual([], db.execute(self.query(path, "getMonitoredIds")).fetchall())
            db.close()

    def test_seen_items_disappear_without_destroying_delivery_receipt(self):
        for anime in (False, True):
            db, path, _, item, _, seen = self.database(anime)
            db.execute(self.query(path, "queueNotice"), (11, 1, 2000, 0))
            self.assertEqual(1, len(db.execute(self.query(path, "getNotices")).fetchall()))
            db.execute(f"UPDATE {item} SET {seen}=1")
            self.assertEqual([], db.execute(self.query(path, "getNotices")).fetchall())
            self.assertEqual(1, db.execute("SELECT count(*) FROM release_notice").fetchone()[0])
            db.close()

    def test_deleting_title_removes_follow_checkpoint_and_outbox(self):
        for anime in (False, True):
            db, path, parent, *_ = self.database(anime)
            db.execute(self.query(path, "setSubscription"), (1, "FOLLOW", 1, 1))
            db.execute(self.query(path, "queueNotice"), (11, 1, 2000, 0))
            db.execute(f"DELETE FROM {parent}")
            for table in ("release_check", "release_notice", "release_subscription"):
                self.assertEqual(0, db.execute(f"SELECT count(*) FROM {table}").fetchone()[0])
            db.close()

    def test_failure_keeps_success_and_success_clears_backoff(self):
        db, path, *_ = self.database(True)
        db.execute(self.query(path, "markSuccess"), {"entryId": 1, "now": 3000, "nextCheck": 4000})
        db.execute(self.query(path, "markFailure"), {"entryId": 1, "now": 5000, "nextCheck": 6000})
        self.assertEqual((5000, 3000, 6000, 1), db.execute("SELECT last_attempt,last_success,next_check,failures FROM release_check").fetchone())
        db.execute(self.query(path, "markSuccess"), {"entryId": 1, "now": 7000, "nextCheck": 8000})
        self.assertEqual((7000, 8000, 0), db.execute("SELECT last_success,next_check,failures FROM release_check").fetchone())
        db.close()

    def test_acquired_history_is_not_a_publication_today(self):
        for anime in (False, True):
            db, path, _, _, *_ = self.database(anime)
            db.execute("INSERT INTO release_notice(item_id,entry_id,created_at,delivered_at) VALUES (11,1,999999,1)")
            # Home keeps undated new items visible. The calendar uses source_at,
            # never the acquisition date; legacy undated receipts are cleaned by the migration.
            notice = db.execute(self.query(path, "getNotices")).fetchone()
            self.assertEqual(999999, notice[2])
            self.assertEqual(0, notice[4])
            migration = ROOT / f"data/src/main/{'sqldelightanime' if anime else 'sqldelight'}/migrations/{'142' if anime else '36'}.sqm"
            cleanup = migration.read_text(encoding="utf-8").split("ALTER TABLE")[0]
            db.executescript(cleanup)
            self.assertEqual(0, db.execute("SELECT count(*) FROM release_notice").fetchone()[0])
            self.assertEqual(1, db.execute(f"SELECT count(*) FROM {'episodes' if anime else 'chapters'}").fetchone()[0])
            db.execute(self.query(path, "queueNotice"), (11, 1, 999999, 1000))
            self.assertEqual((999999, 1000), db.execute("SELECT created_at,source_at FROM release_notice").fetchone())
            db.executescript(cleanup)
            self.assertEqual(1, db.execute("SELECT count(*) FROM release_notice").fetchone()[0])
            db.close()

    def test_verifying_an_existing_notice_repairs_date_without_redelivery(self):
        for anime in (False, True):
            db, path, *_ = self.database(anime)
            db.execute(self.query(path, "queueNotice"), (11, 1, 999999, 0))
            db.execute("UPDATE release_notice SET delivered_at=123456")
            db.execute(self.query(path, "verifyPublicationDate"), {"sourceAt": 1000, "itemId": 11})
            self.assertEqual((999999, 123456, 1000), db.execute(
                "SELECT created_at,delivered_at,source_at FROM release_notice"
            ).fetchone())
            self.assertEqual([], db.execute(self.query(path, "getPending")).fetchall())
            db.close()

    def test_publication_repair_is_queued_only_for_unverified_notices(self):
        for anime in (False, True):
            db, path, parent, item, *_ = self.database(anime)
            db.execute(f"INSERT INTO {parent} SELECT 2,42,'Second',1,0,'',0,0,0")
            db.execute(f"INSERT INTO {item} SELECT 12,2,'Second','',0,0,0,0,0,3000,3000,2")
            for entry in (1, 2):
                db.execute(self.query(path, "markSuccess"), {"entryId": entry, "now": 3000, "nextCheck": 6000})
            db.execute(self.query(path, "queueNotice"), (11, 1, 999999, 0))
            db.execute(self.query(path, "queueNotice"), (12, 2, 999999, 1000))
            migration = ROOT / f"data/src/main/{'sqldelightanime' if anime else 'sqldelight'}/migrations/{'143' if anime else '37'}.sqm"
            db.executescript(migration.read_text(encoding="utf-8"))
            self.assertEqual([(1, 0), (2, 6000)], db.execute(
                "SELECT entry_id,next_check FROM release_check ORDER BY entry_id"
            ).fetchall())
            db.close()

    def test_reminder_migration_and_schema_agree_without_consuming_broadcast(self):
        path = ROOT / "data/src/main/sqldelightanime/dataanime/reminderReceipt.sq"
        migration = ROOT / "data/src/main/sqldelightanime/migrations/146.sqm"
        for schema in (migration.read_text(encoding="utf-8"), path.read_text(encoding="utf-8").split("getReceipts:")[0]):
            db = sqlite3.connect(":memory:")
            db.executescript(schema)
            for _ in range(2):
                db.execute(self.query(path, "recordReceipt"), ("catalog:42:3", "ADVANCE", 1000))
            self.assertEqual([("catalog:42:3", "ADVANCE")], db.execute(self.query(path, "getReceipts")).fetchall())
            db.execute(self.query(path, "recordReceipt"), ("catalog:42:3", "AIRING", 2000))
            self.assertEqual(2, db.execute("SELECT COUNT(*) FROM reminder_receipt").fetchone()[0])
            db.execute(self.query(path, "pruneReceipts"), (1500,))
            self.assertEqual([("catalog:42:3", "AIRING")], db.execute(self.query(path, "getReceipts")).fetchall())
            db.close()

    def test_reminder_alias_receipts_roll_back_together(self):
        path = ROOT / "data/src/main/sqldelightanime/dataanime/reminderReceipt.sq"
        db = sqlite3.connect(":memory:")
        db.executescript(path.read_text(encoding="utf-8").split("getReceipts:")[0])
        with self.assertRaises(RuntimeError):
            with db:
                for key in ("catalog:42:3", "entry:7:3"):
                    db.execute(self.query(path, "recordReceipt"), (key, "ADVANCE", 1000))
                raise RuntimeError("interrupted acknowledgement")
        self.assertEqual([], db.execute(self.query(path, "getReceipts")).fetchall())
        db.close()


if __name__ == "__main__":
    unittest.main()
