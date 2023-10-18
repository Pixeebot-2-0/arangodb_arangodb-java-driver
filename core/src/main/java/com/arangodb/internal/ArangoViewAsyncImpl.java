/*
 * DISCLAIMER
 *
 * Copyright 2018 ArangoDB GmbH, Cologne, Germany
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 *
 * Copyright holder is ArangoDB GmbH, Cologne, Germany
 */

package com.arangodb.internal;

import com.arangodb.*;
import com.arangodb.entity.ViewEntity;

import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;

/**
 * @author Mark Vollmary
 */
public class ArangoViewAsyncImpl extends InternalArangoView implements ArangoViewAsync {
    private final ArangoDatabaseAsyncImpl db;
    protected ArangoViewAsyncImpl(final ArangoDatabaseAsyncImpl db, final String name) {
        super(db, db.name(), name);
        this.db = db;
    }

    @Override
    public ArangoDatabaseAsync db() {
        return db;
    }

    @Override
    public CompletableFuture<Boolean> exists() {
        return getInfo()
                .thenApply(Objects::nonNull)
                .exceptionally(err -> {
                    Throwable e = err instanceof CompletionException ? err.getCause() : err;
                    if (e instanceof ArangoDBException) {
                        ArangoDBException aEx = (ArangoDBException) e;
                        if (ArangoErrors.ERROR_ARANGO_DATA_SOURCE_NOT_FOUND.equals(aEx.getErrorNum())) {
                            return false;
                        }
                    }
                    throw ArangoDBException.wrap(e);
                });
    }

    @Override
    public CompletableFuture<Void> drop() {
        return executorAsync().execute(dropRequest(), Void.class);
    }

    @Override
    public CompletableFuture<ViewEntity> rename(final String newName) {
        return executorAsync().execute(renameRequest(newName), ViewEntity.class);
    }

    @Override
    public CompletableFuture<ViewEntity> getInfo() {
        return executorAsync().execute(getInfoRequest(), ViewEntity.class);
    }

}
