package com.supermov.tv;

import android.util.Log;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * 下载完成后（或手动补生成时）为影片写 Kodi 风格的 {@code .nfo} 与海报。
 *
 * <h3>为什么值得做</h3>
 * 网盘/本地播放器认片靠的是文件名和目录名，海报墙却要 TMDB 那套资料。下载后顺手把
 * NFO + 海报落在影片旁边，等于把「刮削」这一步提前做掉，播放器不用再联网猜。
 *
 * <h3>三条硬规则（来自两份刮削器研究报告，都是踩过坑的结论）</h3>
 * <ol>
 *   <li><b>宁缺毋滥</b>：认不准就什么都不写。Jellyfin/Emby 读 NFO 的优先级高于它自己的
 *       在线刮削、而且关不掉，一条写错的 NFO 会盖掉播放器本来能刮对的结果。</li>
 *   <li><b>图片与 NFO 一律跟视频同名</b>（{@code X.mkv → X.nfo / X-poster.jpg}），
 *       不往目录里塞 {@code movie.nfo}。目录级 {@code poster.jpg} 只在这个目录里
 *       确实只有一部影片时才写，否则同目录多部片会互相覆盖。</li>
 *   <li><b>原子写</b>：先写 {@code .tmp} 再改名。半张海报、被截断的 NFO 一旦落下去，
 *       幂等判断会认为「已经生成过」，永远不会自愈。</li>
 * </ol>
 *
 * <p>所有方法都<b>不能在 UI 线程调用</b>，内部只有阻塞的网络与磁盘 IO；对外绝不抛异常
 * —— 生成海报失败不该让一次已经校验通过的下载变成失败。</p>
 */
public final class NfoWriter {

    private static final String TAG = "SupeMov";

    /** 补生成时认作「影片」的后缀（与下载侧允许落盘的类型一致）。 */
    private static final String[] VIDEO_EXT = {
            "mkv", "mp4", "m4v", "ts", "m2ts", "avi", "wmv", "flv", "mov", "mpg", "mpeg", "webm", "iso", "strm"
    };

    /** 补生成时最多往下走几层目录（根/片名目录/文件 = 2 层，留出 Season 目录的余量）。 */
    private static final int MAX_DEPTH = 4;

    private static final String POSTER_SIZE = "w500";
    private static final String FANART_SIZE = "w1280";

    /** 分集剧的「剧 id / 季清单」解析结果，按剧缓存一次，七集只用一套请求。 */
    private static final Map<String, ShowMeta> SHOW_CACHE = new HashMap<>();

    /** 置 true 时补生成在处理下一个文件前停下（设置页那个「停止」按钮用）。 */
    public static volatile boolean abort;

    private NfoWriter() {}

    /** 补生成时的进度回调（在后台线程上被调，界面自己 post 回主线程）。 */
    public interface Progress {
        void onStep(String text, int done, int total);
    }

    // ==================================================================
    // 入口一：下载完成后
    // ==================================================================

    /**
     * 一条下载任务落盘后的生成入口。开关没开、密钥没填、认不到 TMDB id —— 都安静跳过，
     * 只在日志里留一行原因，不去打扰已经完成下载的用户。
     */
    public static void forDownload(File video, Dl task) {
        if (!Settings.nfoEnabled()) return;
        if (task == null || task.hash == null || task.hash.isEmpty()) {
            Log.d(TAG, "NFO：任务没有云指纹，认不到 TMDB id，跳过");
            return;
        }
        if (!TmdbClient.ready()) {
            Log.d(TAG, "NFO：未填 TMDB 密钥，跳过");
            return;
        }
        MovieStore.TmdbRef ref = MovieStore.tmdbRefForHash(task.hash);
        if (ref == null) {
            Log.d(TAG, "NFO：库里这条 hash 没挂 TMDB id，跳过 " + task.hash);
            return;
        }
        Log.d(TAG, "NFO：" + generate(video, ref));
    }

    // ==================================================================
    // 入口二：给已下载完、当时没生成海报的片子补
    // ==================================================================

