package cn.jia.agent.skill;

import org.junit.jupiter.api.*;
import java.util.*;
import java.util.function.Consumer;
import java.util.stream.Stream;

/** Runs the existing detector/paired-schema assertions against this task's private MySQL.
 * Does not alter the original opt-in test's environment or remove its production guard. */
class SkillManagementSchemaMySqlTest extends SkillManagementMySqlFixture {
    private record Case(String name,Consumer<SkillExecutionSchemaMySqlTest> check) { }
    @TestFactory Stream<DynamicTest> exactSchemaDetectorAndPairedIntegrity() {
        return List.of(
            new Case("latestCatalogReadOnlyDetectionAndLegacyRestartAccept",SkillExecutionSchemaMySqlTest::latestCatalogReadOnlyDetectionAndLegacyRestartAccept),
            new Case("realChecksDistinguishGenericNullMimeFromLegacyAndKeepFailureFence",SkillExecutionSchemaMySqlTest::realChecksDistinguishGenericNullMimeFromLegacyAndKeepFailureFence),
            new Case("detectorDoesNotRepairMissingAttachmentOrWeakenedChecks",SkillExecutionSchemaMySqlTest::detectorDoesNotRepairMissingAttachmentOrWeakenedChecks),
            new Case("receiptScopeIndexIsExplicitAndDriftIsNotRepaired",SkillExecutionSchemaMySqlTest::receiptScopeIndexIsExplicitAndDriftIsNotRepaired),
            new Case("realCasAndTransactionPairRollback",SkillExecutionSchemaMySqlTest::realCasAndTransactionPairRollback),
            new Case("nativeTurnFailureAndEffectResolutionRollbackTogether",SkillExecutionSchemaMySqlTest::nativeTurnFailureAndEffectResolutionRollbackTogether),
            new Case("nativeEmptySkillsExactArrays",SkillExecutionSchemaMySqlTest::nativeEmptySkillsUseNoFakeScalarSkillAndRequireExplicitArrays),
            new Case("taskBindingColumnMissing",SkillExecutionSchemaMySqlTest::detectorRejectsMissingTaskBindingColumnWithoutRepair),
            new Case("taskBindingColumnWrongType",SkillExecutionSchemaMySqlTest::detectorRejectsWrongTaskBindingTypeWithoutRepair),
            new Case("taskBindingColumnNonNullable",SkillExecutionSchemaMySqlTest::detectorRejectsNonNullableTaskBindingWithoutRepair),
            new Case("orphanAndPairedStateMismatch",SkillExecutionSchemaMySqlTest::detectorRejectsOrphanAndMismatchedPairedStateWithoutRepair)
        ).stream().map(test->DynamicTest.dynamicTest(test.name(),()->{
            var fixture=new SkillExecutionSchemaMySqlTest();
            try {fixture.setupIsolated(isolatedUrl());test.check().accept(fixture);}
            finally {fixture.cleanup();}
        }));
    }
}
