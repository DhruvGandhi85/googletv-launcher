package com.gathin.tvhome;

import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.BitmapShader;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Shader;
import android.os.Handler;
import android.os.Looper;
import android.util.LruCache;
import android.view.View;
import android.widget.ImageView;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/**
 * Background data & artwork fetcher for ESPN Live Sports, all Trakt user lists / watchlists / history /
 * recommendations / trending shelves, and Stremio Metahub 16:9 widescreen backdrops.
 */
public final class HubDataFetcher {

    /** User's personal Trakt Client ID from trakt_api.env */
    public static final String TRAKT_CLIENT_ID = "TAFn3uRVPHeS8skvQDnS_DyymbWYbs6Sm9Q-IempuEc";
    /** OAuth Device Code pair (requires matching client_id + client_secret for token exchange) */
    public static final String TRAKT_OAUTH_CLIENT_ID = "e6fde6173adf3c6af8fd1b0694b9b84d7c519cefc24482310e1de06c6abe5467";
    public static final String TRAKT_OAUTH_CLIENT_SECRET = "15119384341d9a61c751d8d515acbc0dd801001d4ebe85d3eef9885df80ee4d9";
    public static final String DEFAULT_TRAKT_TOKEN = "xr_TWrh5JzkX2DGAyg0XQG4PG6lojguyhsFIkisoh1nl4iRo0eXDtoTVbJxqTLUb";
    public static final String DEFAULT_TRAKT_USERNAME = "DhruvG85";
    private static final String PREFS_NAME = "tvhome_hub_prefs";

    private static final ExecutorService IO_POOL = Executors.newFixedThreadPool(2);
    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    private static final Map<String, List<SportGame>> CACHED_LEAGUE_GAMES = new ConcurrentHashMap<>();
    private static volatile long lastSportsFetchTime = 0;
    private static final LruCache<String, Bitmap> MEM_CACHE = new LruCache<String, Bitmap>(8 * 1024) {
        @Override
        protected int sizeOf(String key, Bitmap value) {
            return Math.max(1, value.getByteCount() / 1024);
        }
    };

    public static void clearMemoryCache() {
        MEM_CACHE.evictAll();
        UP_NEXT_CACHE.clear();
        CACHED_LEAGUE_GAMES.clear();
    }

    public static final class SportGame {
        public final String id;
        public final String league;
        public final String awayAbbr;
        public final String awayName;
        public final String awayScore;
        public final int awayColor;
        public final String homeAbbr;
        public final String homeName;
        public final String homeScore;
        public final int homeColor;
        public final String state;
        public final String statusDetail;
        public final String network;
        public final String matchedAppLabel;
        public final String matchedPkg;
        public final boolean isFavorite;
        public final String oddsDetails;
        public final String awayMoneyline;
        public final String homeMoneyline;
        public final boolean isAwayMlFav;
        public final boolean isHomeMlFav;
        public final boolean isAwayWinner;
        public final boolean isHomeWinner;
        public final String awayLogoUrl;
        public final String homeLogoUrl;
        public final String awayRecord;
        public final String homeRecord;
        public final String awayRank;
        public final String homeRank;

        public SportGame(String id, String league, String awayAbbr, String awayName, String awayScore, int awayColor,
                         String homeAbbr, String homeName, String homeScore, int homeColor,
                         String state, String statusDetail, String network,
                         String matchedAppLabel, String matchedPkg, boolean isFavorite,
                         String oddsDetails, String awayMoneyline, String homeMoneyline,
                         boolean isAwayMlFav, boolean isHomeMlFav,
                         boolean isAwayWinner, boolean isHomeWinner,
                         String awayLogoUrl, String homeLogoUrl,
                         String awayRecord, String homeRecord,
                         String awayRank, String homeRank) {
            this.id = id;
            this.league = league;
            this.awayAbbr = awayAbbr;
            this.awayName = awayName;
            this.awayScore = awayScore;
            this.awayColor = awayColor;
            this.homeAbbr = homeAbbr;
            this.homeName = homeName;
            this.homeScore = homeScore;
            this.homeColor = homeColor;
            this.state = state;
            this.statusDetail = statusDetail;
            this.network = network;
            this.matchedAppLabel = matchedAppLabel;
            this.matchedPkg = matchedPkg;
            this.isFavorite = isFavorite;
            this.oddsDetails = oddsDetails != null ? oddsDetails : "";
            this.awayMoneyline = awayMoneyline != null ? awayMoneyline : "";
            this.homeMoneyline = homeMoneyline != null ? homeMoneyline : "";
            this.isAwayMlFav = isAwayMlFav;
            this.isHomeMlFav = isHomeMlFav;
            this.isAwayWinner = isAwayWinner;
            this.isHomeWinner = isHomeWinner;
            this.awayLogoUrl = awayLogoUrl != null ? awayLogoUrl : "";
            this.homeLogoUrl = homeLogoUrl != null ? homeLogoUrl : "";
            this.awayRecord = awayRecord != null ? awayRecord : "";
            this.homeRecord = homeRecord != null ? homeRecord : "";
            this.awayRank = awayRank != null ? awayRank : "";
            this.homeRank = homeRank != null ? homeRank : "";
        }

        public boolean isLive() {
            return "in".equalsIgnoreCase(state);
        }

        public boolean isUpcoming() {
            return "pre".equalsIgnoreCase(state);
        }
    }

    public static final class MediaItem {
        public final String title;
        public final int year;
        public final String imdbId;
        public final String type;          // "movie" or "series"
        public final String subtitle;      // e.g. "Up Next: S8E1 • The Divorce" or "2024 • Movie"
        public final String overview;      // Full synopsis / plot summary
        public final double rating;        // Trakt rating (0.0 to 10.0)
        public final String genres;        // e.g. "Action, Sci-Fi"
        public final String backdropUrl;
        public final int nextSeason;
        public final int nextEpisode;

        public MediaItem(String title, int year, String imdbId, String type, String extraLabel) {
            this(title, year, imdbId, type, extraLabel, 0, 0, false, "", 0.0, "");
        }

        public MediaItem(String title, int year, String imdbId, String type, String extraLabel,
                         int nextSeason, int nextEpisode, boolean directSubtitle,
                         String overview, double rating, String genres) {
            this.title = title;
            this.year = year;
            this.imdbId = imdbId;
            this.type = type;
            this.nextSeason = nextSeason;
            this.nextEpisode = nextEpisode;
            this.rating = rating;
            this.genres = (genres != null) ? genres : "";
            this.overview = (overview != null && !overview.isEmpty()) ? overview :
                    ("Stream on Stremio" + (year > 0 ? (" • " + year) : "") + (!this.genres.isEmpty() ? (" • " + this.genres) : ""));
            if (directSubtitle && extraLabel != null && !extraLabel.isEmpty()) {
                this.subtitle = extraLabel;
            } else {
                String kind = "series".equalsIgnoreCase(type) ? "Series" : "Movie";
                String base = year > 0 ? (year + " • " + kind) : kind;
                this.subtitle = (extraLabel != null && !extraLabel.isEmpty()) ? (base + " • " + extraLabel) : base;
            }
            this.backdropUrl = (imdbId != null && !imdbId.isEmpty())
                    ? "https://images.metahub.space/background/medium/" + imdbId + "/img"
                    : null;
        }
    }

    public static final class TraktShelf {
        public final String title;
        public final List<MediaItem> items;

        public TraktShelf(String title, List<MediaItem> items) {
            this.title = title;
            this.items = items;
        }
    }

    public static final class SportShelf {
        public final String title;
        public final List<SportGame> games;

        public SportShelf(String title, List<SportGame> games) {
            this.title = title;
            this.games = games;
        }
    }

    public static final class TraktDeviceCode {
        public final String deviceCode;
        public final String userCode;
        public final String verificationUrl;
        public final int intervalSeconds;

        public TraktDeviceCode(String deviceCode, String userCode, String verificationUrl, int intervalSeconds) {
            this.deviceCode = deviceCode;
            this.userCode = userCode;
            this.verificationUrl = verificationUrl;
            this.intervalSeconds = intervalSeconds;
        }
    }

    public interface Callback<T> {
        void onResult(T result);
    }

    public static SharedPreferences prefs(Context ctx) {
        return ctx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
    }

    public static String getTraktUsername(Context ctx) {
        if (ctx == null) return DEFAULT_TRAKT_USERNAME;
        String u = prefs(ctx).getString("trakt_username", "").trim();
        return u.isEmpty() ? DEFAULT_TRAKT_USERNAME : u;
    }

    public static String getTraktToken(Context ctx) {
        if (ctx == null) return DEFAULT_TRAKT_TOKEN;
        String t = prefs(ctx).getString("trakt_token", "").trim();
        return t.isEmpty() ? DEFAULT_TRAKT_TOKEN : t;
    }

    public static void saveTraktAuth(Context ctx, String token, String username) {
        SharedPreferences.Editor ed = prefs(ctx).edit();
        if (token != null) ed.putString("trakt_token", token.trim());
        if (username != null) ed.putString("trakt_username", username.trim());
        ed.apply();
    }

    public static void clearTraktAuth(Context ctx) {
        prefs(ctx).edit().remove("trakt_token").remove("trakt_username").apply();
    }

    // ---- ESPN Live Sports Scoreboard (with Dynamic Favorite Teams & ML Winners) ------------------

