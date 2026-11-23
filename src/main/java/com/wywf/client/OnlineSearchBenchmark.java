package com.wywf.client;

import com.wywf.core.*;
import com.wywf.verify.PreviewWorld;
import com.wywf.verify.VerificationSummary;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.client.gui.screens.worldselection.CreateWorldScreen;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import org.slf4j.LoggerFactory;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

public final class OnlineSearchBenchmark {
    private static final String MODE = System.getenv("WYWF_ONLINE_BENCH");
    private static final long TIME_LIMIT = 120_000_000_000L;
    private static final boolean RANDOM_START = "1".equals(System.getenv("WYWF_ONLINE_RANDOM"));
    private static final boolean CHECK_PREVIEW = "1".equals(System.getenv("WYWF_ONLINE_PREVIEW"));
    private static final List<Scenario> SCENARIOS = List.of(
            new Scenario("cherry", "between 0..64 village between 0..64 cherry grove"),
            new Scenario("inside", "between 0..0 village between 0..64 cherry grove"),
            new Scenario("stronghold", "between 0..1200 stronghold between 0..400 village"),
            new Scenario("mixed", "between 0..128 village between 0..128 cherry grove between 0..192 river"),
            new Scenario("deep", "between 0..800 ancient city between 0..640 trial chambers"));
    private record Scenario(String name, String query) {}

    private final List<Scenario> cases;
    private final Set<Long> passed = new LinkedHashSet<>();
    private int index;
    private int attempts;
    private long started;
    private long first;
    private long third;
    private boolean opening;
    private boolean returning;
    private CreateWorldScreen creator;
    private SearchScreen search;
    private PreviewWorld.Session firstPassSession;
    private long previewStarted;
    private int rounds;
    private int round = 1;
    private static OnlineSearchBenchmark active;

    private OnlineSearchBenchmark() {
        String selected = System.getenv().getOrDefault("WYWF_ONLINE_CASES", "cherry,stronghold,mixed,deep");
        var names = Set.of(selected.split(","));
        cases = SCENARIOS.stream().filter(s -> names.contains(s.name())).toList();
        if (cases.size() != names.size()) throw new IllegalArgumentException("Unknown benchmark case: " + selected);
        rounds = Integer.parseInt(System.getenv().getOrDefault("WYWF_ONLINE_ROUNDS", "1"));
    }

    public static boolean enabled() { return MODE != null; }
    public static boolean biomeProbe() { return MODE == null || "current".equals(MODE) || "biome".equals(MODE) || "both".equals(MODE) || "wide".equals(MODE) || "inside-priority".equals(MODE); }
    public static boolean rankCandidates() { return MODE == null || "current".equals(MODE) || "rank".equals(MODE) || "both".equals(MODE) || "wide".equals(MODE) || "inside-priority".equals(MODE); }

    public static void install() {
        if (MODE == null) return;
        active = new OnlineSearchBenchmark();
        ClientTickEvents.END_CLIENT_TICK.register(active::tick);
    }

    public static void verified(SearchScreen owner, long seed, VerificationSummary summary, long attemptNanos,
                                PreviewWorld.Session session) {
        var bench = active;
        if (bench == null || bench.search != owner || bench.returning) return;
        var profile = session.verificationProfile();
        bench.attempts++;
        long elapsed = System.nanoTime() - bench.started;
        if (summary.status() == VerificationSummary.Status.PASS && bench.passed.add(seed)) {
            if (bench.passed.size() == 1) {
                bench.first = elapsed;
                if (CHECK_PREVIEW) bench.firstPassSession = session;
            }
            if (bench.passed.size() == 3) bench.third = elapsed;
        }
        log("attempt mode={} case={} round={} seed={} status={} totalMs={} attemptMs={} verifyMs={} chunks={} full={} count={} spawn={},{} rawWorkers={} rawChecked={} evidence={}",
                MODE, bench.cases.get(bench.index).name(), bench.round, seed, summary.status(), ms(elapsed), ms(attemptNanos),
                ms(profile.nanos()), profile.chunkCount(), profile.fullChunks(), bench.passed.size(), summary.spawnX(), summary.spawnZ(),
                owner.benchmarkSearchWorkers(), owner.benchmarkCheckedSeeds(), summary.evidence());
    }

    private void tick(Minecraft client) {
        if (returning) {
            if (PreviewWorld.active() != null) return;
            returning = false;
            search = null;
            if (++index == cases.size()) {
                index = 0;
                if (++round > rounds) {
                    log("DONE mode={}", MODE);
                    client.stop();
                    return;
                }
            }
        }
        if (search != null) {
            if (third != 0 || search.exhausted() || System.nanoTime() - started >= TIME_LIMIT) {
                if (CHECK_PREVIEW && third != 0 && !previewVerified(client)) return;
                log("result mode={} case={} round={} firstMs={} thirdMs={} attempts={} passes={} seeds={}",
                        MODE, cases.get(index).name(), round, ms(first), ms(third), attempts, passed.size(), passed);
                returning = true;
                search.onClose();
                PreviewWorld.shutdown();
            }
            return;
        }
        if (creator == null) {
            if (ClientPlatform.screen(client) instanceof CreateWorldScreen screen) creator = screen;
            else if (!opening && ClientPlatform.screen(client) instanceof TitleScreen) {
                opening = true;
                CreateWorldScreen.openFresh(client, client::stop);
            }
            return;
        }
        Scenario scenario = cases.get(index);
        var dictionary = new KeywordDictionary(KeywordDictionary.Lang.EN);
        var query = new QueryParser(dictionary).parse(scenario.query());
        if (query.isEmpty() || query.error() != null || !query.ignoredWords().isEmpty())
            throw new IllegalArgumentException("Invalid benchmark query " + scenario.query());
        var config = SearchConfig.defaults().manualThreads(8).nativeMode(SearchConfig.NativeMode.NATIVE)
                .searchCenter(SearchConfig.SearchCenter.SPAWN).queryLanguage(KeywordDictionary.Lang.EN)
                .infiniteSeeds(true).randomizeStart(RANDOM_START).candidatesToCollect(3).minCandidates(3);
        passed.clear();
        attempts = 0;
        first = 0;
        third = 0;
        firstPassSession = null;
        previewStarted = 0;
        search = new SearchScreen(creator, scenario.query(), query, config).fresh();
        started = System.nanoTime();
        log("start mode={} case={} round={} random={} query={}", MODE, scenario.name(), round, RANDOM_START, scenario.query());
        client.setScreenAndShow(search);
    }

    private boolean previewVerified(Minecraft client) {
        if (!(ClientPlatform.screen(client) instanceof CandidateReviewScreen) || !firstPassSession.parked() && previewStarted == 0)
            return false;
        if (previewStarted == 0) {
            previewStarted = System.nanoTime();
            if (firstPassSession.surface() == null) {
                SearchResult result = firstPassSession.result();
                firstPassSession.requestSurface(result.centerX, result.centerZ);
            }
        }
        boolean ready = firstPassSession.surface() != null;
        long elapsed = System.nanoTime() - previewStarted;
        if (!ready && elapsed < 20_000_000_000L) return false;
        log("preview mode={} case={} seed={} ready={} elapsedMs={}", MODE, cases.get(index).name(),
                firstPassSession.result().seed, ready, ms(elapsed));
        return true;
    }
    private static long ms(long nanos) { return nanos == 0 ? -1 : nanos / 1_000_000; }

    private static void log(String line, Object... values) {
        LoggerFactory.getLogger("wywf-online-bench").info("[Online bench] " + line, values);
    }
}
