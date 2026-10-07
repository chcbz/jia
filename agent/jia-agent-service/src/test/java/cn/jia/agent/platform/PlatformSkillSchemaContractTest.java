package cn.jia.agent.platform;

import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
class PlatformSkillSchemaContractTest {
    static final String TABLE="agent_platform_skill_installation";
    static Map<String,Object> properties(){return Map.of("ENGINE","InnoDB","TABLE_COLLATION","utf8mb4_0900_bin");}
    static List<Map<String,Object>> columns() {
        var rows=new ArrayList<Map<String,Object>>();
        PlatformSkillSchemaContract.columns(TABLE).forEach((name,column)->{
            var row=new HashMap<String,Object>();row.put("COLUMN_NAME",name);row.put("COLUMN_TYPE",column.type());row.put("IS_NULLABLE",column.nullable()?"YES":"NO");
            row.put("COLUMN_DEFAULT",null);row.put("EXTRA","");row.put("GENERATION_EXPRESSION","");
            row.put("COLLATION_NAME",column.type().startsWith("varchar")||column.type().startsWith("char")||column.type().equals("longtext")?"utf8mb4_0900_bin":null);rows.add(row);
        });return rows;
    }
    static List<Map<String,Object>> indexes() {
        var rows=new ArrayList<Map<String,Object>>();PlatformSkillSchemaContract.indexes(TABLE).forEach((name,index)->{
            rows.add(new HashMap<>(Map.of("INDEX_NAME",name,"cols",index.columns(),"NON_UNIQUE",index.unique()?0:1,"PREFIX_COUNT",0,"INDEX_TYPE","BTREE")));
        });return rows;
    }
    static List<Map<String,Object>> checks() {
        var rows=new ArrayList<Map<String,Object>>();PlatformSkillSchemaContract.checks(TABLE).forEach((name,clause)->{
            rows.add(new HashMap<>(Map.of("CONSTRAINT_NAME",name,"CHECK_CLAUSE",clause,"ENFORCED","YES")));
        });return rows;
    }
    static void validate(List<Map<String,Object>> c,List<Map<String,Object>> i,List<Map<String,Object>> k){PlatformSkillSchemaContract.validate(TABLE,properties(),c,i,k);}
    @Test void expectedCatalogAndMysqlCheckFormattingPass() {
        validate(columns(),indexes(),checks());
        assertEquals(PlatformSkillSchemaContract.normalize("origin='PLATFORM_PROVISIONED'"),PlatformSkillSchemaContract.normalize("(`origin` = _utf8mb4'PLATFORM_PROVISIONED')"));
        assertEquals(PlatformSkillSchemaContract.normalize("origin='PLATFORM_PROVISIONED'"),PlatformSkillSchemaContract.normalize("(`origin` = _utf8mb4\\'PLATFORM_PROVISIONED\\')"));
    }
    @Test void checkNormalizerPreservesQuotedLiteralCaseWhitespaceAndMeaning() {
        assertNotEquals(PlatformSkillSchemaContract.normalize("origin='PLATFORM_PROVISIONED'"),
                PlatformSkillSchemaContract.normalize("origin='platform_provisioned'"));
        assertNotEquals(PlatformSkillSchemaContract.normalize("origin='PLATFORM PROVISIONED'"),
                PlatformSkillSchemaContract.normalize("origin='PLATFORM_PROVISIONED'"));
        assertEquals("STATEIN('REQUESTED','SUCCEEDED','FAILED')",
                PlatformSkillSchemaContract.normalize("((`state` IN (_utf8mb4\\'REQUESTED\\', _utf8mb4\\'SUCCEEDED\\', _utf8mb4\\'FAILED\\')))"));
    }
    @Test void exactPreReclamationStateCheckIsAcceptedOnlyByTheBoundedUpgradeContract() {
        var predecessor=checks();
        predecessor.stream().filter(row -> row.get("CONSTRAINT_NAME").equals("ck_platform_install_state"))
                .findFirst().orElseThrow().put("CHECK_CLAUSE","state IN ('REQUESTED','SUCCEEDED','FAILED')");
        var oldColumns=columns(); oldColumns.removeIf(row -> "reclaim_batch_json".equals(row.get("COLUMN_NAME")));
        PlatformSkillSchemaContract.validatePreReclamation(TABLE,properties(),oldColumns,indexes(),predecessor);
        assertThrows(IllegalStateException.class,()->validate(columns(),indexes(),predecessor));
        var drift=new ArrayList<>(predecessor);
        drift.add(new HashMap<>(Map.of("CONSTRAINT_NAME","ck_extra","CHECK_CLAUSE","revision<99","ENFORCED","YES")));
        assertThrows(IllegalStateException.class,()->PlatformSkillSchemaContract.validatePreReclamation(
                TABLE,properties(),oldColumns,indexes(),drift));
    }