    /**
     * 扫某个目录（一般是下载根目录），把「有视频文件、没有同名 NFO」的片子补齐。
     *
     * @return 中文结果摘要，直接上屏
     */
    public static String backfill(File root, Progress cb) {
        if (!TmdbClient.ready()) return "还没填 TMDB 接口密钥，先去上面填好再补生成。";
        Map<String, MovieStore.TmdbRef> index = MovieStore.tmdbRefIndex();
        if (index.isEmpty()) return "影片库里没有 TMDB 编号，补不了。";
        List<File> videos = new ArrayList<>();
        collect(root, 0, videos);
        if (videos.isEmpty()) return "目录里没找到影片文件：" + root.getAbsolutePath();

        int made = 0, had = 0, unmatched = 0, failed = 0, processed = 0;
        boolean stopped = false;
        List<String> examples = new ArrayList<>();
        for (int i = 0; i < videos.size(); i++) {
            if (abort) {
                stopped = true;
                break;
            }
            File v = videos.get(i);
            if (nfoOf(v).exists()) {
                had++;                       // 先生成过了，不必认片名
                continue;
            }
            if (cb != null) {
                cb.onStep("正在补 " + v.getName() + "（" + (i + 1) + "/" + videos.size() + "）",
                        i, videos.size());
            }
            MovieStore.TmdbRef ref = index.get(MovieStore.titleKey(nameWithoutExt(v.getName())));
            if (ref == null) {
                unmatched++;
                if (examples.size() < 3) examples.add(v.getName());
                continue;
            }
            String r = generate(v, ref);
            processed++;
            if ("已生成".equals(r)) made++;
            else if ("已有 NFO，跳过".equals(r)) had++;
            else {
                failed++;
                if (examples.size() < 3) examples.add(v.getName() + " → " + r);
            }
        }
        StringBuilder sb = new StringBuilder();
        if (stopped) sb.append("已手动停止（停在第 ").append(processed + 1).append(" 个）。\n");
        sb.append("扫到 ").append(videos.size()).append(" 个影片文件：\n")
          .append("已生成 ").append(made)
          .append("，已有 NFO 跳过 ").append(had)
          .append("，片名没对上 ").append(unmatched)
          .append("，取不到资料 ").append(failed).append("。");
        if (!examples.isEmpty()) {
            sb.append("\n\n例子：\n");
            for (String s : examples) sb.append("· ").append(s).append('\n');
        }
        sb.append("\n没对上的两种情况：不是本片库下下来的（或改过名）；片名重名但其实是不同年代的翻拍，"
                + "文件名里认不出来，就不猜了 —— 这一类请单独下载时自动生成（下载任务带云指纹，认得准）。");
        return sb.toString();
    }

    private static void collect(File dir, int depth, List<File> out) {
        if (dir == null || depth > MAX_DEPTH) return;
        File[] kids = dir.listFiles();
        if (kids == null) return;
        for (File f : kids) {
            String n = f.getName();
            if (n.startsWith(".")) continue;
            if (f.isDirectory()) {
                collect(f, depth + 1, out);
            } else if (isVideo(n)) {
                out.add(f);
            }
        }
    }

    private static boolean isVideo(String name) {
        int dot = name.lastIndexOf('.');
        if (dot < 0) return false;
        String ext = name.substring(dot + 1).toLowerCase(Locale.ROOT);
        for (String v : VIDEO_EXT) if (v.equals(ext)) return true;
        return false;
    }

    // ==================================================================
    // 生成
    // ==================================================================

    /** @return 一句中文结论（上屏与日志共用） */
    private static synchronized String generate(File video, MovieStore.TmdbRef ref) {
        if (video == null || !video.exists()) return "影片文件不在";
        try {
            return ref.isTv() ? forEpisode(video, ref) : forMovie(video, ref);
        } catch (Throwable e) {
            Log.d(TAG, "NFO 生成异常: " + e);
            return "生成出错：" + e;
        }
    }

    /** 与视频同名的那份 NFO。它在不在，就是「这部生成过了」的唯一记号。 */
    private static File nfoOf(File video) {
        return new File(video.getParentFile(), nameWithoutExt(video.getName()) + ".nfo");
    }

