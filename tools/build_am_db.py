#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""生成 AM 变体（山姆影库）的内嵌片库 assets/SuperMOV.db。

数据来自两处（都在仓库外，用绝对路径点名）：
  * 片单主体  c:/py/DJYDXS31NexioAM/tmdbam.db :: movie_tmdb_4k（1109 行，全部 40 位 hash）
  * 海报/剧照  c:/py/艾美mov/ew3.db :: movie.preview_poster / movie_image

表结构与视图沿用网盘版（DJYDXS3Nexio）的 SuperMOV.db，只做一处加法：
movie 多一列 hash，v_movie_app 多暴露 hash + source_type。
schema_version 保持 2 —— 加一列可空字段是向后兼容的，App 的
MovieDb.SCHEMA_SUPPORTED=2 才不会把新库判成「库太新，请升级应用」。

用法（在仓库根目录）：
    python -X utf8 tools/build_am_db.py
    python -X utf8 tools/build_am_db.py --out /tmp/试.db --dry
"""
import argparse
import json
import sqlite3
import sys
from pathlib import Path

SRC_LIB = Path("C:/py/DJYDXS31NexioAM/tmdbam.db")
SRC_EW3 = Path("C:/py/艾美mov/ew3.db")
# 云端容器清点结果（cdn_census.py 写的）：库里的 file_name_ext 不可信，靠它对「只留 mkv」
SRC_CENSUS = Path("C:/py/DJYDXS31NexioAM/cdn_census.db")
REPO = Path(__file__).resolve().parents[1]
ASSETS = REPO / "app/src/main/assets"
DEFAULT_OUT = ASSETS / "SuperMOV.db"

DB_VERSION = 2026092501
SCHEMA_VERSION = 2

# 版块：hash 片库只有 4K 电影与 4K 纪录片两类，与 MovieStore.CAT_FIDS 一一对应
FID_MOVIE = 112
FID_DOC = 37

# 技术标记（进 classification）与题材标记（进 genres，供分类栏下方的过滤器用）
TECH_TAGS = {"4K", "HDR", "SDR", "全景声", "杜比", "DTS", "IMAX", "3D", "杜比视界"}


def rows(db, sql, args=()):
    con = sqlite3.connect(str(db))
    con.row_factory = sqlite3.Row
    try:
        return [dict(r) for r in con.execute(sql, args)]
    finally:
        con.close()


def copy_schema(src, dst):
    """把模板库的表结构原样搬过来（视图自己重写，索引/触发器一并带上）。"""
    con = sqlite3.connect(str(src))
    n = 0
    try:
        for rtype, rname, rsql in con.execute(
            "SELECT type,name,sql FROM sqlite_master "
            "WHERE sql IS NOT NULL AND name NOT LIKE 'sqlite_%' ORDER BY type DESC,rowid"
        ):
            if rtype == "view":
                continue  # 视图在下面重写
            dst.execute(rsql)
            n += 1
    finally:
        con.close()
    return n


V_MOVIE_APP = """
CREATE VIEW v_movie_app AS
SELECT
    m.uid                                          AS uid,
    m.id                                           AS id,
    m.src_tid                                      AS src_tid,
    m.fid                                          AS fid,
    m.forum_name                                    AS forum_name,
    COALESCE(NULLIF(m.title_cn, ''), m.title)      AS name,
    m.title_en                                     AS title_en,
    m.title_alt                                    AS title_alt,
    m.year                                         AS year,
    m.release_date                                 AS release_date,
    m.runtime_min                                  AS runtime_min,
    m.region                                       AS region,
    m.language                                     AS language,
    m.genres                                       AS genres,
    m.rating_douban                                AS rating_douban,
    m.rating_imdb                                  AS rating_imdb,
    m.synopsis                                     AS synopsis,
    COALESCE(NULLIF(m.poster_url, ''), m.pic)      AS poster,
    m.classification                               AS classification,
    m.tags                                         AS tags,
    m.pan_status                                   AS pan_status,
    m.pan_url                                      AS pan_url,
    m.pan_pwd                                      AS pan_pwd,
    m.video_count                                  AS video_count,
    m.total_size                                   AS total_size,
    m.updated_at                                   AS updated_at,
    COALESCE(m.pin_top, 0)                         AS pin_top,
    COALESCE(m.hash, '')                           AS hash,
    CASE WHEN COALESCE(m.hash,'') != '' THEN 'hash' ELSE 'baidu' END AS source_type,
    (SELECT l.id       FROM pan_link l WHERE l.movie_id = m.id AND l.status = 'ok'
      ORDER BY l.video_count DESC LIMIT 1)         AS pan_link_id,
    (SELECT l.shareid  FROM pan_link l WHERE l.movie_id = m.id AND l.status = 'ok'
      ORDER BY l.video_count DESC LIMIT 1)         AS shareid,
    (SELECT l.share_uk FROM pan_link l WHERE l.movie_id = m.id AND l.status = 'ok'
      ORDER BY l.video_count DESC LIMIT 1)         AS share_uk,
    (SELECT count(*)   FROM movie_ext_id e WHERE e.movie_id = m.id) AS ext_id_count
