package com.neverpile.authorization.service.impl;

import static com.neverpile.authorization.service.impl.SimpleMutablePolicyRepository.*;
import static com.neverpile.eureka.api.ObjectStoreService.*;
import static java.nio.charset.StandardCharsets.*;
import static java.time.Instant.*;
import static java.time.temporal.ChronoUnit.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.BDDMockito.*;
import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.time.Instant;
import java.util.Date;
import java.util.function.Supplier;
import java.util.stream.Stream;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.test.context.junit4.SpringRunner;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.neverpile.authorization.policy.AccessPolicy;
import com.neverpile.authorization.policy.Effect;
import com.neverpile.authorization.service.impl.SimpleMutablePolicyRepository;
import com.neverpile.eureka.api.ObjectStoreService;
import com.neverpile.eureka.model.ObjectName;

@RunWith(SpringRunner.class)
@SpringBootTest
public class SimpleMutablePolicyRepositoryTest {
  private final class SimpleStoreObject implements ObjectStoreService.StoreObject {
    private final ObjectName name;
    private final String version;
    private final Supplier<InputStream> contentSupplier;

    public SimpleStoreObject(final ObjectName name, final String version, final byte content[]) {
      this(name, version, () -> new ByteArrayInputStream(content));
    }

    public SimpleStoreObject(final ObjectName name, final String version, final Supplier<InputStream> contentSupplier) {
      this.name = name;
      this.version = version;
      this.contentSupplier = contentSupplier;
    }

    @Override
    public String getVersion() {
      return version;
    }

    @Override
    public ObjectName getObjectName() {
      return name;
    }

    @Override
    public InputStream getInputStream() {
      return contentSupplier.get();
    }
  }

  @Configuration
  @EnableAutoConfiguration
  public static class ServiceConfig {
    @Bean
    SimpleMutablePolicyRepository documentService() {
      return new SimpleMutablePolicyRepository();
    }
  }

  @MockBean
  ObjectStoreService mockObjectStore;

  @Autowired
  SimpleMutablePolicyRepository policyRepository;

  @Autowired
  ObjectMapper objectMapper;


  String policyPattern = "{\"validFrom\" : \"2018-01-01\",\"description\": \"%s\",\"default_effect\": \"DENY\",\"rules\": []}";

  private Instant now;

  private ObjectName oneMinuteAgoName;

  private ObjectName inOneHourName;

  private ObjectName threeHoursAgoName;

  private ObjectName oneHourAgoName;

  private Instant oneMinuteAgo;

  private Instant inOneHour;

  private Instant threeHoursAgo;

  private Instant oneHourAgo;

  @Test
  public void testThat_getCurrentWithNoPoliciesReturnsDefaultPolicy() throws Exception {
    // we don't want the default mockery initialized by initMocks()
    Mockito.reset(mockObjectStore);

    AccessPolicy currentPolicy = policyRepository.getCurrentPolicy();

    assertThat(currentPolicy.getDefaultEffect()).isEqualTo(Effect.DENY);
    assertThat(currentPolicy.getDescription()).contains("default");
    assertThat(currentPolicy.getValidFrom()).isBefore(new Date());
    assertThat(currentPolicy.getRules()).hasSize(1);
  }

  @Test
  public void testThat_getCurrentPolicyReturnsActivePolicy() throws Exception {
    AccessPolicy currentPolicy = policyRepository.getCurrentPolicy();

    assertThat(currentPolicy.getDescription()).contains("current");
  }

  @Test
  public void testThat_getCurrentPolicyArchivesExpiredPolicies() throws Exception {
    // we don't want the default mockery initialized by initMocks()
    Mockito.reset(mockObjectStore);

    // update names to not use the expired prefix
    threeHoursAgoName = POLICY_REPO_PREFIX.append(OBJECT_NAME_FORMATTER.format(threeHoursAgo));
    oneHourAgoName = POLICY_REPO_PREFIX.append(OBJECT_NAME_FORMATTER.format(oneHourAgo));
    
    // assume un-archived policies present
    given(mockObjectStore.list(POLICY_REPO_PREFIX)).willAnswer(i -> {
      return Stream.of( //
          new SimpleStoreObject(threeHoursAgoName, "1", String.format(policyPattern, "older").getBytes(UTF_8)), //
          new SimpleStoreObject(oneHourAgoName, "1", String.format(policyPattern, "old").getBytes(UTF_8)), //
          new SimpleStoreObject(oneMinuteAgoName, "1", String.format(policyPattern, "current").getBytes(UTF_8)), //
          new SimpleStoreObject(inOneHourName, "1", String.format(policyPattern, "upcoming").getBytes(UTF_8)));
    });
    
    policyRepository.getCurrentPolicy();

    System.out.println(threeHoursAgoName);
    System.out.println(oneHourAgoName);
    
    verify(mockObjectStore).put(eq(EXPIRED_POLICY_REPO_PREFIX.append(threeHoursAgoName.tail())), eq(NEW_VERSION),
        any());
    verify(mockObjectStore).delete(eq(threeHoursAgoName));
    verify(mockObjectStore).put(eq(EXPIRED_POLICY_REPO_PREFIX.append(oneHourAgoName.tail())), eq(NEW_VERSION), any());
    verify(mockObjectStore).delete(eq(oneHourAgoName));
  }

  @Test
  public void testThat_queryRepositoryReturnsOldCurrentAndUpcomingPolicies() throws Exception {
    assertThat( //
        policyRepository.queryRepository( //
            Date.from(now().minus(1, DAYS)), Date.from(now().plus(1, DAYS)), //
            Integer.MAX_VALUE //
        ).stream().map(AccessPolicy::getDescription) //
    ).containsExactly("older", "old", "current", "upcoming");
  }

