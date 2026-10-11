"""Verify removal against production SQLite schemas and immutable historical migrations."""
from pathlib import Path
import re
import sqlite3
import unittest

ROOT = Path(__file__).resolve().parents[1]


def schema_statements(directory):
    statements = []
    for file in sorted(directory.glob('*.sq')):
        source = re.split(r'^\w+:\s*$', file.read_text(encoding='utf-8'), maxsplit=1, flags=re.M)[0]
        source = re.sub(r'^import .*?;\s*', '', source, flags=re.M)
        source = re.sub(r'\b(INTEGER|TEXT|BLOB|REAL)\s+AS\s+[A-Z]\w*(?:<[^>]+>)?', r'\1', source)
        pending = ''
        for line in source.splitlines(keepends=True):
            pending += line
            if sqlite3.complete_statement(pending):
                statements.append(pending)
                pending = ''
    def priority(statement):
        code = re.sub(r'--[^\n]*', '', statement).strip().upper()
        if code.startswith('CREATE TABLE') or code.startswith('CREATE VIRTUAL TABLE'):
            return 0
        if code.startswith('CREATE TRIGGER'):
            return 2
        return 1
    return sorted(statements, key=priority)


class RetiredProfileMigrationsTest(unittest.TestCase):
    def database(self, anime):
        base = ROOT / ('data/src/main/sqldelightanime' if anime else 'data/src/main/sqldelight')
        db = sqlite3.connect(':memory:')
        db.row_factory = sqlite3.Row
        for statement in schema_statements(base / ('dataanime' if anime else 'data')):
            db.executescript(statement)
        db.execute('PRAGMA foreign_keys=ON')
        return db, base

    def insert(self, db, table, values):
        for column in db.execute(f'PRAGMA table_info({table})'):
            if column['notnull'] and column['dflt_value'] is None and column['name'] not in values:
                values[column['name']] = '' if 'TEXT' in column['type'] else 0
        db.execute(f'INSERT INTO {table}({",".join(values)}) VALUES({",".join("?" for _ in values)})', list(values.values()))

    def test_fresh_install_has_no_retired_journal(self):
        for anime in (True, False):
            with self.subTest(anime=anime):
                db, _ = self.database(anime)
                with db:
                    self.assertEqual(0, db.execute("SELECT count(*) FROM sqlite_master WHERE name LIKE 'community_%'").fetchone()[0])
                    self.assertGreater(db.execute("SELECT count(*) FROM sqlite_master WHERE name LIKE 'cloud_sync_%'").fetchone()[0], 0)

    def test_upgrade_preserves_library_history_categories_and_cloud_updates(self):
        for anime in (True, False):
            with self.subTest(anime=anime):
                db, base = self.database(anime)
                title, item, parent, history, history_item = (
                    ('animes', 'episodes', 'anime_id', 'animehistory', 'episode_id') if anime else
                    ('mangas', 'chapters', 'manga_id', 'history', 'chapter_id')
                )
                seen, position = ('seen', 'last_second_seen') if anime else ('read', 'last_page_read')
                self.insert(db, title, {'_id': 1, 'source': 9, 'url': '/title', 'title': 'A title', 'favorite': 1})
                self.insert(db, item, {'_id': 2, parent: 1, 'url': '/part/1', 'name': 'Part 1', seen: 1, 'bookmark': 1, position: 123})
                self.insert(db, 'categories', {'_id': 7, 'name': 'My category', 'sort': 1})
                self.insert(db, history, {'_id': 3, history_item: 2})
                db.executescript((base / 'migrations' / ('139.sqm' if anime else '33.sqm')).read_text())
                db.execute('UPDATE community_capture SET enabled=1')
                db.execute('UPDATE cloud_sync_control SET enabled=1')
                db.execute(f'UPDATE {item} SET {position}=124 WHERE _id=2')
                self.assertGreater(db.execute('SELECT count(*) FROM community_changes').fetchone()[0], 0)
                before = {table: [tuple(row) for row in db.execute('SELECT * FROM '+table)] for table in (title, item, 'categories', history)}
                cloud_before = [tuple(row) for row in db.execute('SELECT * FROM cloud_sync_dirty ORDER BY revision')]
                migration = (base / 'migrations' / ('150.sqm' if anime else '40.sqm')).read_text()
                for _ in range(2):
                    db.executescript(migration)
                self.assertEqual(0, db.execute("SELECT count(*) FROM sqlite_master WHERE name LIKE 'community_%'").fetchone()[0])
                for table, rows in before.items():
                    self.assertEqual(rows, [tuple(row) for row in db.execute('SELECT * FROM '+table)])
                self.assertEqual(cloud_before, [tuple(row) for row in db.execute('SELECT * FROM cloud_sync_dirty ORDER BY revision')])
                self.assertEqual(1, db.execute('SELECT enabled FROM cloud_sync_control').fetchone()[0])
                db.execute(f'UPDATE {item} SET {position}=125 WHERE _id=2')
                self.assertGreater(db.execute('SELECT max(revision) FROM cloud_sync_dirty').fetchone()[0], max(row[0] for row in cloud_before))
                self.insert(db, 'categories', {'_id': 8, 'name': 'Another category', 'sort': 2})
                self.assertEqual(2, db.execute('SELECT count(*) FROM categories WHERE _id IN (7,8)').fetchone()[0])
                db.close()


if __name__ == '__main__':
    unittest.main()
