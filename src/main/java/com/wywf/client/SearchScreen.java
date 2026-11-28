package com.wywf.client;

import dev.diylib.client.ui.DiyVanillaButton;
import com.wywf.WYWFClient;
import com.wywf.core.*;
import com.wywf.search.SeedSearcher;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.worldselection.CreateWorldScreen;
import java.util.List;

public final class SearchScreen extends Screen {
    private final CreateWorldScreen parentScreen;
    private final String queryText;
    private final ParsedQuery parsedQuery;
    private final SearchConfig config;
    private final SearchRun run;
    private final java.util.Optional<QueryConflicts.Conflict> conflict;
    private long descriptionTintPhase=Long.MIN_VALUE;
    private final SearchSession session = new SearchSession();
    private SeedSearcher activeSearcher;
    private FinalSearchTask finalizer;
    private SearchProgress.Snapshot lastSnapshot;
    private List<SearchResult> lastCandidates = List.of();
    private long lastUpdateTime;
    private long lastCheckedSeeds;
    private long lastUpdateSpeed;
    private Button showReady,editQuery,exitButton;
    private int failureOffset;
    private List<net.minecraft.util.FormattedCharSequence> failureLines=List.of();
    private List<net.minecraft.util.FormattedCharSequence> descriptionLines=List.of();
    private int descriptionOffset,descriptionRows=1;
    private boolean rawDone,batchRecorded;
    private final SearchStatistics statistics=new SearchStatistics();

    void serverFinished(boolean rejected){statistics.serverFinished(rejected);}
    long benchmarkCheckedSeeds() {
        return activeSearcher.progress().snapshot().checkedSeeds();
    }
    int benchmarkSearchWorkers() {
        return activeSearcher.activeWorkers();
    }
    private volatile boolean finishing;
    private boolean exhausted;
    private String exhaustedReason="";
    private SearchFailure failure;
    private List<SearchResult> priorCandidates = List.of();
    private CandidateReview priorReview;
    private java.util.Set<String> priorLayers;
    private volatile long batchGeneration;
    public boolean exhausted(){
        return exhausted;
    }
    public String exhaustedReason(){
        return exhaustedReason;
    }
    public java.util.Optional<SearchFailure> failure(){
        return java.util.Optional.ofNullable(failure);
    }
    void failure(SearchFailure value){
        failure=value;
    }
    String batchStopReason(){
        return activeSearcher==null?MapTexts.text("finalizing_incomplete"):activeSearcher.stopReason();
    }
    public void noResults(String reason){
        exhausted=true;
        finishing=true;
        rawDone=true;
        exhaustedReason=reason==null?"":reason;
        if(ClientPlatform.screen(Minecraft.getInstance())==this)rebuildWidgets();
    }
    private java.util.concurrent.CompletableFuture<SearchResult> denseCheck;
    private SearchResult denseCandidate;
    SearchDiagnostics diagnostics(){
        return activeSearcher==null ? new SearchDiagnostics() : activeSearcher.diagnostics();
    }
    private boolean historyChecked, reuseHistory;
    private String historyKey;
    private CandidateReview historyReview = new CandidateReview(List.of(), null, false);
    private List<SearchResult> history = List.of();
    boolean verifiesHistory() { return config.serverVerification(); }

    void reuseHistory(boolean value) {
        reuseHistory = value;
    }
    public SearchScreen fresh() {
        historyChecked = true;
        return this;
    }

    SearchScreen appendTo(List<SearchResult> candidates) {
        priorCandidates = List.copyOf(candidates);
        if (parsedQuery.place() == null) run.exclude(candidates.stream().map(candidate -> candidate.seed).toList());
        return this;
    }

    SearchScreen appendTo(CandidateReview review) {
        priorReview = review;
        return appendTo(review.candidates());
    }

    SearchScreen withLayers(java.util.Set<String> layers) {
        priorLayers = layers;
        return this;
    }