    private static String forMovie(File video, MovieStore.TmdbRef ref) {
        File nfo = nfoOf(video);
        if (nfo.exists()) return "已有 NFO，跳过";
        Map<String, String> q = params("language", "zh-CN",
                "append_to_response", "credits,alternative_titles");
        JSONObject d = TmdbClient.get("/movie/" + ref.tmdbId, q);
        if (d == null) return "TMDB 查不到这部（id " + ref.tmdbId + "）";

        String posterPath = d.optString("poster_path", "");
        String fanartPath = d.optString("backdrop_path", "");
        // 中文片名：TMDB 的「中国大陆」发行名最正，其次语言=zh-CN 返回的标题，最后才用库里的名字
        String cn = cnFromAltTitles(d.optJSONObject("alternative_titles"), "titles");
        String title = !cn.isEmpty() ? cn : pickTitle(d.optString("title", ""), ref.title);

        StringBuilder sb = new StringBuilder();
        xmlHead(sb).append("<movie>\n");
        t(sb, "title", title);
        t(sb, "originaltitle", d.optString("original_title", ""));
        t(sb, "sorttitle", title);
        String released = d.optString("release_date", "");
        t(sb, "premiered", released);
        if (released.length() >= 4) t(sb, "year", released.substring(0, 4));
        cdata(sb, "plot", d.optString("overview", ""));
        t(sb, "tagline", d.optString("tagline", ""));
        if (d.optInt("runtime") > 0) t(sb, "runtime", String.valueOf(d.optInt("runtime")));
        double vote = d.optDouble("vote_average", 0);
        if (vote > 0) {
            t(sb, "rating", fmt1(vote));
            t(sb, "votes", String.valueOf(d.optInt("vote_count", 0)));
        }
        JSONArray genres = d.optJSONArray("genres");
        if (genres != null) for (int i = 0; i < genres.length(); i++) {
            t(sb, "genre", field(genres, i, "name"));
        }
        JSONArray countries = d.optJSONArray("production_countries");
        if (countries != null) for (int i = 0; i < countries.length(); i++) {
            t(sb, "country", field(countries, i, "name"));
        }
        JSONArray companies = d.optJSONArray("production_companies");
        if (companies != null && companies.length() > 0) {
            t(sb, "studio", field(companies, 0, "name"));
        }
        writeCrew(sb, d, true);
        writeIds(sb, d.optString("imdb_id", ""), "movie", ref.tmdbId);
        t(sb, "thumb", TmdbClient.imageUrl(posterPath, POSTER_SIZE));
        if (!fanartPath.isEmpty()) {
            sb.append("  <fanart>\n    <thumb>")
              .append(esc(TmdbClient.imageUrl(fanartPath, FANART_SIZE)))
              .append("</thumb>\n  </fanart>\n");
        }
        sb.append("</movie>\n");
        return writeSet(video, nfo, sb.toString(), posterPath, fanartPath);
    }

    private static String forEpisode(File video, MovieStore.TmdbRef ref) {
        File nfo = nfoOf(video);
        if (nfo.exists()) return "已有 NFO，跳过";
        ShowMeta show = resolveShow(ref);
        if (show == null) return "认不出这部剧（「"
                + (ref.seriesName.isEmpty() ? ref.title : ref.seriesName) + "」在 TMDB 对不上）";
        JSONObject ep = show.episode(ref);
        if (ep == null) return "TMDB 没有「" + show.title + "」第 "
                + ref.seasonNo + " 季第 " + ref.episodeNo + " 集";

        // 剧级：tvshow.nfo + 剧海报（一个目录只写一次）
        File showDir = video.getParentFile();
        File tvNfo = new File(showDir, "tvshow.nfo");
        if (!tvNfo.exists()) {
            StringBuilder sb = new StringBuilder();
            xmlHead(sb).append("<tvshow>\n");
            t(sb, "title", show.title);
            t(sb, "showtitle", show.title);
            t(sb, "originaltitle", show.original);
            t(sb, "premiered", show.firstAir);
            if (show.firstAir.length() >= 4) t(sb, "year", show.firstAir.substring(0, 4));
            cdata(sb, "plot", show.overview);
            t(sb, "status", show.status);
            for (String g : show.genres) t(sb, "genre", g);
            for (String n : show.networks) t(sb, "studio", n);
            t(sb, "season", String.valueOf(ref.seasonNo));
            if (show.episodeCount > 0) t(sb, "episode", String.valueOf(show.episodeCount));
            writeIds(sb, show.imdbId, "tmdb", show.id);
            t(sb, "thumb", TmdbClient.imageUrl(show.posterPath, POSTER_SIZE));
            if (!show.fanartPath.isEmpty()) {
                sb.append("  <fanart>\n    <thumb>")
                  .append(esc(TmdbClient.imageUrl(show.fanartPath, FANART_SIZE)))
                  .append("</thumb>\n  </fanart>\n");
            }
            sb.append("</tvshow>\n");
            writeAtomic(tvNfo, sb.toString());
            byte[] poster = TmdbClient.getImage(show.posterPath, POSTER_SIZE);
            if (poster != null) writeAtomic(new File(showDir, "poster.jpg"), poster);
            byte[] fanart = TmdbClient.getImage(show.fanartPath, FANART_SIZE);
            if (fanart != null) writeAtomic(new File(showDir, "fanart.jpg"), fanart);
        }

        String epTitle = ep.optString("name", "");
        StringBuilder sb = new StringBuilder();
        xmlHead(sb).append("<episodedetails>\n");
        t(sb, "title", pickTitle(epTitle, ref.title));
        t(sb, "showtitle", show.title);
        t(sb, "season", String.valueOf(ref.seasonNo));
        t(sb, "episode", String.valueOf(ref.episodeNo));
        t(sb, "aired", ep.optString("air_date", ""));
        cdata(sb, "plot", ep.optString("overview", ""));
        if (ep.optInt("runtime") > 0) t(sb, "runtime", String.valueOf(ep.optInt("runtime")));
        writeIds(sb, "", "tmdb", ref.tmdbId);
        String still = ep.optString("still_path", "");
        t(sb, "thumb", TmdbClient.imageUrl(still.isEmpty() ? show.posterPath : still, POSTER_SIZE));
        sb.append("</episodedetails>\n");
        return writeSet(video, nfo, sb.toString(),
                still.isEmpty() ? show.posterPath : still, "");
    }

