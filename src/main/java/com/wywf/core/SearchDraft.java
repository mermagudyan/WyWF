package com.wywf.core;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

// A distance sketch compiles into existing query syntax without claiming terrain or directions
public final class SearchDraft {
    private final Set<Target> lockedMarkers = new HashSet<>();

    public boolean markerLocked(Target target) {
        return lockedMarkers.contains(target);
    }

    public void toggleMarkerLock(Target target) {
        if (!lockedMarkers.add(target)) lockedMarkers.remove(target);
    }
    public record Target(String canonical, KeywordDictionary.Category category, String english, String russian, int instance) {
        public Target(String canonical, KeywordDictionary.Category category, String english, String russian) {
            this(canonical, category, english, russian, 0);
        }
        private static final Map<String, Target> CATALOG = loadCatalog();
        private static final Map<KeywordDictionary.Lang, Map<String, String>> QUERY_WORDS = new ConcurrentHashMap<>();
        public static final Target VILLAGE = CATALOG.get("minecraft:village");
        public static final Target STRONGHOLD = CATALOG.get("minecraft:stronghold");
        public static final Target CHERRY = CATALOG.get("minecraft:cherry_grove");
        public static final Target FOREST = CATALOG.get("minecraft:forest");
        public static final Target DESERT = CATALOG.get("minecraft:desert");
        public static final Target MUSHROOM = CATALOG.get("minecraft:mushroom_fields");
        public static final Target TEMPLE = CATALOG.get("minecraft:desert_pyramid");
        public static final Target PORTAL = CATALOG.get("minecraft:ruined_portal");

        public Target {
            Objects.requireNonNull(canonical);
            Objects.requireNonNull(category);
            Objects.requireNonNull(english);
        }

        public String phrase(KeywordDictionary.Lang language) {
            return language == KeywordDictionary.Lang.RU && russian != null ? russian : english;
        }

        public String queryPhrase(KeywordDictionary.Lang language) {
            return QUERY_WORDS.computeIfAbsent(language, lang -> {
                QueryParser parser = new QueryParser(new KeywordDictionary(lang));
                Map<String, String> words = new HashMap<>();
                for (Target target : values()) {
                    String phrase = target.phrase(lang);
                    ParsedQuery parsed = parser.parse(phrase);
                    boolean exact = !parsed.terms().isEmpty()
                            && parsed.terms().getFirst().canonical.equals(target.canonical());
                    words.put(target.canonical(), exact ? phrase : target.canonical());
                }
                return Map.copyOf(words);
            }).getOrDefault(canonical, canonical);
        }

        public static Target[] values() {
            return CATALOG.values().toArray(Target[]::new);
        }

        private static Map<String, Target> loadCatalog() {
            Map<String, String> en = aliases("en_us");
            Map<String, String> ru = aliases("ru_ru");
            Map<String, Target> targets = new TreeMap<>();
            for (Map.Entry<String, String> entry : en.entrySet()) {
                String key = entry.getKey();
                String[] parts = key.split("\\.", 4);
                if (parts.length != 4) continue;
                KeywordDictionary.Category category = parts[2].equals("biome")
                        ? KeywordDictionary.Category.BIOME : KeywordDictionary.Category.STRUCTURE;
                targets.put(parts[3], new Target(parts[3], category, entry.getValue(), ru.get(key)));
            }
            return Collections.unmodifiableMap(targets);
        }

        private static Map<String, String> aliases(String language) {
            String path = "/assets/wywf/data/" + language + ".json";
            try (var stream = SearchDraft.class.getResourceAsStream(path)) {
                if (stream == null) throw new IllegalStateException("Missing dictionary " + language);
                JsonObject json = new Gson().fromJson(new InputStreamReader(stream, StandardCharsets.UTF_8), JsonObject.class);
                Map<String, String> aliases = new HashMap<>();
                for (var entry : json.entrySet()) {
                    if ((entry.getKey().startsWith("wywf.synonym.biome.")
                            || entry.getKey().startsWith("wywf.synonym.structure.")) && entry.getValue().isJsonArray()
                            && !entry.getValue().getAsJsonArray().isEmpty()) {
                        aliases.put(entry.getKey(), entry.getValue().getAsJsonArray().get(0).getAsString());
                    }
                }
                return aliases;
            } catch (java.io.IOException error) {
                throw new IllegalStateException("Cannot read constructor catalog", error);
            }
        }
    }