    private FtpScreen placeSearch;
    private com.wywf.verify.PlaceSurvey.Area placeArea;
    SearchScreen forPlace(FtpScreen screen) { return forPlace(screen, null); }
    SearchScreen forPlace(FtpScreen screen, com.wywf.verify.PlaceSurvey.Area area) {
        placeSearch = screen;
        placeArea = area;
        return this;
    }

    private void closeVerificationWorlds() {
        var preserved = new java.util.ArrayList<com.wywf.verify.PreviewWorld.Session>();
        if (placeSearch != null) preserved.addAll(placeSearch.preserved());
        for (var candidate : priorCandidates) {
            var preview = com.wywf.verify.PreviewWorld.find(candidate, parsedQuery, config);
            if (preview != null) preserved.add(preview);
        }
        com.wywf.verify.PreviewWorld.shutdownExcept(preserved);
    }

    public SearchScreen(CreateWorldScreen parentScreen, String queryText, ParsedQuery parsedQuery, SearchConfig config) {
        this(parentScreen, queryText, parsedQuery, config, new SearchRun());
    }

    SearchScreen(CreateWorldScreen parentScreen, String queryText, ParsedQuery parsedQuery, SearchConfig config, SearchRun run) {
        super(parsedQuery.place()==null?ResultTexts.label("search_title"):net.minecraft.network.chat.Component.literal("FindThisPlace"));
        this.parentScreen = parentScreen;
        this.queryText = queryText;
        this.parsedQuery = parsedQuery;
        this.config = config.copy();
        if(parsedQuery.place()!=null)this.config.searchCenter(SearchConfig.SearchCenter.ORIGIN);
        this.run = run;
        conflict=QueryConflicts.find(parsedQuery,this.config);
    }

    @Override
    protected void init() {
        descriptionLines=font.split(understoodDescription(),width-24);
        descriptionRows=Math.min(descriptionLines.size(),Math.max(1,Math.min(4,(height-160)/font.lineHeight)));
        descriptionOffset=Math.max(0,Math.min(descriptionOffset,descriptionLines.size()-descriptionRows));
        showReady=addRenderableWidget(DiyVanillaButton.builder(MapTexts.label("show_ready"),b->{
            if(exhausted){
                cancelSearch();
                Minecraft.getInstance().setScreenAndShow(new SearchScreen(parentScreen,queryText,parsedQuery,config).fresh().forPlace(placeSearch, placeArea));
                return;
            }
            if(finalizer==null||finalizer.ready()==0)
                return;
            finishing=true;
            if(activeSearcher!=null)
                activeSearcher.cancel();
            finalizer.showReady();
        }).bounds(width/2-153,height-55,306,20).build());
        showReady.active=exhausted||finalizer!=null&&finalizer.ready()>0;
        if(exhausted){
            showReady.setMessage(MapTexts.label("search_again"));
            showReady.setWidth(150);
        }
        editQuery=addRenderableWidget(DiyVanillaButton.builder(MapTexts.label("edit_query"),b->{
            cancelSearch();
            Minecraft.getInstance().setScreenAndShow(placeSearch == null ? new SearchQueryEditScreen(parentScreen,queryText,config) : placeSearch);
        })
                .bounds(width/2+3,height-55,150,20).build());
                editQuery.visible=exhausted;
        exitButton=addRenderableWidget(DiyVanillaButton.builder(ResultTexts.label(exhausted?"back":"cancel"), b -> onClose())
                .bounds(width / 2 - 153, height - 30, 150, 20).build());
        addRenderableWidget(DiyVanillaButton.builder(MapTexts.label("simplify"), b -> {
            cancelSearch();
            Minecraft.getInstance().setScreenAndShow(
                    new SearchAdviceScreen(parentScreen,queryText,parsedQuery,config,diagnostics().snapshot()).forPlace(placeSearch));
        }
        ).bounds(width / 2 + 3, height - 30, 150, 20).build());

        if (session.start()) {
            if (reuseHistory) {
                closeVerificationWorlds();
                var shortlist = new CandidateReview(history, null, config.sortCandidatesByDistance());
                for (var result : history) {
                    var summary = historyReview.verification(result);
                    if (summary != null) shortlist.recordVerification(result, summary);
                }
                finalizer = new FinalSearchTask(this, parentScreen, parsedQuery, config, shortlist, null);
            } else startSearch();
        }
    }