    /**
     * 落盘一套 sidecar：NFO + 海报 + 背景图。
     *
     * <p>图片先写、NFO 最后写：「有没有 NFO」就是幂等判断的依据，反过来（先写 NFO）
     * 万一海报没下成，下次重跑会以为这部已经处理完了。</p>
     */
    private static String writeSet(File video, File nfo, String xml,
                                   String posterPath, String fanartPath) {
        String stem = nameWithoutExt(video.getName());
        File dir = video.getParentFile();
        boolean poster = writeImage(new File(dir, stem + "-poster.jpg"), posterPath, POSTER_SIZE);
        boolean fanart = writeImage(new File(dir, stem + "-fanart.jpg"), fanartPath, FANART_SIZE);
        if (!writeAtomic(nfo, xml)) return "NFO 写入失败（目录不可写？）";
        // 目录里只有一部影片时，再给一份目录级 poster/fanart：老播放器只认这一种命名
        if (onlyVideoHere(video)) {
            if (poster) copySidecar(new File(dir, stem + "-poster.jpg"), new File(dir, "poster.jpg"));
            if (fanart) copySidecar(new File(dir, stem + "-fanart.jpg"), new File(dir, "fanart.jpg"));
        }
        return "已生成";
    }

    private static boolean writeImage(File target, String tmdbPath, String size) {
        if (tmdbPath == null || tmdbPath.isEmpty()) return false;
        if (target.exists() && target.length() > 3072) return true;
        byte[] data = TmdbClient.getImage(tmdbPath, size);
        return data != null && writeAtomic(target, data);
    }

    private static void copySidecar(File from, File to) {
        if (!from.exists() || to.exists()) return;
        try (FileInputStream in = new FileInputStream(from)) {
            ByteArrayOutputStream buf = new ByteArrayOutputStream((int) Math.min(from.length(), 8 * 1024 * 1024));
            byte[] chunk = new byte[16384];
            int n;
            while ((n = in.read(chunk)) > 0) buf.write(chunk, 0, n);
            writeAtomic(to, buf.toByteArray());
        } catch (Throwable e) {
            Log.d(TAG, "NFO：目录级海报没拷成 " + to + " :: " + e);
        }
    }

    private static boolean onlyVideoHere(File video) {
        File[] kids = video.getParentFile() == null ? null : video.getParentFile().listFiles();
        if (kids == null) return false;
        int n = 0;
        for (File f : kids) if (!f.isDirectory() && isVideo(f.getName())) n++;
        return n == 1;
    }

    // ==================================================================
    // TMDB 侧的零碎取值
    // ==================================================================