FROM movie m
WHERE COALESCE(m.src_deleted, 0) = 0
"""

# v_episode 保持原样：hash 片在 pan_link/pan_video 里没有行，剧集清单自然为空，
# 详情页的「库内已收录 N 个文件」提示会安静地不显示。
V_EPISODE = """
CREATE VIEW v_episode AS
SELECT
    m.id            AS movie_id,
    m.uid           AS movie_uid,
    COALESCE(NULLIF(m.title_cn, ''), m.title) AS movie_name,
    v.id            AS video_id,
    v.pan_link_id   AS pan_link_id,
    v.path          AS path,
    v.filename      AS filename,
    v.ext           AS ext,
    COALESCE(v.rename_to, v.filename) AS final_filename,
    v.size          AS size,
    v.season        AS season,
    v.episode       AS episode,
    m.pan_url       AS pan_url,
    m.pan_pwd       AS pan_pwd,
    l.shareid       AS shareid,
    l.share_uk      AS share_uk
FROM movie m
JOIN pan_link  l ON l.movie_id = m.id
JOIN pan_video v ON v.pan_link_id = l.id
WHERE COALESCE(m.src_deleted, 0) = 0
"""


def split_tags(category):
    tech, genre = [], []
    for raw in (category or "").split(","):
        tag = raw.strip()
        if not tag:
            continue
        (tech if tag in TECH_TAGS else genre).append(tag)
    return tech, genre


def cloud_report():
    """读 cdn_census.py 的清点结果：movie_id -> (云端容器, 占位桩原因, 请求错误)。

    库里的 file_name_ext 全是 mkv，指望它做「只保留 .mkv」等于没做 —— 真实容器
    只有云端 getCdnUrl 知道（实测 600700 蜘蛛侠：英雄远征 报 .ic2）。
    """
    if not SRC_CENSUS.exists():
        return None
    return {
        r["movie_id"]: (
            str(r["cloud_ext"] or "").strip().lower().lstrip("."),
            str(r["stub"] or ""),
            str(r["err"] or ""),
        )
        for r in rows(SRC_CENSUS, "SELECT movie_id,cloud_ext,stub,err FROM census")
    }


def build(out_path, template, dry):
    # 第一道：上游 file_name_ext。除 mkv 外见过 ic2（厂商加密，拼出来不可播）、
    # iso / m2ts（不是单文件容器）、mp4 等。
    all_rows = rows(SRC_LIB, "SELECT * FROM movie_tmdb_4k ORDER BY movie_id")
    lib = [r for r in all_rows
           if str(r["file_name_ext"] or "").strip().lower().lstrip(".") == "mkv"]
    dropped = len(all_rows) - len(lib)
    print("片源过滤: 总 %d，留 mkv %d，删非 mkv %d" % (len(all_rows), len(lib), dropped))

    # 第二道：云端真实容器。清点没跑完就退出，免得「没清到」被当成「不是 mkv」删掉。
    rep = cloud_report()
    if rep is None:
        sys.exit("缺少云端清点结果 %s：先跑 cdn_census.py" % SRC_CENSUS)
    missing = [r["movie_id"] for r in lib if r["movie_id"] not in rep]
    if missing:
        sys.exit("云端清点不全：还差 %d 条（最早 5 个 %s）" % (len(missing), missing[:5]))
    keep, bad = [], []
    for r in lib:
        ext, stub, err = rep[r["movie_id"]]
        if ext == "mkv" and not stub and not err:
            keep.append(r)
        else:
            bad.append((r["movie_id"], r["name"], ext, stub or err))
    for mid, name, ext, why in bad:
        print("  剔除 %s %s → %s" % (mid, name, why or ("非 mkv 容器 ." + ext)))
    print("云端过滤: 留 %d，剔 %d" % (len(keep), len(bad)))
    lib = keep
    ids = [r["movie_id"] for r in lib]
    con_ew3 = sqlite3.connect(str(SRC_EW3))
    con_ew3.row_factory = sqlite3.Row
    posters, stills = {}, {}
    marks = ",".join("?" * len(ids))
    for r in con_ew3.execute(
        "SELECT id,preview_poster,small_poster FROM movie WHERE id IN (%s)" % marks, ids
    ):
        posters[r["id"]] = (r["preview_poster"] or "").strip() or (r["small_poster"] or "").strip()
    # 剧照：详情页/后续做背景图用，取前 3 张
    for r in con_ew3.execute(
        "SELECT movie_id,image_url FROM movie_image WHERE movie_id IN (%s) "
        "ORDER BY movie_id,order_index" % marks,
        ids,
    ):
        stills.setdefault(r["movie_id"], []).append(r["image_url"].strip())
    con_ew3.close()

    out_path.parent.mkdir(parents=True, exist_ok=True)
    tmp = out_path.with_suffix(out_path.suffix + ".new")
    if tmp.exists():
        tmp.unlink()
    dst = sqlite3.connect(str(tmp))

    copied = copy_schema(template, dst)
    cols = {r[1] for r in dst.execute("PRAGMA table_info(movie)")}
    if "hash" not in cols:
        dst.execute("ALTER TABLE movie ADD COLUMN hash TEXT")
    dst.executescript(V_MOVIE_APP)
    dst.executescript(V_EPISODE)

    now = "2026-09-25 00:00:00"
    ins_movie = 0
    skipped = []
    for r in lib:
        h = (r["hash"] or "").strip().lower()
        if len(h) != 40 or any(c not in "0123456789abcdef" for c in h):
            skipped.append((r["movie_id"], r["name"], "hash 不是 40 位十六进制"))
            continue
        name = (r["name"] or "").strip() or (r["en_name"] or "").strip()
        tech, genre = split_tags(r["category"])
        media_type = r["media_type"] or "movie"
        fid = FID_DOC if media_type == "tv_episode" else FID_MOVIE
        poster = posters.get(r["movie_id"], "")
        rating = (r["rating"] or 0) / 10.0
        dst.execute(
            "INSERT INTO movie(id,src_tid,fid,forum_name,title,title_cn,title_en,title_alt,"
            "year,release_date,runtime_min,region,language,genres,rating_douban,rating_imdb,"
            "synopsis,poster_url,pic,classification,tags,classes,video_count,total_size,"
            "pan_status,pan_url,pan_pwd,uid,hash,pin_top,src_deleted,created_at,updated_at,"
            "src_first_seen,src_last_seen) "
            "VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)",
            (
                r["movie_id"], r["movie_id"], fid, "4K 原盘", name, name,
                (r["en_name"] or "").strip(), "",
                r["year"], (r["tmdb_date"] or "").strip(), r["time_long"],
                (r["area"] or "").strip(), (r["lang"] or "").strip(),
                json.dumps(genre, ensure_ascii=False), rating, 0.0,
                "", poster, poster, " · ".join(["4K"] + [t for t in tech if t != "4K"]),
                json.dumps(sorted(set(tech + genre)), ensure_ascii=False), "",
                1, r["file_size"] or 0,
                "hash", "", "", "a%d" % r["movie_id"], h, 0, 0, now, now, now, now,
            ),
        )
        ins_movie += 1
        mid = r["movie_id"]
        if r["tmdb_id"]:
            dst.execute(
                "INSERT OR IGNORE INTO movie_ext_id(movie_id,source,id,subtype,url,confidence,"
                "origin,created_at,updated_at) VALUES(?,?,?,?,?,?,?,?,?)",
                (mid, "tmdb", str(r["tmdb_id"]), "tv" if media_type == "tv_episode" else "movie",
                 "https://www.themoviedb.org/%s/%d"
                 % ("tv" if media_type == "tv_episode" else "movie", r["tmdb_id"]),
                 100, "am_build", now, now),
            )
        if r["douban_id"]:
            dst.execute(
                "INSERT OR IGNORE INTO movie_ext_id(movie_id,source,id,subtype,url,confidence,"
                "origin,created_at,updated_at) VALUES(?,?,?,?,?,?,?,?,?)",
                (mid, "douban", str(r["douban_id"]), "movie",
                 "https://movie.douban.com/subject/%s/" % r["douban_id"], 100, "am_build", now, now),
            )

    dst.execute("DELETE FROM meta")
    for k, v in {
        "schema_version": str(SCHEMA_VERSION),
        "db_version": str(DB_VERSION),
        "db_build_id": "am4k-%016x" % (hash(tuple(ids)) & 0xFFFFFFFFFFFFFFFF),
        "db_source": "tmdbam.db/movie_tmdb_4k + 艾美mov/ew3.db 海报",
        "movie_total": str(ins_movie),
        "hash_total": str(ins_movie),
        "built_at": now,
    }.items():
        dst.execute("INSERT INTO meta(key,value) VALUES(?,?)", (k, v))
    dst.commit()

    # 自检：App 真正会跑的那几条查询
    probe(dst, "SELECT count(*) FROM v_movie_app WHERE fid=?", [FID_MOVIE])
    probe(dst, "SELECT count(*) FROM v_movie_app WHERE fid=?", [FID_DOC])
    probe(dst, "SELECT count(*) FROM v_movie_app WHERE genres LIKE ?", ['%"动作"%'])
    probe(dst, "SELECT src_tid,substr(hash,1,10),poster FROM v_movie_app WHERE src_tid=15339")
    probe(dst, "SELECT count(*) FROM v_episode")
    build_id = dst.execute("SELECT value FROM meta WHERE key='db_build_id'").fetchone()[0]
    dst.execute("VACUUM")
    dst.close()

    print("表/索引 DDL 复用 %d 条；写入影片 %d 部；跳过 %d" % (copied, ins_movie, len(skipped)))
    for s in skipped[:10]:
        print("  跳过:", s)
    print("产物 %s (%.2f MB)" % (tmp, tmp.stat().st_size / 1048576))
    if dry:
        tmp.unlink()
        print("--dry：不覆盖正式库")
        return
    tmp.replace(out_path)
    ver = out_path.with_suffix(".version")
    ver.write_text(
        "db_version=%d\nschema_version=%d\ndb_build_id=%s\n" % (DB_VERSION, SCHEMA_VERSION, build_id),
        encoding="utf-8",
    )
    print("已写 %s + %s" % (out_path, ver))


def probe(con, sql, args=()):
    print("  自检 %-56s -> %s" % (sql[:56], con.execute(sql, args).fetchall()[:1]))


if __name__ == "__main__":
    ap = argparse.ArgumentParser()
    ap.add_argument("--out", type=Path, default=DEFAULT_OUT)
    ap.add_argument("--template", type=Path, default=DEFAULT_OUT)
    ap.add_argument("--dry", action="store_true")
    a = ap.parse_args()
    for p in (SRC_LIB, SRC_EW3, a.template):
        if not p.exists():
            sys.exit("找不到输入：%s" % p)
    build(a.out, a.template, a.dry)