    public void open() {
        open(() -> {});
    }

    void open(Runnable beforeSearch) {
        var client = Minecraft.getInstance();
        if (!historyChecked) {
            historyChecked = true;
            if (config.offerSearchHistory()
                    && parsedQuery.error() == null && parsedQuery.ignoredWords().isEmpty()) {
                try {
                    historyKey = SearchHistoryContext.key(parentScreen, parsedQuery, config);
                    historyReview = SearchHistoryContext.store().findReview(historyKey, parsedQuery);
                    history = historyReview.candidates();
                    if (placeArea != null) history = history.stream()
                            .filter(result -> placeArea.contains(result.centerX, result.centerZ)).toList();
                } catch (RuntimeException unavailable) { history = List.of(); }
            }
        }
        if (!history.isEmpty() && ClientPlatform.screen(client) != null) {
            SearchHistoryPrompt.show(ClientPlatform.screen(client), this, history.size(), beforeSearch);
            return;
        }
        beforeSearch.run();
        client.setScreenAndShow(this);
    }

    void retrySearch() { startSearch(true); }

    void finishSearch(CandidateReview review, String reason) {
        if (priorReview != null) review = review.prepend(priorReview);
        else if (!priorCandidates.isEmpty()) review = review.prepend(priorCandidates);
        if(review.candidates().isEmpty()){
            noResults(reason);
            return;
        }
        if (!session.finish())
            return;
        if (parsedQuery.error() == null && parsedQuery.ignoredWords().isEmpty()) {
            try {
                if (historyKey == null) historyKey = SearchHistoryContext.key(parentScreen, parsedQuery, config);
                SearchHistoryContext.store().remember(historyKey, review);
            } catch (Exception error) {
                org.slf4j.LoggerFactory.getLogger("wywf-search").warn("Could not save search history", error);
            }
        }
        if (placeSearch != null) {
            placeSearch.showSearchResults(review, parsedQuery, config);
            closeVerificationWorlds();
        } else {
            var screen = new CandidateReviewScreen(parentScreen, queryText, parsedQuery, config, review, reason);
            if (priorLayers != null) screen.rememberLayers(priorLayers);
            Minecraft.getInstance().setScreenAndShow(screen);
        }
    }

    private void startSearch(){startSearch(false);}
    private void startSearch(boolean continuation) {
        rawDone=false;
        batchRecorded=false;
        finishing=false;
        exhausted=false;
        if (denseCheck != null) denseCheck.cancel(false);
        denseCheck = null;
        denseCandidate = null;
        long generation=++batchGeneration;
        if (parsedQuery.error() != null) {
            session.finish();
            Minecraft.getInstance().setScreenAndShow(new QuerySyntaxScreen(parentScreen, parsedQuery, config));
            return;
        }
        if(conflict.isPresent()){noResults("");return;}
        var lootIssues = com.wywf.verify.LootCatalog.inspect(parentScreen, parsedQuery);
        if (!lootIssues.isEmpty()) {
            session.finish();
            Minecraft.getInstance().setScreenAndShow(placeSearch == null ? new LootAdviceScreen(parentScreen, parsedQuery, config, lootIssues)
                    : new LootAdviceScreen(parsedQuery, lootIssues, text -> placeSearch.editConditions(
                            new QueryParser(new KeywordDictionary(config.queryLanguage())).parse(text).expression()),
                            () -> Minecraft.getInstance().setScreenAndShow(placeSearch)));
            return;
        }
        if(!continuation)closeVerificationWorlds();
        WYWFClient.applyQueryLanguage(config.queryLanguage());
        SeedSearcher searcher = WYWFClient.searcher();
        activeSearcher = searcher;
        if(!continuation)finalizer=new
                FinalSearchTask(
                        this,parentScreen,parsedQuery,config,new CandidateReview(List.of(),null,false),run);
        finalizer.streaming();
        lastUpdateTime=System.currentTimeMillis();
        lastCheckedSeeds=0;
        lastUpdateSpeed=0;
        var batchConfig=config.copy();
        int remaining=finalizer.remainingTarget();
        batchConfig.candidatesToCollect(remaining).minCandidates(Math.min(config.minCandidates(),remaining));
        searcher.startStreaming(parsedQuery,batchConfig,run,result -> {
            String reason = result != null && result.stopReason != null ? result.stopReason : searcher.stopReason();
            Minecraft.getInstance().execute(() -> {
                if (generation!=batchGeneration||ClientPlatform.screen(Minecraft.getInstance())!=this||session.phase()!=SearchSession.Phase.RUNNING||finishing)
                    return;
                var snapshot=searcher.progress().snapshot();
                run.batch(searcher.batchSeeds(),snapshot.checkedSeeds(),snapshot.discardedSeeds(),reason);
                batchRecorded=true;
                rawDone=true;
            });
        });
    }

