"""
Omega Persistent Memory v2
━━━━━━━━━━━━━━━━━━━━━━━━━━
✦ ذاكرة دائمة لا تُنسى أبداً (SQLite)
✦ ذاكرة قصيرة المدى (RAM)
✦ ذاكرة الحقائق والمهارات والكود
✦ بحث دلالي بسيط بدون GPU
✦ تذكر التفاعلات والمحادثات
✦ أولوية الذكريات المهمة
"""

import os
import json
import time
import math
import sqlite3
import hashlib
import re
from typing import List, Dict, Optional, Tuple
from dataclasses import dataclass


@dataclass
class MemoryEntry:
    id: str
    content: str
    summary: str
    mtype: str          # fact / skill / code / conversation / web / self_improvement
    tags: List[str]
    importance: float   # 0..1
    created_at: float
    accessed_at: float
    access_count: int
    source: str         # url / user / self / training


class OmegaPersistentMemory:
    """
    ذاكرة دائمة قائمة على SQLite
    - لا تُمحى عند إعادة التشغيل
    - بحث بالكلمات المفتاحية والتاريخ
    - تحديد أهمية تلقائي
    """

    def __init__(self, db_path: str = "memory/omega_memory.db"):
        os.makedirs(os.path.dirname(db_path) if os.path.dirname(db_path) else "memory",
                    exist_ok=True)
        self.db_path = db_path
        self.conn = sqlite3.connect(db_path, check_same_thread=False)
        self._init_db()
        self.stm: List[Dict] = []   # short-term memory (RAM)
        self.stm_limit = 100
        print(f"💾 Memory loaded: {self.count()} entries in {db_path}")

    def _init_db(self):
        self.conn.executescript("""
        CREATE TABLE IF NOT EXISTS memories (
            id           TEXT PRIMARY KEY,
            content      TEXT NOT NULL,
            summary      TEXT,
            mtype        TEXT DEFAULT 'fact',
            tags         TEXT DEFAULT '[]',
            importance   REAL DEFAULT 0.5,
            created_at   REAL,
            accessed_at  REAL,
            access_count INTEGER DEFAULT 0,
            source       TEXT DEFAULT 'user'
        );
        CREATE INDEX IF NOT EXISTS idx_type      ON memories(mtype);
        CREATE INDEX IF NOT EXISTS idx_imp       ON memories(importance DESC);
        CREATE INDEX IF NOT EXISTS idx_accessed  ON memories(accessed_at DESC);

        CREATE TABLE IF NOT EXISTS knowledge_graph (
            id        INTEGER PRIMARY KEY AUTOINCREMENT,
            entity_a  TEXT,
            relation  TEXT,
            entity_b  TEXT,
            weight    REAL DEFAULT 1.0
        );

        CREATE TABLE IF NOT EXISTS self_log (
            id         INTEGER PRIMARY KEY AUTOINCREMENT,
            timestamp  REAL,
            event_type TEXT,
            data       TEXT
        );

        CREATE TABLE IF NOT EXISTS learned_urls (
            url        TEXT PRIMARY KEY,
            title      TEXT,
            learned_at REAL,
            n_facts    INTEGER DEFAULT 0
        );
        """)
        self.conn.commit()

    # ── Store ─────────────────────────────────────────────────────────────
    def remember(self, content: str, mtype: str = 'fact',
                 tags: List[str] = None, importance: float = 0.5,
                 source: str = 'user', summary: str = None) -> str:
        mem_id = hashlib.sha256(content.encode()).hexdigest()[:12]
        now = time.time()
        summary = summary or content[:120]
        tags_json = json.dumps(tags or [], ensure_ascii=False)

        # Upsert
        self.conn.execute("""
            INSERT INTO memories(id,content,summary,mtype,tags,importance,
                                 created_at,accessed_at,access_count,source)
            VALUES(?,?,?,?,?,?,?,?,0,?)
            ON CONFLICT(id) DO UPDATE SET
                importance   = MAX(importance, excluded.importance),
                accessed_at  = excluded.accessed_at,
                access_count = access_count + 1
        """, (mem_id, content, summary, mtype, tags_json,
              importance, now, now, source))
        self.conn.commit()

        # Also in STM
        self.stm.append({'id': mem_id, 'content': content[:200],
                         'type': mtype, 'ts': now})
        if len(self.stm) > self.stm_limit:
            self.stm.pop(0)

        return mem_id

    def remember_code(self, key: str, code: str, lang: str = 'python'):
        tags = ['code', lang, key]
        return self.remember(
            f"[CODE:{lang}]\nkey={key}\n{code}",
            mtype='code', tags=tags, importance=0.8,
            summary=f"Code snippet: {key} ({lang})")

    def remember_url(self, url: str, title: str, facts: List[str]):
        self.conn.execute("""
            INSERT INTO learned_urls(url,title,learned_at,n_facts)
            VALUES(?,?,?,?)
            ON CONFLICT(url) DO UPDATE SET
                learned_at=excluded.learned_at,
                n_facts=n_facts+excluded.n_facts
        """, (url, title, time.time(), len(facts)))
        self.conn.commit()
        for fact in facts:
            self.remember(fact, mtype='web', source=url,
                          tags=['web', 'auto-learned'], importance=0.6)

    def log_self_event(self, event_type: str, data: dict):
        self.conn.execute(
            "INSERT INTO self_log(timestamp,event_type,data) VALUES(?,?,?)",
            (time.time(), event_type, json.dumps(data, ensure_ascii=False)))
        self.conn.commit()

    # ── Recall ────────────────────────────────────────────────────────────
    def recall(self, query: str, top_k: int = 8,
               mtype: Optional[str] = None) -> List[MemoryEntry]:
        words = set(re.findall(r'\w+', query.lower()))
        if not words:
            return []

        # Build WHERE
        conditions = []
        params = []
        if mtype:
            conditions.append("mtype = ?")
            params.append(mtype)

        # Fetch candidates (recent + important)
        where = f"WHERE {' AND '.join(conditions)}" if conditions else ""
        rows = self.conn.execute(f"""
            SELECT id,content,summary,mtype,tags,importance,
                   created_at,accessed_at,access_count,source
            FROM memories {where}
            ORDER BY importance DESC, accessed_at DESC
            LIMIT 200
        """, params).fetchall()

        # Score by keyword overlap + importance
        scored = []
        for row in rows:
            content_words = set(re.findall(r'\w+', row[1].lower()))
            overlap = len(words & content_words)
            if overlap == 0:
                continue
            score = overlap * row[5] * (1 + math.log1p(row[8]))
            scored.append((score, row))

        scored.sort(key=lambda x: x[0], reverse=True)

        results = []
        now = time.time()
        for _, row in scored[:top_k]:
            entry = MemoryEntry(
                id=row[0], content=row[1], summary=row[2],
                mtype=row[3], tags=json.loads(row[4]),
                importance=row[5], created_at=row[6],
                accessed_at=row[7], access_count=row[8],
                source=row[9]
            )
            # Update access
            self.conn.execute(
                "UPDATE memories SET accessed_at=?, access_count=access_count+1 WHERE id=?",
                (now, entry.id))
            results.append(entry)

        self.conn.commit()
        return results

    def recall_recent(self, n: int = 20) -> List[dict]:
        return self.stm[-n:]

    def recall_by_type(self, mtype: str, limit: int = 10) -> List[MemoryEntry]:
        rows = self.conn.execute("""
            SELECT id,content,summary,mtype,tags,importance,
                   created_at,accessed_at,access_count,source
            FROM memories WHERE mtype=?
            ORDER BY importance DESC, accessed_at DESC LIMIT ?
        """, (mtype, limit)).fetchall()
        return [MemoryEntry(id=r[0],content=r[1],summary=r[2],mtype=r[3],
                            tags=json.loads(r[4]),importance=r[5],
                            created_at=r[6],accessed_at=r[7],
                            access_count=r[8],source=r[9]) for r in rows]

    def get_learned_urls(self) -> List[dict]:
        rows = self.conn.execute(
            "SELECT url,title,learned_at,n_facts FROM learned_urls ORDER BY learned_at DESC"
        ).fetchall()
        return [{'url': r[0], 'title': r[1], 'learned_at': r[2], 'n_facts': r[3]}
                for r in rows]

    def get_self_log(self, last_n: int = 50) -> List[dict]:
        rows = self.conn.execute(
            "SELECT timestamp,event_type,data FROM self_log ORDER BY id DESC LIMIT ?",
            (last_n,)).fetchall()
        return [{'ts': r[0], 'type': r[1], 'data': json.loads(r[2])} for r in rows]

    # ── Maintenance ───────────────────────────────────────────────────────
    def reinforce(self, mem_id: str, delta: float = 0.05):
        """تقوية ذكرى معينة"""
        self.conn.execute(
            "UPDATE memories SET importance=MIN(1.0,importance+?) WHERE id=?",
            (delta, mem_id))
        self.conn.commit()

    def forget_weak(self, threshold: float = 0.05):
        """نسيان الذكريات الضعيفة جداً"""
        cur = self.conn.execute(
            "DELETE FROM memories WHERE importance < ? AND access_count = 0",
            (threshold,))
        self.conn.commit()
        return cur.rowcount

    def count(self, mtype: str = None) -> int:
        if mtype:
            r = self.conn.execute("SELECT COUNT(*) FROM memories WHERE mtype=?", (mtype,))
        else:
            r = self.conn.execute("SELECT COUNT(*) FROM memories")
        return r.fetchone()[0]

    def stats(self) -> dict:
        types = {}
        for row in self.conn.execute(
                "SELECT mtype, COUNT(*) FROM memories GROUP BY mtype").fetchall():
            types[row[0]] = row[1]
        urls = self.conn.execute("SELECT COUNT(*) FROM learned_urls").fetchone()[0]
        logs = self.conn.execute("SELECT COUNT(*) FROM self_log").fetchone()[0]
        return {
            'total': self.count(),
            'by_type': types,
            'stm_size': len(self.stm),
            'learned_urls': urls,
            'self_events': logs,
        }

    def close(self):
        self.conn.close()
