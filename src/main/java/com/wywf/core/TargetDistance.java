package com.wywf.core;

public final class TargetDistance {
    public static final int PLACEMENT_MARGIN=512;
    public static final int MAX_RADIUS_BLOCKS = 4096;
    public static final int MAX_RADIUS_CHUNKS = MAX_RADIUS_BLOCKS / 16;

    static void checkRange(int minimum, int maximum) {
        if (minimum < 0 || maximum > MAX_RADIUS_BLOCKS)
            throw new IllegalArgumentException("Distance must be between 0 and " + MAX_RADIUS_BLOCKS + " blocks");
        if (maximum < minimum)
            throw new IllegalArgumentException("The maximum distance must be at least the minimum");
    }
    public static boolean boundary(ParsedQuery query,String target){return true;}
    public static double blocks(ParsedQuery query,String target,int cx,int cz,int x,int z,int minX,int minZ,int maxX,int maxZ){
        return footprint(cx,cz,minX,minZ,maxX,maxZ);
    }
    public static double footprint(int cx,int cz,int minX,int minZ,int maxX,int maxZ){
        return Math.hypot(Math.max(0,Math.max((double)minX-cx,(double)cx-maxX)),Math.max(0,Math.max((double)minZ-cz,(double)cz-maxZ)));
    }
    public static int radius(ParsedQuery.Term t,int defaultBlocks){return switch(t.modifier){
        case BETWEEN->t.betweenMax;case AT->128;case NEAR->200;case IN->64;case FAR->1000;
        case ONLY->500;case SOME->t.someCount>=5 ? 640 : 320;
        case EXACTLY,AT_LEAST,AT_MOST->t.betweenMax>0 ? t.betweenMax : 500;default->defaultBlocks;};}
    public static boolean deferred(ParsedQuery.Term t){return t.category==KeywordDictionary.Category.STRUCTURE
            &&(t.modifier==Modifier.NEVER||t.modifier==Modifier.AT_MOST||t.modifier==Modifier.EXACTLY&&t.someCount==0);}
    private TargetDistance(){}
}