    void stopInputForDeadline(){
        finishing=true;
        rawDone=true;
        batchGeneration++;
        if(activeSearcher!=null)activeSearcher.cancel();if(denseCheck!=null)denseCheck.cancel(true);
    }

    private void cancelSearch() {
        if (!session.cancel()) return;
        if(denseCheck!=null)denseCheck.cancel(true);
        if (activeSearcher != null) activeSearcher.cancel();
        if (finalizer != null) finalizer.cancel();
        closeVerificationWorlds();
    }

    @Override
    public void onClose() {
        cancelSearch();
        Minecraft.getInstance().setScreenAndShow(placeSearch == null ? parentScreen : placeSearch);
    }

    @Override
    public void removed() {
        cancelSearch();
        super.removed();
    }

    @Override
    public void tick() {
        super.tick();
        if(finalizer!=null&&activeSearcher!=null&&!finishing){
            if (denseCheck != null && denseCheck.isDone()) {
                boolean retry = false;
                try {
                    var result = denseCheck.join();
                    if (result != null) finalizer.offer(List.of(result));
                    else if (run.remainingTimeMs(config) > 0) statistics.deepRejected();
                } catch (java.util.concurrent.CompletionException failure) {
                    retry = failure.getCause() instanceof java.util.concurrent.RejectedExecutionException;
                    if (!retry) WYWFClient.LOGGER.warn("Candidate verification failed", failure.getCause());
                } catch (java.util.concurrent.CancellationException ignored) {
                }
                denseCheck = null;
                if (!retry) denseCandidate = null;
            }
            if (denseCheck == null && finalizer.canAccept()) {
                SearchResult next = denseCandidate == null ? activeSearcher.pollCandidate() : denseCandidate;
                if (next != null && (placeArea == null || placeArea.contains(next.centerX, next.centerZ))) {
                    denseCandidate = next;
                    var searcher = activeSearcher;
                    long generation = batchGeneration;
                    denseCheck = MapBackground.cpuResult(() -> searcher.verifyCandidate(parsedQuery, config, next,
                            () -> generation != batchGeneration || session.phase() != SearchSession.Phase.RUNNING
                                    || finishing || run.remainingTimeMs(config) == 0) ? next : null);
                } else if (rawDone && activeSearcher.queuedCandidates() == 0) finalizer.inputDone();
            }
        }
        if (finalizer != null) {
            if (activeSearcher != null) {
                boolean server = config.serverVerification();
                activeSearcher.verificationLoad(server && (finalizer.verifying() || finalizer.queued() > 0),
                        server && finalizer.queued() >= SearchScheduling.of(config).verificationCapacity());
            }
            finalizer.tick();
            if (showReady != null) showReady.active = exhausted || finalizer.ready() > 0 && !finishing;
        }
        if (activeSearcher == null) return;
        lastSnapshot = activeSearcher.progress().snapshot();
        var candidates = activeSearcher.candidates();
        synchronized (candidates) {
            lastCandidates = List.copyOf(candidates);
        }
        long now = System.currentTimeMillis();
        if (now - lastUpdateTime >= 500) {
            lastUpdateSpeed = Math.max(0, lastSnapshot.checkedSeeds() - lastCheckedSeeds) * 1000L
                    / Math.max(1L, now - lastUpdateTime);
            lastCheckedSeeds = lastSnapshot.checkedSeeds();
            lastUpdateTime = now;
        }
    }

