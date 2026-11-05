import os, re, sqlite3, hashlib, hmac, base64, json, time, secrets, shutil
from contextlib import closing
from pathlib import Path
from flask import Flask, request, jsonify, send_file, abort

DATA = Path(os.environ.get("DATA_DIR", "/data"))
DATA.mkdir(parents=True, exist_ok=True)
DB = DATA / "users.db"
SECRET_FILE = DATA / "server_secret"
BACKUPS = DATA / "backups"
BACKUPS.mkdir(exist_ok=True)
SYNC = DATA / "sync"
SYNC.mkdir(exist_ok=True)
MAX_VERSIONS = int(os.environ.get("MAX_VERSIONS", "20"))
MAX_UPLOAD = int(os.environ.get("MAX_UPLOAD_MB", "1024")) * 1024 * 1024
# How many past sync states stay readable; their files are kept with them.
MAX_SYNC_HEADS = int(os.environ.get("MAX_SYNC_HEADS", "10"))
# "closed" stops new accounts once yours exists. Default stays open, as before.
REGISTRATION = os.environ.get("REGISTRATION", "open").strip().lower()
# Space left free on the disk, so a full upload never fills it to the last byte.
RESERVE = int(os.environ.get("RESERVE_MB", "50")) * 1024 * 1024
# A blob nobody references yet may belong to a commit in progress.
BLOB_GRACE_SECONDS = 24 * 60 * 60
API_VERSION = 2

# Login limits: a six digit PIN only has a million values.
LOGIN_FREE_TRIES = 5
LOGIN_MAX_WAIT = 15 * 60
ACCOUNT_LOCK_FAILS = 50
ACCOUNT_LOCK_SECONDS = 60 * 60
REGISTER_PER_HOUR = 10

ZIP_MAGIC = (b"PK\x03\x04", b"PK\x05\x06")
ENCRYPTED_MAGIC = b"SRZBKENC"
VERSION_NAME = re.compile(r"^[A-Za-z0-9_-][A-Za-z0-9._-]*\.zip$")
BLOB_ID = re.compile(r"^[0-9a-f]{64}$")

# Several gunicorn workers import this at the same time: only one may create
# the secret, and all must use the file's content. Otherwise each worker signs
# with its own key and a login from one worker is rejected (401) by the other,
# which breaks upload and download right after a successful login.
try:
    fd = os.open(SECRET_FILE, os.O_WRONLY | os.O_CREAT | os.O_EXCL, 0o600)
    with os.fdopen(fd, "w") as f:
        f.write(secrets.token_hex(64))
except FileExistsError:
    pass
for _ in range(50):
    SECRET = SECRET_FILE.read_text().strip().encode()
    if SECRET: break
    time.sleep(0.1)

app = Flask(__name__)
app.config["MAX_CONTENT_LENGTH"] = MAX_UPLOAD

def db():
    # All tables are added with IF NOT EXISTS, so an existing users.db just grows.
    c = sqlite3.connect(DB, timeout=30, isolation_level=None)
    c.executescript("""
    CREATE TABLE IF NOT EXISTS users(
        id INTEGER PRIMARY KEY AUTOINCREMENT,
        email TEXT UNIQUE NOT NULL,
        salt TEXT NOT NULL,
        pin_hash TEXT NOT NULL,
        created INTEGER NOT NULL
    );
    CREATE TABLE IF NOT EXISTS login_fails(
        key TEXT PRIMARY KEY,
        fails INTEGER NOT NULL,
        last INTEGER NOT NULL
    );
    CREATE TABLE IF NOT EXISTS sync_head(
        uid INTEGER PRIMARY KEY,
        rev TEXT NOT NULL,
        file TEXT NOT NULL,
        updated INTEGER NOT NULL,
        device TEXT NOT NULL DEFAULT ''
    );
    """)
    return c

def norm_email(v): return v.strip().lower() if isinstance(v, str) else ""

def valid_email(e): return "@" in e and 3 <= len(e) <= 254

def valid_pin(pin): return isinstance(pin, str) and len(pin) == 6 and pin.isascii() and pin.isdigit()

