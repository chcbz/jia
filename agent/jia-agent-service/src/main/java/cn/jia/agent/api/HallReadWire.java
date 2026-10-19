package cn.jia.agent.api;

import cn.jia.agent.service.HallReadService;
import cn.jia.agent.service.HallPrivateMarkService;
import com.fasterxml.jackson.annotation.JsonInclude;
import java.util.List;
import java.util.Map;
import java.util.LinkedHashMap;

/** Read-model projections; private mark revisions retain their full domain precision. */
final class HallReadWire {
    private HallReadWire() { }
    @JsonInclude(JsonInclude.Include.ALWAYS)
    record PersonalMark(String revision, boolean archived, HallPrivateMarkService.ResultRef viewedResultRef) { }
    @JsonInclude(JsonInclude.Include.ALWAYS)
    record ItemSummary(HallReadService.Ref ref, String title, HallReadService.Status status,
                       HallReadService.TargetAgent targetAgent, String nextAction, List<String> allowedActions,
                       long updatedAt, PersonalMark personalMark, HallReadService.Review review, String phase) { }
    @JsonInclude(JsonInclude.Include.ALWAYS)
    record Partition(List<ItemSummary> items, String status, String nextCursor, String count, String errorCode) { }
    record Section(String status, Map<String, Partition> partitions) { }
    record Overview(int schemaVersion, Map<String, Section> sections,
                    Map<String, Map<String, HallReadService.SourceStatus>> sourceStatus, long asOf) { }
    record Items(int schemaVersion, String kind, String view, String q, Section section,
                 Map<String, HallReadService.SourceStatus> sourceStatus, long asOf) { }
    static ItemSummary from(HallReadService.ItemSummary v) {
        var mark = v.personalMark();
        return new ItemSummary(v.ref(),v.title(),v.status(),v.targetAgent(),v.nextAction(),v.allowedActions(),v.updatedAt(),
                mark == null ? null : new PersonalMark(Long.toString(mark.revision()),mark.archived(),mark.viewedResultRef()),v.review(),v.phase());
    }
    static Section from(HallReadService.Section v) {
        Map<String, Partition> partitions = new LinkedHashMap<>();
        v.partitions().forEach((key,p) -> partitions.put(key,new Partition(p.items().stream().map(HallReadWire::from).toList(),
                p.status(),p.nextCursor(),p.count()==null?null:p.count().toString(),p.errorCode())));
        return new Section(v.status(),partitions);
    }
    static Overview from(HallReadService.Overview v) {
        Map<String, Section> sections = new LinkedHashMap<>();
        v.sections().forEach((key,value) -> sections.put(key,from(value)));
        return new Overview(v.schemaVersion(),sections,v.sourceStatus(),v.asOf());
    }
    static Items from(HallReadService.Items v) {
        return new Items(v.schemaVersion(),v.kind(),v.view(),v.q(),from(v.section()),v.sourceStatus(),v.asOf());
    }
}
