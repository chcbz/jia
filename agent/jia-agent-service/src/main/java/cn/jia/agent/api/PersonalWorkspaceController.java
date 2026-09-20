package cn.jia.agent.api;

import cn.jia.agent.entity.PersonalWorkspaceViews;
import cn.jia.agent.exception.PersonalWorkspaceException;
import cn.jia.agent.service.PersonalWorkspaceService;
import cn.jia.agent.service.impl.PersonalWorkspacePreviewRenderer;
import jakarta.servlet.http.HttpServletRequest;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** Browser-only personal file API. It never accepts owner/client scope in request input. */
@Slf4j
@RestController
@RequestMapping("/agent/personal-workspace")
public class PersonalWorkspaceController {
    static final String CACHE_CONTROL = "private, no-store";
    static final String NOSNIFF = "nosniff";
    private static final Map<String, String> FILENAME_MIME_TYPES = Map.of(
            "png", "image/png", "jpg", "image/jpeg", "jpeg", "image/jpeg", "txt", "text/plain",
            "pdf", "application/pdf",
            "docx", "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
            "xlsx", "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
            "pptx", "application/vnd.openxmlformats-officedocument.presentationml.presentation");
    private final PersonalWorkspaceService service;

    public PersonalWorkspaceController(PersonalWorkspaceService service) { this.service = Objects.requireNonNull(service, "service"); }

