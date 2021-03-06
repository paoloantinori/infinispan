package org.infinispan.stream;

import org.infinispan.CacheCollection;
import org.infinispan.CacheStream;
import org.testng.annotations.Test;

/**
 * Verifies stream tests work when rehash is disabled
 */
@Test(groups = "functional", testName = "streams.DistributedNonRehashStreamTest")
public class DistributedNonRehashStreamTest extends DistributedStreamTest {
   @Override
   protected <E> CacheStream<E> createStream(CacheCollection<E> entries) {
      return entries.stream().disableRehashAware().parallelDistribution();
   }
}
