package cn.jia.chat.archive.maintenance.http;

import cn.jia.chat.archive.maintenance.service.ArchiveMaintenanceException;

final class ArchiveHttpPreconditions {
    private ArchiveHttpPreconditions(){}
    static long revision(String value){
        if(value==null) throw new ArchiveMaintenanceException(428,"PRECONDITION_REQUIRED","If-Match is required");
        java.util.regex.Matcher m=java.util.regex.Pattern.compile("\"v(0|[1-9][0-9]{0,18})\"").matcher(value);
        if(!m.matches()) throw new ArchiveMaintenanceException(400,"INVALID_CONDITIONAL_HEADER","If-Match header is malformed");
        return Long.parseLong(m.group(1));
    }
    static String etag(String revision){return "\"v"+revision+"\"";}
}