    private record PresetTarget(String name, int radius, double angle) {}

    public enum Preset {
        VILLAGE(SearchConfig.SearchCenter.SPAWN, pick("village", 64)),
        CHERRY_GROVE(SearchConfig.SearchCenter.SPAWN, pick("cherry_grove", 128)),
        VILLAGE_AND_CHERRY(SearchConfig.SearchCenter.SPAWN, pick("village", 128, 0), pick("cherry_grove", 128, 2.4)),
        STRONGHOLD_START(SearchConfig.SearchCenter.SPAWN, pick("village", 128, .5), pick("stronghold", 456, 3.2)),
        DESERT_EXPLORER(SearchConfig.SearchCenter.SPAWN, pick("desert", 64, 0), pick("desert_pyramid", 128, 2.4), pick("ruined_portal", 128, 4.6)),
        FARM_START(SearchConfig.SearchCenter.SPAWN, pick("plains", 128), pick("village", 256), pick("ruined_portal", 448)),
        WARM_COAST(SearchConfig.SearchCenter.SPAWN, pick("warm_ocean", 128), pick("shipwreck", 128)),
        MONUMENT_COAST(SearchConfig.SearchCenter.SPAWN, pick("ocean_monument", 128), pick("shipwreck", 128)),
        SNOWY_START(SearchConfig.SearchCenter.SPAWN, pick("snowy_plains", 64), pick("village", 128), pick("frozen_peaks", 256)),
        MOUNTAIN_MEADOW(SearchConfig.SearchCenter.SPAWN, pick("meadow", 128), pick("stony_peaks", 128), pick("village", 64)),
        ANCIENT_DEPTHS(SearchConfig.SearchCenter.SPAWN, pick("deep_dark", 64), pick("ancient_city", 128)),
        TRIAL_START(SearchConfig.SearchCenter.SPAWN, pick("trial_chambers", 64), pick("village", 128)),
        PALE_EXPLORER(SearchConfig.SearchCenter.SPAWN, pick("pale_garden", 64), pick("mansion", 128)),
        SWAMP_OUTPOST(SearchConfig.SearchCenter.SPAWN, pick("swamp", 64), pick("swamp_hut", 128)),
        MUSHROOM_COAST(SearchConfig.SearchCenter.SPAWN, pick("mushroom_fields", 256), pick("shipwreck", 256)),
        BADLANDS_PORTAL(SearchConfig.SearchCenter.SPAWN, pick("badlands", 256), pick("ruined_portal", 64)),
        CAMP_AND_VILLAGE(SearchConfig.SearchCenter.SPAWN, pick("abandoned_camp", 256), pick("village", 64)),
        NETHER_FORTRESS(SearchConfig.SearchCenter.SPAWN, pick("ruined_portal", 64), pick("fortress", 64));

        private final SearchConfig.SearchCenter searchCenter;
        private final PresetTarget[] targets;

        Preset(SearchConfig.SearchCenter searchCenter, PresetTarget... targets) {
            this.searchCenter = searchCenter;
            this.targets = targets;
        }

        public SearchConfig.SearchCenter searchCenter() { return searchCenter; }

        private static PresetTarget pick(String name, int radius) {
                        return pick(name, radius, Double.NaN);
        }

        private static PresetTarget pick(String name, int radius, double angle) {
            return new PresetTarget("minecraft:" + name, radius, angle);
        }
    }

    public static boolean supportsModifier(KeywordDictionary.Category category, Modifier modifier) {
        return switch (modifier) {
            case AT, IN, NEAR, FAR, NEVER, DEFAULT -> true;
            case UNDER -> category == KeywordDictionary.Category.BIOME;
            case ONLY, SOME, EXACTLY, AT_LEAST, AT_MOST -> category == KeywordDictionary.Category.STRUCTURE;
            default -> false;
        };
    }

