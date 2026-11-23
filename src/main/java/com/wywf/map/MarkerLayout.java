package com.wywf.map;

import java.util.*;
import java.util.function.Predicate;

public final class MarkerLayout {
    private static String key(StructureLayers.Marker m){return m.id()+":"+m.x()+":"+m.z();}
    public static List<StructureLayers.Marker> select(List<StructureLayers.Marker> input,List<StructureLayers.Marker> previous,MapViewport view,Predicate<String> requested){
        var retained=new HashSet<String>();
        previous.forEach(m->retained.add(key(m)));
        var markers=new ArrayList<>(input);
        markers.sort(Comparator.<StructureLayers.Marker>comparingInt(m -> m.queryMatched() ? 0 : m.verified() ? 1 : 2)
                .thenComparingInt(m->requested.test(m.id())?0:1)
                .thenComparingInt(m->retained.contains(key(m))?0:1).thenComparingDouble(m->Math.hypot(m.x()-view.x(),m.z()-view.z()))
                .thenComparing(StructureLayers.Marker::id).thenComparingInt(StructureLayers.Marker::x).thenComparingInt(StructureLayers.Marker::z));
        var spacing=new MarkerSpacing(20);
        var shown=new ArrayList<StructureLayers.Marker>();
        for (var marker : markers) {
            double x = view.pixelX(marker.x()), z = view.pixelZ(marker.z());
            if (x < 0 || x > view.width() || z < 0 || z > view.height()) continue;
            if (spacing.add((int) x, (int) z, marker.queryMatched())) shown.add(marker);
        }
        return List.copyOf(shown);
    }
    private MarkerLayout(){}
}
