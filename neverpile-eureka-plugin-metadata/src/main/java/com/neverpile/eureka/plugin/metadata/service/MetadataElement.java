package com.neverpile.eureka.plugin.metadata.service;

import java.util.Arrays;
import java.util.Date;
import java.util.Objects;

import javax.ws.rs.core.MediaType;
import javax.xml.bind.annotation.adapters.XmlJavaTypeAdapter;

import com.fasterxml.jackson.databind.annotation.JsonDeserialize;
import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import com.neverpile.eureka.model.EncryptableElement;
import com.neverpile.eureka.model.MediaTypeDeserializer;
import com.neverpile.eureka.model.MediaTypeSerializer;
import com.neverpile.eureka.util.JacksonObjectNodeAdapter;

public class MetadataElement extends EncryptableElement {
  private String schema;

  @JsonSerialize(using = MediaTypeSerializer.class)
  @JsonDeserialize(using = MediaTypeDeserializer.class)
  private MediaType contentType;

  private byte[] content;

  private Date dateCreated;

  private Date dateModified;
  
  public String getSchema() {
    return schema;
  }

  public void setSchema(final String schema) {
    this.schema = schema;
  }

  public MediaType getContentType() {
    return contentType;
  }

  public void setContentType(final MediaType format) {
    this.contentType = format;
  }

  @XmlJavaTypeAdapter(value = JacksonObjectNodeAdapter.class)
  public byte[] getContent() {
    return content;
  }

  public void setContent(final byte[] content) {
    this.content = content;
  }

  @Override
  public int hashCode() {
    return Objects.hash(super.hashCode(), schema, contentType, getEncryption(), content);
  }

  @Override
  public boolean equals(final Object obj) {
    if (this == obj)
      return true;
    if (!super.equals(obj))
      return false;
    if (getClass() != obj.getClass())
      return false;
    MetadataElement other = (MetadataElement) obj;
    if (!Arrays.equals(content, other.content)) 
       return false;
    if (contentType == null) {
      if (other.contentType != null)
        return false;
    } else if (!contentType.equals(other.contentType))
      return false;
    if (schema == null) {
      if (other.schema != null)
        return false;
    } else if (!schema.equals(other.schema))
      return false;
    return true;
  }

  public Date getDateCreated() {
    return dateCreated;
  }

  public void setDateCreated(final Date dateCreated) {
    this.dateCreated = dateCreated;
  }

  public Date getDateModified() {
    return dateModified;
  }

  public void setDateModified(final Date dateModified) {
    this.dateModified = dateModified;
  }
}