    public record Placement(Target target, double x, double y, int minRadius, int radius, Modifier countMode, int quantity, LootConstraint loot, boolean pinned, StructureTags tags) {
        public Placement(Target t,double x,double y,int min,int max,Modifier m,int n,LootConstraint loot,boolean pinned){this(t,x,y,min,max,m,n,loot,pinned,StructureTags.NONE);}
        public Placement(Target target,double x,double y,int minRadius,int radius,Modifier mode,int quantity,LootConstraint loot){this(target,x,y,minRadius,radius,mode,quantity,loot,false);}
        public Placement(Target target, double x, double y, int minRadius, int radius, Modifier mode, int quantity) {
            this(target, x, y, minRadius, radius, mode, quantity, null);
        }
        public Placement(Target target, double x, double y, int minRadius, int radius) { this(target, x, y, minRadius, radius, null, 1); }
        public Placement {
            Objects.requireNonNull(target);
            Objects.requireNonNull(tags);
            if(!tags.supports(target.canonical()))throw new IllegalArgumentException("Unsupported structure tags");
            if (loot != null && (countMode != null && (countMode.counted() || countMode == Modifier.NEVER || countMode == Modifier.ONLY || countMode == Modifier.SOME || countMode == Modifier.UNDER)
                    || target.category() == KeywordDictionary.Category.BIOME && loot.items().isEmpty()))
                throw new IllegalArgumentException("Loot requires a positive distance or presence condition");
            if (countMode != null && countMode.counted() && target.category() != KeywordDictionary.Category.STRUCTURE)
                throw new IllegalArgumentException("Counts apply to structures");
            if (countMode != null && !countMode.counted() && !supportsModifier(target.category(), countMode))
                throw new IllegalArgumentException("Unsupported marker modifier");
            if (quantity < 0 || quantity > 64) throw new IllegalArgumentException("Invalid quantity");
            if (!Double.isFinite(x) || !Double.isFinite(y)) throw new IllegalArgumentException("Non-finite position");
            if (radius < 64 || radius > 4096 || minRadius < 0 || minRadius > radius) {
                throw new IllegalArgumentException("Invalid distance interval");
            }
        }
    }

    private record Snapshot(List<Placement> placements, QueryLogic.Op logic, SearchConfig.SearchCenter requiredCenter) {}

    private final List<Placement> placements = new ArrayList<>();
    private final List<Snapshot> history = new ArrayList<>();
    private QueryLogic.Op logic = QueryLogic.Op.AND;
    private SearchConfig.SearchCenter requiredCenter;
    private Snapshot moveStart;
    private int nextInstance = 1;

    public QueryLogic.Op logic() { return logic; }
    public SearchConfig.SearchCenter requiredSearchCenter() { return requiredCenter; }

    public void logic(QueryLogic.Op value) {
        Objects.requireNonNull(value);
        if (logic == value) return;
        endMove();
        remember();
        logic = value;
    }

    public List<Placement> placements() { return List.copyOf(placements); }
    public boolean isEmpty() { return placements.isEmpty(); }
    public boolean canUndo() { return !history.isEmpty(); }
    public Optional<Placement> placement(Target target) {
        return placements.stream().filter(p -> p.target().equals(target)).findFirst();
    }

    public void addPlacement(Target target, double x, double y) {
        String canonical = target.canonical();
        long count = placements.stream().filter(p -> p.target().canonical().equals(canonical)).count();
        if (count >= 64) return;
        if (placement(target).isPresent()) {
            target = new Target(target.canonical(), target.category(), target.english(), target.russian(), nextInstance++);
        }
        place(target, x, y);
    }

    public void place(Target target, double x, double y) {
        Placement next = positioned(target, x, y, 0);
        endMove();
        remember();
        replace(next);
    }

    public void beginMove(Target target) {
        endMove();
        if (placement(target).isPresent()) moveStart = snapshot();
    }

    public void move(Target target, double x, double y) {
        if (moveStart == null) throw new IllegalStateException("Move was not started");
        Placement original = moveStart.placements().stream().filter(p -> p.target().equals(target)).findFirst().orElseThrow();
        replace(positioned(target, x, y, original.minRadius()));
    }

