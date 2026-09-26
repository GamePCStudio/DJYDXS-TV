#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""生成 AM 变体（山姆影库）的内嵌片库 assets/SuperMOV.db。

数据来自四处（都在仓库外，用绝对路径点名）：
  * hash 片单  c:/py/DJYDXS31NexioAM/tmdbam.db :: movie_tmdb_4k（40 位 hash，走艾美 CDN）
  * 海报/剧照  c:/py/艾美mov/ew3.db :: movie.preview_poster / movie_image
  * 云端容器   c:/py/DJYDXS31NexioAM/cdn_census.db :: census（只认真实容器，剔 .ic2 桩）
  * 网盘 4K    C:/py/SuperMOVDB/SuperMOV.db :: movie(fid=112 4KSDR.Remux)，只有百度链接
    —— **默认不并**，要它得显式加 --with-pan（见下）

tmdbam.db 的上游是 c:/py/艾美mov/ 里那套脚本，链路是
    ew3.db → ew3_tmdb_map.py → ew3_finalize.py → ew3_tv_fill.py → 覆盖 tmdbam.db
ew3.db 是**厂商会原地改写的活库**（同一文件先后读到 8238 行/最大 2021 与 11088 行/最大
2026，mtime 却没动），所以跑链路前先 cp 一份快照（当前快照 ew3_snapshot_20260926.db）。
2026-09-26 那次「2026 新片全不在库」就是这么来的：tmdbam.db 是 09-25 上午的旧快照，
feat-11 只有 1224 条；重跑后 1698 条 → movie_tmdb_4k 1562 行 → 云端真实 mkv 1519 部，
其中 2022+ 358 部（《超级少女》2026-06-24 / 《揭秘日》/ 《挽救计划》都在），
hash 侧已经覆盖新片，**不再需要网盘兜底**，故网盘段默认关闭。

网盘段的现状（--with-pan 打开时）：fid=112 单片候选 436 → 内部去重 413 → 与 hash 同豆瓣
158 条 → 净新增 255 部，体积中位 17.2 GB（≥30 GB 只有 48 部），且全部要先登录百度转存，
2026-09-26 已决定不并入。去重口径不变：hash 侧 douban_id 全有值，先按豆瓣号比，网盘侧
9 条没豆瓣号的用「片名+年份」兜底；同片两边都有时留 hash（免登录、分段直链、能逐段 SHA1）。
网盘侧另两处口径：video_count>2 的整季剧集（实测 4 帖）不收，本变体一卡对一文件；
region 在 SuperMOV.db 里 441 条全空，所以网盘行「地区」留空，不拿语言去猜。

表结构与视图沿用网盘版（DJYDXS3Nexio）的 SuperMOV.db，只做一处加法：
movie 多一列 hash，v_movie_app 多暴露 hash + source_type。
schema_version 保持 2 —— 加一列可空字段是向后兼容的，App 的
MovieDb.SCHEMA_SUPPORTED=2 才不会把新库判成「库太新，请升级应用」。

用法（在仓库根目录）：
    python -X utf8 tools/build_am_db.py                 # hash 片源，1519 部
    python -X utf8 tools/build_am_db.py --with-pan      # 另并 255 条网盘 4K
    python -X utf8 tools/build_am_db.py --out /tmp/试.db --dry