    public static Set<String> getFavoriteTeams(Context ctx) {
        if (ctx == null) return defaultFavTeams();
        Set<String> set = prefs(ctx).getStringSet("fav_teams_set", null);
        if (set == null || set.isEmpty()) {
            return defaultFavTeams();
        }
        return new HashSet<>(set);
    }

    public static void setFavoriteTeams(Context ctx, Set<String> favs) {
        if (ctx == null) return;
        prefs(ctx).edit().putStringSet("fav_teams_set", new HashSet<>(favs)).apply();
    }

    public static Set<String> defaultFavTeams() {
        return new HashSet<>(Arrays.asList("ARI", "NYG", "BKN", "CARDINALS", "GIANTS", "NETS"));
    }

    public static boolean isFavTeam(Set<String> favs, String abbr, String name) {
        if (favs == null || favs.isEmpty()) favs = defaultFavTeams();
        String a = abbr != null ? abbr.toUpperCase() : "";
        String n = name != null ? name.toUpperCase() : "";
        if (favs.contains(a)) return true;
        for (String f : favs) {
            String fu = f.toUpperCase();
            if (fu.length() > 2 && (n.contains(fu) || fu.contains(n))) return true;
            if (fu.equals(a)) return true;
        }
        return false;
    }

    public static final class LeagueDef {
        public final String code;
        public final String name;
        public final String emoji;
        public final String scoreboardUrl;

        public LeagueDef(String code, String name, String emoji, String scoreboardUrl) {
            this.code = code;
            this.name = name;
            this.emoji = emoji;
            this.scoreboardUrl = scoreboardUrl;
        }
    }

    public static final List<LeagueDef> ALL_LEAGUES = Collections.unmodifiableList(Arrays.asList(
            new LeagueDef("NFL", "NFL Football", "🏈", "https://site.api.espn.com/apis/site/v2/sports/football/nfl/scoreboard"),
            new LeagueDef("NBA", "NBA Basketball", "🏀", "https://site.api.espn.com/apis/site/v2/sports/basketball/nba/scoreboard"),
            new LeagueDef("MLB", "MLB Baseball", "⚾", "https://site.api.espn.com/apis/site/v2/sports/baseball/mlb/scoreboard"),
            new LeagueDef("NHL", "NHL Hockey", "🏒", "https://site.api.espn.com/apis/site/v2/sports/hockey/nhl/scoreboard"),
            new LeagueDef("CFB", "College Football (CFB)", "🏈", "https://site.api.espn.com/apis/site/v2/sports/football/college-football/scoreboard?limit=14"),
            new LeagueDef("CBB", "College Basketball (CBB)", "🏀", "https://site.api.espn.com/apis/site/v2/sports/basketball/mens-college-basketball/scoreboard?limit=14"),
            new LeagueDef("EPL", "Premier League (EPL)", "⚽", "https://site.api.espn.com/apis/site/v2/sports/soccer/eng.1/scoreboard"),
            new LeagueDef("MLS", "Major League Soccer (MLS)", "⚽", "https://site.api.espn.com/apis/site/v2/sports/soccer/usa.1/scoreboard"),
            new LeagueDef("UCL", "Champions League (UCL)", "⚽", "https://site.api.espn.com/apis/site/v2/sports/soccer/uefa.champions/scoreboard"),
            new LeagueDef("WNBA", "WNBA Basketball", "🏀", "https://site.api.espn.com/apis/site/v2/sports/basketball/wnba/scoreboard")
    ));

    public static Set<String> getEnabledLeagues(Context ctx) {
        if (ctx == null) return defaultEnabledLeagues();
        Set<String> set = prefs(ctx).getStringSet("enabled_sports_set", null);
        if (set == null || set.isEmpty()) {
            return defaultEnabledLeagues();
        }
        return new HashSet<>(set);
    }

    public static Set<String> defaultEnabledLeagues() {
        Set<String> set = new HashSet<>();
        for (LeagueDef def : ALL_LEAGUES) {
            set.add(def.code);
        }
        return set;
    }

    public static void setEnabledLeagues(Context ctx, Set<String> enabled) {
        if (ctx == null) return;
        prefs(ctx).edit().putStringSet("enabled_sports_set", new HashSet<>(enabled)).apply();
    }

    public static void toggleLeague(Context ctx, String code) {
        if (ctx == null || code == null) return;
        Set<String> current = getEnabledLeagues(ctx);
        if (current.contains(code)) {
            if (current.size() > 1) {
                current.remove(code);
            }
        } else {
            current.add(code);
        }
        setEnabledLeagues(ctx, current);
    }

    private static boolean hasTeamInList(List<SportGame> list, String teamAbbr) {
        for (SportGame g : list) {
            if (teamAbbr.equalsIgnoreCase(g.awayAbbr) || teamAbbr.equalsIgnoreCase(g.homeAbbr)) {
                return true;
            }
        }
        return false;
    }

    public static void fetchLiveSports(Callback<List<SportGame>> cb) {
        fetchLiveSports(null, cb);
    }

    private static void writeCacheFile(Context ctx, String fileName, String content) {
        if (ctx == null || content == null) return;
        try {
            File f = new File(ctx.getCacheDir(), fileName);
            try (FileOutputStream fos = new FileOutputStream(f)) {
                fos.write(content.getBytes(StandardCharsets.UTF_8));
            }
        } catch (Exception ignored) {}
    }

