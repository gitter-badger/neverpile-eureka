package com.neverpile.eureka.rest.api.document;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Set;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.stream.Collectors;

import javax.servlet.http.HttpServletRequest;

import org.modelmapper.ModelMapper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import com.google.common.base.Strings;
import com.neverpile.eureka.api.DocumentIdGenerationStrategy;
import com.neverpile.eureka.api.DocumentService;
import com.neverpile.eureka.model.Document;
import com.neverpile.eureka.rest.api.document.DocumentFacet.ConstraintViolation;
import com.neverpile.eureka.rest.api.exception.AlreadyExistsException;
import com.neverpile.eureka.rest.api.exception.BadInputParameter;
import com.neverpile.eureka.rest.api.exception.NotFoundException;
import com.neverpile.eureka.rest.api.exception.ValidationError;
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
@Api(tags = "Document", authorizations = {
    @Authorization(value = "oauth")
})
public class DocumentResource {

  @Autowired
  private DocumentService documentService;

  @Autowired
  private DocumentIdGenerationStrategy idGenerationStrategy;

  @Autowired(required = false)
  private final List<DocumentFacet<?>> facets = new ArrayList<DocumentFacet<?>>();

  @Autowired
  @Qualifier("document")
  ModelMapper documentMapper;

  // GET - Returns a specific document by ID
  @PreSignedUrlEnabled
  @GetMapping(value = "{documentID}")
  @ApiOperation(value = "Fetches a document by ID")
  @ApiResponses({
      @ApiResponse(code = 200, message = "Document found"),
      @ApiResponse(code = 400, message = "Invalid documentID supplied"),
      @ApiResponse(code = 404, message = "Document not found")
  })
  @Timed(description = "get document", extraTags = {
      "operation", "retrieve", "target", "document"
  }, value = "eureka.document.get")
  public DocumentDto get(
      @ApiParam(value = "The ID of the document to be fetched") @PathVariable("documentID") final String documentId,
      @ApiParam(value = "The list of facets to be included in the response; return all facets if empty") @RequestParam(name = "facets", required = false) final List<String> requestedFacets) {
    // @formatter:on
    Document document = getDocument(documentId);

    DocumentDto dto = new DocumentDto();

    activeFacets(requestedFacets, f -> f.onRetrieve(document, dto));

    return dto;
  }

  @PostMapping(consumes = {
      MediaType.APPLICATION_JSON_VALUE
  })
  @ApiOperation(value = "Creates a new document", //
      notes = "The ID of the newly created document is taken from the posted document if present, "
          + "otherwise generated automatically.")
  @ApiResponses({
      @ApiResponse(code = 201, message = "Document created"),
      @ApiResponse(code = 409, message = "Document with the given id already exists")
  })
  @Transactional
  @Timed(description = "create document", extraTags = {
      "operation", "create", "target", "document"
  }, value = "eureka.document.create")
  public DocumentDto create(@ApiParam @RequestBody final DocumentDto requestDto,
      @ApiParam(value = "The list of facets to be included in the response; return all facets if empty") @RequestParam(name = "facets", required = false) final List<String> requestedFacets) {

    validate(f -> f.validateCreate(requestDto));

    Document newDocument = documentMapper.map(requestDto, Document.class);
    if (checkDocumentExist(newDocument.getDocumentId())) {
      throw new AlreadyExistsException("DocumentId already exists");
    }

    allFacets(f -> f.beforeCreate(newDocument, requestDto));

    Document created = documentService.createDocument(newDocument);
    DocumentDto responseDto = new DocumentDto();

    // will populate everything, therefore...
    allFacets(f -> f.afterCreate(created, responseDto));

    // ...prune unwanted stuff before returning
    // FIXME: come up with a better solution.
    // Discussion so far:
    // https://levigo.de/stash/projects/CUBE/repos/neverpile-eureka/pull-requests/20/overview?commentId=19351
    pruneUnwantedFacetData(requestedFacets, responseDto);

    return responseDto;
  }

  // just some syntactic sugar
  private void allFacets(final Consumer<DocumentFacet<?>> facetConsumer) {
    facets.forEach(facetConsumer);
  }

  public void validate(final Function<DocumentFacet<?>, Set<ConstraintViolation>> validationFunction) {
    Set<ConstraintViolation> violations = facets.stream() //
        .map(f -> validationFunction.apply(f)) //
        .flatMap(r -> r.stream()) //
        .collect(Collectors.toSet());

    if (!violations.isEmpty())
      throw new ValidationError(violations);
  }

  private void pruneUnwantedFacetData(final List<String> requestedFacets, final DocumentDto responseDto) {
    responseDto.getFacets().keySet().removeAll(unwantedFacets(requestedFacets));
  }