    /** 一部剧的元数据 + 已取过的季清单。 */
    private static final class ShowMeta {
        String id;
        String title = "";
        String original = "";
        String firstAir = "";
        String overview = "";
        String status = "";
        String imdbId = "";
        String posterPath = "";
        String fanartPath = "";
        int episodeCount;
        final List<String> genres = new ArrayList<>();
        final List<String> networks = new ArrayList<>();
        /** 季号 → 该季分集数组（TMDB 一次请求就是一整季）。 */
        final Map<Integer, JSONArray> seasons = new HashMap<>();

        /** 取某季的清单（带缓存，取不到返回 null）。 */
        JSONArray season(int n) {
            JSONArray hit = seasons.get(n);
            if (hit != null) return hit.length() == 0 ? null : hit;
            JSONObject d = TmdbClient.get("/tv/" + id + "/season/" + n,
                    params("language", "zh-CN"));
            JSONArray arr = d == null ? null : d.optJSONArray("episodes");
            seasons.put(n, arr == null ? new JSONArray() : arr);
            return arr;
        }

        /** 这一季里是否真有库里存的那个 TMDB 分集 id —— 判定「搜对了没有」的唯一硬证据。 */
        boolean hasEpisode(MovieStore.TmdbRef ref) {
            JSONArray arr = season(ref.seasonNo);
            return arr != null && find(arr, ref) != null;
        }

        /** 取本集的分集资料；先认 TMDB 分集 id，再退到集号。 */
        JSONObject episode(MovieStore.TmdbRef ref) {
            JSONArray arr = season(ref.seasonNo);
            if (arr == null) return null;
            JSONObject byId = find(arr, ref);
            if (byId != null) return byId;
            for (int i = 0; i < arr.length(); i++) {
                JSONObject o = arr.optJSONObject(i);
                if (o != null && ref.episodeNo > 0 && o.optInt("episode_number") == ref.episodeNo) {
                    return o;
                }
            }
            return null;
        }

        private static JSONObject find(JSONArray arr, MovieStore.TmdbRef ref) {
            for (int i = 0; i < arr.length(); i++) {
                JSONObject o = arr.optJSONObject(i);
                if (o != null && String.valueOf(o.optInt("id")).equals(ref.tmdbId)) return o;
            }
            return null;
        }
    }

    /**
     * 由「分集 id」反查剧。
     *
     * <p>库里分集剧存的 TMDB id 是 <b>TMDB 的分集 id</b>，而 TMDB 没有「按分集 id 直接查详情」
     * 的接口，所以只能：按剧名搜 → 取那一季的清单 → <b>看这个分集 id 在不在里面</b>。
     * 在，就是它；不在就换下一个候选，全都不在就不写。这样「按片名搜」这件不可靠的事
     * 被分集 id 反向验了一遍，不是盲取第一条。</p>
     *
     * <p>结果按「剧名+季号」缓存：一部 7 集的纪录片只会打一次搜索、一次季清单。</p>
     */
    private static ShowMeta resolveShow(MovieStore.TmdbRef ref) {
        String key = MovieStore.titleKey(
                !ref.seriesName.isEmpty() ? ref.seriesName : ref.title) + "|" + ref.seasonNo;
        synchronized (SHOW_CACHE) {
            if (SHOW_CACHE.containsKey(key)) return SHOW_CACHE.get(key);
        }
        ShowMeta got = searchShow(ref);
        synchronized (SHOW_CACHE) {
            SHOW_CACHE.put(key, got);    // 搜不到也记一笔，补生成时不重复打网络
        }
        return got;
    }

    private static ShowMeta searchShow(MovieStore.TmdbRef ref) {
        String query = !ref.seriesName.isEmpty() ? ref.seriesName : ref.title;
        JSONObject res = TmdbClient.get("/search/tv",
                params("query", query, "language", "zh-CN", "include_adult", "true"));
        JSONArray arr = res == null ? null : res.optJSONArray("results");
        if (arr == null || arr.length() == 0) return null;
        for (int i = 0; i < Math.min(3, arr.length()); i++) {
            JSONObject cand = arr.optJSONObject(i);
            if (cand == null || cand.optInt("id") <= 0) continue;
            ShowMeta meta = loadShow(String.valueOf(cand.optInt("id")));
            if (meta != null && meta.hasEpisode(ref)) return meta;
        }
        return null;
    }