    private static String readCacheFile(Context ctx, String fileName) {
        if (ctx == null) return null;
        try {
            File f = new File(ctx.getCacheDir(), fileName);
            if (!f.exists()) return null;
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(new java.io.FileInputStream(f), StandardCharsets.UTF_8))) {
                StringBuilder sb = new StringBuilder();
                String line;
                while ((line = reader.readLine()) != null) {
                    sb.append(line).append('\n');
                }
                return sb.toString();
            }
        } catch (Exception ignored) {
            return null;
        }
    }

    private static void loadCachedEspnGames(Context ctx, Set<String> enabled, Set<String> favs) {
        if (ctx == null) return;
        for (LeagueDef def : ALL_LEAGUES) {
            if (enabled.contains(def.code) && !CACHED_LEAGUE_GAMES.containsKey(def.code)) {
                String raw = readCacheFile(ctx, "espn_" + def.code + ".json");
                if (raw != null && !raw.isEmpty()) {
                    try {
                        JSONObject root = new JSONObject(raw);
                        JSONArray events = root.optJSONArray("events");
                        if (events != null) {
                            List<SportGame> games = new ArrayList<>();
                            for (int i = 0; i < events.length(); i++) {
                                SportGame g = parseEspnEvent(def.code, events.optJSONObject(i), favs);
                                if (g != null) games.add(g);
                            }
                            if (!games.isEmpty()) {
                                CACHED_LEAGUE_GAMES.put(def.code, games);
                            }
                        }
                    } catch (Exception ignored) {}
                }
            }
        }
    }

    private static List<SportGame> buildActiveGamesFromCache(Set<String> enabled) {
        List<SportGame> raw = new ArrayList<>();
        for (LeagueDef def : ALL_LEAGUES) {
            if (enabled.contains(def.code)) {
                List<SportGame> g = CACHED_LEAGUE_GAMES.get(def.code);
                if (g != null) raw.addAll(g);
            }
        }
        List<SportGame> active = new ArrayList<>();
        for (SportGame g : raw) {
            if (g.isLive() || g.isUpcoming()) active.add(g);
        }
        if (active.isEmpty()) active.addAll(raw);
        Collections.sort(active, (a, b) -> {
            int ra = stateRank(a.state), rb = stateRank(b.state);
            if (ra != rb) return Integer.compare(ra, rb);
            if (a.isFavorite != b.isFavorite) return a.isFavorite ? -1 : 1;
            return 0;
        });
        return active;
    }

    private static List<SportShelf> buildShelvesFromCache(Set<String> enabled) {
        List<SportShelf> shelves = new ArrayList<>();
        for (LeagueDef def : ALL_LEAGUES) {
            if (enabled.contains(def.code)) {
                List<SportGame> games = CACHED_LEAGUE_GAMES.get(def.code);
                if (games != null && !games.isEmpty()) {
                    List<SportGame> copy = new ArrayList<>(games);
                    sortGamesByFav(copy);
                    shelves.add(new SportShelf(def.emoji + " " + def.name.toUpperCase(), copy));
                }
            }
        }
        return shelves;
    }

    public static void fetchLiveSports(Context ctx, Callback<List<SportGame>> cb) {
        Set<String> favs = getFavoriteTeams(ctx);
        Set<String> enabled = getEnabledLeagues(ctx);

        // Instant return from in-memory or disk cache if available
        if (CACHED_LEAGUE_GAMES.isEmpty() && ctx != null) {
            loadCachedEspnGames(ctx, enabled, favs);
        }
        if (!CACHED_LEAGUE_GAMES.isEmpty()) {
            List<SportGame> cachedActive = buildActiveGamesFromCache(enabled);
            if (!cachedActive.isEmpty()) {
                cb.onResult(cachedActive);
            }
        }

        // Parallel background fetch for live real-time scores
        IO_POOL.execute(() -> {
            List<LeagueDef> targets = new ArrayList<>();
            for (LeagueDef def : ALL_LEAGUES) {
                if (enabled.contains(def.code)) targets.add(def);
            }
            if (targets.isEmpty()) {
                MAIN.post(() -> cb.onResult(Collections.emptyList()));
                return;
            }

            CountDownLatch latch = new CountDownLatch(targets.size());
            for (LeagueDef def : targets) {
                IO_POOL.execute(() -> {
                    try {
                        List<SportGame> games = new ArrayList<>();
                        fetchEspnLeague(ctx, def.code, def.scoreboardUrl, games, favs);
                        CACHED_LEAGUE_GAMES.put(def.code, games);
                    } finally {
                        latch.countDown();
                    }
                });
            }
            try {
                latch.await(4, TimeUnit.SECONDS);
            } catch (InterruptedException ignored) {}

            lastSportsFetchTime = System.currentTimeMillis();
            List<SportGame> active = buildActiveGamesFromCache(enabled);
            MAIN.post(() -> cb.onResult(active));
        });
    }

    public static void fetchAllSportsShelves(Context ctx, Callback<List<SportShelf>> cb) {
        Set<String> enabled = getEnabledLeagues(ctx);

        // If cache already has data, construct and deliver shelves immediately
        if (!CACHED_LEAGUE_GAMES.isEmpty()) {
            List<SportShelf> cachedShelves = buildShelvesFromCache(enabled);
            if (!cachedShelves.isEmpty()) {
                cb.onResult(cachedShelves);
            }
        }

        IO_POOL.execute(() -> {
            Set<String> favs = getFavoriteTeams(ctx);
            List<LeagueDef> targets = new ArrayList<>();
            for (LeagueDef def : ALL_LEAGUES) {
                if (enabled.contains(def.code)) targets.add(def);
            }
            if (targets.isEmpty()) {
                MAIN.post(() -> cb.onResult(Collections.emptyList()));
                return;
            }

            boolean needsFetch = CACHED_LEAGUE_GAMES.isEmpty() || (System.currentTimeMillis() - lastSportsFetchTime > 30_000);
            if (needsFetch) {
                CountDownLatch latch = new CountDownLatch(targets.size());
                for (LeagueDef def : targets) {
                    IO_POOL.execute(() -> {
                        try {
                            List<SportGame> games = new ArrayList<>();
                            fetchEspnLeague(ctx, def.code, def.scoreboardUrl, games, favs);
                            CACHED_LEAGUE_GAMES.put(def.code, games);
                        } finally {
                            latch.countDown();
                        }
                    });
                }
                try {
                    latch.await(4, TimeUnit.SECONDS);
                } catch (InterruptedException ignored) {}
                lastSportsFetchTime = System.currentTimeMillis();
            }

            List<SportShelf> shelves = buildShelvesFromCache(enabled);
            MAIN.post(() -> cb.onResult(shelves));
        });
    }

    private static void sortGamesByFav(List<SportGame> list) {
        Collections.sort(list, (a, b) -> {
            int ra = stateRank(a.state), rb = stateRank(b.state);
            if (ra != rb) return Integer.compare(ra, rb);
            if (a.isFavorite != b.isFavorite) return a.isFavorite ? -1 : 1;
            return 0;
        });
    }

    private static int stateRank(String st) {
        if ("in".equalsIgnoreCase(st)) return 0;
        if ("pre".equalsIgnoreCase(st)) return 1;
        return 2;
    }

    private static void fetchEspnLeague(Context ctx, String league, String urlStr, List<SportGame> out, Set<String> favs) {
        try {
            String raw = httpGet(urlStr, null);
            if (raw != null && !raw.isEmpty()) {
                writeCacheFile(ctx, "espn_" + league + ".json", raw);
            } else if (ctx != null) {
                raw = readCacheFile(ctx, "espn_" + league + ".json");
            }
            if (raw == null) return;
            JSONObject root = new JSONObject(raw);
            JSONArray events = root.optJSONArray("events");
            if (events == null) return;
            for (int i = 0; i < events.length(); i++) {
                SportGame g = parseEspnEvent(league, events.optJSONObject(i), favs);
                if (g != null) out.add(g);
            }
        } catch (Exception ignored) {
        }
    }

    private static void fetchFavoriteTeamNextEvent(String league, String urlStr, List<SportGame> out, Set<String> favs) {
        try {
            String raw = httpGet(urlStr, null);
            if (raw == null) return;
            JSONObject root = new JSONObject(raw);
            JSONObject team = root.optJSONObject("team");
            if (team == null) return;
            JSONArray nextEvents = team.optJSONArray("nextEvent");
            if (nextEvents == null || nextEvents.length() == 0) return;
            SportGame g = parseEspnEvent(league, nextEvents.optJSONObject(0), favs);
            if (g != null) out.add(g);
        } catch (Exception ignored) {
        }
    }

    private static SportGame parseEspnEvent(String league, JSONObject ev, Set<String> favs) {
        if (ev == null) return null;
        JSONArray comps = ev.optJSONArray("competitions");
        if (comps == null || comps.length() == 0) return null;
        JSONObject comp = comps.optJSONObject(0);
        if (comp == null) return null;
        JSONArray competitors = comp.optJSONArray("competitors");
        if (competitors == null || competitors.length() < 2) return null;

        JSONObject homeObj = competitors.optJSONObject(0);
        JSONObject awayObj = competitors.optJSONObject(1);
        for (int c = 0; c < competitors.length(); c++) {
            JSONObject co = competitors.optJSONObject(c);
            if (co == null) continue;
            if ("away".equalsIgnoreCase(co.optString("homeAway"))) awayObj = co;
            else if ("home".equalsIgnoreCase(co.optString("homeAway"))) homeObj = co;
        }
        if (awayObj == null || homeObj == null) return null;

        JSONObject awayTeam = awayObj.optJSONObject("team");
        JSONObject homeTeam = homeObj.optJSONObject("team");
        if (awayTeam == null || homeTeam == null) return null;

        String awayAbbr = awayTeam.optString("abbreviation", "AWAY");
        String awayName = awayTeam.optString("shortDisplayName", awayAbbr);
        String awayScore = extractScoreString(awayObj);
        int awayColor = parseTeamColor(awayAbbr, awayTeam.optString("color", ""));

        String homeAbbr = homeTeam.optString("abbreviation", "HOME");
        String homeName = homeTeam.optString("shortDisplayName", homeAbbr);
        String homeScore = extractScoreString(homeObj);
        int homeColor = parseTeamColor(homeAbbr, homeTeam.optString("color", ""));

        JSONObject status = ev.optJSONObject("status");
        if (status == null) status = comp.optJSONObject("status");
        JSONObject statusType = status != null ? status.optJSONObject("type") : null;
        String state = statusType != null ? statusType.optString("state", "pre") : "pre";
        String detail = statusType != null ? statusType.optString("shortDetail", "") : "";

        String network = resolveBroadcastNetwork(league, comp, awayAbbr, homeAbbr);

        String oddsDetails = "";
        String awayMoneyline = "";
        String homeMoneyline = "";
        boolean isAwayMlFav = false;
        boolean isHomeMlFav = false;
        JSONArray oddsArr = comp.optJSONArray("odds");
        if (oddsArr != null && oddsArr.length() > 0) {
            JSONObject o0 = oddsArr.optJSONObject(0);
            if (o0 != null) {
                oddsDetails = o0.optString("details", "");
                oddsDetails = oddsDetails.replaceAll("(?i)\\bO/?U\\b", "").trim();
                oddsDetails = oddsDetails.replaceFirst("^[\\s:\\-]+", "").trim();
                if (oddsDetails.isEmpty()) {
                    double ou = o0.optDouble("overUnder", 0);
                    if (ou > 0) {
                        oddsDetails = (ou == (long) ou)
                                ? String.format(Locale.US, "%d", (long) ou)
                                : String.format(Locale.US, "%.1f", ou);
                    }
                }
                JSONObject ml = o0.optJSONObject("moneyline");
                if (ml != null) {
                    JSONObject awayMl = ml.optJSONObject("away");
                    if (awayMl != null) {
                        JSONObject closeObj = awayMl.optJSONObject("close");
                        if (closeObj != null) awayMoneyline = closeObj.optString("odds", "");
                        if (awayMoneyline.isEmpty()) {
                            JSONObject openObj = awayMl.optJSONObject("open");
                            if (openObj != null) awayMoneyline = openObj.optString("odds", "");
                        }
                    }
                    JSONObject homeMl = ml.optJSONObject("home");
                    if (homeMl != null) {
                        JSONObject closeObj = homeMl.optJSONObject("close");
                        if (closeObj != null) homeMoneyline = closeObj.optString("odds", "");
                        if (homeMoneyline.isEmpty()) {
                            JSONObject openObj = homeMl.optJSONObject("open");
                            if (openObj != null) homeMoneyline = openObj.optString("odds", "");
                        }
                    }
                }
                if (awayMoneyline.isEmpty()) {
                    JSONObject ato = o0.optJSONObject("awayTeamOdds");
                    if (ato != null) awayMoneyline = ato.optString("moneyLine", "");
                }
                if (homeMoneyline.isEmpty()) {
                    JSONObject hto = o0.optJSONObject("homeTeamOdds");
                    if (hto != null) homeMoneyline = hto.optString("moneyLine", "");
                }
                if (!awayMoneyline.isEmpty() && !awayMoneyline.startsWith("+") && !awayMoneyline.startsWith("-")) {
                    awayMoneyline = "+" + awayMoneyline;
                }
                if (!homeMoneyline.isEmpty() && !homeMoneyline.startsWith("+") && !homeMoneyline.startsWith("-")) {
                    homeMoneyline = "+" + homeMoneyline;
                }

                JSONObject ato = o0.optJSONObject("awayTeamOdds");
                JSONObject hto = o0.optJSONObject("homeTeamOdds");
                if (ato != null && ato.optBoolean("favorite", false)) {
                    isAwayMlFav = true;
                } else if (hto != null && hto.optBoolean("favorite", false)) {
                    isHomeMlFav = true;
                } else if (!awayMoneyline.isEmpty() && !homeMoneyline.isEmpty()) {
                    try {
                        int aVal = Integer.parseInt(awayMoneyline.replace("+", ""));
                        int hVal = Integer.parseInt(homeMoneyline.replace("+", ""));
                        if (aVal < hVal) isAwayMlFav = true;
                        else if (hVal < aVal) isHomeMlFav = true;
                    } catch (Exception ignored) {}
                }
            }
        }

        int aSc = -1, hSc = -1;
        try { aSc = Integer.parseInt(awayScore); } catch (Exception ignored) {}
        try { hSc = Integer.parseInt(homeScore); } catch (Exception ignored) {}
        boolean isAwayWinner = "post".equalsIgnoreCase(state) && aSc > hSc;
        boolean isHomeWinner = "post".equalsIgnoreCase(state) && hSc > aSc;

        boolean isFav = isFavTeam(favs, awayAbbr, awayName) || isFavTeam(favs, homeAbbr, homeName);
        String[] matched = matchBroadcastApp(league, network);

        String awayLogoUrl = awayTeam.optString("logo", "");
        if (awayLogoUrl.isEmpty()) {
            JSONArray arr = awayTeam.optJSONArray("logos");
            if (arr != null && arr.length() > 0) awayLogoUrl = arr.optJSONObject(0).optString("href", "");
        }
        String homeLogoUrl = homeTeam.optString("logo", "");
        if (homeLogoUrl.isEmpty()) {
            JSONArray arr = homeTeam.optJSONArray("logos");
            if (arr != null && arr.length() > 0) homeLogoUrl = arr.optJSONObject(0).optString("href", "");
        }

        String awayRecord = "";
        JSONArray awayRecs = awayObj.optJSONArray("records");
        if (awayRecs != null && awayRecs.length() > 0) {
            JSONObject r0 = awayRecs.optJSONObject(0);
            if (r0 != null) awayRecord = r0.optString("summary", "");
        }
        String homeRecord = "";
        JSONArray homeRecs = homeObj.optJSONArray("records");
        if (homeRecs != null && homeRecs.length() > 0) {
            JSONObject r0 = homeRecs.optJSONObject(0);
            if (r0 != null) homeRecord = r0.optString("summary", "");
        }

        String awayRank = "";
        JSONObject awayCr = awayObj.optJSONObject("curatedRank");
        if (awayCr != null) {
            int r = awayCr.optInt("current", 99);
            if (r > 0 && r <= 25) awayRank = "#" + r + " ";
        }
        String homeRank = "";
        JSONObject homeCr = homeObj.optJSONObject("curatedRank");
        if (homeCr != null) {
            int r = homeCr.optInt("current", 99);
            if (r > 0 && r <= 25) homeRank = "#" + r + " ";
        }

        return new SportGame(
                ev.optString("id", awayAbbr + "@" + homeAbbr),
                league,
                awayAbbr, awayName, awayScore, awayColor,
                homeAbbr, homeName, homeScore, homeColor,
                state, detail, network,
                matched[0], matched[1],
                isFav,
                oddsDetails,
                awayMoneyline,
                homeMoneyline,
                isAwayMlFav,
                isHomeMlFav,
                isAwayWinner,
                isHomeWinner,
                awayLogoUrl,
                homeLogoUrl,
                awayRecord,
                homeRecord,
                awayRank,
                homeRank
        );
    }

    private static String extractScoreString(JSONObject compObj) {
        Object sc = compObj.opt("score");
        if (sc instanceof JSONObject) {
            return ((JSONObject) sc).optString("displayValue", "0");
        }
        if (sc instanceof String) {
            return (String) sc;
        }
        return "0";
    }

    private static int parseTeamColor(String abbr, String hex) {
        try {
            if (hex == null || hex.isEmpty() || "null".equalsIgnoreCase(hex)) return 0xFF3B82F6;
            if (!hex.startsWith("#")) hex = "#" + hex;
            int c = Color.parseColor(hex);
            float[] hsv = new float[3];
            Color.colorToHSV(c, hsv);
            hsv[2] = Math.max(0.55f, hsv[2]);
            return Color.HSVToColor(hsv);
        } catch (Exception e) {
            return 0xFF3B82F6;
        }
    }

    private static final Map<String, String> NBA_TEAM_RSN = new ConcurrentHashMap<>();
    static {
        // Atlantic
        NBA_TEAM_RSN.put("BOS", "NBCS Boston");
        NBA_TEAM_RSN.put("BKN", "YES");
        NBA_TEAM_RSN.put("NYK", "MSG");
        NBA_TEAM_RSN.put("NY", "MSG");
        NBA_TEAM_RSN.put("PHI", "NBCS Philadelphia");
        NBA_TEAM_RSN.put("TOR", "TSN / Sportsnet");
        // Central
        NBA_TEAM_RSN.put("CHI", "CHSN");
        NBA_TEAM_RSN.put("CLE", "FanDuel SN Ohio");
        NBA_TEAM_RSN.put("DET", "FanDuel SN Detroit");
        NBA_TEAM_RSN.put("IND", "FanDuel SN Indiana");
        NBA_TEAM_RSN.put("MIL", "FanDuel SN WI");
        // Southeast
        NBA_TEAM_RSN.put("ATL", "FanDuel SN SE");
        NBA_TEAM_RSN.put("CHA", "FanDuel SN SE");
        NBA_TEAM_RSN.put("MIA", "FanDuel SN Sun");
        NBA_TEAM_RSN.put("ORL", "FanDuel SN Florida");
        NBA_TEAM_RSN.put("WAS", "MNMT");
        NBA_TEAM_RSN.put("WSH", "MNMT");
        // Northwest
        NBA_TEAM_RSN.put("DEN", "Altitude");
        NBA_TEAM_RSN.put("MIN", "FanDuel SN North");
        NBA_TEAM_RSN.put("OKC", "FanDuel SN OK");
        NBA_TEAM_RSN.put("POR", "BlazerVision");
        NBA_TEAM_RSN.put("UTA", "KJZZ");
        // Pacific
        NBA_TEAM_RSN.put("GSW", "NBCS Bay Area");
        NBA_TEAM_RSN.put("GS", "NBCS Bay Area");
        NBA_TEAM_RSN.put("LAC", "KTLA");
        NBA_TEAM_RSN.put("LAL", "Spectrum SN");
        NBA_TEAM_RSN.put("PHX", "3TV");
        NBA_TEAM_RSN.put("SAC", "NBCS California");
        // Southwest
        NBA_TEAM_RSN.put("DAL", "KFAA");
        NBA_TEAM_RSN.put("HOU", "Space City");
        NBA_TEAM_RSN.put("MEM", "FanDuel SN SE");
        NBA_TEAM_RSN.put("NOP", "GCSEN");
        NBA_TEAM_RSN.put("NO", "GCSEN");
        NBA_TEAM_RSN.put("SAS", "FanDuel SN SW");
        NBA_TEAM_RSN.put("SA", "FanDuel SN SW");
    }

    public static String getNbaTeamRsn(String teamAbbr) {
        if (teamAbbr == null) return null;
        return NBA_TEAM_RSN.get(teamAbbr.toUpperCase());
    }

    private static String resolveBroadcastNetwork(String league, JSONObject comp, String awayAbbr, String homeAbbr) {
        if (comp == null) return league != null ? league : "";

        List<String> rawList = new ArrayList<>();
        JSONArray geo = comp.optJSONArray("geoBroadcasts");
        if (geo != null) {
            for (int i = 0; i < geo.length(); i++) {
                JSONObject b = geo.optJSONObject(i);
                if (b == null) continue;
                JSONObject media = b.optJSONObject("media");
                if (media != null) {
                    String sn = media.optString("shortName", "");
                    if (!sn.isEmpty()) rawList.add(sn);
                }
            }
        }

        JSONArray broadcasts = comp.optJSONArray("broadcasts");
        if (broadcasts != null) {
            for (int i = 0; i < broadcasts.length(); i++) {
                JSONObject b = broadcasts.optJSONObject(i);
                if (b == null) continue;
                JSONArray names = b.optJSONArray("names");
                if (names != null) {
                    for (int j = 0; j < names.length(); j++) {
                        String n = names.optString(j, "");
                        if (!n.isEmpty()) rawList.add(n);
                    }
                }
                JSONObject media = b.optJSONObject("media");
                if (media != null) {
                    String sn = media.optString("shortName", "");
                    if (!sn.isEmpty()) rawList.add(sn);
                }
            }
        }

        List<String> cleaned = new ArrayList<>();
        for (String raw : rawList) {
            String c = cleanNetworkName(raw);
            if (!c.isEmpty() && !cleaned.contains(c)) {
                cleaned.add(c);
            }
        }

        // 1. National TV priority (ESPN, ABC, TNT, NBA TV, CBS, NBC, FOX)
        for (String c : cleaned) {
            String u = c.toUpperCase();
            if (u.equals("ESPN") || u.equals("ESPN2") || u.equals("ABC") || u.equals("TNT") ||
                u.equals("TBS") || u.equals("NBA TV") || u.equals("NBC") || u.equals("FOX") || u.equals("CBS")) {
                return c;
            }
        }

        // 2. Local Regional Sports Network priority (e.g. YES, NBCS Boston, MSG, MNMT, KTLA, KJZZ, GCSEN, FanDuel SN)
        for (String c : cleaned) {
            if (!isGenericStreamOrPass(c)) {
                return c;
            }
        }

        // 3. Fallback to NBA Team RSN lookup for Home or Away team (matches IPTV category name!)
        if ("NBA".equalsIgnoreCase(league)) {
            String homeRsn = getNbaTeamRsn(homeAbbr);
            if (homeRsn != null && !homeRsn.isEmpty()) return homeRsn;
            String awayRsn = getNbaTeamRsn(awayAbbr);
            if (awayRsn != null && !awayRsn.isEmpty()) return awayRsn;
        }

        if (!cleaned.isEmpty()) {
            return cleaned.get(0);
        }

        return league != null ? league : "";
    }

    private static String cleanNetworkName(String raw) {
        if (raw == null) return "";
        String s = raw.trim();
        // Extract parenthetical like "DAZN (YES)" -> "YES"
        int op = s.indexOf('(');
        int cp = s.indexOf(')', op);
        if (op >= 0 && cp > op) {
            String inside = s.substring(op + 1, cp).trim();
            if (!inside.isEmpty()) return cleanNetworkName(inside);
        }
        if (s.equalsIgnoreCase("KJZZ-TV")) return "KJZZ";
        if (s.equalsIgnoreCase("KFAA-TV")) return "KFAA";
        if (s.equalsIgnoreCase("KUNP 16") || s.toLowerCase().contains("blazervision")) return "BlazerVision";
        if (s.toLowerCase().contains("arizona's family") || s.toLowerCase().contains("3tv")) return "3TV";
        if (s.toLowerCase().contains("bally")) return "FanDuel SN";
        if (s.startsWith("DAZN ")) {
            String sub = s.substring(5).trim();
            if (sub.equalsIgnoreCase("IND")) return "FanDuel SN Indiana";
            if (sub.equalsIgnoreCase("MEM")) return "FanDuel SN SE";
            if (sub.equalsIgnoreCase("DET")) return "FanDuel SN Detroit";
            return "FanDuel SN";
        }
        return s;
    }

    private static boolean isGenericStreamOrPass(String c) {
        if (c == null) return true;
        String u = c.toUpperCase();
        return u.contains("LEAGUE PASS") || u.contains("APP") || u.contains(".COM") || u.contains("STREAM") || u.contains("DAZN");
    }

    private static String[] matchBroadcastApp(String league, String network) {
        String n = network == null ? "" : network.toUpperCase();
        if (n.contains("NBC") || n.contains("PEACOCK")) {
            return new String[]{"Peacock", "com.peacocktv.peacockandroid"};
        }
        if (n.contains("PRIME") || n.contains("AMAZON")) {
            return new String[]{"Prime Video", "com.amazon.amazonvideo.livingroom"};
        }
        if (n.contains("CBS") || n.contains("PARAMOUNT")) {
            return new String[]{"Paramount+", "com.cbs.ott"};
        }
        if (n.contains("APPLE")) {
            return new String[]{"Apple TV+", "com.apple.atve.androidtv.appletv"};
        }
        if (n.contains("MAX") || n.contains("TNT") || n.contains("TBS") || n.contains("HBO")) {
            return new String[]{"Max", "com.wbd.stream"};
        }
        if (n.contains("ESPN") || n.contains("ABC") || n.contains("HULU")) {
            return new String[]{"Hulu", "com.hulu.livingroomplus"};
        }
        if ("NBA".equalsIgnoreCase(league)) {
            return new String[]{"NBA App", "com.nbaimd.gametime.nba2011"};
        }
        return new String[]{"NFL App", "com.gotv.nflgamecenter.us.lite"};
    }

    // ---- Multi-Shelf Trakt Fetcher (All Custom Lists, Watchlists, History, Recommendations, Trending)

    public static void fetchAllTraktShelves(Context ctx, Callback<List<TraktShelf>> cb) {
        IO_POOL.execute(() -> {
            String token = getTraktToken(ctx);
            String user = getTraktUsername(ctx);
            List<TraktShelf> shelves = new ArrayList<>();

            // 1. Recently Watched / Continue Watching & Up Next (First Trakt row at the top)
            if (!token.isEmpty()) {
                List<MediaItem> history = new ArrayList<>();
                fetchTraktUserList("https://api.trakt.tv/sync/playback?limit=12", token, "Continue Watching", history);
                fetchTraktUserList("https://api.trakt.tv/sync/history?limit=40", token, "Recently Watched", history);
                if (!history.isEmpty()) {
                    shelves.add(new TraktShelf("TRAKT  •  RECENTLY WATCHED & UP NEXT", history));
                }
            } else if (!user.isEmpty()) {
                List<MediaItem> history = new ArrayList<>();
                fetchTraktUserList("https://api.trakt.tv/users/" + user + "/history?limit=40", null, "Recently Watched", history);
                if (!history.isEmpty()) {
                    shelves.add(new TraktShelf("TRAKT  •  RECENTLY WATCHED", history));
                }
            }

            // 2. User's Custom Trakt Lists (public, link-only, and private lists via /users/me/lists + liked lists)
            if (!user.isEmpty() || !token.isEmpty()) {
                String targetUser = !token.isEmpty() ? "me" : user;
                fetchCustomUserLists(targetUser, token.isEmpty() ? null : token, shelves);
                if (!token.isEmpty()) {
                    fetchLikedLists(token, shelves);
                }
            }

            // 3. Movie Watchlist
            if (!token.isEmpty() || !user.isEmpty()) {
                List<MediaItem> movieWatchlist = new ArrayList<>();
                String url = !token.isEmpty()
                        ? "https://api.trakt.tv/sync/watchlist/movies?limit=10"
                        : "https://api.trakt.tv/users/" + user + "/watchlist/movies?limit=10";
                fetchTraktUserList(url, token.isEmpty() ? null : token, "Watchlist", movieWatchlist);
                if (!movieWatchlist.isEmpty()) {
                    shelves.add(new TraktShelf("TRAKT  •  MY MOVIE WATCHLIST", movieWatchlist));
                }
            }

            // 4. Series Watchlist
            if (!token.isEmpty() || !user.isEmpty()) {
                List<MediaItem> showWatchlist = new ArrayList<>();
                String url = !token.isEmpty()
                        ? "https://api.trakt.tv/sync/watchlist/shows?limit=10"
                        : "https://api.trakt.tv/users/" + user + "/watchlist/shows?limit=10";
                fetchTraktUserList(url, token.isEmpty() ? null : token, "Watchlist", showWatchlist);
                if (!showWatchlist.isEmpty()) {
                    shelves.add(new TraktShelf("TRAKT  •  MY SERIES WATCHLIST", showWatchlist));
                }
            }

            // 5. Personal Trakt Recommendations (when OAuth token is linked)
            if (!token.isEmpty()) {
                List<MediaItem> recs = new ArrayList<>();
                fetchTraktDirectArray("https://api.trakt.tv/recommendations/shows?limit=6", token, "series", "Recommended", recs);
                fetchTraktDirectArray("https://api.trakt.tv/recommendations/movies?limit=6", token, "movie", "Recommended", recs);
                if (!recs.isEmpty()) {
                    shelves.add(new TraktShelf("TRAKT  •  RECOMMENDED FOR YOU", recs));
                }
            }

            // 6. Trakt Trending Movies
            List<MediaItem> trendingMovies = new ArrayList<>();
            fetchTraktTrending("https://api.trakt.tv/movies/trending?limit=10", "movie", trendingMovies);
            if (!trendingMovies.isEmpty()) {
                shelves.add(new TraktShelf("TRAKT  •  TRENDING MOVIES", trendingMovies));
            }

            // 7. Trakt Trending Series
            List<MediaItem> trendingShows = new ArrayList<>();
            fetchTraktTrending("https://api.trakt.tv/shows/trending?limit=10", "series", trendingShows);
            if (!trendingShows.isEmpty()) {
                shelves.add(new TraktShelf("TRAKT  •  TRENDING SERIES", trendingShows));
            }

            MAIN.post(() -> cb.onResult(shelves));
        });
    }

    private static void fetchCustomUserLists(String user, String token, List<TraktShelf> outShelves) {
        try {
            String raw = httpGet("https://api.trakt.tv/users/" + user + "/lists", token);
            if (raw == null) return;
            JSONArray lists = new JSONArray(raw);
            List<TraktShelf> custom = new ArrayList<>();
            for (int i = 0; i < lists.length(); i++) {
                JSONObject l = lists.getJSONObject(i);
                String name = l.optString("name", "Custom List");
                JSONObject ids = l.optJSONObject("ids");
                String slug = ids != null ? ids.optString("slug", "") : "";
                if (slug.isEmpty()) continue;

                String cleanTitle = cleanListName(name);
                List<MediaItem> items = new ArrayList<>();
                fetchTraktUserList("https://api.trakt.tv/users/" + user + "/lists/" + slug + "/items?limit=12", token, cleanTitle, items);
                if (!items.isEmpty()) {
                    TraktShelf shelf = new TraktShelf("TRAKT LIST  •  " + cleanTitle.toUpperCase(), items);
                    // Put "In Progress" first among custom lists!
                    if (name.toLowerCase().contains("progress")) {
                        custom.add(0, shelf);
                    } else {
                        custom.add(shelf);
                    }
                }
            }
            outShelves.addAll(custom);
        } catch (Exception ignored) {
        }
    }

    private static void fetchLikedLists(String token, List<TraktShelf> outShelves) {
        try {
            String raw = httpGet("https://api.trakt.tv/users/likes/lists?limit=15", token);
            if (raw == null) return;
            JSONArray rows = new JSONArray(raw);
            for (int i = 0; i < rows.length(); i++) {
                JSONObject row = rows.getJSONObject(i);
                JSONObject l = row.optJSONObject("list");
                if (l == null) continue;
                String name = l.optString("name", "Liked List");
                JSONObject ids = l.optJSONObject("ids");
                String slug = ids != null ? ids.optString("slug", "") : "";
                JSONObject ownerObj = l.optJSONObject("user");
                String owner = ownerObj != null ? ownerObj.optString("username", "") : "";
                if (slug.isEmpty() || owner.isEmpty()) continue;

                String cleanTitle = cleanListName(name);
                List<MediaItem> items = new ArrayList<>();
                fetchTraktUserList("https://api.trakt.tv/users/" + owner + "/lists/" + slug + "/items?limit=12", token, cleanTitle, items);
                if (!items.isEmpty()) {
                    outShelves.add(new TraktShelf("LIKED LIST  •  " + cleanTitle.toUpperCase(), items));
                }
            }
        } catch (Exception ignored) {
        }
    }

    private static String cleanListName(String rawName) {
        if (rawName == null) return "Trakt List";
        String s = rawName.replace("[couchmoney.tv]", "").replace("\"", "").trim();
        if (s.equalsIgnoreCase("TV Based on List sitcoms watched")) return "Sitcom Recommendations";
        if (s.equalsIgnoreCase("TV Based on My Watchlist")) return "TV Picks From Watchlist";
        if (s.equalsIgnoreCase("Movies Based on My Watchlist")) return "Movie Picks From Watchlist";
        return s;
    }

    private static final java.util.Map<String, UpNextInfo> UP_NEXT_CACHE =
            new java.util.concurrent.ConcurrentHashMap<>();

    private static final class UpNextInfo {
        final int season;
        final int episode;
        final String label;

        UpNextInfo(int season, int episode, String label) {
            this.season = season;
            this.episode = episode;
            this.label = label;
        }
    }

    private static UpNextInfo resolveShowUpNext(String imdb, String token) {
        if (imdb == null || imdb.isEmpty()) return null;
        UpNextInfo cached = UP_NEXT_CACHE.get(imdb);
        if (cached != null) return cached;

        try {
            String raw = httpGet("https://api.trakt.tv/shows/" + imdb
                    + "/progress/watched?hidden=false&specials=false&count_specials=false", token);
            if (raw == null) return null;
            JSONObject prog = new JSONObject(raw);
            JSONObject ne = prog.optJSONObject("next_episode");
            if (ne != null) {
                int s = ne.optInt("season", 0);
                int e = ne.optInt("number", 0);
                String epTitle = ne.optString("title", "").trim();
                if (s > 0 && e > 0) {
                    String lbl = "Up Next: S" + s + "E" + e
                            + (!epTitle.isEmpty() ? (" • " + epTitle) : "");
                    UpNextInfo info = new UpNextInfo(s, e, lbl);
                    UP_NEXT_CACHE.put(imdb, info);
                    return info;
                }
            }
            JSONObject le = prog.optJSONObject("last_episode");
            if (le != null) {
                int s = le.optInt("season", 0);
                int e = le.optInt("number", 0);
                if (s > 0 && e > 0) {
                    UpNextInfo info = new UpNextInfo(s, e, "Caught Up • Last: S" + s + "E" + e);
                    UP_NEXT_CACHE.put(imdb, info);
                    return info;
                }
            }
            UpNextInfo fallback = new UpNextInfo(1, 1, "Up Next: S1E1");
            UP_NEXT_CACHE.put(imdb, fallback);
            return fallback;
        } catch (Exception ignored) {
            return null;
        }
    }

    private static String parseGenres(JSONObject m) {
        JSONArray gArr = m.optJSONArray("genres");
        if (gArr == null || gArr.length() == 0) return "";
        StringBuilder sb = new StringBuilder();
        for (int g = 0; g < gArr.length() && g < 3; g++) {
            String genre = gArr.optString(g, "");
            if (!genre.isEmpty()) {
                if (sb.length() > 0) sb.append(", ");
                sb.append(Character.toUpperCase(genre.charAt(0))).append(genre.substring(1));
            }
        }
        return sb.toString();
    }

    private static void fetchTraktUserList(String urlStr, String token, String badgeLabel, List<MediaItem> out) {
        try {
            String fullUrl = urlStr.contains("?") ? (urlStr + "&extended=full") : (urlStr + "?extended=full");
            String raw = httpGet(fullUrl, token);
            if (raw == null) raw = httpGet(urlStr, token);
            if (raw == null) return;
            JSONArray arr = new JSONArray(raw);
            String bl = badgeLabel != null ? badgeLabel.toLowerCase() : "";
            boolean checkUpNext = bl.contains("progress")
                    || bl.contains("continue")
                    || bl.contains("recently");
            for (int i = 0; i < arr.length(); i++) {
                JSONObject item = arr.getJSONObject(i);
                JSONObject m = item.optJSONObject("movie");
                String type = "movie";
                if (m == null) {
                    m = item.optJSONObject("show");
                    type = "series";
                }
                if (m == null) continue;
                String title = m.optString("title", "");
                int year = m.optInt("year", 0);
                JSONObject ids = m.optJSONObject("ids");
                String imdb = ids != null ? ids.optString("imdb", "") : "";
                if (title.isEmpty() || imdb.isEmpty() || !imdb.startsWith("tt")) continue;
                if (!containsImdb(out, imdb)) {
                    String overview = m.optString("overview", "");
                    double rating = m.optDouble("rating", 0.0);
                    String genres = parseGenres(m);
                    if ("series".equals(type) && checkUpNext) {
                        UpNextInfo up = resolveShowUpNext(imdb, token);
                        if (up != null) {
                            out.add(new MediaItem(title, year, imdb, type, up.label, up.season, up.episode, true, overview, rating, genres));
                            if (out.size() >= 14) break;
                            continue;
                        }
                    }
                    out.add(new MediaItem(title, year, imdb, type, badgeLabel, 0, 0, false, overview, rating, genres));
                    if (out.size() >= 14) break;
                }
            }
        } catch (Exception ignored) {
        }
    }

    private static void fetchTraktDirectArray(String urlStr, String token, String type, String badgeLabel, List<MediaItem> out) {
        try {
            String fullUrl = urlStr.contains("?") ? (urlStr + "&extended=full") : (urlStr + "?extended=full");
            String raw = httpGet(fullUrl, token);
            if (raw == null) raw = httpGet(urlStr, token);
            if (raw == null) return;
            JSONArray arr = new JSONArray(raw);
            for (int i = 0; i < arr.length(); i++) {
                JSONObject m = arr.getJSONObject(i);
                String title = m.optString("title", "");
                int year = m.optInt("year", 0);
                JSONObject ids = m.optJSONObject("ids");
                String imdb = ids != null ? ids.optString("imdb", "") : "";
                if (title.isEmpty() || imdb.isEmpty() || !imdb.startsWith("tt")) continue;
                if (!containsImdb(out, imdb)) {
                    String overview = m.optString("overview", "");
                    double rating = m.optDouble("rating", 0.0);
                    String genres = parseGenres(m);
                    out.add(new MediaItem(title, year, imdb, type, badgeLabel, 0, 0, false, overview, rating, genres));
                }
            }
        } catch (Exception ignored) {
        }
    }

    private static void fetchTraktTrending(String urlStr, String type, List<MediaItem> out) {
        try {
            String fullUrl = urlStr.contains("?") ? (urlStr + "&extended=full") : (urlStr + "?extended=full");
            String raw = httpGet(fullUrl, null);
            if (raw == null) raw = httpGet(urlStr, null);
            if (raw == null) return;
            JSONArray arr = new JSONArray(raw);
            for (int i = 0; i < arr.length(); i++) {
                JSONObject row = arr.getJSONObject(i);
                int watchers = row.optInt("watchers", 0);
                JSONObject m = row.optJSONObject("movie".equals(type) ? "movie" : "show");
                if (m == null) continue;
                String title = m.optString("title", "");
                int year = m.optInt("year", 0);
                JSONObject ids = m.optJSONObject("ids");
                String imdb = ids != null ? ids.optString("imdb", "") : "";
                if (title.isEmpty() || imdb.isEmpty() || !imdb.startsWith("tt")) continue;
                if (!containsImdb(out, imdb)) {
                    String extra = watchers > 0 ? (watchers + " watching") : "Trending";
                    String overview = m.optString("overview", "");
                    double rating = m.optDouble("rating", 0.0);
                    String genres = parseGenres(m);
                    out.add(new MediaItem(title, year, imdb, type, extra, 0, 0, false, overview, rating, genres));
                }
            }
        } catch (Exception ignored) {
        }
    }

    private static boolean containsImdb(List<MediaItem> list, String imdb) {
        for (MediaItem m : list) {
            if (imdb.equals(m.imdbId)) return true;
        }
        return false;
    }

    // ---- Trakt OAuth Device Code Flow ------------------------------------------------------------

    public static void requestTraktDeviceCode(Callback<TraktDeviceCode> cb) {
        IO_POOL.execute(() -> {
            try {
                JSONObject body = new JSONObject();
                body.put("client_id", TRAKT_OAUTH_CLIENT_ID);
                HttpURLConnection conn = (HttpURLConnection) new URL("https://api.trakt.tv/oauth/device/code").openConnection();
                conn.setRequestMethod("POST");
                conn.setConnectTimeout(8000);
                conn.setReadTimeout(8000);
                conn.setRequestProperty("Content-Type", "application/json");
                conn.setRequestProperty("trakt-api-version", "2");
                conn.setRequestProperty("trakt-api-key", TRAKT_OAUTH_CLIENT_ID);
                conn.setRequestProperty("User-Agent", "TvHome/2.0");
                conn.setDoOutput(true);
                byte[] bytes = body.toString().getBytes(StandardCharsets.UTF_8);
                try (OutputStream os = conn.getOutputStream()) {
                    os.write(bytes);
                }
                if (conn.getResponseCode() == 200) {
                    String resp = readStream(conn.getInputStream());
                    JSONObject j = new JSONObject(resp);
                    TraktDeviceCode dc = new TraktDeviceCode(
                            j.optString("device_code", ""),
                            j.optString("user_code", ""),
                            j.optString("verification_url", "https://trakt.tv/activate"),
                            Math.max(5, j.optInt("interval", 6))
                    );
                    MAIN.post(() -> cb.onResult(dc));
                    return;
                }
            } catch (Exception ignored) {
            }
            MAIN.post(() -> cb.onResult(null));
        });
    }

    public static void pollTraktDeviceToken(Context ctx, String deviceCode, Callback<String> onAuthorized) {
        IO_POOL.execute(() -> {
            try {
                JSONObject body = new JSONObject();
                body.put("code", deviceCode);
                body.put("client_id", TRAKT_OAUTH_CLIENT_ID);
                body.put("client_secret", TRAKT_OAUTH_CLIENT_SECRET);
                HttpURLConnection conn = (HttpURLConnection) new URL("https://api.trakt.tv/oauth/device/token").openConnection();
                conn.setRequestMethod("POST");
                conn.setConnectTimeout(8000);
                conn.setReadTimeout(8000);
                conn.setRequestProperty("Content-Type", "application/json");
                conn.setRequestProperty("trakt-api-version", "2");
                conn.setRequestProperty("trakt-api-key", TRAKT_OAUTH_CLIENT_ID);
                conn.setRequestProperty("User-Agent", "TvHome/2.0");
                conn.setDoOutput(true);
                try (OutputStream os = conn.getOutputStream()) {
                    os.write(body.toString().getBytes(StandardCharsets.UTF_8));
                }
                int code = conn.getResponseCode();
                if (code == 200) {
                    String resp = readStream(conn.getInputStream());
                    JSONObject j = new JSONObject(resp);
                    String accessToken = j.optString("access_token", "");
                    if (!accessToken.isEmpty()) {
                        String username = "Trakt User";
                        String settingsRaw = httpGet("https://api.trakt.tv/users/settings", accessToken);
                        if (settingsRaw != null) {
                            JSONObject sObj = new JSONObject(settingsRaw);
                            JSONObject uObj = sObj.optJSONObject("user");
                            if (uObj != null) username = uObj.optString("username", username);
                        }
                        saveTraktAuth(ctx, accessToken, username);
                        final String finalUser = username;
                        MAIN.post(() -> onAuthorized.onResult(finalUser));
                        return;
                    }
                }
            } catch (Exception ignored) {
            }
            MAIN.post(() -> onAuthorized.onResult(null));
        });
    }

    // ---- Image Loader (Memory + Disk Cache) ------------------------------------------------------

    public static void loadBackdrop(Context ctx, String urlStr, ImageView target) {
        if (urlStr == null || urlStr.isEmpty()) {
            target.setImageDrawable(null);
            return;
        }
        target.setTag(urlStr);
        Bitmap cached = MEM_CACHE.get(urlStr);
        if (cached != null) {
            target.setImageBitmap(cached);
            return;
        }
        target.setImageDrawable(null);
        final File cacheDir = new File(ctx.getCacheDir(), "backdrops");
        if (!cacheDir.exists()) cacheDir.mkdirs();

        IO_POOL.execute(() -> {
            try {
                File diskFile = new File(cacheDir, md5(urlStr) + ".jpg");
                Bitmap bmp = null;
                if (diskFile.exists() && diskFile.length() > 512) {
                    bmp = decodeSampled(diskFile.getAbsolutePath(), 392, 220);
                }
                if (bmp == null) {
                    HttpURLConnection conn = (HttpURLConnection) new URL(urlStr).openConnection();
                    conn.setConnectTimeout(6000);
                    conn.setReadTimeout(6000);
                    conn.setInstanceFollowRedirects(true);
                    conn.setRequestProperty("User-Agent", "TvHome/2.0");
                    if (conn.getResponseCode() == 200) {
                        try (InputStream is = conn.getInputStream();
                             FileOutputStream fos = new FileOutputStream(diskFile)) {
                            byte[] buf = new byte[8192];
                            int n;
                            while ((n = is.read(buf)) != -1) {
                                fos.write(buf, 0, n);
                            }
                        }
                        bmp = decodeSampled(diskFile.getAbsolutePath(), 392, 220);
                    }
                }
                if (bmp != null) {
                    MEM_CACHE.put(urlStr, bmp);
                    final Bitmap finalBmp = bmp;
                    MAIN.post(() -> {
                        if (urlStr.equals(target.getTag())) {
                            target.setImageBitmap(finalBmp);
                        }
                    });
                }
            } catch (Exception ignored) {
            }
        });
    }

    public static void loadTeamLogo(Context ctx, String urlStr, ImageView target) {
        if (urlStr == null || urlStr.isEmpty() || target == null) {
            if (target != null) target.setVisibility(View.GONE);
            return;
        }
        target.setTag(urlStr);
        Bitmap cached = MEM_CACHE.get(urlStr + "_logo");
        if (cached != null) {
            target.setImageBitmap(cached);
            target.setVisibility(View.VISIBLE);
            return;
        }

        final File cacheDir = new File(ctx.getCacheDir(), "team_logos");
        if (!cacheDir.exists()) cacheDir.mkdirs();

        IO_POOL.execute(() -> {
            try {
                File diskFile = new File(cacheDir, md5(urlStr) + ".png");
                Bitmap bmp = null;
                if (diskFile.exists() && diskFile.length() > 64) {
                    bmp = decodeLogoSampled(diskFile.getAbsolutePath(), 64, 64);
                }
                if (bmp == null) {
                    HttpURLConnection conn = (HttpURLConnection) new URL(urlStr).openConnection();
                    conn.setConnectTimeout(4000);
                    conn.setReadTimeout(4000);
                    conn.setInstanceFollowRedirects(true);
                    conn.setRequestProperty("User-Agent", "TvHome/2.0");
                    if (conn.getResponseCode() == 200) {
                        try (InputStream is = conn.getInputStream();
                             FileOutputStream fos = new FileOutputStream(diskFile)) {
                            byte[] buf = new byte[4096];
                            int n;
                            while ((n = is.read(buf)) != -1) {
                                fos.write(buf, 0, n);
                            }
                        }
                        bmp = decodeLogoSampled(diskFile.getAbsolutePath(), 64, 64);
                    }
                }
                if (bmp != null) {
                    MEM_CACHE.put(urlStr + "_logo", bmp);
                    final Bitmap finalBmp = bmp;
                    MAIN.post(() -> {
                        if (urlStr.equals(target.getTag())) {
                            target.setImageBitmap(finalBmp);
                            target.setVisibility(View.VISIBLE);
                        }
                    });
                }
            } catch (Exception ignored) {}
        });
    }

    public interface BackdropLoadListener {
        void onLoaded(Bitmap bmp);
    }

    public static void loadHeroBackdrop(Context ctx, String urlStr, ImageView target, BackdropLoadListener listener) {
        if (urlStr == null || urlStr.isEmpty()) {
            if (listener != null) listener.onLoaded(null);
            return;
        }
        target.setTag(urlStr);
        Bitmap cached = MEM_CACHE.get(urlStr + "_hero");
        if (cached != null) {
            if (listener != null) listener.onLoaded(cached);
            return;
        }
        final File cacheDir = new File(ctx.getCacheDir(), "backdrops");
        if (!cacheDir.exists()) cacheDir.mkdirs();

        IO_POOL.execute(() -> {
            try {
                File diskFile = new File(cacheDir, md5(urlStr) + ".jpg");
                Bitmap bmp = null;
                if (diskFile.exists() && diskFile.length() > 512) {
                    bmp = decodeHeroSampled(diskFile.getAbsolutePath(), 1280, 720);
                }
                if (bmp == null) {
                    HttpURLConnection conn = (HttpURLConnection) new URL(urlStr).openConnection();
                    conn.setConnectTimeout(6000);
                    conn.setReadTimeout(6000);
                    conn.setInstanceFollowRedirects(true);
                    conn.setRequestProperty("User-Agent", "TvHome/2.0");
                    if (conn.getResponseCode() == 200) {
                        try (InputStream is = conn.getInputStream();
                             FileOutputStream fos = new FileOutputStream(diskFile)) {
                            byte[] buf = new byte[8192];
                            int n;
                            while ((n = is.read(buf)) != -1) {
                                fos.write(buf, 0, n);
                            }
                        }
                        bmp = decodeHeroSampled(diskFile.getAbsolutePath(), 1280, 720);
                    }
                }
                if (bmp != null) {
                    MEM_CACHE.put(urlStr + "_hero", bmp);
                    final Bitmap finalBmp = bmp;
                    MAIN.post(() -> {
                        if (urlStr.equals(target.getTag())) {
                            if (listener != null) listener.onLoaded(finalBmp);
                        }
                    });
                }
            } catch (Exception ignored) {
            }
        });
    }

    private static Bitmap decodeHeroSampled(String path, int reqW, int reqH) {
        BitmapFactory.Options opts = new BitmapFactory.Options();
        opts.inJustDecodeBounds = true;
        BitmapFactory.decodeFile(path, opts);
        int sample = 1;
        while ((opts.outWidth / sample) > reqW || (opts.outHeight / sample) > reqH) {
            sample *= 2;
        }
        opts.inJustDecodeBounds = false;
        opts.inSampleSize = sample;
        opts.inPreferredConfig = Bitmap.Config.RGB_565; // Halves memory bandwidth on TV's Mali GPU
        return BitmapFactory.decodeFile(path, opts);
    }

    private static Bitmap decodeSampled(String path, int reqW, int reqH) {
        BitmapFactory.Options opts = new BitmapFactory.Options();
        opts.inJustDecodeBounds = true;
        BitmapFactory.decodeFile(path, opts);
        int sample = 1;
        while ((opts.outWidth / sample) > reqW || (opts.outHeight / sample) > reqH) {
            sample *= 2;
        }
        opts.inJustDecodeBounds = false;
        opts.inSampleSize = sample;
        opts.inPreferredConfig = Bitmap.Config.RGB_565;
        Bitmap raw = BitmapFactory.decodeFile(path, opts);
        if (raw == null) return null;
        return roundCenterCrop(raw, reqW, reqH, 20f);
    }

    private static Bitmap decodeLogoSampled(String path, int reqW, int reqH) {
        BitmapFactory.Options opts = new BitmapFactory.Options();
        opts.inJustDecodeBounds = true;
        BitmapFactory.decodeFile(path, opts);
        int sample = 1;
        while ((opts.outWidth / sample) > reqW || (opts.outHeight / sample) > reqH) {
            sample *= 2;
        }
        opts.inJustDecodeBounds = false;
        opts.inSampleSize = sample;
        return BitmapFactory.decodeFile(path, opts);
    }

    public static Bitmap roundCenterCrop(Bitmap src, int outW, int outH, float radiusPx) {
        if (src == null) return null;
        Bitmap out = Bitmap.createBitmap(outW, outH, Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(out);
        Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
        BitmapShader shader = new BitmapShader(src, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP);

        float scale;
        float dx = 0f, dy = 0f;
        int sw = src.getWidth(), sh = src.getHeight();
        if (sw * outH > outW * sh) {
            scale = (float) outH / (float) sh;
            dx = (outW - sw * scale) * 0.5f;
        } else {
            scale = (float) outW / (float) sw;
            dy = (outH - sh * scale) * 0.5f;
        }
        Matrix m = new Matrix();
        m.setScale(scale, scale);
        m.postTranslate(dx, dy);
        shader.setLocalMatrix(m);
        paint.setShader(shader);

        canvas.drawRoundRect(new RectF(0, 0, outW, outH), radiusPx, radiusPx, paint);
        if (src != out) {
            src.recycle();
        }
        return out;
    }

    private static String httpGet(String urlStr, String bearerToken) {
        HttpURLConnection conn = null;
        try {
            conn = (HttpURLConnection) new URL(urlStr).openConnection();
            conn.setRequestMethod("GET");
            conn.setConnectTimeout(8000);
            conn.setReadTimeout(8000);
            conn.setRequestProperty("Accept", "application/json");
            conn.setRequestProperty("User-Agent", "TvHome/2.0");
            if (urlStr.contains("trakt.tv")) {
                conn.setRequestProperty("Content-Type", "application/json");
                conn.setRequestProperty("trakt-api-version", "2");
                boolean hasBearer = bearerToken != null && !bearerToken.isEmpty();
                conn.setRequestProperty("trakt-api-key", hasBearer ? TRAKT_OAUTH_CLIENT_ID : TRAKT_CLIENT_ID);
                if (hasBearer) {
                    conn.setRequestProperty("Authorization", "Bearer " + bearerToken);
                }
            }
            if (conn.getResponseCode() == 200) {
                return readStream(conn.getInputStream());
            }
        } catch (Exception ignored) {
        } finally {
            if (conn != null) conn.disconnect();
        }
        return null;
    }

    private static String readStream(InputStream is) throws Exception {
        StringBuilder sb = new StringBuilder();
        try (BufferedReader br = new BufferedReader(new InputStreamReader(is, StandardCharsets.UTF_8))) {
            String line;
            while ((line = br.readLine()) != null) {
                sb.append(line);
            }
        }
        return sb.toString();
    }

    private static String md5(String s) {
        try {
            byte[] d = MessageDigest.getInstance("MD5").digest(s.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder();
            for (byte b : d) sb.append(String.format("%02x", b));
            return sb.toString();
        } catch (Exception e) {
            return String.valueOf(s.hashCode());
        }
    }

    public static class WeatherData {
        public final int tempF;
        public final String icon;
        public final String condition;
        public final boolean isDay;

        public WeatherData(int tempF, String icon, String condition, boolean isDay) {
            this.tempF = tempF;
            this.icon = icon;
            this.condition = condition;
            this.isDay = isDay;
        }

        public String getSummary() {
            return icon + " " + tempF + "°F";
        }
    }

    public static void fetchCurrentWeather(Context ctx, java.util.function.Consumer<WeatherData> callback) {
        if (callback == null) return;
        String cached = readCacheFile(ctx, "weather_cache.json");
        if (cached != null && !cached.isEmpty()) {
            WeatherData wd = parseWeatherJson(cached);
            if (wd != null) {
                new Handler(Looper.getMainLooper()).post(() -> callback.accept(wd));
            }
        }

        IO_POOL.execute(() -> {
            try {
                String tz = java.util.TimeZone.getDefault().getID();
                double lat = 33.45, lon = -112.07;
                if ("America/New_York".equals(tz)) { lat = 40.71; lon = -74.01; }
                else if ("America/Chicago".equals(tz)) { lat = 41.88; lon = -87.63; }
                else if ("America/Los_Angeles".equals(tz)) { lat = 34.05; lon = -118.24; }
                else if ("America/Denver".equals(tz)) { lat = 39.74; lon = -104.99; }

                String url = String.format(Locale.US,
                        "https://api.open-meteo.com/v1/forecast?latitude=%.2f&longitude=%.2f&current=temperature_2m,weather_code,is_day&temperature_unit=fahrenheit",
                        lat, lon);
                String raw = httpGet(url, null);
                if (raw != null) {
                    WeatherData wd = parseWeatherJson(raw);
                    if (wd != null) {
                        writeCacheFile(ctx, "weather_cache.json", raw);
                        new Handler(Looper.getMainLooper()).post(() -> callback.accept(wd));
                    }
                }
            } catch (Exception ignored) {
            }
        });
    }

    private static WeatherData parseWeatherJson(String raw) {
        try {
            JSONObject root = new JSONObject(raw);
            JSONObject current = root.optJSONObject("current");
            if (current == null) return null;
            double temp = current.optDouble("temperature_2m", 70.0);
            int code = current.optInt("weather_code", 0);
            boolean isDay = current.optInt("is_day", 1) == 1;
            int tempF = (int) Math.round(temp);

            String icon = isDay ? "☀️" : "🌙";
            String condition = "Clear";
            if (code == 1) { icon = isDay ? "🌤️" : "🌙"; condition = "Mainly Clear"; }
            else if (code == 2) { icon = isDay ? "⛅" : "☁️"; condition = "Partly Cloudy"; }
            else if (code == 3) { icon = "☁️"; condition = "Overcast"; }
            else if (code == 45 || code == 48) { icon = "🌫️"; condition = "Fog"; }
            else if (code >= 51 && code <= 57) { icon = "🌦️"; condition = "Drizzle"; }
            else if (code >= 61 && code <= 67) { icon = "🌧️"; condition = "Rain"; }
            else if (code >= 71 && code <= 77) { icon = "❄️"; condition = "Snow"; }
            else if (code >= 80 && code <= 82) { icon = "🌧️"; condition = "Showers"; }
            else if (code >= 95) { icon = "⛈️"; condition = "Thunderstorm"; }

            return new WeatherData(tempF, icon, condition, isDay);
        } catch (Exception e) {
            return null;
        }
    }
}
