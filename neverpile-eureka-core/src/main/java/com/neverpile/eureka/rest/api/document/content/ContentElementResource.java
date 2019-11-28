package com.neverpile.eureka.rest.api.document.content;

import java.lang.reflect.Type;
import java.net.URI;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import javax.annotation.PostConstruct;
import javax.servlet.http.HttpServletRequest;
import javax.validation.Valid;

import org.modelmapper.ModelMapper;
import org.modelmapper.TypeToken;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Import;
import org.springframework.core.io.InputStreamResource;
import org.springframework.hateoas.IanaLinkRelations;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import com.google.common.base.Strings;
import com.neverpile.eureka.api.ContentElementService;
import com.neverpile.eureka.api.DocumentIdGenerationStrategy;
import com.neverpile.eureka.api.DocumentService;
import com.neverpile.eureka.api.ObjectStoreService.StoreObject;
import com.neverpile.eureka.model.ContentElement;
import com.neverpile.eureka.model.Document;
import com.neverpile.eureka.rest.api.document.DocumentDto;
import com.neverpile.eureka.rest.api.document.DocumentResource;
import com.neverpile.eureka.rest.api.document.content.AllRequestPartsMethodArgumentResolver.AllRequestParts;
import com.neverpile.eureka.rest.api.document.core.ModificationDateFacet;
import com.neverpile.eureka.rest.api.exception.ConflictException;
import com.neverpile.eureka.rest.api.exception.NotAcceptableException;
import com.neverpile.eureka.rest.api.exception.NotFoundException;
import com.neverpile.urlcrypto.PreSignedUrlEnabled;

import io.micrometer.core.annotation.Timed;
import io.swagger.annotations.Api;
import io.swagger.annotations.ApiOperation;
import io.swagger.annotations.ApiParam;
import io.swagger.annotations.ApiResponse;
import io.swagger.annotations.ApiResponses;
import io.swagger.annotations.Authorization;

@RestController
@RequestMapping(path = "/api/v1/documents", produces = {
    MediaType.APPLICATION_JSON_VALUE
})
@Api(tags = "Content", authorizations = {
    @Authorization(value = "oauth")
})
@Import(ContentElementResourceConfiguration.class)
public class ContentElementResource {
  public static final String DOCUMENT_FORM_ELEMENT_NAME = "__DOC";

  private static final Logger LOGGER = LoggerFactory.getLogger(ContentElementResource.class);

  private static final Type CE_DTO_TYPE = new TypeToken<List<ContentElementDto>>() {
  }.getType();

  @Autowired
  private DocumentService documentService;

  @Autowired
  private ContentElementService contentElementService;

  @Autowired
  @Qualifier("document")
  private ModelMapper documentMapper;

  @Autowired
  private DocumentResource documentResource;

  @Autowired
  private DocumentIdGenerationStrategy idGenerationStrategy;

  private MessageDigest messageDigest;

  @Value("${neverpile-eureka.message-digest-algorithm:SHA-256}")
  private String messageDigestAlgorithm;

  @Autowired
  private ModificationDateFacet mdFacet;

  @PostConstruct
  public void init() throws NoSuchAlgorithmException {
    messageDigest = MessageDigest.getInstance(messageDigestAlgorithm);
  }

  @PreSignedUrlEnabled
  @GetMapping(value = "{documentID}/content/{element}")
  @ApiOperation(value = "Fetch a single content element", produces = "*/*")
  @ApiResponses({
      @ApiResponse(code = 200, message = "Content element found", response = byte[].class),
      @ApiResponse(code = 404, message = "Document or content element not found")
  })
  @Timed(description = "get content element", extraTags = {
      "operation", "retrieve", "target", "content"
  }, value = "eureka.content.get")
  public ResponseEntity<?> getById(
      @ApiParam(value = "ID of the document") @PathVariable("documentID") final String documentId,
      @ApiParam(value = "ID of the content element to be fetched") @PathVariable("element") final String contentId) {
    // preconditions
    documentResource.validateDocumentId(documentId);
    assertContentExists(documentId, contentId);

    // fetch document and content elements
    Document document = documentService.getDocument(documentId) //
        .orElseThrow(() -> new NotFoundException("Document not found"));

    ContentElement contentElement = document.getContentElements().stream().filter(
        e -> e.getId().equals(contentId)).findFirst().orElseThrow(
            () -> new NotFoundException("Content element not found"));

    return returnSingleContentElement(document, contentElement);
  }

