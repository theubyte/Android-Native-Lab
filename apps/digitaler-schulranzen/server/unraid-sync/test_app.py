"""Tests of the sync server. Run: python -m unittest test_app  (needs Flask; not part of the image)."""
import hashlib, importlib, io, os, shutil, tempfile, time, unittest
from pathlib import Path


class ServerTest(unittest.TestCase):
    def setUp(self):
        self.dir = tempfile.mkdtemp()
        os.environ["DATA_DIR"] = self.dir
        os.environ["MAX_UPLOAD_MB"] = "1"
        os.environ["RESERVE_MB"] = "0"
        os.environ.pop("REGISTRATION", None)
        import app as server
        self.server = importlib.reload(server)
        self.c = self.server.app.test_client()

    def tearDown(self):
        shutil.rmtree(self.dir, ignore_errors=True)

    def login(self, mail="a@b.de", pin="123456"):
        r = self.c.post("/api/register", json={"email": mail, "pin": pin})
        self.assertEqual(200, r.status_code, r.data)
        return {"Authorization": "Bearer " + r.json["token"]}

    def upload(self, h, data=b"PK\x03\x04data", name="x.zip"):
        return self.c.post("/api/sync/upload", headers=h, data={"backup": (io.BytesIO(data), name)}, content_type="multipart/form-data")

    def put(self, h, data):
        bid = hashlib.sha256(data).hexdigest()
        r = self.c.put(f"/api/sync/blob/{bid}", headers={**h, "X-Content-SHA256": bid}, data=data)
        self.assertEqual(200, r.status_code, r.data)
        return bid

    def commit(self, h, blobs, match=None, manifest="bWFuaWZlc3Q="):
        hdr = dict(h)
        if match is None: hdr["If-None-Match"] = "*"
        else: hdr["If-Match"] = match
        return self.c.post("/api/sync/commit", headers=hdr, json={"manifest": manifest, "blobs": blobs, "device": "Test"})

    # --- old API, as V15 and app versions up to 0.4.2 use it ---

    def test_old_clients_keep_working(self):
        self.assertTrue(self.c.get("/health").json["ok"])
        self.assertIn("Schulranzen", self.c.get("/health").json["service"])
        h = self.login()
        r = self.upload(h)
        self.assertEqual(200, r.status_code)
        v = r.json["version"]
        self.assertEqual(v, self.c.get("/api/sync/versions", headers=h).json["versions"][0]["version"])
        self.assertEqual(b"PK\x03\x04data", self.c.get("/api/sync/latest", headers=h).data)
        self.assertEqual(b"PK\x03\x04data", self.c.get(f"/api/sync/version/{v}", headers=h).data)

    def test_encrypted_backup_is_accepted(self):
        self.assertEqual(200, self.upload(self.login(), b"SRZBKENC" + b"x" * 100).status_code)

    def test_existing_database_is_upgraded(self):
        import sqlite3
        db = Path(self.dir) / "users.db"
        if db.exists(): db.unlink()
        c = sqlite3.connect(db)
        c.execute("CREATE TABLE users(id INTEGER PRIMARY KEY AUTOINCREMENT, email TEXT UNIQUE NOT NULL, salt TEXT NOT NULL, pin_hash TEXT NOT NULL, created INTEGER NOT NULL)")
        salt = "ab" * 16
        c.execute("INSERT INTO users(email,salt,pin_hash,created) VALUES(?,?,?,1)", ("alt@b.de", salt, self.server.pin_hash("111111", salt)))
        c.commit(); c.close()
        r = self.c.post("/api/login", json={"email": "alt@b.de", "pin": "111111"})
        self.assertEqual(200, r.status_code)
        h = {"Authorization": "Bearer " + r.json["token"]}
        self.assertIsNone(self.c.get("/api/sync/head", headers=h).json["rev"])

    def test_auth_is_required_everywhere(self):
        bid = "0" * 64
        for method, path in [("post", "/api/sync/upload"), ("get", "/api/sync/versions"), ("get", "/api/sync/latest"),
                             ("get", "/api/sync/version/a.zip"), ("get", "/api/sync/head"), ("post", "/api/sync/commit"),
                             ("post", "/api/sync/blobs/missing"), ("put", f"/api/sync/blob/{bid}"), ("get", f"/api/sync/blob/{bid}")]:
            self.assertEqual(401, getattr(self.c, method)(path).status_code, path)
            self.assertEqual(401, getattr(self.c, method)(path, headers={"Authorization": "Bearer a.b"}).status_code, path)

    def test_expired_or_forged_token(self):
        h = self.login()
        tok = h["Authorization"][7:]
        forged = tok[:-3] + ("AAA" if tok[-3:] != "AAA" else "BBB")
        self.assertEqual(401, self.c.get("/api/sync/versions", headers={"Authorization": "Bearer " + forged}).status_code)
        old = self.server.token_for(1, "a@b.de", ttl=-5)
        self.assertEqual(401, self.c.get("/api/sync/versions", headers={"Authorization": "Bearer " + old}).status_code)

    def test_users_are_separated(self):
        a, b = self.login("a@b.de"), self.login("b@b.de")
        v = self.upload(a).json["version"]
        self.assertEqual(404, self.c.get(f"/api/sync/version/{v}", headers=b).status_code)
        bid = self.put(a, b"geheim")
        self.assertEqual(404, self.c.get(f"/api/sync/blob/{bid}", headers=b).status_code)
        self.assertEqual([bid], self.c.post("/api/sync/blobs/missing", headers=b, json={"ids": [bid]}).json["missing"])

    def test_paths_cannot_leave_the_folder(self):
        h = self.login()
        for name in ["..%2F..%2Fusers.db", "..%2Fx.zip", "..zip", ".hidden.zip", "a%5Cb.zip", "users.db"]:
            self.assertIn(self.c.get(f"/api/sync/version/{name}", headers=h).status_code, (400, 404), name)
        self.assertIn(self.c.get("/api/sync/blob/..%2F..%2Fusers.db", headers=h).status_code, (400, 404))
        self.assertEqual(400, self.c.put("/api/sync/blob/" + "g" * 64, headers=h, data=b"x").status_code)

    def test_upload_limits_and_garbage(self):
        h = self.login()
        self.assertEqual(400, self.upload(h, b"kein zip").status_code)
        self.assertEqual(413, self.upload(h, b"PK\x03\x04" + b"x" * (2 << 20)).status_code)
        self.assertEqual([], self.c.get("/api/sync/versions", headers=h).json["versions"])
        folder = Path(self.dir) / "backups" / "1"
        self.assertEqual([], list(folder.glob("*.tmp")))

    def test_full_disk_is_refused_cleanly(self):
        h = self.login()
        self.server.RESERVE = 1 << 62
        self.assertEqual(507, self.upload(h).status_code)
        self.assertEqual(507, self.c.put("/api/sync/blob/" + "0" * 64, headers=h, data=b"x").status_code)

    def test_versions_rotate(self):
        h = self.login()
        self.server.MAX_VERSIONS = 2
        names = []
        for i in range(4):
            names.append(self.upload(h).json["version"])
            os.utime(Path(self.dir) / "backups" / "1" / names[-1], (1000 + i, 1000 + i))
        left = [v["version"] for v in self.c.get("/api/sync/versions", headers=h).json["versions"]]
        self.assertEqual(2, len(left))

    # --- limits ---

    def test_login_is_rate_limited_per_client_and_account(self):
        self.login("a@b.de", "123456")
        for _ in range(self.server.LOGIN_FREE_TRIES):
            self.assertEqual(401, self.c.post("/api/login", json={"email": "a@b.de", "pin": "000000"}).status_code)
        r = self.c.post("/api/login", json={"email": "a@b.de", "pin": "123456"})
        self.assertEqual(429, r.status_code, "the right PIN must wait too, otherwise guessing still works")
        self.assertGreater(int(r.headers["Retry-After"]), 0)
        # Another address is not affected.
        self.login("c@b.de", "123456")
        self.assertEqual(200, self.c.post("/api/login", json={"email": "c@b.de", "pin": "123456"}).status_code)

    def test_success_clears_the_counter(self):
        self.login("a@b.de", "123456")
        for _ in range(3):
            self.c.post("/api/login", json={"email": "a@b.de", "pin": "000000"})
        self.assertEqual(200, self.c.post("/api/login", json={"email": "a@b.de", "pin": "123456"}).status_code)
        for _ in range(3):
            self.assertEqual(401, self.c.post("/api/login", json={"email": "a@b.de", "pin": "000000"}).status_code)

    def test_register_limits(self):
        self.assertEqual(400, self.c.post("/api/register", json={"email": "a@b.de", "pin": "٣٣٣٣٣٣"}).status_code)
        self.assertEqual(400, self.c.post("/api/register", json={"email": "x" * 300 + "@b.de", "pin": "123456"}).status_code)
        self.assertEqual(400, self.c.post("/api/register", json={"email": 5, "pin": "123456"}).status_code)
        self.server.REGISTER_PER_HOUR = 2
        self.login("a@b.de"); self.login("b@b.de")
        self.assertEqual(429, self.c.post("/api/register", json={"email": "c@b.de", "pin": "123456"}).status_code)

    def test_registration_can_be_closed(self):
        self.login("a@b.de")
        self.server.REGISTRATION = "closed"
        self.assertEqual(403, self.c.post("/api/register", json={"email": "b@b.de", "pin": "123456"}).status_code)
        self.assertEqual(200, self.c.post("/api/login", json={"email": "a@b.de", "pin": "123456"}).status_code)

    # --- sync ---

    def test_compare_and_swap(self):
        h = self.login()
        self.assertIsNone(self.c.get("/api/sync/head", headers=h).json["rev"])
        bid = self.put(h, b"inhalt")
        r1 = self.commit(h, [bid])
        self.assertEqual(200, r1.status_code)
        rev1 = r1.json["rev"]
        head = self.c.get("/api/sync/head", headers=h)
        self.assertEqual(rev1, head.json["rev"])
        self.assertEqual("bWFuaWZlc3Q=", head.json["manifest"])
        self.assertEqual(f'"{rev1}"', head.headers["ETag"])

        # A second "first" state, or one based on an old state, is refused.
        self.assertEqual(412, self.commit(h, [bid]).status_code)
        r2 = self.commit(h, [bid], match=rev1, manifest="MQ==")
        self.assertEqual(200, r2.status_code)
        r3 = self.commit(h, [bid], match=rev1, manifest="Mg==")
        self.assertEqual(412, r3.status_code)
        self.assertEqual(r2.json["rev"], r3.json["rev"])
        self.assertEqual("MQ==", self.c.get("/api/sync/head", headers=h).json["manifest"])

    def test_commit_needs_precondition_and_blobs(self):
        h = self.login()
        self.assertEqual(428, self.c.post("/api/sync/commit", headers=h, json={"manifest": "", "blobs": []}).status_code)
        r = self.commit(h, ["a" * 64])
        self.assertEqual(409, r.status_code)
        self.assertEqual(["a" * 64], r.json["missing"])
        self.assertEqual(400, self.commit(h, ["../x"]).status_code)
        self.assertIsNone(self.c.get("/api/sync/head", headers=h).json["rev"])

    def test_blob_integrity(self):
        h = self.login()
        bid = "a" * 64
        r = self.c.put(f"/api/sync/blob/{bid}", headers={**h, "X-Content-SHA256": "b" * 64}, data=b"x")
        self.assertEqual(422, r.status_code)
        self.assertEqual([bid], self.c.post("/api/sync/blobs/missing", headers=h, json={"ids": [bid]}).json["missing"])
        self.assertEqual([], list((Path(self.dir) / "sync" / "1" / "blobs").iterdir()))
        good = self.put(h, b"hallo")
        self.assertEqual(b"hallo", self.c.get(f"/api/sync/blob/{good}", headers=h).data)
        self.assertEqual([], self.c.post("/api/sync/blobs/missing", headers=h, json={"ids": [good]}).json["missing"])

    def test_old_states_and_unused_blobs_are_cleaned(self):
        h = self.login()
        self.server.MAX_SYNC_HEADS = 2
        a, b, c = self.put(h, b"a"), self.put(h, b"b"), self.put(h, b"c")
        rev = self.commit(h, [a]).json["rev"]
        time.sleep(0.002); rev = self.commit(h, [b], match=rev).json["rev"]
        blobs = Path(self.dir) / "sync" / "1" / "blobs"
        for name in (a, b, c):
            os.utime(blobs / name, (1000, 1000))
        time.sleep(0.002); rev = self.commit(h, [b], match=rev).json["rev"]
        time.sleep(0.002); rev = self.commit(h, [b], match=rev).json["rev"]
        self.assertTrue((blobs / b).exists(), "still referenced")
        self.assertFalse((blobs / a).exists(), "no state uses it any more")
        self.assertFalse((blobs / c).exists(), "uploaded long ago and never used")
        self.assertEqual(2, len(list((Path(self.dir) / "sync" / "1" / "heads").glob("*.json"))))
        # A fresh blob without a commit yet stays.
        d = self.put(h, b"d")
        time.sleep(0.002); self.commit(h, [b], match=rev)
        self.assertTrue((blobs / d).exists())


if __name__ == "__main__":
    unittest.main()
