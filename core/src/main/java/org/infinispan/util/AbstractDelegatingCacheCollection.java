package org.infinispan.util;

import org.infinispan.CacheCollection;
import org.infinispan.CacheStream;

/**
 *
 * @param <E>
 */
public abstract class AbstractDelegatingCacheCollection<E> extends AbstractDelegatingCloseableIteratorCollection<E>
        implements CacheCollection<E> {
   @Override
   protected abstract CacheCollection<E> delegate();

   @Override
   public abstract CacheStream<E> stream();

   @Override
   public abstract CacheStream<E> parallelStream();
}