  public enum Return {
    /**
     * Return the only element matching the query. Fail if more than one element matches.
     */
    only,
    /**
     * Return the first element matching the query. Silently ignore other matches.
     */
    first,
    /**
     * Return all elements matching the query using a MIME Multipart body.
     */
    all
  }

  @PreSignedUrlEnabled
  @GetMapping(value = "{documentID}/content")
  @ApiOperation(value = "Queries content elements", produces = "*/*")
  @ApiResponses({
      @ApiResponse(code = 200, message = "Content element found", response = byte[].class),
      @ApiResponse(code = 404, message = "Document or content element not found")
  })
  @Timed(description = "get content element", extraTags = {
      "operation", "retrieve", "target", "content"
  }, value = "eureka.content.get")
  public ResponseEntity<?> query(
      @ApiParam(value = "ID of the document") @PathVariable("documentID") final String documentId,
      @ApiParam(value = "Role(s) of the content elements to be fetched. Multiple roles can be specified separated by comma") @RequestParam(name = "role", required = false) final List<String> roles,
      @ApiParam(value = "How and what to return") @RequestParam(name = "return", required = false, defaultValue = "first") final Return ret) {
    // preconditions
    documentResource.validateDocumentId(documentId);

    // fetch document and content elements
    Document document = documentService.getDocument(documentId) //
        .orElseThrow(() -> new NotFoundException("Document not found"));

    Stream<ContentElement> elements = document.getContentElements().stream();

    // filter by roles
    if (null != roles)
      elements = elements.filter(ce -> roles.contains(ce.getRole()));

    List<ContentElement> matches = elements.collect(Collectors.toList());

    // return mode
    switch (ret){
      case only :
        if (matches.size() > 1)
          throw new NotAcceptableException("More than one content element matches the query");
        // fall-through

      case first :
        if (matches.isEmpty())
          throw new NotFoundException("No matching content element");

        return returnSingleContentElement(document, matches.get(0));

      case all :
        return returnMultipleElementsAsMultipart(document, matches);

      default :
        throw new NotAcceptableException("Unrecognized return mode");
    }
  }

  private ResponseEntity<MultiValueMap<String, HttpEntity<?>>> returnMultipleElementsAsMultipart(
      final Document document, final List<ContentElement> matches) {
    MultiValueMap<String, HttpEntity<?>> mbb = new LinkedMultiValueMap<>(matches.size());

    matches.forEach(ce -> mbb.add(ce.getRole(), returnSingleContentElement(document, ce)));

    return ResponseEntity.ok() //
        .lastModified(document.getDateModified() != null
            ? document.getDateModified().getTime()
            : document.getDateCreated().getTime()) //
        .header(HttpHeaders.CONTENT_TYPE, "multipart/mixed") //
        .body(mbb);
  }

  private ResponseEntity<?> returnSingleContentElement(final Document document, final ContentElement contentElement) {
    // retrieve content
    StoreObject storeObject = contentElementService.getContentElement(document.getDocumentId(), contentElement.getId());
    if (storeObject == null)
      throw new NotFoundException("Object not found in backing store");

    LOGGER.info("add Content response");

    ContentDisposition contentDisposition = ContentDisposition //
        .builder("inline") //
        .name(contentElement.getRole()).filename(contentElement.getFileName()) //
        .creationDate(document.getDateCreated().toInstant().atZone(ZoneId.systemDefault())) //
        .modificationDate(document.getDateModified().toInstant().atZone(ZoneId.systemDefault())) //
        .size(contentElement.getLength()) //
        .build();

    return ResponseEntity.ok() //
        .lastModified(document.getDateModified() != null
            ? document.getDateModified().getTime()
            : document.getDateCreated().getTime()) //
        .header(HttpHeaders.CONTENT_DISPOSITION, contentDisposition.toString()) //
        .header(HttpHeaders.CONTENT_TYPE, contentElement.getType().toString()) //
        .header(HttpHeaders.CONTENT_LENGTH, Long.toString(contentElement.getLength())) //
        .body(new InputStreamResource( //
            storeObject.getInputStream(), document.getDocumentId() + "/" + contentElement.getId()));
  }