def pin_hash(pin, salt_hex):
    return hashlib.pbkdf2_hmac("sha256", pin.encode(), bytes.fromhex(salt_hex), 310000).hex()

def token_for(uid, email, ttl=60*60*24*30):
    payload = {"uid": uid, "email": email, "exp": int(time.time()) + ttl}
    raw = base64.urlsafe_b64encode(json.dumps(payload,separators=(",",":")).encode()).rstrip(b"=")
    sig = hmac.new(SECRET, raw, hashlib.sha256).digest()
    return (raw + b"." + base64.urlsafe_b64encode(sig).rstrip(b"=")).decode()

def auth():
    h = request.headers.get("Authorization","")
    if not h.startswith("Bearer "): abort(401)
    try:
        tok = h[7:].encode()
        raw, sig64 = tok.split(b".",1)
        sig = base64.urlsafe_b64decode(sig64 + b"=" * (-len(sig64)%4))
        if not hmac.compare_digest(sig, hmac.new(SECRET, raw, hashlib.sha256).digest()): abort(401)
        payload = json.loads(base64.urlsafe_b64decode(raw + b"=" * (-len(raw)%4)))
        if payload["exp"] < time.time(): abort(401)
        return payload
    except Exception:
        abort(401)

# --- limits -------------------------------------------------------------

def client_ip(): return request.remote_addr or "?"

def wait_seconds(c, email):
    """Seconds this client still has to wait before the next login attempt."""
    now = int(time.time())
    row = c.execute("SELECT fails,last FROM login_fails WHERE key=?", (f"mail|{email}",)).fetchone()
    if row and row[0] >= ACCOUNT_LOCK_FAILS and now - row[1] < ACCOUNT_LOCK_SECONDS:
        return ACCOUNT_LOCK_SECONDS - (now - row[1])
    row = c.execute("SELECT fails,last FROM login_fails WHERE key=?", (f"ip|{client_ip()}|{email}",)).fetchone()
    if row and row[0] >= LOGIN_FREE_TRIES:
        wait = min(30 * 2 ** (row[0] - LOGIN_FREE_TRIES), LOGIN_MAX_WAIT)
        if now - row[1] < wait:
            return wait - (now - row[1])
    return 0

def bump(c, key, now):
    c.execute("""INSERT INTO login_fails(key,fails,last) VALUES(?,1,?)
                 ON CONFLICT(key) DO UPDATE SET fails=fails+1,last=excluded.last""", (key, now))

def note_failure(c, email):
    now = int(time.time())
    bump(c, f"mail|{email}", now)
    bump(c, f"ip|{client_ip()}|{email}", now)
    # Forget old entries, so the table stays small and old mistakes expire.
    c.execute("DELETE FROM login_fails WHERE last<?", (now - 24 * 60 * 60,))

def too_many(wait):
    r = jsonify(error=f"Zu viele Fehlversuche. Bitte in {max(1, (wait + 59) // 60)} Minute(n) erneut versuchen.", retry_after=wait)
    r.status_code = 429
    r.headers["Retry-After"] = str(wait)
    return r

def require_space(extra=0):
    """Refuses before anything is written when the disk could not take it."""
    if shutil.disk_usage(DATA).free < (request.content_length or 0) + extra + RESERVE:
        return jsonify(error="Auf dem Server ist nicht genug Speicherplatz frei."), 507
    return None

def fsynced_replace(tmp, dest):
    """Makes a finished temp file visible under its final name, all or nothing."""
    with open(tmp, "r+b") as f:
        os.fsync(f.fileno())
    os.replace(tmp, dest)
    try:
        d = os.open(dest.parent, os.O_RDONLY)
        try: os.fsync(d)
        finally: os.close(d)
    except OSError:
        pass  # Not every file system lets a folder be synced.

def sweep_temp(folder, max_age=24 * 60 * 60):
    """Removes leftovers of uploads that broke off when the server was killed."""
    limit = time.time() - max_age
    for p in folder.glob("*.tmp"):
        try:
            if p.stat().st_mtime < limit: p.unlink(missing_ok=True)
        except OSError:
            pass

