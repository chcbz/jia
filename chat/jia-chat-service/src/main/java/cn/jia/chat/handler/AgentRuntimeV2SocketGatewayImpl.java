package cn.jia.chat.handler;

import cn.jia.agent.entity.*;
import cn.jia.agent.service.AgentRuntimeV2SocketGateway;
import cn.jia.agent.service.impl.AgentRuntimeV2DispatchServiceImpl;
import jakarta.annotation.PreDestroy;
import java.time.Clock;
import java.util.concurrent.*;

/** Qualified V2 gateway, backed solely by committed registration routes, not the V1 registry. */
public final class AgentRuntimeV2SocketGatewayImpl implements AgentRuntimeV2SocketGateway {
    private final AgentRuntimeV2WebSocketAdapter adapter;
    private final Clock clock;
    private final ExecutorService sends=new ThreadPoolExecutor(1,4,30,TimeUnit.SECONDS,new ArrayBlockingQueue<>(100),task->{Thread t=new Thread(task,"runtime-v2-raw-send");t.setDaemon(true);return t;},new ThreadPoolExecutor.AbortPolicy());
    private final ExecutorService closes=new ThreadPoolExecutor(1,1,0,TimeUnit.SECONDS,new ArrayBlockingQueue<>(100),task->{Thread t=new Thread(task,"runtime-v2-route-close");t.setDaemon(true);return t;},new ThreadPoolExecutor.AbortPolicy());
    public AgentRuntimeV2SocketGatewayImpl(AgentRuntimeV2WebSocketAdapter adapter,Clock clock){this.adapter=adapter;this.clock=clock;}
    public String ownerInstanceId(){return adapter.ownerInstanceId();}
    public boolean current(AgentRuntimeV2SocketTuple tuple){return adapter.hasDispatchRoute(tuple);}
    public boolean send(AgentRuntimeV2SocketTuple tuple,byte[] original,long deadline){
        AgentRuntimeV2DispatchServiceImpl.outside();byte[] bytes=original.clone();AgentRuntimeV2DispatchCodec.decode(bytes);
        long remaining=Math.min(5000,Math.subtractExact(deadline,clock.millis()));if(remaining<=0)return false;
        Future<Boolean> send;try{send=sends.submit(()->adapter.rawDispatch(tuple,bytes,deadline,clock));}catch(RejectedExecutionException full){return false;}
        try{return send.get(remaining,TimeUnit.MILLISECONDS);}
        catch(InterruptedException interrupted){Thread.currentThread().interrupt();send.cancel(true);retire(tuple);return false;}
        catch(TimeoutException timeout){send.cancel(true);retire(tuple);return false;}
        catch(ExecutionException failure){return false;}
    }
    private void retire(AgentRuntimeV2SocketTuple tuple){Runnable cleanup=adapter.retireDispatchRoute(tuple);try{closes.execute(cleanup);}catch(RejectedExecutionException full){/* already fenced; bounded cleanup cannot extend the caller timeout */}}
    @PreDestroy public void close(){sends.shutdownNow();closes.shutdownNow();}
}
