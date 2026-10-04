package cn.jia.chat.archive.service;

import cn.jia.chat.archive.content.ArchiveEtags;
import cn.jia.chat.archive.content.ArchiveManifestLoader;
import cn.jia.chat.archive.dto.*;
import cn.jia.chat.archive.maintenance.dto.ArchiveWorkSummaryDTO;
import cn.jia.chat.archive.maintenance.dto.ArchiveWorksDTO;
import cn.jia.chat.archive.model.*;
import cn.jia.chat.archive.store.ArchiveContentStore;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Objects;

@Service
public class ArchiveReaderServiceImpl implements ArchiveReaderService {
    private final ArchiveContentStore store;
    public ArchiveReaderServiceImpl(ArchiveContentStore store){this.store=Objects.requireNonNull(store,"store");}

    @Override @Transactional(readOnly=true)
    public ArchiveRepresentation<ArchiveCatalogDTO> catalog(){return workCatalog(ArchiveManifestLoader.WORK_ID);}

    @Override @Transactional(readOnly=true)
    public ArchiveRepresentation<ArchiveWorksDTO> works(int limit){
        int bounded=Math.max(1,Math.min(limit,100));
        return worksPage(null,bounded,ArchivePageCursor.binding("reader-works-legacy","legacy"));
    }

    @Override @Transactional(readOnly=true)
    public ArchiveRepresentation<ArchiveWorksDTO> works(String tenantId,String clientId,
            String cursor,int limit){
        if(limit<1||limit>100)throw new IllegalArgumentException("Invalid archive page limit");
        String binding=ArchivePageCursor.binding("reader-works",tenantId,clientId);
        String after=cursor==null?null:ArchivePageCursor.decode(binding,cursor);
        return worksPage(after,limit,binding);
    }

