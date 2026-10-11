"""Exercise production SQLite journal triggers without an Android emulator.

Run: python tools/test_community_journal.py
Uses the actual SQLDelight schema declarations, removing only Kotlin type adapters.
"""
from pathlib import Path
import re
import sqlite3
import unittest

ROOT = Path(__file__).resolve().parents[1]


class CommunityJournalTest(unittest.TestCase):
    def test_retirement_removes_sync_payloads_and_triggers_but_preserves_local_library(self):
        cleanup_source = (ROOT / 'app/src/main/java/eu/kanade/tachiyomi/data/community/CommunityRetirement.kt').read_text()
        cleanup = re.findall(r'"((?:DROP TRIGGER IF EXISTS|DELETE FROM) community_[a-z_]+)"', cleanup_source)
        self.assertEqual(19, len(cleanup))
        for anime in (True, False):
            with self.subTest(anime=anime), self.database(anime) as db:
                title, item = self.populate(db, anime)
                seen = 'seen' if anime else 'read'
                position = 'last_second_seen' if anime else 'last_page_read'
                db.execute('UPDATE community_capture SET enabled=1')
                db.execute(f'UPDATE {item} SET {position}=123,{seen}=1,bookmark=1 WHERE _id=2')
                db.execute("INSERT INTO categories(_id,name,sort,flags) VALUES(7,'Keep me',1,0)")
                self.assertGreater(db.execute('SELECT count(*) FROM community_changes').fetchone()[0], 0)
                before = tuple(db.execute(f'SELECT * FROM {item} WHERE _id=2').fetchone())
                for _ in range(2):
                    db.execute('UPDATE community_capture SET enabled=0')
                    for statement in cleanup:
                        db.execute(statement)
                self.assertEqual(before, tuple(db.execute(f'SELECT * FROM {item} WHERE _id=2').fetchone()))
                self.assertEqual(1, db.execute(f'SELECT favorite FROM {title} WHERE _id=1').fetchone()[0])
                self.assertEqual('Keep me', db.execute('SELECT name FROM categories WHERE _id=7').fetchone()[0])
                db.execute(f'UPDATE {item} SET {position}=124 WHERE _id=2')
                db.execute("INSERT INTO categories(_id,name,sort,flags) VALUES(8,'New local category',2,0)")
                for table in ('community_changes', 'community_category_ids', 'community_removed_categories'):
                    self.assertEqual(0, db.execute(f'SELECT count(*) FROM {table}').fetchone()[0])
                self.assertEqual(0, db.execute(
                    "SELECT count(*) FROM sqlite_master WHERE type='trigger' AND name LIKE 'community_%'"
                ).fetchone()[0])

    def database(self, anime, community=True):
        namespace = ROOT / ('data/src/main/sqldelightanime/dataanime' if anime else 'data/src/main/sqldelight/data')
        db = sqlite3.connect(':memory:')
        db.row_factory = sqlite3.Row
        files = ['animes.sq', 'episodes.sq', 'animehistory.sq', 'categories.sq', 'animes_categories.sq'] if anime else ['mangas.sq', 'chapters.sq', 'history.sq', 'categories.sq', 'mangas_categories.sq']
        # Read table and trigger declarations, not the named generated query bodies.
        for name in files + (['communitySync.sq'] if community else []):
            source = (namespace / name).read_text()
            schema = re.split(r'^\w+:\s*$', source, maxsplit=1, flags=re.M)[0]
            schema = re.sub(r'^import .*?;\s*', '', schema, flags=re.M)
            schema = re.sub(r'\b(INTEGER|TEXT|BLOB)\s+AS\s+[A-Z]\w*(?:<[^>]+>)?', r'\1', schema)
            db.executescript(schema)
        return db

    def populate(self, db, anime):
        title, item, parent = ('animes', 'episodes', 'anime_id') if anime else ('mangas', 'chapters', 'manga_id')
        # Supply required schema fields without mirroring trigger logic.
        def insert(table, values):
            for column in db.execute(f'PRAGMA table_info({table})').fetchall():
                if column['notnull'] and column['dflt_value'] is None and column['name'] not in values:
                    values[column['name']] = '' if 'TEXT' in column['type'] else 0
            cols = ','.join(values)
            db.execute(f'INSERT INTO {table}({cols}) VALUES({",".join("?" for _ in values)})', list(values.values()))
        insert(title, {'_id': 1, 'source': 9, 'url': '/title', 'title': 'A title', 'favorite': 1})
        insert(item, {'_id': 2, parent: 1, 'url': '/part/1', 'name': 'Part 1'})
        db.commit()
        return title, item

    def test_migration_keeps_existing_library_and_captures_restored_items(self):
        for anime in (True, False):
            with self.subTest(anime=anime), self.database(anime, community=False) as db:
                _, item = self.populate(db, anime)
                db.execute("INSERT INTO categories(_id,name,sort,flags) VALUES(7,'Existing',1,0)")
                migration = 'sqldelightanime/migrations/139.sqm' if anime else 'sqldelight/migrations/33.sqm'
                db.executescript((ROOT / 'data/src/main' / migration).read_text())
                self.assertEqual(1, db.execute(f'SELECT count(*) FROM {item}').fetchone()[0])
                self.assertEqual(1, db.execute('SELECT count(*) FROM community_category_ids WHERE local_id=7').fetchone()[0])
                self.assertEqual(0, db.execute('SELECT count(*) FROM community_changes').fetchone()[0])
                db.execute('UPDATE community_capture SET enabled=1')
                columns = [row['name'] for row in db.execute(f'PRAGMA table_info({item})')]
                expressions = []
                for column in columns:
                    if column == '_id': expressions.append('3')
                    elif column == 'url': expressions.append("'/part/2'")
                    elif column == ('seen' if anime else 'read'): expressions.append('1')
                    elif column == 'bookmark': expressions.append('1')
                    else: expressions.append(column)
                db.execute(f'INSERT INTO {item}({",".join(columns)}) SELECT {",".join(expressions)} FROM {item} WHERE _id=2')
                row = db.execute("SELECT * FROM community_changes WHERE item_url='/part/2'").fetchone()
                self.assertEqual(1, row['seen'])
                self.assertEqual(1, row['bookmark'])
                self.assertIn('Seen', row['fields'])
                self.assertIn('Bookmark', row['fields'])

    def test_progress_completion_bookmarks_and_atomic_rollback(self):
        for anime in (True, False):
            with self.subTest(anime=anime), self.database(anime) as db:
                _, item = self.populate(db, anime)
                seen = 'seen' if anime else 'read'
                position = 'last_second_seen' if anime else 'last_page_read'
                db.execute('UPDATE community_capture SET enabled=1')
                db.commit()
                db.execute(f'UPDATE {item} SET {position}=900,{seen}=1 WHERE _id=2')
                self.assertEqual(1, db.execute('SELECT count(*) FROM community_changes').fetchone()[0])
                db.rollback()
                self.assertEqual(0, db.execute('SELECT count(*) FROM community_changes').fetchone()[0])
                db.execute(f'UPDATE {item} SET {position}=900,{seen}=1 WHERE _id=2')
                db.execute(f'UPDATE {item} SET bookmark=1 WHERE _id=2')
                db.execute(f'UPDATE {item} SET {position}=100 WHERE _id=2')
                record = db.execute('SELECT * FROM community_changes').fetchone()
                self.assertEqual(100, record['position'])
                self.assertEqual(1, record['seen'])
                self.assertEqual(1, record['bookmark'])
                for field in ('Seen', 'Progress', 'Bookmark'):
                    self.assertIn(field, record['fields'])
                db.execute('DELETE FROM community_changes')
                db.execute(f'UPDATE {item} SET {position}=0,is_syncing=1 WHERE _id=2')
                self.assertEqual(0, db.execute('SELECT count(*) FROM community_changes').fetchone()[0])

    def test_partial_remote_bookmark_preserves_unregistered_local_progress(self):
        for anime in (True, False):
            with self.subTest(anime=anime), self.database(anime) as db:
                _, item = self.populate(db, anime)
                seen = 'seen' if anime else 'read'
                position = 'last_second_seen' if anime else 'last_page_read'
                db.execute(f'UPDATE {item} SET {position}=900,{seen}=1 WHERE _id=2')
                namespace = 'sqldelightanime/dataanime' if anime else 'sqldelight/data'
                source = (ROOT / 'data/src/main' / namespace / 'communitySync.sq').read_text()
                query = re.search(r'applyItem:\s*(.*?);', source, re.S).group(1)
                db.execute(query, dict(seen=None, bookmark=1, position=None, duration=None, url='/part/1', source=9, titleUrl='/title'))
                row = db.execute(f'SELECT * FROM {item} WHERE _id=2').fetchone()
                self.assertEqual(900, row[position])
                self.assertEqual(1, row[seen])
                self.assertEqual(1, row['bookmark'])

    def test_empty_categories_and_deletion_tombstones(self):
        for anime in (True, False):
            with self.subTest(anime=anime), self.database(anime) as db:
                self.populate(db, anime)
                db.execute('UPDATE community_capture SET enabled=1')
                db.execute("INSERT INTO categories(_id,name,sort,flags) VALUES(7,'Empty, category: 🐈',1,0)")
                identity = db.execute('SELECT id FROM community_category_ids WHERE local_id=7').fetchone()[0]
                self.assertEqual(32, len(identity))
                self.assertEqual(1, db.execute('SELECT count(*) FROM community_category_ids WHERE local_id=7').fetchone()[0])
                db.execute("UPDATE categories SET name='Renamed' WHERE _id=7")
                db.execute('DELETE FROM categories WHERE _id=7')
                row = db.execute('SELECT * FROM community_changes WHERE title_url=?', ('nyanime:category:'+identity,)).fetchone()
                self.assertEqual(1, row['deleted'])
                self.assertEqual(1, db.execute('SELECT count(*) FROM community_removed_categories WHERE id=?', (identity,)).fetchone()[0])

    def test_history_clear_is_a_change_and_remote_writes_do_not_echo(self):
        for anime in (True, False):
            with self.subTest(anime=anime), self.database(anime) as db:
                self.populate(db, anime)
                hist, item_id, at = ('animehistory', 'episode_id', 'last_seen') if anime else ('history', 'chapter_id', 'last_read')
                db.execute('UPDATE community_capture SET enabled=1')
                columns, values = (f'{item_id},{at}', '2,1234') if anime else (f'{item_id},{at},time_read', '2,1234,0')
                db.execute(f'INSERT INTO {hist}({columns}) VALUES({values})')
                self.assertEqual(1234, db.execute('SELECT history_at FROM community_changes').fetchone()[0])
                db.execute(f'DELETE FROM {hist}')
                self.assertEqual(0, db.execute('SELECT history_at FROM community_changes').fetchone()[0])
                db.execute('DELETE FROM community_changes')
                db.execute('UPDATE community_capture SET enabled=0')
                db.execute(f'INSERT INTO {hist}({columns}) VALUES({values})')
                self.assertEqual(0, db.execute('SELECT count(*) FROM community_changes').fetchone()[0])

    def test_live_resume_overtakes_a_large_initial_library_copy(self):
        for anime in (True, False):
            with self.subTest(anime=anime), self.database(anime) as db:
                _, item = self.populate(db, anime)
                position = 'last_second_seen' if anime else 'last_page_read'
                db.execute('UPDATE community_capture SET enabled=1')
                db.execute(f'UPDATE {item} SET {position}=100 WHERE _id=2')
                template = dict(db.execute('SELECT * FROM community_changes').fetchone())
                template.pop('seq')
                template['fields'] = 'all'
                columns = list(template)
                statement = f'INSERT INTO community_changes({",".join(columns)}) VALUES({",".join("?" for _ in columns)})'
                for index in range(1500):
                    template['title_url'] = f'/archive/{index}'
                    template['history_at'] = index + 1
                    db.execute(statement, list(template.values()))
                # A seed row may already contain all fields when playback modifies it again.
                db.execute("UPDATE community_changes SET fields='all' WHERE title_url='/title'")
                db.execute(f'UPDATE {item} SET {position}=500 WHERE _id=2')
                namespace = 'sqldelightanime/dataanime' if anime else 'sqldelight/data'
                source = (ROOT / 'data/src/main' / namespace / 'communitySync.sq').read_text()
                query = re.search(r'drain:\s*(.*?);', source, re.S).group(1)
                rows = db.execute(query).fetchall()
                self.assertEqual(128, len(rows))
                self.assertEqual('/title', rows[0]['title_url'])
                self.assertEqual(500, rows[0]['position'])
                self.assertEqual('/archive/1499', rows[1]['title_url'])
                # Turning capture off preserves existing work and makes later writes local only.
                db.execute('UPDATE community_capture SET enabled=0')
                db.execute(f'UPDATE {item} SET {position}=600 WHERE _id=2')
                self.assertEqual(1501, db.execute('SELECT count(*) FROM community_changes').fetchone()[0])
                self.assertEqual(500, db.execute(query).fetchone()['position'])


if __name__ == '__main__':
    unittest.main()