  @Test
  public void testThat_queryRepositoryHonorsRangeLowerBound() throws Exception {
    assertThat( //
        policyRepository.queryRepository( //
            Date.from(now().minus(2, HOURS)), Date.from(now().plus(1, DAYS)), //
            Integer.MAX_VALUE //
        ).stream().map(AccessPolicy::getDescription) //
    ).containsExactly("old", "current", "upcoming");
  }

  @Test
  public void testThat_queryRepositoryHonorsRangeUpperBound() throws Exception {
    assertThat( //
        policyRepository.queryRepository( //
            Date.from(now().minus(1, DAYS)), Date.from(now()), //
            Integer.MAX_VALUE //
        ).stream().map(AccessPolicy::getDescription) //
    ).containsExactly("older", "old", "current");
  }

  @Test
  public void testThat_queryUpcomingReturnsOnlyUpcoming() throws Exception {
    assertThat( //
        policyRepository.queryUpcoming(Integer.MAX_VALUE //
        ).stream().map(AccessPolicy::getDescription) //
    ).containsExactly("upcoming");
  }

  @Test(expected = IllegalArgumentException.class)
  public void testThat_saveIsDeniedForCurrentPolicy() throws Exception {
    policyRepository.save(new AccessPolicy() //
        .withDefaultEffect(Effect.DENY) //
        .withValidFrom(Date.from(oneMinuteAgo)) // "current"
    );
  }

  @Test(expected = IllegalArgumentException.class)
  public void testThat_saveIsDeniedForOldPolicies() throws Exception {
    policyRepository.save(new AccessPolicy() //
        .withDefaultEffect(Effect.DENY) //
        .withValidFrom(Date.from(oneHourAgo)) // "old"
    );
  }

  @Test
  public void testThat_saveAddsNewPolicy() throws Exception {
    Instant inOneMinute = now.plus(1, MINUTES);

    policyRepository.save(new AccessPolicy() //
        .withDefaultEffect(Effect.DENY) //
        .withDescription("new") //
        .withValidFrom(Date.from(inOneMinute)));

    ArgumentCaptor<InputStream> c = ArgumentCaptor.forClass(InputStream.class);
    verify(mockObjectStore).put( //
        eq(POLICY_REPO_PREFIX.append(OBJECT_NAME_FORMATTER.format(inOneMinute))), //
        eq(ObjectStoreService.NEW_VERSION), //
        c.capture());

    assertThat(objectMapper.readValue(c.getValue(), AccessPolicy.class).getDescription()).contains("new");
  }

  @Test
  public void testThat_saveUpdatesPolicy() throws Exception {
    policyRepository.save(new AccessPolicy() //
        .withDefaultEffect(Effect.DENY) //
        .withDescription("new") //
        .withValidFrom(Date.from(inOneHour)));

    ArgumentCaptor<InputStream> c = ArgumentCaptor.forClass(InputStream.class);
    verify(mockObjectStore).put(eq(inOneHourName), eq("1"), c.capture());

    assertThat(objectMapper.readValue(c.getValue(), AccessPolicy.class).getDescription()).contains("new");
  }

  @Test(expected = IllegalArgumentException.class)
  public void testThat_deletePolicyDeniesDeleteOfOldPolicy() throws Exception {
    policyRepository.delete(Date.from(oneHourAgo));
  }

  @Test
  public void testThat_deletePolicyDeletesFuturePolicies() throws Exception {
    policyRepository.delete(Date.from(inOneHour));

    verify(mockObjectStore).delete(eq(inOneHourName));
  }

  @Before
  public void initMocks() {
    now = now();
    oneMinuteAgo = now.minus(1, MINUTES);
    oneMinuteAgoName = POLICY_REPO_PREFIX.append(OBJECT_NAME_FORMATTER.format(oneMinuteAgo));
    inOneHour = now.plus(1, HOURS);
    inOneHourName = POLICY_REPO_PREFIX.append(OBJECT_NAME_FORMATTER.format(inOneHour));
    threeHoursAgo = now.minus(3, HOURS);
    threeHoursAgoName = EXPIRED_POLICY_REPO_PREFIX.append(OBJECT_NAME_FORMATTER.format(threeHoursAgo));
    oneHourAgo = now.minus(1, HOURS);
    oneHourAgoName = EXPIRED_POLICY_REPO_PREFIX.append(OBJECT_NAME_FORMATTER.format(oneHourAgo));

    given(mockObjectStore.list(POLICY_REPO_PREFIX)).willAnswer(i -> {
      return Stream.of( //
          new SimpleStoreObject(oneMinuteAgoName, "1", String.format(policyPattern, "current").getBytes(UTF_8)), //
          new SimpleStoreObject(inOneHourName, "1", String.format(policyPattern, "upcoming").getBytes(UTF_8)));
    });

    // must also consider expired ones!
    given(mockObjectStore.list(EXPIRED_POLICY_REPO_PREFIX)).willAnswer(i -> {
      return Stream.of( //
          new SimpleStoreObject(threeHoursAgoName, "1", String.format(policyPattern, "older").getBytes(UTF_8)), //
          new SimpleStoreObject(oneHourAgoName, "1", String.format(policyPattern, "old").getBytes(UTF_8)) //
      );
    });

    given(mockObjectStore.get(inOneHourName)).willAnswer(i -> {
      return new SimpleStoreObject(inOneHourName, "1", String.format(policyPattern, "upcoming").getBytes(UTF_8));
    });
  }
}