    public void endMove() {
        if (moveStart == null) return;
        if (!moveStart.placements().equals(placements)) remember(moveStart);
        moveStart = null;
    }

    public void cancelMove() {
        if (moveStart == null) return;
        restore(moveStart);
        moveStart = null;
    }

    public void configure(Target target, int minimum, int maximum) {
        Placement old = placement(target).orElseThrow();
        configure(target, minimum, maximum, old.countMode(), old.quantity());
    }

    public void configure(Target target, int minimum, int maximum, Modifier mode, int quantity) {
        Placement old = placement(target).orElseThrow();
        configure(target,minimum,maximum,mode,quantity,old.loot(),old.pinned());
    }

    public void configure(Target target,int minimum,int maximum,Modifier mode,int quantity,LootConstraint loot,boolean pinned) {
        configure(target,minimum,maximum,mode,quantity,loot,pinned,placement(target).orElseThrow().tags());
    }
    public void configure(Target target,int minimum,int maximum,Modifier mode,int quantity,LootConstraint loot,boolean pinned,StructureTags tags) {
        Placement old = placement(target).orElseThrow();
        double distance = Math.log(maximum / 64.0) / Math.log(64);
        double angle = Math.atan2(old.y(), old.x());
        Placement next = new Placement(target, Math.cos(angle) * distance, Math.sin(angle) * distance, mode == null ? minimum : 0, maximum, mode, quantity, loot,pinned,tags);
        endMove();
        if (next.equals(old)) return;
        remember();
        replace(next);
    }

    public void loot(Target target, LootConstraint value) {
        endMove();
        var old = placement(target).orElseThrow();
        var next = new Placement(target, old.x(), old.y(), old.minRadius(), old.radius(), old.countMode(), old.quantity(), value,old.pinned(),old.tags());
        if (next.equals(old)) return;
        remember();
        replace(next);
    }

    public void pin(Target target,boolean value){
        endMove();
        var p=placement(target).orElseThrow();if(p.pinned()==value)return;
        remember();
        replace(new Placement(target,p.x(),p.y(),p.minRadius(),p.radius(),p.countMode(),p.quantity(),p.loot(),value,p.tags()));
    }
    public void remove(Target target) {
        endMove();
        if (placement(target).isEmpty()) return;
        remember();
        placements.removeIf(p -> p.target().equals(target));
        if (placements.isEmpty()) requiredCenter = null;
    }

    public void clear() {
        endMove();
        if (placements.isEmpty()) return;
        remember();
        placements.clear();
        requiredCenter = null;
    }

    public void undo() {
        endMove();
        if (!canUndo()) return;
        restore(history.removeLast());
    }

    public void preset(Preset preset) {
        endMove();
        remember();
        placements.clear();
        requiredCenter = preset.searchCenter();
        for (int i = 0; i < preset.targets.length; i++) {
            var target = preset.targets[i];
                        double angle = Double.isNaN(target.angle()) ? i * Math.TAU / preset.targets.length : target.angle();
            add(Target.CATALOG.get(target.name()), target.radius(), angle);
        }
    }

    public String compile(KeywordDictionary.Lang language) {
        Objects.requireNonNull(language);
        String between = language == KeywordDictionary.Lang.RU ? "между" : "between";
        if (placements.isEmpty()) return "";
        String rendered = QueryLogic.render(query(language).logic(), term -> (term.pinned ? "keep " : "")+(term.modifier.counted()
                ? term.modifier.name().toLowerCase(Locale.ROOT) + " " + term.someCount + " within " + term.betweenMax
                : term.modifier == Modifier.BETWEEN
                        ? between + " " + term.betweenMin + ".." + term.betweenMax
                        : term.modifier == Modifier.SOME ? "some " + term.someCount
                        : term.modifier == Modifier.DEFAULT ? "" : term.modifier.name().toLowerCase(Locale.ROOT))
                + " " + placements.stream().map(Placement::target).filter(t -> t.canonical().equals(term.canonical)).findFirst().orElseThrow().queryPhrase(language) + term.tags.suffix() + (term.loot == null ? "" : term.loot.suffix()));
        return placements.size() == 1 ? "(" + rendered.strip() + ")" : rendered;
    }