  private void activeFacets(final List<String> requestedFacets, final Consumer<DocumentFacet<?>> facetConsumer) {
    (requestedFacets != null && !requestedFacets.isEmpty()
        ? facets.stream().filter(f -> requestedFacets.contains(f.getName()))
        : facets.stream().filter(DocumentFacet::includeByDefault)) //
            .forEach(facetConsumer);
  }

  private Collection<String> unwantedFacets(final List<String> requestedFacets) {
    return (requestedFacets != null && !requestedFacets.isEmpty()
        ? facets.stream().filter(f -> !requestedFacets.contains(f.getName()))
        : facets.stream().filter(f -> !f.includeByDefault())) //
            .map(f -> f.getName()).collect(Collectors.toSet());
  }

  @PutMapping(value = "{documentID}", consumes = {
      MediaType.APPLICATION_JSON_VALUE
  })
  @ApiOperation(value = "Update a document", notes = "The document must already exist. It is not possible to create a new document with this method. ")
  @ApiResponses({
      @ApiResponse(code = 202, message = "Document updated"),
      @ApiResponse(code = 400, message = "Invalid documentID supplied"),
      @ApiResponse(code = 404, message = "Document not found")
  })
  @Transactional
  @Timed(description = "update document", extraTags = {
      "operation", "update", "target", "document"
  }, value = "eureka.document.update")
  public DocumentDto update(final HttpServletRequest request,
      @ApiParam("The ID of the document to be updated") @PathVariable("documentID") final String documentId,
      @ApiParam() @RequestBody final DocumentDto requestDto,
      @ApiParam(value = "The list of facets to be included in the response; return all facets if empty") @RequestParam(name = "facets", required = false) final List<String> requestedFacets) {
    if (!checkDocumentExist(documentId)) {
      throw new NotFoundException("Document not found");
    }

    if (Strings.isNullOrEmpty(requestDto.getDocumentId())) {
      requestDto.setDocumentId(documentId);
    }
    return update(request, requestDto, getDocument(documentId), requestedFacets);
    // @formatter:off
  }

  public DocumentDto update(final HttpServletRequest request, final DocumentDto requestDto, final Document storedDocument,
      final List<String> requestedFacets) {
    Document currentDocument = getDocument(requestDto.getDocumentId());
    Document updatedDocument = documentMapper.map(currentDocument, Document.class); // deep copy

    validate(f -> f.validateUpdate(currentDocument, requestDto));

    allFacets(f -> f.beforeUpdate(currentDocument, updatedDocument, requestDto));
    
    Document updated = documentService.update(updatedDocument) //
        .orElseThrow(() -> new NotFoundException("Document not found"));

    DocumentDto responseDto = new DocumentDto();

    // will populate everything, therefore...
    allFacets(f -> f.afterUpdate(updated, responseDto));

    // ...prune unwanted stuff before returning
    pruneUnwantedFacetData(requestedFacets, responseDto);

    return responseDto;
  }

  @DeleteMapping(value = "{documentID}")
  @ResponseStatus(HttpStatus.NO_CONTENT)
  @ApiOperation(value = "Delete a document identified by its ID")
  @ApiResponses({
      @ApiResponse(code = 204, message = "Document successfully deleted"),
      @ApiResponse(code = 400, message = "Invalid documentID supplied"),
      @ApiResponse(code = 404, message = "Document not found"),
      @ApiResponse(code = 409, message = "The request could not be completed due to a conflict with the current state of the target resource.")
  })
  @Transactional
  @Timed(description = "delete document", extraTags = {"operation", "delete", "target", "document"}, value="eureka.document.delete")
  public void delete(
      @ApiParam(value = "The ID of the document to be deleted") @PathVariable("documentID") final String documentId) {
    Document document = getDocument(documentId);
    if (null == document) {
      throw new NotFoundException("Document not found");
    }

    allFacets(f -> f.validateDelete(document));

    allFacets(f -> f.onDelete(document));

    documentService.deleteDocument(documentId);
  }

  /**
   * The method check_BadInputParameter checks the documentenId for a specific pattern. If the Id
   * does not meet the required pattern, this method throws an APIExeption.
   *
   * @param documentId Id to check
   */
  public void validateDocumentId(final String documentId) {
    if (!idGenerationStrategy.validateDocumentId(documentId)) {
      throw new BadInputParameter("Invalid documentID supplied");
    }
  }

  /**
   * The get_Document method returns the document with the given id. If no document could be found,
   * this method throws an APIExeption.
   *
   * @param documentId Id of document to get
   * @return the document or throws an APIExeption
   */
  private Document getDocument(final String documentId) {
    return documentService.getDocument(documentId).orElseThrow(() -> new NotFoundException("Document not found"));
  }

  /**
   * The checkDocumentExist method checks if there is a document with the given id.
   *
   * @param documentId Id of document to check
   * @return false, if this is not the case
   */
  private boolean checkDocumentExist(final String documentId) {
    return documentService.documentExists(documentId);
  }

}