# --- account ------------------------------------------------------------

@app.get("/health")
def health():
    return jsonify(ok=True, service="DigitalerSchulranzen Sync", time=int(time.time()), api=API_VERSION)

@app.post("/api/register")
def register():
    j = request.get_json(silent=True) or {}
    email, pin = norm_email(j.get("email")), j.get("pin")
    if not valid_email(email) or not valid_pin(pin):
        return jsonify(error="E-Mail oder PIN ungueltig; PIN muss 6 Ziffern haben."), 400
    with closing(db()) as c:
        if REGISTRATION == "closed" and c.execute("SELECT COUNT(*) FROM users").fetchone()[0] > 0:
            return jsonify(error="Neue Konten sind auf diesem Server abgeschaltet."), 403
        now = int(time.time())
        key = f"reg|{client_ip()}"
        row = c.execute("SELECT fails,last FROM login_fails WHERE key=?", (key,)).fetchone()
        if row and row[0] >= REGISTER_PER_HOUR and now - row[1] < 3600:
            return too_many(3600 - (now - row[1]))
        if row and now - row[1] >= 3600:
            c.execute("DELETE FROM login_fails WHERE key=?", (key,))
        salt = secrets.token_hex(16)
        try:
            cur = c.execute("INSERT INTO users(email,salt,pin_hash,created) VALUES(?,?,?,?)",
                            (email, salt, pin_hash(pin, salt), now))
            uid = cur.lastrowid
            bump(c, key, now)
        except sqlite3.IntegrityError:
            return jsonify(error="E-Mail ist bereits registriert."), 409
    (BACKUPS / str(uid)).mkdir(exist_ok=True)
    return jsonify(ok=True, token=token_for(uid,email), email=email)

# Spent on unknown addresses, so the time does not tell which accounts exist.
DUMMY_SALT = "00" * 16

@app.post("/api/login")
def login():
    j = request.get_json(silent=True) or {}
    email, pin = norm_email(j.get("email")), j.get("pin")
    if not valid_pin(pin): return jsonify(error="Login fehlgeschlagen."),401
    with closing(db()) as c:
        wait = wait_seconds(c, email)
        if wait > 0: return too_many(wait)
        row = c.execute("SELECT id,salt,pin_hash FROM users WHERE email=?",(email,)).fetchone()
        given = pin_hash(pin, row[1] if row else DUMMY_SALT)
        if not row or not hmac.compare_digest(given, row[2]):
            note_failure(c, email)
            return jsonify(error="Login fehlgeschlagen."),401
        c.execute("DELETE FROM login_fails WHERE key IN (?,?)", (f"mail|{email}", f"ip|{client_ip()}|{email}"))
    return jsonify(ok=True,token=token_for(row[0],email),email=email)

# --- full backups (API of V15 and all older app versions, unchanged) ----

@app.post("/api/sync/upload")
def upload():
    u=auth()
    full = require_space()
    if full: return full
    if "backup" not in request.files: return jsonify(error="Feld 'backup' fehlt."),400
    folder=BACKUPS/str(u["uid"]); folder.mkdir(exist_ok=True)
    sweep_temp(folder)
    stamp=time.strftime("%Y%m%d-%H%M%S",time.localtime())
    name=f"{stamp}-{secrets.token_hex(3)}.zip"
    dest=folder/name
    # Through a temp file: a broken write must never look like the newest backup.
    tmp=folder/(name+".tmp")
    try:
        request.files["backup"].save(tmp)
        with open(tmp,"rb") as f: head=f.read(8)
        if head[:4] not in ZIP_MAGIC and head != ENCRYPTED_MAGIC:
            return jsonify(error="Die Datei ist kein Schulranzen-Backup."),400
        fsynced_replace(tmp,dest)
    finally:
        tmp.unlink(missing_ok=True)
    files=sorted(folder.glob("*.zip"), key=lambda p:p.stat().st_mtime, reverse=True)
    for old in files[MAX_VERSIONS:]:
        old.unlink(missing_ok=True)
    return jsonify(ok=True,version=name,size=dest.stat().st_size)

