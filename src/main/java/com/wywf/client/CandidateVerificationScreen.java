package com.wywf.client;

import com.wywf.WYWFClient;
import com.wywf.core.CandidateReview;
import com.wywf.core.ParsedQuery;
import com.wywf.core.SearchConfig;
import com.wywf.core.SearchResult;
import com.wywf.verify.PreviewWorld;
import com.wywf.verify.VerificationSummary;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;

import java.util.List;

final class CandidateVerificationScreen extends Screen {
    private final CandidateReviewScreen parent;
    private final ParsedQuery query;
    private final CandidateReview review;
    private final List<SearchResult> candidates;
    private int next;
    private boolean stopping;
    private boolean historyChanged;
    private PreviewWorld.Session session;
    private Button cancel;

    CandidateVerificationScreen(CandidateReviewScreen parent, ParsedQuery query, CandidateReview review) {
        super(MapTexts.label("verify_title"));
        this.parent = parent;
        this.query = query;
        this.review = review;
        candidates = review.candidates().stream().filter(result ->
                needsVerification(result, query, parent.config(), review)).toList();
    }

    static boolean needsVerification(SearchResult result, ParsedQuery query, SearchConfig config, CandidateReview review) {
        var summary = review.verification(result);
        return (summary == null || summary.status() != VerificationSummary.Status.FAIL
                && summary.status() != VerificationSummary.Status.PASS)
                && PreviewWorld.find(result, query, config) == null;
    }

    static String statusText(VerificationSummary summary) {
        if (summary == null) return ResultTexts.text("offline");
        return ResultTexts.text(switch (summary.status()) {
            case PASS -> "verify_passed";
            case FAIL -> "verify_failed";
            case ERROR -> "verify_error";
            default -> "verify_incomplete";
        });
    }

    @Override protected void init() {
        cancel = addRenderableWidget(MenuButton.builder(ResultTexts.label("cancel"), button -> onClose())
                .bounds(width / 2 - 75, height - 30, 150, 20).build());
        cancel.active = !stopping;
    }

    @Override public void tick() {
        if (stopping || next == candidates.size()) {
            if (PreviewWorld.active() == null) returnToResults();
            return;
        }
        SearchResult candidate = candidates.get(next);
        if (session != null) {
            VerificationSummary summary = session.summary();
            if (!summary.terminal()) return;
            review.recordVerification(candidate, summary);
            if (summary.status() == VerificationSummary.Status.PASS || summary.status() == VerificationSummary.Status.FAIL)
                historyChanged = true;
            if (summary.status() == VerificationSummary.Status.PASS) session.park();
            else session.cancel();
            session = null;
            next++;
        } else if (PreviewWorld.active() == null) {
            try {
                session = PreviewWorld.begin(parent.creator(), candidate, query, parent.config(), this);
            } catch (Exception error) {
                WYWFClient.LOGGER.warn("Could not verify seed {}", candidate.seed, error);
                review.recordVerification(candidate, new VerificationSummary(
                        VerificationSummary.Status.ERROR, 0, 0, 0, List.of(), error.toString()));
                next++;
            }
        }
    }

    @Override public void onClose() {
        stopping = true;
        cancel.active = false;
        if (session != null) {
            session.cancel();
            session = null;
        }
        if (PreviewWorld.active() == null) returnToResults();
    }

    private void returnToResults() {
        if (historyChanged) {
            parent.saveVerifiedHistory();
            historyChanged = false;
        }
        minecraft.setScreenAndShow(parent);
    }

    @Override public void extractRenderState(GuiGraphicsExtractor g, int mouseX, int mouseY, float tick) {
        MenuBands.draw(g, width, height, 45, height - 40);
        int center = width / 2;
        g.centeredText(font, title, center, 12, 0xFFFFFFFF);
        g.centeredText(font, ResultTexts.label("verify_progress", next, candidates.size()), center, height / 2 - 18, 0xFFFFFFFF);
        if (stopping || next == candidates.size() || session == null && PreviewWorld.active() != null) {
            g.centeredText(font, ResultTexts.label("verify_wait"), center, height / 2, 0xFFD5DEE3);
        } else {
            g.centeredText(font, MapTexts.label("verify_seed", candidates.get(next).seed), center, height / 2, 0xFFD5DEE3);
            g.centeredText(font, session == null || session.summary().status() == VerificationSummary.Status.BOOTING
                    ? ResultTexts.label("verify_start") : MapTexts.label("verify_working"), center, height / 2 + 13, 0xFFD5DEE3);
        }
        super.extractRenderState(g, mouseX, mouseY, tick);
    }
}