    private ArchiveRepresentation<ArchiveWorksDTO> worksPage(String after,int limit,String binding){
        List<ArchiveWorkRecord> rows=store.listActiveWorks(after,limit+1);
        boolean more=rows.size()>limit;
        List<ArchiveWorkSummaryDTO> items=rows.subList(0,Math.min(limit,rows.size())).stream()
                .map(w->new ArchiveWorkSummaryDTO(w.workId(),w.title(),w.activeEditionId())).toList();
        String next=more?ArchivePageCursor.encode(binding,items.getLast().workId()):null;
        ArchiveWorksDTO page=new ArchiveWorksDTO(items,next);
        String digest=ArchiveEtags.sha256(page.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8));
        return new ArchiveRepresentation<>(ArchiveEtags.catalog(digest),page);
    }

    @Override @Transactional(readOnly=true)
    public ArchiveRepresentation<ArchiveCatalogDTO> workCatalog(String workId){
        ArchiveContentStore.ActiveContent active=store.findActiveContent(workId);
        if(active==null||active.work()==null||active.edition()==null||!store.isPublished(active.edition().editionId())) notFound();
        return catalogRepresentation(active.work(),active.edition());
    }

    @Override @Transactional(readOnly=true)
    public ArchiveRepresentation<ArchiveCatalogDTO> editionCatalog(String editionId){
        ArchiveContentStore.ActiveContent published=requirePublished(editionId);
        if(published.work()==null||published.edition()==null) notFound();
        return catalogRepresentation(published.work(),published.edition());
    }

    @Override @Transactional(readOnly=true)
    public ArchiveRepresentation<ArchiveBlockDTO> preface(String editionId){
        ArchiveEditionRecord edition=requirePublished(editionId).edition();
        ArchiveBlockRecord block=store.listBlocks(editionId).stream().filter(b->"PREFACE".equals(b.blockType())).findFirst().orElse(null);
        if(block==null) notFound();
        return representation(edition,block);
    }

    @Override @Transactional(readOnly=true)
    public ArchiveRepresentation<ArchiveBlockDTO> chapter(String editionId,String chapterId){
        ArchiveEditionRecord edition=requirePublished(editionId).edition();
        ArchiveBlockRecord block=store.findBlock(editionId,chapterId);
        if(block==null||!"CHAPTER".equals(block.blockType())||block.chapterNumber()==null||block.readerOrdinal()!=block.chapterNumber()) notFound();
        return representation(edition,block);
    }

    private ArchiveRepresentation<ArchiveCatalogDTO> catalogRepresentation(ArchiveWorkRecord work,ArchiveEditionRecord edition){
        if(!"READY".equals(edition.importState())||!work.workId().equals(edition.workId())) notFound();
        List<ArchiveBlockRecord> rows=store.listBlocks(edition.editionId());
        ArchiveBlockSummaryDTO preface=null;
        java.util.ArrayList<ArchiveBlockSummaryDTO> chapters=new java.util.ArrayList<>();
        int expected=1;
        for(ArchiveBlockRecord row:rows){
            if("PREFACE".equals(row.blockType())){
                require(preface==null&&row.readerOrdinal()==0&&row.chapterNumber()==null,"preface order drift");
                preface=summary(row);
            }else{
                require("CHAPTER".equals(row.blockType())&&row.chapterNumber()!=null&&row.readerOrdinal()==expected&&row.chapterNumber()==expected,"chapter order drift");
                chapters.add(summary(row)); expected++;
            }
        }
        require(chapters.size()==edition.chapterCount(),"chapter count drift");
        require((preface==null)==(edition.prefaceParagraphCount()==0),"preface count drift");
        ArchiveActiveEditionDTO active=new ArchiveActiveEditionDTO(edition.editionId(),edition.manifestSha256(),
                edition.sourceSha256(),edition.prefaceParagraphCount(),edition.chapterParagraphCount(),
                edition.readerParagraphCount(),edition.readerUtf8ByteLength(),preface,chapters);
        return new ArchiveRepresentation<>(ArchiveEtags.catalog(edition.manifestSha256()),
                new ArchiveCatalogDTO(ArchiveEtags.REPRESENTATION_SCHEMA_VERSION,work.workId(),work.title(),active));
    }

    private ArchiveContentStore.ActiveContent requirePublished(String editionId){
        String state=store.publicationState(editionId);
        if("WITHDRAWN".equals(state)) throw new ArchiveResourceGoneException();
        if(!"PUBLISHED".equals(state)) notFound();
        ArchiveContentStore.ActiveContent value=store.findPublishedContent(editionId);
        if(value==null||value.edition()==null||!"READY".equals(value.edition().importState())) notFound();
        return value;
    }
    private ArchiveRepresentation<ArchiveBlockDTO> representation(ArchiveEditionRecord edition,ArchiveBlockRecord block){
        List<ArchiveParagraphRecord> rows=store.listParagraphs(edition.editionId(),block.blockId());
        require(rows.size()==block.paragraphCount(),"paragraph count drift");
        java.util.ArrayList<ArchiveParagraphDTO> paragraphs=new java.util.ArrayList<>();
        for(int i=0;i<rows.size();i++){
            ArchiveParagraphRecord row=rows.get(i); require(row.ordinal()==i+1,"paragraph order drift");
            paragraphs.add(new ArchiveParagraphDTO(row.paragraphId(),row.ordinal(),row.text(),row.utf8ByteLength(),row.sha256()));
        }
        return new ArchiveRepresentation<>(ArchiveEtags.blockFromDigest(block.blockContentSha256()),
                new ArchiveBlockDTO(ArchiveEtags.REPRESENTATION_SCHEMA_VERSION,edition.editionId(),edition.manifestSha256(),
                        block.blockType(),block.blockId(),block.chapterNumber(),block.title(),block.paragraphCount(),
                        block.utf8ByteLength(),paragraphs));
    }
    private ArchiveBlockSummaryDTO summary(ArchiveBlockRecord block){return new ArchiveBlockSummaryDTO(block.blockType(),block.blockId(),block.chapterNumber(),block.title(),block.paragraphCount(),block.utf8ByteLength(),ArchiveEtags.blockFromDigest(block.blockContentSha256()));}
    private void require(boolean condition,String message){if(!condition)throw new IllegalStateException(message);}
    private void notFound(){throw new ArchiveResourceNotFoundException();}
}