    private static ShowMeta loadShow(String showId) {
        JSONObject d = TmdbClient.get("/tv/" + showId,
                params("language", "zh-CN", "append_to_response", "alternative_titles"));
        if (d == null) return null;
        ShowMeta m = new ShowMeta();
        m.id = String.valueOf(d.optInt("id"));
        m.title = d.optString("name", "");
        m.original = d.optString("original_name", "");
        m.firstAir = d.optString("first_air_date", "");
        m.overview = d.optString("overview", "");
        m.status = d.optString("status", "");
        m.imdbId = d.optString("imdb_id", "");
        m.posterPath = d.optString("poster_path", "");
        m.fanartPath = d.optString("backdrop_path", "");
        m.episodeCount = d.optInt("number_of_episodes", 0);
        JSONArray g = d.optJSONArray("genres");
        if (g != null) for (int i = 0; i < g.length(); i++) {
            m.genres.add(field(g, i, "name"));
        }
        JSONArray n = d.optJSONArray("networks");
        if (n != null) for (int i = 0; i < n.length(); i++) {
            m.networks.add(field(n, i, "name"));
        }
        String cn = cnFromAltTitles(d.optJSONObject("alternative_titles"), "titles");
        if (!cn.isEmpty()) m.title = cn;
        return m;
    }

    // ==================================================================
    // NFO 片段
    // ==================================================================

    /** 导演 / 编剧 / 前 10 名演员。分集不重复要 credits（一集一份，太贵）。 */
    private static void writeCrew(StringBuilder sb, JSONObject d, boolean withCredits) {
        if (!withCredits) return;
        JSONObject cr = d.optJSONObject("credits");
        if (cr == null) return;
        JSONArray crew = cr.optJSONArray("crew");
        if (crew != null) {
            for (int i = 0; i < crew.length(); i++) {
                JSONObject p = crew.optJSONObject(i);
                if (p != null && "Director".equals(p.optString("job"))) {
                    t(sb, "director", p.optString("name", ""));
                    break;
                }
            }
            for (int i = 0; i < crew.length(); i++) {
                JSONObject p = crew.optJSONObject(i);
                if (p != null && ("Writer".equals(p.optString("job"))
                        || "Screenplay".equals(p.optString("job")))) {
                    t(sb, "credits", p.optString("name", ""));
                }
            }
        }
        JSONArray cast = cr.optJSONArray("cast");
        if (cast != null) {
            for (int i = 0; i < Math.min(10, cast.length()); i++) {
                JSONObject p = cast.optJSONObject(i);
                if (p == null) continue;
                sb.append("  <actor>\n");
                t(sb, "    ", "name", p.optString("name", ""));
                t(sb, "    ", "role", p.optString("character", ""));
                t(sb, "    ", "sortorder", String.valueOf(p.optInt("order", i)));
                sb.append("  </actor>\n");
            }
        }
    }

    /**
     * ID 节点：有 IMDb 就以 IMDb 为 default，否则 TMDB 为 default；
     * 同时把 tmdbid / imdbid 老字段冗余写一份 —— 新老播放器各认各的。
     */
    private static void writeIds(StringBuilder sb, String imdbId, String tmdbType, String tmdbId) {
        boolean imdb = imdbId != null && imdbId.startsWith("tt");
        if (imdb) {
            sb.append("  <uniqueid type=\"imdb\" default=\"true\">")
              .append(esc(imdbId)).append("</uniqueid>\n");
            sb.append("  <uniqueid type=\"tmdb\">").append(esc(tmdbId)).append("</uniqueid>\n");
        } else {
            sb.append("  <uniqueid type=\"").append(tmdbType).append("\" default=\"true\">")
              .append(esc(tmdbId)).append("</uniqueid>\n");
        }
        t(sb, "tmdbid", tmdbId);
        if (imdb) t(sb, "imdbid", imdbId);
    }

    /** 中文名优先：TMDB 给的标题里有汉字就用它；否则找别名里的中文名；再否则用库里的片名。 */
    private static String pickTitle(String tmdbTitle, String libraryTitle) {
        if (hasCjk(tmdbTitle)) return tmdbTitle;
        if (hasCjk(libraryTitle)) return libraryTitle;
        return !tmdbTitle.isEmpty() ? tmdbTitle : libraryTitle;
    }

    private static String cnFromAltTitles(JSONObject alt, String key) {
        if (alt == null) return "";
        JSONArray arr = alt.optJSONArray(key);
        if (arr == null) return "";
        for (int i = 0; i < arr.length(); i++) {
            JSONObject o = arr.optJSONObject(i);
            if (o != null && "CN".equalsIgnoreCase(o.optString("iso_3166_1", ""))
                    && hasCjk(o.optString("title", ""))) {
                return o.optString("title", "");
            }
        }
        return "";
    }

