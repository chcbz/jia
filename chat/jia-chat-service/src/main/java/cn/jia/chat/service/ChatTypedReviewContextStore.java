package cn.jia.chat.service;

import cn.jia.chat.deliberation.ChatTypedDeliberationStore;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.Objects;

/** Immutable admission snapshot. No upsert, latest-batch fallback, or write-on-GET recovery. */
@Repository
@ConditionalOnProperty(prefix="chat.delivery-review",name="enabled",havingValue="true")
public class ChatTypedReviewContextStore {
    private final JdbcTemplate jdbc;
    public ChatTypedReviewContextStore(JdbcTemplate jdbc) { this.jdbc=Objects.requireNonNull(jdbc); }
    @Transactional(propagation=Propagation.MANDATORY,rollbackFor=Exception.class)
    public void insert(ChatTypedDeliberationStore.Scope scope,String request,long revision,
            ChatDeliveryRevisionContract.Basis basis,long now) {
        if (revision<1 || now<0) throw new IllegalArgumentException("DELIVERY_REVIEW_CONTEXT_INVALID");
        ChatDeliveryReviewContract.id(request,100);
        if (basis!=null && !request.equals(basis.sourceRequestId())) throw new IllegalArgumentException("DELIVERY_REVIEW_CONTEXT_INVALID");
        String json=ChatDeliveryRevisionContract.json(basis);
        int rows=jdbc.update("""
            INSERT INTO chat_typed_review_context
             (tenant_id,owner_jiacn,client_id,request_id,request_revision,basis_json,basis_sha256,created_at)
             VALUES (?,?,?,?,?,?,?,?)
            """,scope.tenantId(),scope.ownerJiacn(),scope.clientId(),request,revision,json,ChatDeliveryReviewContract.sha(json),now);
        if(rows!=1)throw storage();
    }
    @Transactional(readOnly=true)
    public ChatDeliveryRevisionContract.Basis require(ChatTypedDeliberationStore.Scope scope,String request,long revision) {
        if(revision<1)throw storage(); ChatDeliveryReviewContract.id(request,100);
        record Row(String json,String sha,String message) { }
        var rows=jdbc.query("""
            SELECT c.basis_json,c.basis_sha256,CAST(r.user_message_id AS CHAR) FROM chat_typed_review_context c
             JOIN chat_request r ON BINARY r.tenant_id=BINARY c.tenant_id AND BINARY r.owner_jiacn=BINARY c.owner_jiacn
              AND BINARY r.client_id=BINARY c.client_id AND BINARY r.request_id=BINARY c.request_id AND r.request_revision=c.request_revision
             WHERE BINARY c.tenant_id=BINARY ? AND BINARY c.owner_jiacn=BINARY ? AND BINARY c.client_id=BINARY ?
              AND BINARY c.request_id=BINARY ? AND c.request_revision=? AND BINARY r.conversation_id=BINARY ? AND r.conversation_generation=?
            """,(r,n)->new Row(r.getString(1),r.getString(2),r.getString(3)),scope.tenantId(),scope.ownerJiacn(),scope.clientId(),
                request,revision,scope.conversationId(),scope.conversationGeneration());
        if(rows.size()!=1)throw storage();
        Row row=rows.getFirst();
        if(row.json()==null || !ChatDeliveryReviewContract.sha(row.json()).equals(row.sha()))throw storage();
        try {
            var basis=ChatDeliveryRevisionContract.basisJson(row.json());
            if(basis!=null && (!request.equals(basis.sourceRequestId()) || !row.message().equals(basis.sourceMessageId())))throw storage();
            return basis;
        } catch(IllegalArgumentException invalid) {throw storage();}
    }
    private static IllegalStateException storage() {return new IllegalStateException("DELIVERY_REVIEW_CONTEXT_UNAVAILABLE");}
}