    public ParsedQuery query(KeywordDictionary.Lang language) {
        List<ParsedQuery.Term> terms = terms();
        if (terms.isEmpty()) return new ParsedQuery("", terms);
        QueryLogic.Node tree = new QueryLogic.Leaf(terms.getFirst());
        for (int i = 1; i < terms.size(); i++) tree = new QueryLogic.Binary(logic, tree, new QueryLogic.Leaf(terms.get(i)));
        return new ParsedQuery(QueryLogic.render(tree), terms, List.of(), tree, null);
    }

    private List<ParsedQuery.Term> terms() {
        if (logic != QueryLogic.Op.AND) return placements.stream().map(SearchDraft::term).toList();
        var byTarget = placements.stream().filter(SearchDraft::plainRadius)
                .collect(Collectors.groupingBy(p -> p.target().canonical()));
        Set<String> emitted = new HashSet<>();
        List<ParsedQuery.Term> terms = new ArrayList<>();
        for (var placement : placements) {
            String canonical = placement.target().canonical();
            var group = byTarget.get(canonical);
            if (!plainRadius(placement) || group.size() == 1) {
                terms.add(term(placement));
            } else if (emitted.add(canonical)) {
                var ordered = group.stream().sorted(Comparator.comparingInt(Placement::radius)).toList();
                boolean pinned = false;
                for (int i = 0; i < ordered.size(); i++) {
                    var marker = ordered.get(i);
                    pinned |= marker.pinned();
                    if (i + 1 < ordered.size() && marker.radius() == ordered.get(i + 1).radius()) continue;
                    terms.add(new ParsedQuery.Term(canonical, marker.target().category(), Modifier.AT_LEAST,
                            i + 1, 0, marker.radius()).withPinned(pinned));
                }
            }
        }
        return terms;
    }

    private static boolean plainRadius(Placement placement) {
        return placement.countMode() == null && placement.minRadius() == 0
                && placement.loot() == null && placement.tags().empty();
    }

    private static ParsedQuery.Term term(Placement placement) {
        Modifier modifier = placement.countMode() == null ? Modifier.BETWEEN : placement.countMode();
        int radius = modifier == Modifier.BETWEEN || modifier.counted() ? placement.radius() : 0;
        return new ParsedQuery.Term(placement.target().canonical(), placement.target().category(), modifier,
                placement.quantity(), placement.minRadius(), radius, placement.loot(), placement.pinned(), placement.tags());
    }

    private Placement positioned(Target target, double x, double y, int minimum) {
        if (!Double.isFinite(x) || !Double.isFinite(y)) throw new IllegalArgumentException("Non-finite position");
        double length = Math.hypot(x, y);
        if (length > 1) { x /= length; y /= length; }
        int radius = Math.min(4096, (int) Math.ceil(64 * Math.pow(64, Math.min(1, Math.hypot(x, y))) / 16) * 16);
        var previous = placement(target).orElse(null);
        return new Placement(target, x, y, Math.min(minimum, radius), radius,
                previous == null ? null : previous.countMode(), previous == null ? 1 : previous.quantity(), previous == null ? null : previous.loot(),previous != null && previous.pinned(),previous==null?StructureTags.NONE:previous.tags());
    }

    private void replace(Placement next) {
        for (int i = 0; i < placements.size(); i++) {
            if (placements.get(i).target().equals(next.target())) {
                placements.set(i, next);
                return;
            }
        }
        placements.add(next);
    }

    private void add(Target target, int radius, double angle) {
        double distance = Math.log(radius / 64.0) / Math.log(64);
        placements.add(new Placement(target, Math.cos(angle) * distance, Math.sin(angle) * distance, 0, radius));
    }

    private Snapshot snapshot() {
        return new Snapshot(List.copyOf(placements), logic, requiredCenter);
    }

    private void restore(Snapshot snapshot) {
        placements.clear();
        placements.addAll(snapshot.placements());
        logic = snapshot.logic();
        requiredCenter = snapshot.requiredCenter();
    }

    private void remember() { remember(snapshot()); }

    private void remember(Snapshot snapshot) {
        if (history.size() == 32) history.removeFirst();
        history.add(snapshot);
    }
}
