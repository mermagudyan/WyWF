package com.wywf.core;

import java.util.List;
import java.util.concurrent.ThreadLocalRandom;

public final class WorldSplashes {
    public static final List<String> VALUES=List.of(
            "Thanks for using WyWF", "Tell your friends about WyWF", "See you on Discord", "42",
            "No ads just the charts", "Do you like 2Pac", "What about coffee", "One more seed",
            "The cake is in another chunk", "Terraria called it wants its caves back", "Digging the algorithm",
            "Trust the compass", "A village worth the wait", "Certified block moment", "Plot twist it is a mushroom island",
            "Somewhere a creeper is waiting", "Bring a pickaxe", "Less searching more crafting", "You found your place",
            "This seed has main character energy", "The grass is greener in this chunk", "Now with actual terrain",
            "You might cry when you see this", "Your next adventure starts here");
    public static String choose(){return VALUES.get(ThreadLocalRandom.current().nextInt(VALUES.size()));}
    private WorldSplashes(){}
}