    private net.minecraft.network.chat.Component understoodDescription(){
        String text=MapTexts.text("understood",ResultTexts.normalized(parsedQuery));
        if(conflict.isEmpty())
            return net.minecraft.network.chat.Component.literal(text);
        boolean ru=ConstructorTexts.displayLanguage(KeywordDictionary.Lang.AUTO)==KeywordDictionary.Lang.RU;
        var phrases=conflict.get().conditions().stream().map(
                t->QueryDescription.condition(t,ru,ResultTexts::name)).toList();
        var out=net.minecraft.network.chat.Component.empty();
        int offset=0;
        for(var span:TextHighlights.conflicts(text,phrases)){
            if(span.start()>offset)
                out.append(net.minecraft.network.chat.Component.literal(
                        text.substring(offset,span.start())));
            out.append(MenuAccent.conflictText(text.substring(span.start(),span.end())));
            offset=span.end();
        }
        if(offset<text.length())
            out.append(net.minecraft.network.chat.Component.literal(text.substring(offset)));return out;
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor g, int mouseX, int mouseY, float partialTick) {
        long phase=System.currentTimeMillis()/80;
        if(conflict.isPresent()&&descriptionTintPhase!=phase){
            descriptionTintPhase=phase;
            descriptionLines=font.split(understoodDescription(),width-24);
        }
        super.extractRenderState(g, mouseX, mouseY, partialTick);
        g.centeredText(font, title, width / 2, 12, 0xFFFFFFFF);
        for(int i=0;i<descriptionRows;i++)
            g.text(font,descriptionLines.get(descriptionOffset+i),(
                    width-font.width(descriptionLines.get(descriptionOffset+i)))/2,31+i*font.lineHeight,0xFFD9DFE2);
        int extra=Math.max(0,descriptionRows-1)*font.lineHeight;
        if(descriptionLines.size()>descriptionRows)g.text(font,net.minecraft.network.chat.Component.literal("↕"),width-10,31,0xFFB9CBBF);
        if(exhausted){
            drawFailure(g,63+extra);
            return;
        }
        if (lastSnapshot == null) {
            centered(g, reuseHistory ? MapTexts.text("history_checking")
                    : ResultTexts.text("preparing"),63+extra, 0xFFCCCCCC);
            return;
        }
        int barWidth = Math.min(400, width - 32);
        int barX = (width - barWidth) / 2;
        int barY=55+extra;
        g.fill(barX - 1, barY - 1, barX + barWidth + 1, barY + 11, 0xFF64756C);
        g.fill(barX, barY, barX + barWidth, barY + 10, 0xFF19231E);
        int segment = Math.max(8, barWidth / 4);
        int offset = (int) ((System.currentTimeMillis() / 8) % (barWidth + segment)) - segment;
        g.fill(Math.max(barX, barX + offset), barY, Math.min(barX + barWidth, barX + offset + segment),
                barY + 10, 0xFF71AD83);
        String state = ResultTexts.text("searching");
        if (config.serverVerification()) {
            if (rawDone || reuseHistory) state = MapTexts.text("search_checking");
            else if (activeSearcher.waitingForVerification()) state = MapTexts.text("pipeline_waiting");
            else if (finalizer != null && finalizer.verifying()) state = MapTexts.text("search_pipeline");
        }
        g.centeredText(font,MenuAccent.versionText(state,0xFFC3EACD,0xFFE9D69F),width/2,72+extra,0xFFFFFFFF);
        int panelWidth = Math.min(400, width - 32);
        int left = (width - panelWidth) / 2;
        int right = left + panelWidth / 2 + 6;
        long completed=run.completedCheckedSeeds(),live=batchRecorded?0:lastSnapshot.checkedSeeds();
        row(g,left,91+extra,ResultTexts.text("checked",number(live>Long.MAX_VALUE-completed?Long.MAX_VALUE:completed+live)));
        row(g, right,91+extra, MapTexts.text(parsedQuery.place() == null
                        ? "speed_raw" : "speed_places",
                number(lastUpdateSpeed)));
        row(g, left,105+extra, ResultTexts.text("elapsed", time(run.elapsedMs())));
        row(g, right,105+extra, MapTexts.text("pipeline_threads",activeSearcher.activeWorkers(),lastSnapshot.threads()));
        row(g, left,119+extra, MapTexts.text(config.serverVerification()
                ? "ready_count" : "raw_results", finalizer==null ? 0 : finalizer.ready()));
        row(g, right,119+extra, ResultTexts.text("accelerator",
                config.nativeMode()!=SearchConfig.NativeMode.CLASSIC&&com.wywf.search.CubiomesBridge.isAvailable()?"DLL":"Java"));
        row(g, left,133+extra, ResultTexts.text("discarded",
                number(statistics.discarded(run.completedDiscardedSeeds(),batchRecorded?0:lastSnapshot.discardedSeeds()))));
        row(g, right,133+extra, ResultTexts.text("limit", config.infiniteSeeds()
                ? ResultTexts.text("unlimited") : number(config.maxSeedsToCheck())));
        row(g, left, 147 + extra, config.serverVerification()
                ? MapTexts.text("server_checked", number(statistics.serverChecked()))
                : MapTexts.text("server_disabled"));
        var obstacle=diagnostics().obstacle();
        if(obstacle.isPresent()&&height>=260){
            var count=obstacle.get();
            var term=parsedQuery.terms().stream().filter(t->t.toString().equals(count.condition())).findFirst();
            if(term.isPresent()){
                String message=MapTexts.text("obstacle",ResultTexts.name(term.get().canonical),count.failed(),count.total());
                int y=165+extra;for(var line:font.split(net.minecraft.network.chat.Component.literal(message),width-32)){
                    if(y+font.lineHeight>height-62)break;
                    g.text(font,line,(width-font.width(line))/2,y,0xFFE6CD91);
                    y+=font.lineHeight;
                }
            }
        }
        if(WyWFDebug.ENABLED&&!lastCandidates.isEmpty()&&height>=240&&obstacle.isEmpty()) {
            SearchResult latest = lastCandidates.getLast();
            centered(g, ResultTexts.text("latest", latest.seed),168+extra, 0xFFE6DBA1);
        }
        if (!parsedQuery.ignoredWords().isEmpty()) {
            centered(g, ResultTexts.text("ignored", String.join(", ", parsedQuery.ignoredWords())),
                    height - 51, 0xFFEAC17F);
        }
    }

