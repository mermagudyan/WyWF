package com.wywf.core;

import java.util.concurrent.CancellationException;
import java.util.function.BooleanSupplier;

public final class SearchCancellation {
    public interface Scope extends AutoCloseable {void close();}
    private static final ThreadLocal<BooleanSupplier> TOKEN=new ThreadLocal<>();
    public static Scope bind(BooleanSupplier cancelled){
        var old=TOKEN.get();
        TOKEN.set(cancelled);
        return ()->{if(old==null)TOKEN.remove();else TOKEN.set(old);};
    }
    public static void checkpoint(){var token=TOKEN.get();if(token!=null&&token.getAsBoolean())throw new CancellationException("Search verification cancelled");}
    private SearchCancellation(){}
}
