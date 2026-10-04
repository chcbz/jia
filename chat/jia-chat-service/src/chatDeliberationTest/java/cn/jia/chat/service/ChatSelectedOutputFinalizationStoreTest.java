package cn.jia.chat.service;

import cn.jia.agent.service.SelectedOutputFinalizationDigest;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import java.sql.ResultSet;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

class ChatSelectedOutputFinalizationStoreTest {
    static final class MemoryJdbc extends JdbcTemplate {
        Object[] operation;final List<Object[]> items=new ArrayList<>();int writes;
        @Override public int update(String sql,Object...args){
            writes++;
            if(sql.contains("INSERT INTO chat_selected_output_finalization_item")){items.add(args.clone());return 1;}
            if(sql.contains("INSERT INTO chat_selected_output_finalization")){
                operation=new Object[]{args[0],args[4],args[5],args[6],args[7],args[8],args[9],args[10],"pending","PROMOTING",1L,
                        null,null,args[11],args[12],null,false};return 1;
            }
            throw new AssertionError(sql);
        }
        @Override public <T> List<T> query(String sql,RowMapper<T> mapper,Object...args){
            try {
                if(sql.contains("FROM agent_task_meta")){
                    ResultSet rs=mock(ResultSet.class);when(rs.getString(1)).thenReturn("assigned");when(rs.getLong(2)).thenReturn(7L);
                    return List.of(mapper.mapRow(rs,0));
                }
                if(!Arrays.equals(Arrays.copyOf(args,3),new Object[]{"0","owner","client"}))return List.of();
                if(sql.contains("FROM chat_selected_output_finalization_item")){
                    List<T> result=new ArrayList<>();for(Object[] item:items){
                        Object[] values=new Object[]{item[4],item[5],item[6],item[7],item[8],item[9],item[10],item[11],item[12],item[13],item[14],item[15]};
                        result.add(mapper.mapRow(row(values),result.size()));
                    }return result;
                }
                if(operation==null || !args[3].equals(operation[1]) || !(args[4].equals(operation[0]) || args[4].equals(operation[2])))return List.of();
                return List.of(mapper.mapRow(row(operation),0));
            }catch(RuntimeException failure){throw failure;}catch(Exception failure){throw new AssertionError(failure);}
        }
        ResultSet row(Object[] values)throws Exception{
            ResultSet rs=mock(ResultSet.class);
            when(rs.getString(anyInt())).thenAnswer(i->{Object v=values[(Integer)i.getArgument(0)-1];return v==null?null:v.toString();});
            when(rs.getLong(anyInt())).thenAnswer(i->((Number)values[(Integer)i.getArgument(0)-1]).longValue());
            when(rs.getInt(anyInt())).thenAnswer(i->((Number)values[(Integer)i.getArgument(0)-1]).intValue());
            when(rs.getBoolean(anyInt())).thenAnswer(i->values[(Integer)i.getArgument(0)-1]);return rs;
        }
    }
    private static List<ChatSelectedOutputFinalizationStore.Selection> selections(){
        return List.of(new ChatSelectedOutputFinalizationStore.Selection("req-media","step","out","a".repeat(64),"Image","final"),
                new ChatSelectedOutputFinalizationStore.Selection("req-text",null,null,"b".repeat(64),"Text","final",
                        new SelectedOutputFinalizationDigest.MessageSource("turn","9223372036854775807","snapshot","sha256:"+"c".repeat(64))));
    }
    private static ChatSelectedOutputFinalizationStore.Operation create(ChatSelectedOutputFinalizationStore store){
        return store.create("0","owner","client","operation","task","original-key","d".repeat(64),"42",7,3,"accept",selections());
    }
    @Test void actualStoreWritesAndReconstructsOrderedSourceUnionAcrossNewInstancesAndOriginalKeyReplay(){
        MemoryJdbc jdbc=new MemoryJdbc();var store=new ChatSelectedOutputFinalizationStore(jdbc);var created=create(store);
        assertEquals(selections(),created.selections());assertEquals(3,jdbc.writes);
        assertNull(jdbc.items.get(1)[6]);assertNull(jdbc.items.get(1)[7]);assertEquals("COMPLETED_MESSAGE",jdbc.items.get(1)[11]);
        var restarted=new ChatSelectedOutputFinalizationStore(jdbc);
        assertEquals(created,restarted.findByKey("0","owner","client","task","original-key"));
        assertEquals(created,restarted.findByOperation("0","owner","client","task","operation"));
        assertEquals(created,create(restarted));assertEquals(3,jdbc.writes);
        assertThrows(ChatSelectedOutputFinalizationStore.Conflict.class,()->restarted.create("0","owner","client","operation","task",
                "original-key","e".repeat(64),"42",7,3,"accept",selections()));assertEquals(3,jdbc.writes);
        assertNull(restarted.findByKey("0","another","client","task","original-key"));
    }
    @Test void persistedUnknownOrHybridSourceAndMissingTextRefsFailClosedInsteadOfDroppingText(){
        for(String kind:List.of("unknown","OUTPUT","hybrid","missing")){
            MemoryJdbc jdbc=new MemoryJdbc();create(new ChatSelectedOutputFinalizationStore(jdbc));
            var row=jdbc.items.get(1);
            if("hybrid".equals(kind))row[6]="fake-step";
            else if("missing".equals(kind))row[13]=null;
            else row[11]=kind;
            assertThrows(ChatSelectedOutputFinalizationStore.Persistence.class,()->new ChatSelectedOutputFinalizationStore(jdbc)
                    .findByKey("0","owner","client","task","original-key"));assertEquals(3,jdbc.writes);
        }
    }
}