    private void drawFailure(GuiGraphicsExtractor g,int top){
        var messages=new java.util.ArrayList<String>();
        var conflict=this.conflict;
        if(conflict.isPresent()){
            messages.add(MapTexts.text("search_impossible"));
            messages.add(MapTexts.text("conflict_"+conflict.get().reason().name().toLowerCase(java.util.Locale.ROOT)));
            boolean ru=ConstructorTexts.displayLanguage(KeywordDictionary.Lang.AUTO) == KeywordDictionary.Lang.RU;
            for(var term:conflict.get().conditions())messages.add(
                    QueryDescription.condition(term,ru,ResultTexts::name));
        }
        else{
            messages.add(MapTexts.text("search_exhausted"));
            if(failure!=null){
                var names=parsedQuery.terms().stream().filter(
                        t -> failure.conditions().contains(t.toString())).map(
                                t->ResultTexts.name(t.canonical)).distinct().toList();
                String detail=names.isEmpty()?MapTexts.text("search_failure_unknown"):String.join(", ",names);
                messages.add(MapTexts.text(
                        failure.kind()==SearchFailure.Kind.PARTIAL?"search_failure_partial"
                                : failure.kind()==SearchFailure.Kind.CONDITION?"search_failure_condition":"search_failure_error",
                        failure.infrastructure()
                                ? failure.note():detail));
            }
            diagnostics().obstacle().ifPresent(
                    count -> parsedQuery.terms().stream().filter(t->t.toString().equals(
                    count.condition())).findFirst().ifPresent(t ->
                    messages.add(MapTexts.text("obstacle",ResultTexts.name(t.canonical),
                            count.failed(),
                            count.total()))));
            if(!exhaustedReason.isBlank())
                messages.add(MapTexts.text("search_failure_limit",exhaustedReason));
        }
        messages.add(MapTexts.text("search_failure_actions"));
        int w=Math.min(640,width-32);
        var lines=new java.util.ArrayList<net.minecraft.util.FormattedCharSequence>();
        boolean ru=ConstructorTexts.displayLanguage(KeywordDictionary.Lang.AUTO)==KeywordDictionary.Lang.RU;
        var accented=conflict.map(
                c->c.conditions().stream().map(t->QueryDescription.condition(t,ru,ResultTexts::name))
                        .collect(java.util.stream.Collectors.toSet())).orElse(java.util.Set.of());
        for(String message:messages){
            lines.addAll(font.split(accented.contains(message)
                    ? MenuAccent.conflictText(message)
                    : net.minecraft.network.chat.Component.literal(message),w));
            lines.add(net.minecraft.util.FormattedCharSequence.EMPTY);
        }
        failureLines=List.copyOf(lines);
        int rows=Math.max(1,(height-66-top)/font.lineHeight);
        failureOffset=Math.max(0,Math.min(failureOffset,Math.max(0,lines.size()-rows)));
        int y=top;

        for(var line:lines.subList(failureOffset,Math.min(lines.size(),failureOffset+rows))){
            g.text(font,line,(width-font.width(line))/2,y,0xFFE6D4A0);
            y+=font.lineHeight;
        }
        if(lines.size()>rows)g.text(font,net.minecraft.network.chat.Component.literal("↕"),width-12,top,0xFFB9CBBF);
    }

