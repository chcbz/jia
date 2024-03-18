package cn.jia.core.context;

public class EsContextHolder {
    private static final ThreadLocal<EsContext> CONTEXT = new ThreadLocal<>();

    public static EsContext getContext() {
        return CONTEXT.get();
    }
}
