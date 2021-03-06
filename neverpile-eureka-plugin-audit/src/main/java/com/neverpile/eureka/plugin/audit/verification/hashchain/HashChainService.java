package com.neverpile.eureka.plugin.audit.verification.hashchain;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.Collections;
import java.util.List;
import java.util.Optional;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.neverpile.eureka.api.NeverpileException;
import com.neverpile.eureka.model.ObjectName;
import com.neverpile.eureka.plugin.audit.service.AuditEvent;
import com.neverpile.eureka.plugin.audit.service.AuditLogService;
import com.neverpile.eureka.plugin.audit.storage.AuditStorageBridge;
import com.neverpile.eureka.plugin.audit.verification.AuditHash;
import com.neverpile.eureka.plugin.audit.verification.HashStrategyService;
import com.neverpile.eureka.tx.atomic.DistributedAtomicReference;
import com.neverpile.eureka.tx.atomic.DistributedAtomicType;

public class HashChainService implements HashStrategyService {

  private static final Logger LOGGER = LoggerFactory.getLogger(HashChainService.class);

  @Autowired
  private AuditStorageBridge auditStorageBridge;

  @Autowired
  private ObjectMapper objectMapper;

  @Autowired
  private AuditLogService auditLogService;

  @DistributedAtomicType("neverpile-audit-currentProof")
  DistributedAtomicReference<HashChainStoreObject> currentProof;

  private final ObjectName currentHashName = getObjectNameOf("current_hash");

  @Value("${neverpile-eureka.audit.verification.seed:NotSoSecretSeed}")
  private String rootNodeHashSeed = "NotSoSecretSeed";

  private HashChainStoreObject rootProof;

  public HashChainService() {
    AuditHash rootHash = new AuditHash(rootNodeHashSeed.getBytes());
    HashChainStoreObject root = new HashChainStoreObject(new HashChainLink("root", rootHash));
    root.setLinkHash(new AuditHash(rootHash, rootHash));
    this.rootProof = root;
  }

  @Override
  public void addElement(AuditEvent auditEvent) {
    addElements(Collections.singletonList(auditEvent));
  }

  @Override
  public void addElements(List<AuditEvent> newLogEvents) {
    if (0 >= newLogEvents.size()) {
      return;
    }
    initCurrentProof();
    // Insert all new events as link into chain
    HashChainStoreObject nextProofLink = null;
    ByteArrayOutputStream baos = null;

    for (AuditEvent auditEvent : newLogEvents) {
      nextProofLink = currentProof.alterAndGet(input -> {
        HashChainStoreObject nextProof = new HashChainStoreObject();
        nextProof.setAuditId(auditEvent.getAuditId());
        nextProof.setParentId(input.getAuditId());
        nextProof.setLinkHash(new AuditHash(input.getLinkHash(), new AuditHash(auditEvent)));
        return nextProof;
      });

      // FIXME: replace BAISO/BAOS with buffer manager for efficiency
      baos = new ByteArrayOutputStream(65535);
      try {
        objectMapper.writeValue(baos, nextProofLink);
      } catch (IOException e) {
        LOGGER.error("Failed to serialize auditLog HashChainLink @{}", nextProofLink.getAuditId(), e);
        e.printStackTrace();
      }

      auditStorageBridge.putVerificationElement(getObjectNameOf(auditEvent.getAuditId()), new ByteArrayInputStream(baos.toByteArray()), baos.size());
      LOGGER.info("Proof persisted for auditId: @{}", auditEvent.getAuditId());
    }
    if (!(null == nextProofLink) && currentProof.get().getAuditId().equals(nextProofLink.getAuditId())) {
      auditStorageBridge.updateHeadVerificationElement(new ByteArrayInputStream(baos.toByteArray()), baos.size());
    }
  }

  private void initCurrentProof() {
    // Get current proof
    Optional<HashChainStoreObject> initProof = Optional.ofNullable(currentProof.get());
    // If distributed atomic is not jet set.
    if (!initProof.isPresent()) {
      // Get current hash from db.
      Optional<InputStream> is = auditStorageBridge.getHeadVerificationElement();
      // If current hash was found in db.
      if (is.isPresent()) {
        try {
          initProof = Optional.ofNullable(
              objectMapper.readValue(is.get(), objectMapper.getTypeFactory().constructType(HashChainStoreObject.class)));
        } catch (IOException e) {
          LOGGER.error("Failed to serialize auditLog HashChainHead", e);
          e.printStackTrace();
        }

        // Try to initialize atomic.
        currentProof.compareAndSet(null, initProof.get());
      } else {
        // initialize chain with new root.
        currentProof.compareAndSet(null, rootProof);
      }
    }
  }

  @Override
  public boolean verifyHash(AuditEvent auditEvent) {
    Optional<HashChainStoreObject> link = getHashChainLink(getObjectNameOf(auditEvent.getAuditId()));
    if (!link.isPresent() || null == link.get().getParentId()) {
      return false;
    }
    Optional<HashChainStoreObject> proofLink = getHashChainLink(getObjectNameOf(link.get().getParentId()));
    if (proofLink.isPresent()) {
      return link.get().getLinkHash().equals(new AuditHash(proofLink.get().getLinkHash(), new AuditHash(auditEvent)));
    } else {
      return false;
    }
  }

  @Override
  public boolean completeVerification() {
    Optional<HashChainStoreObject> curProof = getHashChainLink(currentHashName);
    while (curProof.isPresent() && null != curProof.get().getParentId()) {
      Optional<AuditEvent> curEvent = auditLogService.getEvent(curProof.get().getAuditId());
      if (curEvent.isPresent()) {
        Optional<HashChainStoreObject> parentProof = getHashChainLink(getObjectNameOf(curProof.get().getParentId()));
        if (!curProof.get().getLinkHash().equals(
            new AuditHash(parentProof.get().getLinkHash(), new AuditHash(curEvent.get())))) {
          return false; // tampered audit event found.
        }
        curProof = parentProof;
      } else {
        throw new NeverpileException("AuditLog Event with Id `" + curProof.get().getAuditId() + "` not found.");
      }
    }
    return true;
  }


  private Optional<HashChainStoreObject> getHashChainLink(ObjectName auditHashObjectName) {
    HashChainStoreObject link = null;
    Optional<InputStream> is = auditStorageBridge.getVerificationElement(auditHashObjectName);
    if (is.isPresent()) {
      try {
        link = objectMapper.readValue(is.get(), objectMapper.getTypeFactory().constructType(HashChainStoreObject.class));
      } catch (IOException e) {
        LOGGER.error("Failed to serialize auditLog Verification @{}", auditHashObjectName, e);
        e.printStackTrace();
      }
    }
    return Optional.ofNullable(link);
  }

  private ObjectName getObjectNameOf(String auditId) {
    return ObjectName.of("eureka", "hash_chain", auditId);
  }
}