@app.get("/api/sync/versions")
def versions():
    u=auth(); folder=BACKUPS/str(u["uid"]); folder.mkdir(exist_ok=True)
    out=[{"version":p.name,"size":p.stat().st_size,"mtime":int(p.stat().st_mtime)}
         for p in sorted(folder.glob("*.zip"),key=lambda p:p.stat().st_mtime,reverse=True)]
    return jsonify(ok=True,versions=out)

@app.get("/api/sync/latest")
def latest():
    u=auth(); folder=BACKUPS/str(u["uid"]); folder.mkdir(exist_ok=True)
    files=sorted(folder.glob("*.zip"),key=lambda p:p.stat().st_mtime,reverse=True)
    if not files: return jsonify(error="Noch kein Backup vorhanden."),404
    return send_file(files[0],as_attachment=True,download_name="schulranzen-backup.zip")

@app.get("/api/sync/version/<name>")
def version(name):
    u=auth()
    if not VERSION_NAME.match(name): abort(400)
    p=BACKUPS/str(u["uid"])/name
    if not p.is_file(): abort(404)
    return send_file(p,as_attachment=True,download_name=name)

# --- sync: one current state per account, changed only by compare-and-swap ---
#
# The state is a list of files ("manifest", opaque to the server, encrypted if
# the user encrypts) plus the files themselves as content-addressed blobs. A
# device fetches and sends only the blobs it lacks. A commit names the state
# it was based on; if another device was faster the answer is 412, the device
# merges again, and nothing that was not merged is ever replaced.

def sync_dir(uid):
    d = SYNC / str(uid)
    (d / "blobs").mkdir(parents=True, exist_ok=True)
    (d / "heads").mkdir(exist_ok=True)
    return d

def current_head(c, uid):
    return c.execute("SELECT rev,file,updated,device FROM sync_head WHERE uid=?", (uid,)).fetchone()

@app.get("/api/sync/head")
def head():
    u = auth()
    with closing(db()) as c:
        row = current_head(c, u["uid"])
    if not row:
        return jsonify(ok=True, rev=None)
    data = json.loads((sync_dir(u["uid"]) / "heads" / row[1]).read_text())
    r = jsonify(ok=True, rev=row[0], manifest=data["manifest"], updated=row[2], device=row[3])
    r.headers["ETag"] = f'"{row[0]}"'
    return r

@app.post("/api/sync/blobs/missing")
def blobs_missing():
    u = auth()
    ids = (request.get_json(silent=True) or {}).get("ids")
    if not isinstance(ids, list) or not all(isinstance(i, str) and BLOB_ID.match(i) for i in ids):
        return jsonify(error="Ungueltige Liste."), 400
    blobs = sync_dir(u["uid"]) / "blobs"
    missing = []
    now = time.time()
    for i in ids:
        p = blobs / i
        try:
            # Touched: the device is about to reference it, so cleaning must wait.
            os.utime(p, (now, now))
        except OSError:
            missing.append(i)
    return jsonify(ok=True, missing=missing)

@app.put("/api/sync/blob/<blob>")
def blob_put(blob):
    u = auth()
    if not BLOB_ID.match(blob): abort(400)
    length = request.content_length
    if length is None: return jsonify(error="Laengenangabe fehlt."), 411
    full = require_space()
    if full: return full
    blobs = sync_dir(u["uid"]) / "blobs"
    sweep_temp(blobs)
    tmp = blobs / f"{blob}.{secrets.token_hex(4)}.tmp"
    md = hashlib.sha256()
    got = 0
    try:
        with open(tmp, "wb") as f:
            while True:
                part = request.stream.read(1 << 20)
                if not part: break
                f.write(part); md.update(part); got += len(part)
        if got != length:
            return jsonify(error="Uebertragung unvollstaendig."), 400
        expected = request.headers.get("X-Content-SHA256", "").lower()
        if expected and expected != md.hexdigest():
            return jsonify(error="Pruefsumme stimmt nicht."), 422
        fsynced_replace(tmp, blobs / blob)
    finally:
        tmp.unlink(missing_ok=True)
    return jsonify(ok=True, size=got)