    @Test void partialReclaimColumnAndOldCheckCannotMasqueradeAsExactPredecessor() {
        var oldChecks=checks();
        oldChecks.stream().filter(row -> row.get("CONSTRAINT_NAME").equals("ck_platform_install_state"))
                .findFirst().orElseThrow().put("CHECK_CLAUSE","state IN ('REQUESTED','SUCCEEDED','FAILED')");
        assertThrows(IllegalStateException.class,()->PlatformSkillSchemaContract.validatePreReclamation(
                TABLE,properties(),columns(),indexes(),oldChecks));
        var missing=columns(); missing.removeIf(row -> "reclaim_batch_json".equals(row.get("COLUMN_NAME")));
        assertThrows(IllegalStateException.class,()->validate(missing,indexes(),checks()));
    }

    @Test void lengthsNullabilityUnsignedAndGeneratedValuesAreRejected() {
        for(var mutation:List.of(Map.of("COLUMN_TYPE","binary(16)"),Map.of("IS_NULLABLE","YES"),Map.of("COLUMN_DEFAULT","x"),Map.of("EXTRA","STORED GENERATED"))) {
            var columns=columns();columns.stream().filter(c->c.get("COLUMN_NAME").equals("registration_hash")).findFirst().orElseThrow().putAll(mutation);
            assertThrows(IllegalStateException.class,()->validate(columns,indexes(),checks()));
        }
        var columns=columns();columns.stream().filter(c->c.get("COLUMN_NAME").equals("revision")).findFirst().orElseThrow().put("COLUMN_TYPE","bigint unsigned");
        assertThrows(IllegalStateException.class,()->validate(columns,indexes(),checks()));
    }
    @Test void exactUniqueAndOrdinaryIndexContractsAreEnforced() {
        validate(columns(),indexes(),checks());
        var resolve=PlatformSkillSchemaContract.indexes(TABLE).get("ix_platform_install_resolve");
        assertEquals("tenant_id,client_id,owner_jiacn,origin,agent_id,skill_key,skill_version,package_sha256,binding_id,runtime_instance_id,registration_hash,state,created_at,installation_id",resolve.columns());
        assertFalse(resolve.unique());
        var history=PlatformSkillSchemaContract.indexes(TABLE).get("ix_platform_install_resolve_history");
        assertEquals("tenant_id,client_id,owner_jiacn,origin,agent_id,skill_key,skill_version,package_sha256,binding_id,runtime_instance_id,registration_hash,created_at,installation_id",history.columns());
        assertFalse(history.unique());
        var ordinary=indexes();ordinary.stream().filter(i->i.get("INDEX_NAME").equals("ix_platform_install_reconcile"))
                .findFirst().orElseThrow().put("NON_UNIQUE",0);
        assertThrows(IllegalStateException.class,()->validate(columns(),ordinary,checks()));
        var unique=indexes();unique.stream().filter(i->i.get("INDEX_NAME").equals("uk_platform_install_command"))
                .findFirst().orElseThrow().put("NON_UNIQUE",1);
        assertThrows(IllegalStateException.class,()->validate(columns(),unique,checks()));
        var extra=indexes();extra.add(new HashMap<>(Map.of("INDEX_NAME","ix_extra","cols","state","NON_UNIQUE",1,
                "PREFIX_COUNT",0,"INDEX_TYPE","BTREE")));
        assertThrows(IllegalStateException.class,()->validate(columns(),extra,checks()));
    }
    @Test void primaryKeyPrefixTypeAndMissingOrUnenforcedChecksAreRejected() {
        var indexes=indexes();indexes.stream().filter(i->i.get("INDEX_NAME").equals("PRIMARY")).findFirst().orElseThrow().put("INDEX_NAME","not_primary");
        assertThrows(IllegalStateException.class,()->validate(columns(),indexes,checks()));
        var prefixed=indexes();prefixed.getFirst().put("PREFIX_COUNT",1);assertThrows(IllegalStateException.class,()->validate(columns(),prefixed,checks()));
        var nonBtree=indexes();nonBtree.getFirst().put("INDEX_TYPE","HASH");assertThrows(IllegalStateException.class,()->validate(columns(),nonBtree,checks()));
        assertThrows(IllegalStateException.class,()->validate(columns(),indexes(),List.of()));
        var checks=checks();checks.getFirst().put("ENFORCED","NO");assertThrows(IllegalStateException.class,()->validate(columns(),indexes(),checks));
    }
}
