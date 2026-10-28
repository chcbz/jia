package cn.jia.agent.service.impl;

import cn.jia.agent.service.PersonalWorkspaceExecutionService;
import org.junit.jupiter.api.Test;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class PersonalWorkspaceControlledImageV3ExecutionTest {
 @Test void v3CommandAndInputProjectionCarryExactOperationDigestAndLineage() {
  var provider=new PersonalWorkspaceExecutionService.ProviderExecution("CONTROLLED_IMAGE_HTTP_V1",
    "consent_1234567890abcdef1234567890abcdef","binding","1","model",16,1,1);
  var command=new PersonalWorkspaceExecutionService.ControlledConversationRuntimeCommandV3(3,"execution","task","run",
    "conversation","command","message","EDIT_IMAGE","edit","a".repeat(64),"image/png","output_1",provider);
  assertEquals(3,command.schemaVersion());assertEquals("EDIT_IMAGE",command.operation());assertEquals(provider,command.providerExecution());
  var source=new PersonalWorkspaceExecutionService.RuntimeSource("CURRENT_CONVERSATION_ASSET",null,null,null,
    "conversation","1","asset","1","request","step","execution0","run0","output_1");
  var snapshot=new PersonalWorkspaceExecutionService.ConversationInputSnapshotV3(3,"execution",1,"EDIT_IMAGE",
    "a".repeat(64),false,List.of(new PersonalWorkspaceExecutionService.RuntimeInputV3("input_1",source,"image/png","4","b".repeat(64))));
  assertEquals(1,snapshot.inputs().size());assertFalse(snapshot.noReferencedMaterials());
 }
 @Test void retiredV3RuntimeControllerDoesNotExposeInputOrStart() {
  var names=java.util.Arrays.stream(cn.jia.agent.api.PersonalWorkspaceConversationRuntimeController.class.getDeclaredMethods())
    .map(java.lang.reflect.Method::getName).toList();
  assertFalse(names.contains("inputsV3"));assertFalse(names.contains("controlledProviderStartV3"));
 }

}