"""
import argparse
import json
import re
import sqlite3
import sys
from pathlib import Path

SRC_LIB = Path("C:/py/DJYDXS31NexioAM/tmdbam.db")
SRC_EW3 = Path("C:/py/艾美mov/ew3.db")
# 云端容器清点结果（cdn_census.py 写的）：库里的 file_name_ext 不可信，靠它对「只留 mkv」
SRC_CENSUS = Path("C:/py/DJYDXS31NexioAM/cdn_census.db")
# 网盘 4K 源：只有百度链接、没有 hash。只在 --with-pan 时用（默认不并，理由见文件头）
SRC_PAN = Path("C:/py/SuperMOVDB/SuperMOV.db")
REPO = Path(__file__).resolve().parents[1]
ASSETS = REPO / "app/src/main/assets"
DEFAULT_OUT = ASSETS / "SuperMOV.db"

# 2026092502：按云端真实容器二次过滤，剔掉 43 条 .ic2（库里 file_name_ext 全写 mkv，不可信）
# 2026092601：重跑上游映射（ew3.db 旧快照 → 新快照），1066 → 1519 部，2022+ 从 0 涨到 358
DB_VERSION = 2026092601
SCHEMA_VERSION = 2

# 版块：hash 片库只有 4K 电影与 4K 纪录片两类，与 MovieStore.CAT_FIDS 一一对应
FID_MOVIE = 112
FID_DOC = 37
# 网盘侧只取 4KSDR.Remux 这一版（与应用「4K 片库」定位一致）
PAN_FID = 112
PAN_FORUM = "4KSDR.Remux"
# movie.src_tid 是全库唯一键，hash 侧占 15339~602401、网盘侧原生 tid 19063~36074 会撞号，
# 所以网盘行统一抬到 1e6 以上（详情页按 src_tid 查，只要唯一就行）。uid 前缀 p 区分来源。
PAN_TID_BASE = 1000000


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


def txt(v):
    """源库把 NULL 写成字符串 'None' 的地方不少，统一清成空串。"""
    s = str(v if v is not None else "").strip()
    return "" if s in ("None", "null", "NULL") else s


def main_title(title_cn, fallback):
    """网盘帖的 title_cn 是「中文名/港译/台译/英文名」斜杠串，海报卡片只显示第一段。"""
    s = txt(title_cn) or txt(fallback)
    for sep in ("/", "／"):
        if sep in s:
            s = s.split(sep)[0].strip()
    return s


def first_date(*vals):
    for v in vals:
        m = re.search(r"(\d{4})[-/.](\d{1,2})[-/.](\d{1,2})", txt(v))
        if m:
            return "%s-%02d-%02d" % (m.group(1), int(m.group(2)), int(m.group(3)))
    return ""


def genre_list(raw):
    return [t.strip() for t in re.split(r"[/、,，]", txt(raw)) if t.strip()]


def pan_key(title_en, title_cn, year):
    """没豆瓣号时的兜底身份：英文片名（无则中文首段）+ 年份。"""
    name = (txt(title_en) or main_title(title_cn, "")).lower()
    name = re.sub(r"[^0-9a-z\u4e00-\u9fff]+", " ", name).strip()
    return (name, year or 0)


def pan_4k_rows(hash_douban, hash_keys):
    """SuperMOV.db 的 4KSDR.Remux 版块，去掉与 hash 侧重复的、内部重复的（同片多帖留最大的）。"""
    raw = rows(SRC_PAN, "SELECT * FROM movie WHERE fid=? AND coalesce(src_deleted,0)=0 "
                         "AND pan_status='ok' AND coalesce(pan_url,'')<>'' "
                         "AND coalesce(video_count,0)<=2 ORDER BY id", [PAN_FID])
    best = {}
    for r in raw:
        db = txt(r["douban_id"])
        k = "d" + db if db else "k%s" % (pan_key(r["title_en"], r["title_cn"], r["year"]),)
        if k in best and (r["total_size"] or 0) <= (best[k]["total_size"] or 0):
            continue
        best[k] = r
    out, dup_hash, dup_inner = [], 0, len(raw) - len(best)
    for k, r in sorted(best.items(), key=lambda x: -(x[1]["year"] or 0)):
        db = txt(r["douban_id"])
        if db and db in hash_douban:
            dup_hash += 1
            continue
        if not db and pan_key(r["title_en"], r["title_cn"], r["year"]) in hash_keys:
            dup_hash += 1
            continue
        out.append(r)
    print("网盘 4K(%s): 取 %d 条，内部重复 %d，与 hash 重叠 %d → 净新增 %d"
          % (PAN_FORUM, len(raw), dup_inner, dup_hash, len(out)))
    return out



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


def build(out_path, template, dry, with_pan=False):
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

    now = "2026-09-26 00:00:00"
    ins_movie = 0
    skipped = []
    hash_douban, hash_keys = set(), set()
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
        hash_douban.add(str(r["douban_id"] or "").strip())
        hash_keys.add(pan_key(r["en_name"], r["name"], r["year"]))
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

    hash_ins = ins_movie
    pan_ins = 0
    pan_rows = pan_4k_rows(hash_douban, hash_keys) if with_pan else []
    if not with_pan:
        print("网盘段：跳过（只出 hash 片源；--with-pan 才并 SuperMOV.db 的 255 条）")
    for r in pan_rows:
        tid = PAN_TID_BASE + int(r["src_tid"])
        name = main_title(r["title_cn"], r["title"])
        poster = txt(r["poster_url"]) or txt(r["pic"])
        date = first_date(r["release_date"], r["post_date"])
        genre = genre_list(r["genres"])
        tech = ["4K"] + [t for t in ("HDR", "杜比视界")
                         if re.search(t if t == "HDR" else r"Dolby Vision|杜比视界",
                                      txt(r["title"]) + txt(r["title_cn"]), re.I)]
        dst.execute(
            "INSERT INTO movie(id,src_tid,fid,forum_name,title,title_cn,title_en,title_alt,"
            "year,release_date,runtime_min,region,language,genres,rating_douban,rating_imdb,"
            "synopsis,poster_url,pic,classification,tags,classes,video_count,total_size,"
            "pan_status,pan_url,pan_pwd,uid,hash,pin_top,src_deleted,created_at,updated_at,"
            "src_first_seen,src_last_seen) "
            "VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)",
            (
                tid, tid, PAN_FID, PAN_FORUM, txt(r["title"]), name,
                txt(r["title_en"]), txt(r["title_cn"]),
                r["year"] or 0, date, r["runtime_min"] or 0,
                "", txt(r["language"]),
                json.dumps(genre, ensure_ascii=False), float(r["rating_douban"] or 0), 0.0,
                txt(r["synopsis"]), poster, poster, " · ".join(tech),
                json.dumps(sorted(set(tech + genre)), ensure_ascii=False), "",
                r["video_count"] or 0, r["total_size"] or 0,
                "ok", txt(r["pan_url"]), txt(r["pan_pwd"]), "p%d" % r["id"], "", 0, 0,
                now, now, now, now,
            ),
        )
        pan_ins += 1
        if txt(r["douban_id"]):
            dst.execute(
                "INSERT OR IGNORE INTO movie_ext_id(movie_id,source,id,subtype,url,confidence,"
                "origin,created_at,updated_at) VALUES(?,?,?,?,?,?,?,?,?)",
                (tid, "douban", txt(r["douban_id"]), "movie",
                 "https://movie.douban.com/subject/%s/" % txt(r["douban_id"]),
                 100, "am_build", now, now),
            )

    build_ids = tuple(ids) + tuple(PAN_TID_BASE + int(r["src_tid"]) for r in pan_rows)
    dst.execute("DELETE FROM meta")
    for k, v in {
        "schema_version": str(SCHEMA_VERSION),
        "db_version": str(DB_VERSION),
        "db_build_id": "am4k-%016x" % (hash(build_ids) & 0xFFFFFFFFFFFFFFFF),
        "db_source": "tmdbam.db/movie_tmdb_4k + 艾美mov/ew3.db 海报"
                     + (" + SuperMOV.db 网盘4K" if with_pan else ""),
        "movie_total": str(ins_movie + pan_ins),
        "hash_total": str(hash_ins),
        "pan_total": str(pan_ins),
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
    # 重跑要验证的是「新片真进库」：2022+ 有货；并网盘时 source_type/链接也一起看
    probe(dst, "SELECT count(*) FROM v_movie_app WHERE release_date>='2022-01-01'")
    probe(dst, "SELECT src_tid,name,source_type,substr(hash,1,6),substr(pan_url,1,28) FROM v_movie_app "
               "WHERE release_date>='2026-01-01' ORDER BY release_date DESC LIMIT 1")
    probe(dst, "SELECT count(*) FROM v_movie_app WHERE source_type='baidu' "
               "AND coalesce(pan_url,'')=''")
    build_id = dst.execute("SELECT value FROM meta WHERE key='db_build_id'").fetchone()[0]
    dst.execute("VACUUM")
    dst.close()

    print("表/索引 DDL 复用 %d 条；写入影片 %d 部（hash %d + 网盘 %d）；跳过 %d"
          % (copied, ins_movie + pan_ins, hash_ins, pan_ins, len(skipped)))
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
    ap.add_argument("--with-pan", action="store_true",
                    help="另并 SuperMOV.db 的网盘 4K（默认只出 hash 片源）")
    a = ap.parse_args()
    need = [SRC_LIB, SRC_EW3, a.template] + ([SRC_PAN] if a.with_pan else [])
    for p in need:
        if not p.exists():
            sys.exit("找不到输入：%s" % p)
    build(a.out, a.template, a.dry, a.with_pan)
