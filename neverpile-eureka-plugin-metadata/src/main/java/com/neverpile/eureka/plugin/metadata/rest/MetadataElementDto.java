package com.neverpile.eureka.plugin.metadata.rest;

import java.util.Arrays;
import java.util.Date;

import javax.validation.constraints.Pattern;
import javax.ws.rs.core.MediaType;
import javax.xml.bind.annotation.adapters.XmlJavaTypeAdapter;

import org.springframework.hateoas.ResourceSupport;

import com.fasterxml.jackson.databind.annotation.JsonDeserialize;
import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import com.neverpile.eureka.model.EncryptionType;
import com.neverpile.eureka.model.MediaTypeDeserializer;
import com.neverpile.eureka.model.MediaTypeSerializer;
import com.neverpile.eureka.rest.api.document.IDto;
import com.neverpile.eureka.util.JacksonObjectNodeAdapter;

import io.swagger.annotations.ApiModel;
import io.swagger.annotations.ApiModelProperty;

@ApiModel(value = "MetadataElement", description = "A metadata element associated with a document")
public class MetadataElementDto extends ResourceSupport implements IDto {
  private String schema;

  @ApiModelProperty(value = "The MIME-Type of the metadata element as specified in RFC 2045 without parameters", dataType = "string")
  @Pattern(regexp = "[-\\w+]+/[-\\w+]+")
  @JsonSerialize(using = MediaTypeSerializer.class)
  @JsonDeserialize(using = MediaTypeDeserializer.class)
  private MediaType contentType;

  private byte[] content;

  private EncryptionType encryption;

  private String keyHint;

  private Date dateCreated;

  private Date dateModified;
  
  @ApiModelProperty("A reference to a schema which the element is supposed to conform to, "
      + "e.g. an XML namespace definition, an XSD reference, a JSON schema reference etc.")
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

  @ApiModelProperty("The content (payload) of the metadata element")
  @XmlJavaTypeAdapter(value = JacksonObjectNodeAdapter.class)
  public byte[] getContent() {
    return content;
  }

  public void setContent(final byte[] content) {
    this.content = content;
  }

  @ApiModelProperty("The type of encryption the content element is subject to")
  public EncryptionType getEncryption() {
    return encryption;
  }

  public void setEncryption(final EncryptionType encryption) {
    this.encryption = encryption;
  }

  @ApiModelProperty("A key hint may be used by a client to store information about which key was used to encrypt the element")
  public String getKeyHint() {
    return keyHint;
  }

  public void setKeyHint(final String keyHint) {
    this.keyHint = keyHint;
  }

  @ApiModelProperty("The timestamp at which the element was created")
  public Date getDateCreated() {
    return dateCreated;
  }

  public void setDateCreated(final Date dateCreated) {
    this.dateCreated = dateCreated;
  }

  @ApiModelProperty("The timestamp at which the element was last modified")
  public Date getDateModified() {
    return dateModified;
  }

  public void setDateModified(final Date dateModified) {
    this.dateModified = dateModified;
  }

  @Override
  public int hashCode() {
    final int prime = 31;
    int result = super.hashCode();
    result = prime * result + Arrays.hashCode(content);
    result = prime * result + ((contentType == null) ? 0 : contentType.hashCode());
    result = prime * result + ((dateCreated == null) ? 0 : dateCreated.hashCode());
    result = prime * result + ((dateModified == null) ? 0 : dateModified.hashCode());
    result = prime * result + ((encryption == null) ? 0 : encryption.hashCode());
    result = prime * result + ((keyHint == null) ? 0 : keyHint.hashCode());
    result = prime * result + ((schema == null) ? 0 : schema.hashCode());
    return result;
  }

  @Override
  public boolean equals(final Object obj) {
    if (this == obj)
      return true;
    if (getClass() != obj.getClass())
      return false;
    MetadataElementDto other = (MetadataElementDto) obj;
    if (!Arrays.equals(content, other.content))
      return false;
    if (contentType == null) {
      if (other.contentType != null)
        return false;
    } else if (!contentType.equals(other.contentType))
      return false;
    if (dateCreated == null) {
      if (other.dateCreated != null)
        return false;
    } else if (!dateCreated.equals(other.dateCreated))
      return false;
    if (dateModified == null) {
      if (other.dateModified != null)
        return false;
    } else if (!dateModified.equals(other.dateModified))
      return false;
    if (encryption != other.encryption)
      return false;
    if (keyHint == null) {
      if (other.keyHint != null)
        return false;
    } else if (!keyHint.equals(other.keyHint))
      return false;
    if (schema == null) {
      if (other.schema != null)
        return false;
    } else if (!schema.equals(other.schema))
      return false;
    return true;
  }
}