    @Override public boolean mouseScrolled(double x,double y,double horizontal,double vertical){
        if(exhausted&&y>=63&&y<height-66&&vertical!=0){
            failureOffset=Math.max(0,failureOffset+(vertical>0?-1:1));
            return true;
        }
        if(y>=31&&y<31+descriptionRows*font.lineHeight&&descriptionLines.size() > descriptionRows&&vertical!=0){
            descriptionOffset=Math.max(0,Math.min(descriptionLines.size()-descriptionRows,descriptionOffset+(vertical>0?-1:1)));
            return true;
        }
        return super.mouseScrolled(x,y,horizontal,vertical);
    }

    private void row(GuiGraphicsExtractor g, int x, int y, String value) {
        g.text(font, font.plainSubstrByWidth(value, Math.min(400, width - 32) / 2 - 12), x, y, 0xFFD5DEE3);
    }

    private void centered(GuiGraphicsExtractor g, String value, int y, int color) {
        g.centeredText(font, font.plainSubstrByWidth(value, width - 24), width / 2, y, color);
    }

    private static String number(long n) {
        return String.format(java.util.Locale.ROOT, "%,d", n).replace(',', ' ');
    }

    private static String time(long ms) {
        long seconds = ms / 1000;
        return String.format(java.util.Locale.ROOT, "%02d:%02d", seconds / 60, seconds % 60);
    }
}
