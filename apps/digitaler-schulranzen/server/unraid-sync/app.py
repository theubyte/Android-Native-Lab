import os, sqlite3, hashlib, hmac, base64, json, time, secrets
from pathlib import Path
from flask import Flask, request, jsonify, send_file, abort

DATA = Path(os.environ.get("DATA_DIR", "/data"))
DATA.mkdir(parents=True, exist_ok=True)
DB = DATA / "users.db"
SECRET_FILE = DATA / "server_secret"
BACKUPS = DATA / "backups"
BACKUPS.mkdir(exist_ok=True)
MAX_VERSIONS = int(os.environ.get("MAX_VERSIONS", "20"))
MAX_UPLOAD = int(os.environ.get("MAX_UPLOAD_MB", "1024")) * 1024 * 1024

if not SECRET_FILE.exists():
    SECRET_FILE.write_text(secrets.token_hex(64))
SECRET = SECRET_FILE.read_text().strip().encode()

app = Flask(__name__)
app.config["MAX_CONTENT_LENGTH"] = MAX_UPLOAD

def db():
    c = sqlite3.connect(DB)
    c.execute("""CREATE TABLE IF NOT EXISTS users(
        id INTEGER PRIMARY KEY AUTOINCREMENT,
        email TEXT UNIQUE NOT NULL,
        salt TEXT NOT NULL,
        pin_hash TEXT NOT NULL,
        created INTEGER NOT NULL
    )""")
    c.commit()
    return c

def norm_email(v): return (v or "").strip().lower()

def valid_pin(pin): return isinstance(pin, str) and len(pin) == 6 and pin.isdigit()

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

@app.get("/health")
def health():
    return jsonify(ok=True, service="DigitalerSchulranzen Sync", time=int(time.time()))

@app.post("/api/register")
def register():
    j = request.get_json(silent=True) or {}
    email, pin = norm_email(j.get("email")), j.get("pin")
    if "@" not in email or not valid_pin(pin):
        return jsonify(error="E-Mail oder PIN ungueltig; PIN muss 6 Ziffern haben."), 400
    salt = secrets.token_hex(16)
    try:
        c=db()
        cur=c.execute("INSERT INTO users(email,salt,pin_hash,created) VALUES(?,?,?,?)",
                      (email,salt,pin_hash(pin,salt),int(time.time())))
        c.commit(); uid=cur.lastrowid; c.close()
        (BACKUPS / str(uid)).mkdir(exist_ok=True)
        return jsonify(ok=True, token=token_for(uid,email), email=email)
    except sqlite3.IntegrityError:
        return jsonify(error="E-Mail ist bereits registriert."), 409

@app.post("/api/login")
def login():
    j=request.get_json(silent=True) or {}
    email,pin=norm_email(j.get("email")),j.get("pin")
    if not valid_pin(pin): return jsonify(error="Login fehlgeschlagen."),401
    c=db(); row=c.execute("SELECT id,salt,pin_hash FROM users WHERE email=?",(email,)).fetchone(); c.close()
    if not row or not hmac.compare_digest(pin_hash(pin,row[1]),row[2]):
        return jsonify(error="Login fehlgeschlagen."),401
    return jsonify(ok=True,token=token_for(row[0],email),email=email)

@app.post("/api/sync/upload")
def upload():
    u=auth()
    if "backup" not in request.files: return jsonify(error="Feld 'backup' fehlt."),400
    folder=BACKUPS/str(u["uid"]); folder.mkdir(exist_ok=True)
    stamp=time.strftime("%Y%m%d-%H%M%S",time.localtime())
    name=f"{stamp}-{secrets.token_hex(3)}.zip"
    dest=folder/name
    request.files["backup"].save(dest)
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
    u=auth(); folder=BACKUPS/str(u["uid"]); files=sorted(folder.glob("*.zip"),key=lambda p:p.stat().st_mtime,reverse=True)
    if not files: return jsonify(error="Noch kein Backup vorhanden."),404
    return send_file(files[0],as_attachment=True,download_name="schulranzen-backup.zip")

@app.get("/api/sync/version/<name>")
def version(name):
    u=auth()
    if "/" in name or "\\" in name or not name.endswith(".zip"): abort(400)
    p=BACKUPS/str(u["uid"])/name
    if not p.exists(): abort(404)
    return send_file(p,as_attachment=True,download_name=name)

@app.errorhandler(413)
def too_large(_):
    return jsonify(error="Backup ist groesser als das Serverlimit."),413

if __name__ == "__main__":
    db().close()
    app.run(host="0.0.0.0",port=8788)
