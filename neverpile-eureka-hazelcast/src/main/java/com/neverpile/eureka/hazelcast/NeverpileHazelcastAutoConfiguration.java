package com.neverpile.eureka.hazelcast;

import org.springframework.beans.factory.InjectionPoint;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.AutoConfigureBefore;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Scope;

import com.hazelcast.config.Config;
import com.hazelcast.config.MapConfig;
import com.hazelcast.config.MultiMapConfig;
import com.neverpile.eureka.autoconfig.NeverpileEurekaAutoConfiguration;
import com.neverpile.eureka.hazelcast.queue.HazelcastTaskQueue;
import com.neverpile.eureka.hazelcast.wal.HazelcastWAL;
import com.neverpile.eureka.tasks.DistributedPersistentQueueType;
import com.neverpile.eureka.tasks.TaskQueue;
import com.neverpile.eureka.tx.wal.WriteAheadLog;

@Configuration
@ConditionalOnProperty(name = "neverpile-eureka.hazelcast.enabled", havingValue = "true", matchIfMissing = false)
@ComponentScan
@AutoConfigureBefore(NeverpileEurekaAutoConfiguration.class)
public class NeverpileHazelcastAutoConfiguration {
  @Autowired
  HazelcastConfigurationProperties hazelcastConfig;

  @Bean
  @ConditionalOnProperty(name = "neverpile-eureka.hazelcast.wal.enabled", havingValue = "true", matchIfMissing = true)
  WriteAheadLog hazelcastWAL() {
    return new HazelcastWAL();
  }

  @Bean
  @Scope("prototype")
  public TaskQueue<?> getQueue(final InjectionPoint ip) {
    return new HazelcastTaskQueue<>(ip.getAnnotation(DistributedPersistentQueueType.class).value());
  }

  @Bean
  public Config config() {
    Config configuration = hazelcastConfig.getConfiguration();

    configuration.addMapConfig(new MapConfig() //
        .setName(HazelcastWAL.class.getName() + ".*") //
        .setBackupCount(2));
    
    configuration.addMultiMapConfig(new MultiMapConfig() //
        .setName(HazelcastTaskQueue.class.getName() + ".*") //
        .setBackupCount(2));

    return configuration;
  }
}
