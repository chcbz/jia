package cn.jia.agent.service;

/** Unfunded/unassigned task adapter. Never converts acknowledgement into payment authority. */
public interface HallTaskCreationService {
    HallRequestDraftService.TaskReference create(HallRequestDraftService.OwnerScope scope,
                                                String title, String description);
    HallRequestDraftService.TaskReference get(HallRequestDraftService.OwnerScope scope, String taskId);
}
