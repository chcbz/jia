package cn.jia.core.context;

public class EsContextHolder {
    private static final ThreadLocal<EsContext> CONTEXT = new ThreadLocal<>();

    public static EsContext getContext() {
        EsContext esContext = CONTEXT.get();
        if (esContext == null) {
            esContext = new EsContext();
            CONTEXT.set(esContext);
        }
        return esContext;
    }
}