    @GetMapping(value = "/files", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<PersonalWorkspaceViews.ListView> list(
            @RequestParam(required = false) String q, @RequestParam(required = false) String mediaFamily,
            @RequestParam(required = false) String state, @RequestParam(required = false) String cursor,
            Authentication authentication) {
        return json(service.list(scope(authentication), new PersonalWorkspaceService.ListQuery(q, mediaFamily, state, cursor)));
    }
    @PostMapping(value = "/files", consumes = MediaType.MULTIPART_FORM_DATA_VALUE, produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<PersonalWorkspaceViews.UploadView> create(@RequestParam("file") MultipartFile file,
            @RequestParam(required = false) String displayName, @RequestHeader("Idempotency-Key") String key,
            Authentication authentication) throws IOException {
        PersonalWorkspaceViews.UploadView view = service.create(scope(authentication), upload(file, displayName, key));
        return ResponseEntity.status(HttpStatus.CREATED).header(HttpHeaders.CACHE_CONTROL, CACHE_CONTROL).body(view);
    }
    @GetMapping(value = "/files/{fileId}", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<PersonalWorkspaceViews.FileDetailView> get(@PathVariable String fileId, Authentication authentication) {
        PersonalWorkspaceViews.FileDetailView view = service.get(scope(authentication), fileId);
        return ResponseEntity.ok().header(HttpHeaders.CACHE_CONTROL, CACHE_CONTROL).eTag(etag(view.file())).body(view);
    }
    @PatchMapping(value = "/files/{fileId}", consumes = MediaType.APPLICATION_JSON_VALUE, produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<PersonalWorkspaceViews.FileView> rename(@PathVariable String fileId,
            @RequestBody RenameRequest body, @RequestHeader(value = HttpHeaders.IF_MATCH, required = false) String ifMatch,
            @RequestHeader("Idempotency-Key") String key, Authentication authentication) {
        PersonalWorkspaceViews.FileView view = service.rename(scope(authentication), fileId, body == null ? null : body.displayName(), ifMatch, new PersonalWorkspaceService.Idempotency(key));
        return ResponseEntity.ok().header(HttpHeaders.CACHE_CONTROL, CACHE_CONTROL).eTag(etag(view)).body(view);
    }
    @GetMapping(value = "/files/{fileId}/versions", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<Map<String, Object>> versions(@PathVariable String fileId, Authentication authentication) {
        return json(Map.of("items", service.versions(scope(authentication), fileId), "nextCursor", null));
    }
    @PostMapping(value = "/files/{fileId}/versions", consumes = MediaType.MULTIPART_FORM_DATA_VALUE, produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<PersonalWorkspaceViews.UploadView> append(@PathVariable String fileId,
            @RequestParam("file") MultipartFile file, @RequestParam(required = false) String displayName,
            @RequestParam("expectedPreviousVersion") String expectedPreviousVersion,
            @RequestHeader("Idempotency-Key") String key, Authentication authentication) throws IOException {
        PersonalWorkspaceViews.UploadView view = service.appendVersion(scope(authentication), fileId,
                upload(file, displayName, key), version(expectedPreviousVersion));
        return ResponseEntity.status(HttpStatus.CREATED).header(HttpHeaders.CACHE_CONTROL, CACHE_CONTROL).body(view);
    }
    @GetMapping("/files/{fileId}/versions/{version}/content")
    public ResponseEntity<byte[]> content(@PathVariable String fileId, @PathVariable String version, Authentication authentication) {
        return binary(service.readContent(scope(authentication), fileId, version(version)), true);
    }
    @GetMapping(value = "/files/{fileId}/versions/{version}/preview", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<PersonalWorkspaceViews.PreviewView> preview(@PathVariable String fileId,
            @PathVariable String version, HttpServletRequest request,
            Authentication authentication) {
        PersonalWorkspaceService.Scope authenticatedScope = scope(authentication);
        boolean partsView = requirePartsView(request);
        PersonalWorkspaceViews.PreviewView rendered =
                service.preview(authenticatedScope, fileId, version(version));
        return json(partsView ? rendered : legacyPreview(rendered));
    }
    @GetMapping("/files/{fileId}/versions/{version}/preview/parts/{partId}")
    public ResponseEntity<byte[]> previewPart(@PathVariable String fileId, @PathVariable String version,
            @PathVariable String partId, Authentication authentication) {
        return binary(service.readPreviewPart(scope(authentication), fileId, version(version), partId), false);
    }
    @GetMapping(value = "/files/{fileId}/usage", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<PersonalWorkspaceViews.UsageView> usage(@PathVariable String fileId, Authentication authentication) {
        return json(service.usage(scope(authentication), fileId));
    }
    @PostMapping(value = "/files/{fileId}/trash", consumes = MediaType.APPLICATION_JSON_VALUE, produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<PersonalWorkspaceViews.FileView> trash(@PathVariable String fileId, @RequestBody TrashRequest body,
            @RequestHeader(value = HttpHeaders.IF_MATCH, required = false) String ifMatch,
            @RequestHeader("Idempotency-Key") String key, Authentication authentication) {
        PersonalWorkspaceViews.FileView view=service.trash(scope(authentication),fileId,body==null?-1:body.impactRevision(),body!=null&&body.acknowledgeExistingReferences(),ifMatch,new PersonalWorkspaceService.Idempotency(key));
        return ResponseEntity.ok().header(HttpHeaders.CACHE_CONTROL,CACHE_CONTROL).eTag(etag(view)).body(view);
    }
    @PostMapping(value = "/files/{fileId}/restore", consumes = MediaType.APPLICATION_JSON_VALUE, produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<PersonalWorkspaceViews.FileView> restore(@PathVariable String fileId,
            @RequestHeader(value = HttpHeaders.IF_MATCH, required = false) String ifMatch,
            @RequestHeader("Idempotency-Key") String key, Authentication authentication) {
        PersonalWorkspaceViews.FileView view=service.restore(scope(authentication),fileId,ifMatch,new PersonalWorkspaceService.Idempotency(key));
        return ResponseEntity.ok().header(HttpHeaders.CACHE_CONTROL,CACHE_CONTROL).eTag(etag(view)).body(view);
    }
    @GetMapping(value = "/operations/{operationId}", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<PersonalWorkspaceViews.OperationView> operation(@PathVariable String operationId, Authentication authentication) { return json(service.operation(scope(authentication),operationId)); }

    @ExceptionHandler(PersonalWorkspaceException.class)
    public ResponseEntity<ErrorBody> failure(PersonalWorkspaceException failure) {
        return switch (failure.getReason()) {
            case BAD_REQUEST -> error(HttpStatus.BAD_REQUEST, "BAD_REQUEST", "Invalid personal workspace request");
            case NOT_FOUND -> error(HttpStatus.NOT_FOUND, "FILE_NOT_FOUND", "File is unavailable");
            case IDEMPOTENCY_CONFLICT -> error(HttpStatus.CONFLICT, "IDEMPOTENCY_CONFLICT", "Operation key conflicts with a different request");
            case PROCESSING -> error(HttpStatus.ACCEPTED, "OPERATION_PROCESSING", "Operation is still being reconciled");
            case OPERATION_FAILED -> error(HttpStatus.SERVICE_UNAVAILABLE, "OPERATION_RECONCILIATION_REQUIRED", "Operation outcome requires reconciliation");
            case METADATA_CHANGED -> error(HttpStatus.PRECONDITION_FAILED, "METADATA_CHANGED", "File metadata changed");
            case PRECONDITION_REQUIRED -> error(HttpStatus.PRECONDITION_REQUIRED, "PRECONDITION_REQUIRED", "If-Match is required");
            case VERSION_CONFLICT -> error(HttpStatus.CONFLICT, "FILE_VERSION_CONFLICT", "File version changed");
            case UNSUPPORTED -> error(HttpStatus.UNSUPPORTED_MEDIA_TYPE, "FILE_TYPE_UNSUPPORTED", "File capability is not available");
            case STORAGE_UNAVAILABLE, STORAGE_CORRUPT -> error(HttpStatus.SERVICE_UNAVAILABLE, "STORAGE_UNAVAILABLE", "Personal workspace storage is unavailable");
        };
    }
    @ExceptionHandler({IOException.class, IllegalArgumentException.class})
    public ResponseEntity<ErrorBody> malformed(Exception ignored) { return error(HttpStatus.BAD_REQUEST,"BAD_REQUEST","Invalid personal workspace request"); }
    @ExceptionHandler(Exception.class)
    public ResponseEntity<ErrorBody> unexpected(Exception failure, HttpServletRequest request) { log.error("Personal workspace request failed: uri={}",request.getRequestURI(),failure); return error(HttpStatus.SERVICE_UNAVAILABLE,"WORKSPACE_UNAVAILABLE","Personal workspace is temporarily unavailable"); }

    private static boolean requirePartsView(HttpServletRequest request) {
        if (request.getParameterMap().isEmpty()) return false;
        if (request.getParameterMap().size() != 1
                || !request.getParameterMap().containsKey("view")) {
            throw new PersonalWorkspaceException(PersonalWorkspaceException.Reason.BAD_REQUEST);
        }
        String[] values = request.getParameterValues("view");
        if (values == null || values.length != 1 || !"parts".equals(values[0])) {
            throw new PersonalWorkspaceException(PersonalWorkspaceException.Reason.BAD_REQUEST);
        }
        return true;
    }

    private static PersonalWorkspaceViews.PreviewView legacyPreview(
            PersonalWorkspaceViews.PreviewView rendered) {
        if (!"READY".equals(rendered.state())) return rendered;
        PersonalWorkspaceViews.PreviewPart content = rendered.parts().stream()
                .filter(part -> PersonalWorkspacePreviewRenderer.CONTENT_PART_ID.equals(part.partId()))
                .findFirst()
                .orElseThrow(() -> new PersonalWorkspaceException(
                        PersonalWorkspaceException.Reason.STORAGE_CORRUPT));
        return new PersonalWorkspaceViews.PreviewView(rendered.state(), List.of(content),
                rendered.partial(), rendered.reason());
    }

    private PersonalWorkspaceService.Scope scope(Authentication authentication) {
        if(authentication==null||!authentication.isAuthenticated()||!(authentication instanceof JwtAuthenticationToken jwt)) throw new PersonalWorkspaceException(PersonalWorkspaceException.Reason.NOT_FOUND);
        Map<String,Object> claims=jwt.getToken().getClaims(); Object owner=claims.get("jiacn"),client=claims.get("client_id");
        if(!(owner instanceof String o)||!(client instanceof String c)||!valid(o,50)||!valid(c,50)||"0".equals(o)) throw new PersonalWorkspaceException(PersonalWorkspaceException.Reason.NOT_FOUND);
        return new PersonalWorkspaceService.Scope("0",c,o);
    }
    private PersonalWorkspaceService.UploadCommand upload(MultipartFile file,String displayName,String key) throws IOException {
        if(file==null) throw new PersonalWorkspaceException(PersonalWorkspaceException.Reason.BAD_REQUEST);
        return new PersonalWorkspaceService.UploadCommand(new PersonalWorkspaceService.Idempotency(key),displayName,
                file.getOriginalFilename(), contentMimeType(file), file.getBytes());
    }
    private static String contentMimeType(MultipartFile file) {
        String supplied = normalizedMime(file.getContentType());
        if (supplied != null && !MediaType.APPLICATION_OCTET_STREAM_VALUE.equals(supplied)) return supplied;
        String filename = file.getOriginalFilename();
        int dot = filename == null ? -1 : filename.lastIndexOf('.');
        if (dot < 0 || dot == filename.length() - 1) return supplied;
        return FILENAME_MIME_TYPES.getOrDefault(filename.substring(dot + 1).toLowerCase(java.util.Locale.ROOT), supplied);
    }
    private static String normalizedMime(String value) {
        if (value == null) return null;
        String mime = value.split(";", 2)[0].trim().toLowerCase(java.util.Locale.ROOT);
        if (mime.isEmpty()) return null;
        return "image/jpg".equals(mime) ? "image/jpeg" : mime;
    }
    private static int version(String value){if(value==null||!value.matches("[1-9][0-9]{0,9}"))throw new PersonalWorkspaceException(PersonalWorkspaceException.Reason.BAD_REQUEST);try{return Math.toIntExact(Long.parseLong(value));}catch(ArithmeticException ex){throw new PersonalWorkspaceException(PersonalWorkspaceException.Reason.BAD_REQUEST);}}
    private static ResponseEntity<byte[]> binary(PersonalWorkspaceService.Content content,boolean attachment) {
        MediaType type=MediaType.parseMediaType(content.contentMimeType()); ResponseEntity.BodyBuilder b=ResponseEntity.ok().header(HttpHeaders.CACHE_CONTROL,CACHE_CONTROL).header("X-Content-Type-Options",NOSNIFF).contentType(type).contentLength(content.bytes().length);
        if(attachment)b.header(HttpHeaders.CONTENT_DISPOSITION,ContentDisposition.attachment().filename(safeFilename(content.filename()),StandardCharsets.UTF_8).build().toString());
        return b.body(content.bytes());
    }
    private static String safeFilename(String name){return name.replaceAll("[\\\\/:*?\"<>|\\p{Cntrl}]","_").replaceAll("^\\.+","").trim();}
    private static <T> ResponseEntity<T> json(T body){return ResponseEntity.ok().header(HttpHeaders.CACHE_CONTROL,CACHE_CONTROL).contentType(MediaType.APPLICATION_JSON).body(body);}
    private static ResponseEntity<ErrorBody> error(HttpStatus status,String code,String message){return ResponseEntity.status(status).header(HttpHeaders.CACHE_CONTROL,CACHE_CONTROL).contentType(new MediaType(MediaType.APPLICATION_JSON,StandardCharsets.UTF_8)).body(new ErrorBody(code,message));}
    private static String etag(PersonalWorkspaceViews.FileView view){return "\""+view.fileId()+":"+view.metadataRevision()+"\"";}
    private static boolean valid(String v,int max){return v!=null&&!v.isBlank()&&v.equals(v.strip())&&v.codePointCount(0,v.length())<=max&&!v.chars().anyMatch(Character::isISOControl);}
    public record RenameRequest(String displayName) { }
    public record TrashRequest(long impactRevision, boolean acknowledgeExistingReferences) { }
    public record ErrorBody(String code,String message) { }
}