    private static boolean hasCjk(String s) {
        if (s == null) return false;
        for (int i = 0; i < s.length(); i++) {
            char ch = s.charAt(i);
            if (ch >= 0x4E00 && ch <= 0x9FFF) return true;
        }
        return false;
    }

    // ==================================================================
    // 写盘（原子）
    // ==================================================================

    private static boolean writeAtomic(File target, String text) {
        return writeAtomic(target, text.getBytes(StandardCharsets.UTF_8));
    }

    private static boolean writeAtomic(File target, byte[] data) {
        if (target == null) return false;
        File tmp = new File(target.getAbsolutePath() + ".tmp");
        FileOutputStream out = null;
        try {
            File parent = target.getParentFile();
            if (parent != null && !parent.exists() && !parent.mkdirs()) return false;
            out = new FileOutputStream(tmp);
            out.write(data);
            out.flush();
            out.close();
            out = null;
            if (target.exists() && !target.delete()) return false;
            return tmp.renameTo(target);
        } catch (Throwable e) {
            Log.d(TAG, "NFO：写 " + target.getName() + " 失败 :: " + e);
            return false;
        } finally {
            if (out != null) {
                try {
                    out.close();
                } catch (Throwable ignored) {
                }
            }
            if (tmp.exists()) {
                // 走到这里就是没成功：改名成功的正常情况下 tmp 已经不存在了。
                // 留一堆 .tmp 在下载盘里，用户看到的是"这目录怎么这么多垃圾文件"。
                try {
                    tmp.delete();
                } catch (Throwable ignored) {
                }
            }
        }
    }

    // ==================================================================
    // XML 小工具
    // ==================================================================

    private static StringBuilder xmlHead(StringBuilder sb) {
        sb.append("<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>\n");
        return sb;
    }

    private static void t(StringBuilder sb, String name, String value) {
        t(sb, "  ", name, value);
    }

    private static void t(StringBuilder sb, String indent, String name, String value) {
        if (value == null) return;
        String v = value.trim();
        if (v.isEmpty()) return;
        sb.append(indent).append('<').append(name).append('>')
          .append(esc(v)).append("</").append(name).append(">\n");
    }

    private static void cdata(StringBuilder sb, String name, String value) {
        if (value == null || value.trim().isEmpty()) return;
        sb.append("  <").append(name).append("><![CDATA[")
          .append(value.trim().replace("]]>", "]]]]><![CDATA[>"))
          .append("]]></").append(name).append(">\n");
    }

    private static String esc(String s) {
        if (s == null) return "";
        StringBuilder sb = new StringBuilder(s.length() + 16);
        for (int i = 0; i < s.length(); i++) {
            char ch = s.charAt(i);
            switch (ch) {
                case '&': sb.append("&amp;"); break;
                case '<': sb.append("&lt;"); break;
                case '>': sb.append("&gt;"); break;
                case '"': sb.append("&quot;"); break;
                case '\'': sb.append("&apos;"); break;
                default:
                    // XML 1.0 不允许的控制字符直接丢掉，不然整份 NFO 解析失败
                    if (ch >= 0x20 || ch == '\t' || ch == '\n' || ch == '\r') sb.append(ch);
            }
        }
        return sb.toString();
    }

    private static String fmt1(double d) {
        return String.format(Locale.ROOT, "%.1f", d);
    }

    /** 数组里第 i 个对象的某个字段；对象缺失给空串，不让一个坏元素毁掉整份 NFO。 */
    private static String field(JSONArray arr, int i, String key) {
        JSONObject o = arr.optJSONObject(i);
        return o == null ? "" : o.optString(key, "");
    }

    private static Map<String, String> params(String... kv) {
        Map<String, String> m = new java.util.LinkedHashMap<>();
        for (int i = 0; i + 1 < kv.length; i += 2) m.put(kv[i], kv[i + 1]);
        return m;
    }

    /** 去掉扩展名（保留原样，不做任何清洗——文件名已经是最终名）。 */
    private static String nameWithoutExt(String fileName) {
        int dot = fileName.lastIndexOf('.');
        return dot > 0 ? fileName.substring(0, dot) : fileName;
    }
}
