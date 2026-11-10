#!/usr/bin/env python3
"""Download MariaDB for Android (aarch64) from the Termux repo and stage a
minimal, relocatable tree: bin/mariadbd, the shared libraries it needs
(copied under their NEEDED names, so no symlinks), the error-message/charset
files and a ready-to-run bootstrap.sql.

Usage: termux_mariadb.py <out_usr_dir> <work_dir>
"""
import lzma
import os
import re
import shutil
import subprocess
import sys
import urllib.request

REPO = "https://packages.termux.dev/apt/termux-main"
ARCH = "aarch64"
PREFIX = "data/data/com.termux/files/usr"
# Tools that MariaDB lists as dependencies but the server binary never loads.
SKIP = {
    "bash", "dash", "coreutils", "procps", "sed", "grep", "findutils", "perl",
    "termux-tools", "termux-exec", "termux-am", "termux-core", "termux-licenses",
    "python", "less", "gawk", "debianutils", "ncurses-utils",
}
# Libraries provided by Android itself.
SYSTEM_LIBS = {"libc.so", "libm.so", "libdl.so", "liblog.so", "libandroid.so"}


def fetch(url):
    print("GET", url, flush=True)
    req = urllib.request.Request(url, headers={"User-Agent": "vaila-build"})
    with urllib.request.urlopen(req, timeout=120) as r:
        return r.read()


def load_index():
    base = f"{REPO}/dists/stable/main/binary-{ARCH}/Packages"
    for suffix, dec in (("", lambda b: b), (".xz", lzma.decompress)):
        try:
            return dec(fetch(base + suffix)).decode()
        except Exception as e:  # noqa: BLE001
            print("  failed:", e)
    sys.exit("cannot download Termux package index")


def parse(index):
    pkgs, provides = {}, {}
    for block in index.split("\n\n"):
        d = {}
        for line in block.splitlines():
            if line and not line.startswith(" ") and ":" in line:
                k, v = line.split(":", 1)
                d[k] = v.strip()
        if "Package" in d:
            pkgs[d["Package"]] = d
            for p in d.get("Provides", "").split(","):
                p = re.sub(r"\(.*?\)", "", p).strip()
                if p:
                    provides.setdefault(p, d["Package"])
    return pkgs, provides


def deps_of(d):
    out = []
    for field in ("Pre-Depends", "Depends"):
        for item in d.get(field, "").split(","):
            alts = [re.sub(r"\(.*?\)", "", a).strip() for a in item.split("|")]
            alts = [a for a in alts if a]
            if alts:
                out.append(alts)
    return out


def main():
    out_usr, work = sys.argv[1], sys.argv[2]
    os.makedirs(work, exist_ok=True)
    pkgs, provides = parse(load_index())

    want, queue = [], ["mariadb"]
    while queue:
        name = queue.pop(0)
        name = name if name in pkgs else provides.get(name, name)
        if name in want or name in SKIP:
            continue
        if name not in pkgs:
            print("  WARN: unknown package", name)
            continue
        want.append(name)
        for alts in deps_of(pkgs[name]):
            pick = next((a for a in alts if a in pkgs or a in provides), alts[0])
            queue.append(pick)
    print("packages:", " ".join(want))

    stage = os.path.join(work, "stage")
    shutil.rmtree(stage, ignore_errors=True)
    os.makedirs(stage)
    for name in want:
        deb = os.path.join(work, name + ".deb")
        if not os.path.exists(deb):
            with open(deb, "wb") as f:
                f.write(fetch(f"{REPO}/{pkgs[name]['Filename']}"))
        subprocess.run(["dpkg-deb", "-x", deb, stage], check=True)

    usr = os.path.join(stage, PREFIX)
    lib_dir = os.path.join(usr, "lib")

    # Server binary
    server = None
    for cand in ("bin/mariadbd", "bin/mysqld", "libexec/mariadbd", "sbin/mariadbd"):
        p = os.path.join(usr, cand)
        if os.path.exists(p):
            server = os.path.realpath(p)
            break
    if not server:
        sys.exit("mariadbd not found in package")
    os.makedirs(os.path.join(out_usr, "bin"), exist_ok=True)
    os.makedirs(os.path.join(out_usr, "lib"), exist_ok=True)
    shutil.copy(server, os.path.join(out_usr, "bin", "mariadbd"))

    # Shared library closure, resolved by NEEDED names.
    def needed(path):
        r = subprocess.run(["readelf", "-d", path], capture_output=True, text=True, check=True)
        return re.findall(r"\(NEEDED\)\s+Shared library: \[(.+?)\]", r.stdout)

    seen, todo = set(), [server]
    while todo:
        for n in needed(todo.pop()):
            if n in seen:
                continue
            seen.add(n)
            src = os.path.join(lib_dir, n)
            if os.path.exists(src):
                real = os.path.realpath(src)
                shutil.copy(real, os.path.join(out_usr, "lib", n))
                todo.append(real)
            elif n not in SYSTEM_LIBS:
                print("  NOTE: assuming system library:", n)
    print("bundled libs:", sorted(os.listdir(os.path.join(out_usr, "lib"))))

    # Error messages + charsets
    msg_dir = None
    for root, _dirs, files in os.walk(os.path.join(usr, "share")):
        if os.path.basename(root) == "english" and "errmsg.sys" in files:
            msg_dir = os.path.dirname(root)
            break
    if not msg_dir:
        sys.exit("errmsg.sys not found")
    share_out = os.path.join(out_usr, "share", "mariadb")
    shutil.copytree(msg_dir, share_out, symlinks=False, dirs_exist_ok=True)
    if not os.path.isdir(os.path.join(share_out, "charsets")):
        for root, dirs, _f in os.walk(usr):
            if "charsets" in dirs and os.path.exists(os.path.join(root, "charsets", "Index.xml")):
                shutil.copytree(os.path.join(root, "charsets"), os.path.join(share_out, "charsets"))
                break

    # bootstrap.sql, same as mariadb-install-db --auth-root-authentication-method=normal
    def find_sql(name):
        for root, _d, files in os.walk(usr):
            if name in files:
                return os.path.join(root, name)
        return None

    parts = ["create database if not exists mysql;", "use mysql;", "SET @auth_root_socket=NULL;"]
    for name in ("mysql_system_tables.sql", "mysql_performance_tables.sql",
                 "mysql_system_tables_data.sql", "maria_add_gis_sp_bootstrap.sql"):
        p = find_sql(name)
        if not p:
            if name in ("mysql_system_tables.sql", "mysql_system_tables_data.sql"):
                sys.exit(f"{name} not found")
            continue
        print("bootstrap:", p)
        with open(p, encoding="utf-8") as f:
            parts.append(f.read())
    parts.append("CREATE DATABASE IF NOT EXISTS `opencart` "
                 "CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci;")
    with open(os.path.join(out_usr, "share", "bootstrap.sql"), "w", encoding="utf-8") as f:
        f.write("\n".join(parts) + "\n")

    ver = pkgs["mariadb"]["Version"]
    with open(os.path.join(out_usr, "share", "mariadb-version.txt"), "w") as f:
        f.write(ver + "\n")
    print("MariaDB", ver, "staged into", out_usr)


if __name__ == "__main__":
    main()