@app.get("/api/sync/blob/<blob>")
def blob_get(blob):
    u = auth()
    if not BLOB_ID.match(blob): abort(400)
    p = sync_dir(u["uid"]) / "blobs" / blob
    if not p.is_file(): return jsonify(error="Datei fehlt auf dem Server."), 404
    return send_file(p, mimetype="application/octet-stream", conditional=False)

@app.post("/api/sync/commit")
def commit():
    u = auth()
    uid = u["uid"]
    j = request.get_json(silent=True) or {}
    manifest, blobs, device = j.get("manifest"), j.get("blobs"), j.get("device", "")
    if not isinstance(manifest, str) or not isinstance(blobs, list) or not all(isinstance(i, str) and BLOB_ID.match(i) for i in blobs):
        return jsonify(error="Ungueltiger Stand."), 400
    expected = request.headers.get("If-Match", "").strip().strip('"')
    first = request.headers.get("If-None-Match", "").strip() == "*"
    if not expected and not first:
        return jsonify(error="Es fehlt, auf welchem Stand diese Aenderung beruht."), 428
    d = sync_dir(uid)
    missing = [i for i in blobs if not (d / "blobs" / i).is_file()]
    if missing:
        return jsonify(error="Es fehlen noch Dateien.", missing=missing[:1000]), 409
    full = require_space()
    if full: return full

    rev = secrets.token_hex(12)
    name = f"{int(time.time() * 1000):015d}-{rev}.json"
    dest = d / "heads" / name
    tmp = d / "heads" / (name + ".tmp")
    try:
        tmp.write_text(json.dumps({"manifest": manifest, "blobs": blobs}))
        fsynced_replace(tmp, dest)
    finally:
        tmp.unlink(missing_ok=True)

    # The one place the state changes: check and set in one write transaction,
    # which is also safe between the gunicorn workers.
    with closing(db()) as c:
        c.execute("BEGIN IMMEDIATE")
        row = current_head(c, uid)
        current = row[0] if row else None
        if (first and current is not None) or (not first and current != expected):
            c.execute("ROLLBACK")
            dest.unlink(missing_ok=True)
            return jsonify(error="Inzwischen gibt es einen neueren Stand.", rev=current), 412
        c.execute("INSERT OR REPLACE INTO sync_head(uid,rev,file,updated,device) VALUES(?,?,?,?,?)",
                  (uid, rev, name, int(time.time()), str(device)[:120]))
        c.execute("COMMIT")
    clean_sync(d, name)
    r = jsonify(ok=True, rev=rev)
    r.headers["ETag"] = f'"{rev}"'
    return r

def clean_sync(d, keep_name):
    """Drops old states and the blobs only they used. A failure here is harmless."""
    try:
        heads = sorted((p for p in (d / "heads").glob("*.json")), key=lambda p: p.name, reverse=True)
        keep = [p for p in heads[:max(1, MAX_SYNC_HEADS)]]
        if keep_name not in {p.name for p in keep}:
            keep.append(d / "heads" / keep_name)
        for old in heads:
            if old not in keep: old.unlink(missing_ok=True)
        used = set()
        for p in keep:
            used.update(json.loads(p.read_text()).get("blobs", []))
        limit = time.time() - BLOB_GRACE_SECONDS
        for p in (d / "blobs").iterdir():
            if p.name in used or p.name.endswith(".tmp"): continue
            if p.stat().st_mtime < limit: p.unlink(missing_ok=True)
    except (OSError, ValueError):
        app.logger.exception("Aufraeumen fehlgeschlagen")

@app.errorhandler(413)
def too_large(_):
    return jsonify(error="Backup ist groesser als das Serverlimit."),413

@app.errorhandler(OSError)
def disk_problem(e):
    # Almost always a full disk; the temp files are gone again at this point.
    app.logger.exception("Dateifehler")
    return jsonify(error="Der Server konnte die Datei nicht speichern (Speicher voll?)."),507

if __name__ == "__main__":
    with closing(db()):
        pass
    app.run(host="0.0.0.0",port=8788,threaded=True)
