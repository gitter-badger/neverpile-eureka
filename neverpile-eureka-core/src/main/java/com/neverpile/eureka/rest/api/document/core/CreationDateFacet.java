package com.neverpile.eureka.rest.api.document.core;

import java.util.Date;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import com.fasterxml.jackson.databind.JavaType;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.type.TypeFactory;
import com.neverpile.authorization.api.AuthorizationContext;
import com.neverpile.authorization.policy.impl.SingleValueAuthorizationContext;
import com.neverpile.eureka.api.index.Field;
import com.neverpile.eureka.api.index.Field.Type;
import com.neverpile.eureka.api.index.Schema;
import com.neverpile.eureka.model.Document;
import com.neverpile.eureka.rest.api.document.DocumentDto;
import com.neverpile.eureka.rest.api.document.DocumentFacet;

@Component
@ConditionalOnProperty(name = "neverpile.facet.creationDate.enabled", matchIfMissing = true)
public class CreationDateFacet implements DocumentFacet<Date> {
  @Autowired
  private ObjectMapper objectMapper;

  @Override
  public String getName() {
    return "dateCreated";
  }

  @Override
  public JavaType getValueType(final TypeFactory f) {
    return f.constructType(Date.class);
  }

  @Override
  public void beforeCreate(final Document toBeCreated, final DocumentDto requestDto) {
    /*
     * FIXME: allow caller to set dateCreated/dateModified if a certain permission is present. This
     * is required for data migrated from other systems where the lifecycle dates should be
     * preserved.
     */
    Date now = new Date();
    toBeCreated.setDateCreated(now);
  }

  @Override
  public void onRetrieve(final Document document, final DocumentDto dto) {
    if (null != document.getDateCreated()) {
      dto.setFacet(getName(), document.getDateCreated());
    }
  }

  @Override
  public Schema getIndexSchema() {
    return new Field(getName(), Type.DateTime);
  }

  @Override
  public JsonNode getIndexData(final Document document) {
    return objectMapper.getNodeFactory().numberNode(
        document.getDateCreated() != null ? document.getDateCreated().getTime() : null);
  }

  public AuthorizationContext getAuthorizationContextContribution(final Document document) {
    return new SingleValueAuthorizationContext(document.getDateCreated());
  }

  @Override
  public String toString() {
    return "DocumentFacet{" + "name=" + getName() + '}';
  }
}
