package cn.jia.agent.service.impl;

import cn.jia.agent.service.PersonalWorkspaceExecutionService;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class PersonalWorkspaceControlledImageV3StartTest {
 @Test void startReceiptIsSchemaThreeAndNeverCarriesFenceToken() {
  var provider=new PersonalWorkspaceExecutionService.ProviderExecution("CONTROLLED_IMAGE_HTTP_V1",
    "consent_1234567890abcdef1234567890abcdef","binding","1","model",16,1,1);
  var receipt=new PersonalWorkspaceExecutionService.ControlledProviderStartReceiptV3(3,true,"task","run","conversation",
    "execution","command","message","GENERATE_IMAGE","a".repeat(64),provider,1);
  assertTrue(receipt.started());assertEquals(3,receipt.schemaVersion());
  assertTrue(java.util.Arrays.stream(receipt.getClass().getRecordComponents()).noneMatch(c->c.getName().toLowerCase().contains("token")));
 }
 @Test void startReadsExternalBytesBeforeRootTransactionAndOnlyRevalidatesRowsInsideLateCheck()
     throws Exception {
  String source=java.nio.file.Files.readString(java.nio.file.Path.of(
    "src/main/java/cn/jia/agent/service/impl/PersonalWorkspaceExecutionServiceImpl.java"));
  int method=source.indexOf("beginControlledConversationProviderStartV3(");
  int bytes=source.indexOf("verifiedV3SourceBytes(scope,candidate)",method);
  int consume=source.indexOf("followupAuthority.consumeForStart",method);
  int rows=source.indexOf("verifiedV3SourceRows(scope,execution)",consume);
  int nextMethod=source.indexOf("private static ProviderExecution provider",method);
  assertTrue(method>=0&&bytes>method&&consume>bytes&&rows>consume&&nextMethod>rows);
  assertEquals(-1,source.substring(consume,nextMethod)
    .indexOf("verifiedV3SourceBytes(scope,execution)"));
 }
}
