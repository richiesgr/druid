/*
 * Licensed to Metamarkets Group Inc. (Metamarkets) under one
 * or more contributor license agreements. See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership. Metamarkets licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License. You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied. See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */

package io.druid.client.cache;

import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.common.base.Function;
import com.google.common.base.Preconditions;
import com.google.common.util.concurrent.FutureCallback;
import com.google.common.util.concurrent.Futures;
import com.google.common.util.concurrent.ListenableFuture;
import com.google.common.util.concurrent.ListeningExecutorService;
import com.google.common.util.concurrent.MoreExecutors;
import io.druid.java.util.common.guava.Sequence;
import io.druid.java.util.common.guava.Sequences;
import io.druid.java.util.common.logger.Logger;
import io.druid.query.CacheStrategy;
import io.druid.query.Query;

import java.io.ByteArrayOutputStream;
import java.util.LinkedList;
import java.util.List;
import java.util.concurrent.ExecutorService;

public class BackgroundCachePopulator implements CachePopulator
{
  private static final Logger log = new Logger(BackgroundCachePopulator.class);

  private final ListeningExecutorService exec;
  private final ObjectMapper objectMapper;
  private final long maxEntrySize;

  public BackgroundCachePopulator(
      final ExecutorService exec,
      final ObjectMapper objectMapper,
      final long maxEntrySize
  )
  {
    this.exec = MoreExecutors.listeningDecorator(exec);
    this.objectMapper = Preconditions.checkNotNull(objectMapper, "objectMapper");
    this.maxEntrySize = maxEntrySize;
  }

  @Override
  public <T, CacheType, QueryType extends Query<T>> Sequence<T> wrap(
      final Sequence<T> sequence,
      final CacheStrategy<T, CacheType, QueryType> cacheStrategy,
      final Cache cache,
      final Cache.NamedKey cacheKey
  )
  {
    final Function<T, CacheType> cacheFn = cacheStrategy.prepareForCache();
    final List<ListenableFuture<CacheType>> cacheFutures = new LinkedList<>();

    final Sequence<T> wrappedSequence = Sequences.map(
        sequence,
        input -> {
          cacheFutures.add(exec.submit(() -> cacheFn.apply(input)));
          return input;
        }
    );

    return Sequences.withEffect(
        wrappedSequence,
        () -> {
          Futures.addCallback(
              Futures.allAsList(cacheFutures),
              new FutureCallback<List<CacheType>>()
              {
                @Override
                public void onSuccess(List<CacheType> results)
                {
                  populateCache(cache, cacheKey, results);
                  // Help out GC by making sure all references are gone
                  cacheFutures.clear();
                }

                @Override
                public void onFailure(Throwable t)
                {
                  log.error(t, "Background caching failed");
                }
              },
              exec
          );
        },
        MoreExecutors.sameThreadExecutor()
    );
  }

  private <CacheType> void populateCache(
      final Cache cache,
      final Cache.NamedKey cacheKey,
      final List<CacheType> results
  )
  {
    try {
      final ByteArrayOutputStream bytes = new ByteArrayOutputStream();

      try (JsonGenerator gen = objectMapper.getFactory().createGenerator(bytes)) {
        for (CacheType result : results) {
          gen.writeObject(result);

          if (maxEntrySize > 0 && bytes.size() > maxEntrySize) {
            return;
          }
        }
      }

      if (maxEntrySize > 0 && bytes.size() > maxEntrySize) {
        return;
      }

      cache.put(cacheKey, bytes.toByteArray());
    }
    catch (Exception e) {
      log.warn(e, "Could not populate cache");
    }
  }
}