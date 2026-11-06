"""Execute the production Room SELECT against SQLite; no Android or source-match assertions."""
import pathlib
import re
import sqlite3
import unittest


class ReceiptQueryTest(unittest.TestCase):
    def test_workspace_filters_and_pagination(self):
        root = next(p for p in pathlib.Path(__file__).resolve().parents if (p / "core-storage").is_dir())
        source = (root / "core-storage/src/commonMain/kotlin/com/aharou/feature/agent/data/local/dao/ToolCallReceiptDao.kt").read_text()
        annotation = source.split("suspend fun querySession", 1)[0].rsplit("@Query(", 1)[1]
        sql = "".join(re.findall(r'"([^"\n]*)"', annotation))
        db = sqlite3.connect(":memory:")
        db.executescript("CREATE TABLE chat_sessions(id TEXT,workspacePath TEXT); CREATE TABLE tool_call_receipts(id INTEGER,sessionId TEXT,toolName TEXT,status TEXT,createdAt INTEGER); INSERT INTO chat_sessions VALUES('a','one'),('b','two'); INSERT INTO tool_call_receipts VALUES(1,'a','read','SUCCESS',10),(2,'a','read','ERROR',20),(3,'b','read','SUCCESS',30),(4,'a','write','SUCCESS',40);")
        args = dict(sessionId="a", workspace="two", tool=None, status=None, after=None, before=None, limit=20, offset=0)
        self.assertEqual([], db.execute(sql, args).fetchall())
        args.update(workspace="one", tool="read", status="SUCCESS", after=10, before=10)
        self.assertEqual([1], [r[0] for r in db.execute(sql,args)])
        args.update(tool=None,status=None,after=None,before=None,limit=1,offset=1)
        self.assertEqual([2], [r[0] for r in db.execute(sql,args)])
        args.update(tool="read' OR 1=1 --")
        self.assertEqual([], db.execute(sql,args).fetchall())


if __name__ == "__main__":
    unittest.main()