  @PostMapping(consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
  @ApiOperation(value = "Creates a new document with content elements", notes = "The ID of the newly created document is taken from the posted document if "
      + "present, otherwise generated automatically")
  @ApiResponses({
      @ApiResponse(code = 201, message = "Document created. Return the document JSON"),
      @ApiResponse(code = 409, message = "Document already exists")
  })
  @Transactional
  public ResponseEntity<DocumentDto> createDocumentFromMultipart(
      @RequestPart(name = DOCUMENT_FORM_ELEMENT_NAME, required = false) @Valid final Optional<DocumentDto> requestDto, //
      final AllRequestParts files, // mapped using AllRequestPartsMethodArgumentResolver
      @ApiParam(value = "The list of facets to be included in the response; return all facets if empty") @RequestParam(name = "facets", required = false) final List<String> requestedFacets)
      throws Exception {
    DocumentDto doc = requestDto.orElse(new DocumentDto());

    /*
     * We need to validate at this point lest we create content elements with an invalid id.
     * DocumentResource.create(...) will perform the same validation again, but the validation
     * should be cheap enough not to be a problem.
     */
    documentResource.validate(f -> f.validateCreate(doc));

    if (Strings.isNullOrEmpty(doc.getDocumentId())) {
      doc.setDocumentId(idGenerationStrategy.createDocumentId());
    }

    List<ContentElement> elements = new ArrayList<>();
    for (MultipartFile file : (Iterable<MultipartFile>) files.getAllParts().stream().filter(
        f -> !f.getName().equals(DOCUMENT_FORM_ELEMENT_NAME))::iterator) {
      elements.add(//
          contentElementService.createContentElement(doc.getDocumentId(), null, file.getInputStream(), file.getName(),
              file.getOriginalFilename(), file.getContentType(), messageDigest, elements));
    }

    doc.setFacet("contentElements", documentMapper.map(elements, CE_DTO_TYPE));

    // delegate the rest of the document creation to the document resource
    LOGGER.info("create Document with Content delegate");

    // create document and return as status 201 CREATED
    DocumentDto created = documentResource.create(doc, requestedFacets);

    return ResponseEntity//
        .created(URI.create(created.getLink(IanaLinkRelations.SELF)
            .orElseThrow(() -> new RuntimeException("self rel not populated")).getHref())) //
        .lastModified(created.getFacetData(mdFacet).orElse(new Date()).getTime()) //
        .body(created);
  }

  @PostMapping(value = "{documentId}/content", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
  @ApiOperation(value = "Add content elements to a document")
  @ApiResponses({
      @ApiResponse(code = 200, message = "Content element(s) added"),
      @ApiResponse(code = 400, message = "Invalid documentID supplied"),
      @ApiResponse(code = 404, message = "Document not found")
  })
  @Transactional
  @Timed(description = "create document with content", extraTags = {
      "operation", "create", "target", "document-with-content"
  }, value = "eureka.document.create-with-content")
  public DocumentDto add(final HttpServletRequest request,
      @ApiParam("ID of the document to be updated") @PathVariable("documentId") final String documentId,
      final AllRequestParts files, // mapped using AllRequestPartsMethodArgumentResolver
      @ApiParam("The list of facets to be included in the response; return all facets if empty") @RequestParam(name = "facets", required = false) final List<String> requestedFacets)
      throws Exception {
    // preconditions
    documentResource.validateDocumentId(documentId);

    // fetch document
    Document document = documentService.getDocument(documentId).orElseThrow(
        () -> new NotFoundException("Document not found"));

    List<ContentElement> contentElements = document.getContentElements();

    // update list of content elements with newly stored one
    for (MultipartFile file : (Iterable<MultipartFile>) files.getAllParts().stream().filter(
        f -> !f.getName().equals(DOCUMENT_FORM_ELEMENT_NAME))::iterator) {
      contentElements.add(//
          contentElementService.createContentElement(documentId, null, file.getInputStream(), file.getName(),
              file.getOriginalFilename(), file.getContentType(), messageDigest, contentElements));
    }

    // persist document
    return documentResource.update(request, documentMapper.map(document, DocumentDto.class), document, requestedFacets);
  }

  @PutMapping(value = "{documentID}/content/{content}", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
  @ApiOperation(value = "Updates a specific content element")
  @ApiResponses({
      @ApiResponse(code = 202, message = "Content element updated"),
      @ApiResponse(code = 400, message = "Invalid documentID supplied"),
      @ApiResponse(code = 404, message = "Content element not found")
  })
  @Transactional
  @Timed(description = "update content element", extraTags = {
      "operation", "update", "target", "content"
  }, value = "eureka.content.update")
  public DocumentDto update(final HttpServletRequest request,
      @ApiParam("ID of the document") @PathVariable("documentID") final String documentId,
      @ApiParam("ID of the content element to be updated") @PathVariable("content") final String contentId,
      final AllRequestParts files, // mapped using AllRequestPartsMethodArgumentResolver
      @ApiParam("The list of facets to be included in the response; return all facets if empty") @RequestParam(name = "facets", required = false) final List<String> requestedFacets)
      throws Exception {
    // preconditions
    assertContentExists(documentId, contentId);

    documentResource.validateDocumentId(documentId);

    // fetch document
    Document document = documentService.getDocument(documentId).orElseThrow(
        () -> new NotFoundException("Document not found"));

    List<ContentElement> contentElements = document.getContentElements();

    // Find index of insertion point and remove CE to be replaced
    ContentElement toBeReplaced = contentElements.stream().filter(
        e -> e.getId().equals(contentId)).findAny().orElseThrow(
            () -> new NotFoundException("Content element not found"));
    int insertionPoint = contentElements.indexOf(toBeReplaced);
    contentElements.remove(insertionPoint);

    // add new element(s)
    for (MultipartFile file : (Iterable<MultipartFile>) files.getAllParts().stream().filter(
        f -> !f.getName().equals(DOCUMENT_FORM_ELEMENT_NAME))::iterator) {
      contentElements.add(insertionPoint++, //
          contentElementService.createContentElement(documentId, null, file.getInputStream(), file.getName(),
              file.getOriginalFilename(), file.getContentType(), messageDigest, contentElements));
    }

    // persist document
    return documentResource.update(request, documentMapper.map(document, DocumentDto.class), document, requestedFacets);
  }

  @DeleteMapping(value = "{documentID}/content/{element}")
  @ResponseStatus(value = HttpStatus.NO_CONTENT)
  @ApiOperation(value = "Deletes a content element by ID")
  @ApiResponses({
      @ApiResponse(code = 204, message = "Content element deleted"),
      @ApiResponse(code = 400, message = "Invalid documentID supplied"),
      @ApiResponse(code = 404, message = "Document/Content not found")
  })
  @Transactional
  @Timed(description = "delete content element", extraTags = {
      "operation", "delete", "target", "content"
  }, value = "eureka.content.delete")
  public void delete(final HttpServletRequest request,
      @ApiParam(value = "ID of the document to be fetched") @PathVariable("documentID") final String documentId,
      @ApiParam(value = "ID of the content element to be deleted") @PathVariable("element") final String elementId,
      @ApiParam(value = "The list of facets to be included in the response; return all facets if empty") @RequestParam(name = "facets", required = false) final List<String> requestedFacets) {
    documentResource.validateDocumentId(documentId);

    Document doc = documentService.getDocument(documentId).orElseThrow(
        () -> new NotFoundException("Document not found"));

    assertContentExists(documentId, elementId);

    List<ContentElement> contentElements = doc.getContentElements();

    contentElements.removeIf(obj -> obj.getId().equals(elementId));

    if (!contentElementService.deleteContentElement(documentId, elementId)) {
      throw new ConflictException(
          "The request could not be completed due to a conflict with the current state of the target resource. ");
    }

    documentResource.update(request, documentMapper.map(doc, DocumentDto.class), doc, requestedFacets);
  }

  private void assertContentExists(final String documentId, final String contentId) {
    if (!contentElementService.checkContentExist(documentId, contentId)) {
      throw new NotFoundException("Content not found");
    }
  }
}
