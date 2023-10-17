/*
 * DISCLAIMER
 *
 * Copyright 2016 ArangoDB GmbH, Cologne, Germany
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

import com.arangodb.ArangoDBException;
import com.arangodb.ArangoEdgeCollection;
import com.arangodb.ArangoGraph;
import com.arangodb.entity.EdgeEntity;
import com.arangodb.entity.EdgeUpdateEntity;
import com.arangodb.model.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * @author Mark Vollmary
 */
public class ArangoEdgeCollectionImpl extends InternalArangoEdgeCollection implements ArangoEdgeCollection {

    private static final Logger LOGGER = LoggerFactory.getLogger(ArangoEdgeCollectionImpl.class);

    private final ArangoGraphImpl graph;

    protected ArangoEdgeCollectionImpl(final ArangoGraphImpl graph, final String name) {
        super(graph, graph.db().name(), graph.name(), name);
        this.graph = graph;
    }

    @Override
    public ArangoGraph graph() {
        return graph;
    }

    @Override
    public void drop() {
        drop(new EdgeCollectionDropOptions());
    }

    @Override
    public void drop(final EdgeCollectionDropOptions options) {
        executorSync().execute(removeEdgeDefinitionRequest(options), Void.class);
    }

    @Override
    public EdgeEntity insertEdge(final Object value) {
        return executorSync().execute(insertEdgeRequest(value, new EdgeCreateOptions()),
                insertEdgeResponseDeserializer());
    }

    @Override
    public EdgeEntity insertEdge(final Object value, final EdgeCreateOptions options) {
        return executorSync().execute(insertEdgeRequest(value, options), insertEdgeResponseDeserializer());
    }

    @Override
    public <T> T getEdge(final String key, final Class<T> type) {
        // FIXME
        try {
            return executorSync().execute(getEdgeRequest(key, new GraphDocumentReadOptions()),
                    getEdgeResponseDeserializer(type));
        } catch (final ArangoDBException e) {
            if (LOGGER.isDebugEnabled()) {
                LOGGER.debug(e.getMessage(), e);
            }
            return null;
        }
    }

    @Override
    public <T> T getEdge(final String key, final Class<T> type, final GraphDocumentReadOptions options) {
        // FIXME
        try {
            return executorSync().execute(getEdgeRequest(key, options), getEdgeResponseDeserializer(type));
        } catch (final ArangoDBException e) {
            if (LOGGER.isDebugEnabled()) {
                LOGGER.debug(e.getMessage(), e);
            }
            return null;
        }
    }

    @Override
    public EdgeUpdateEntity replaceEdge(final String key, final Object value) {
        return executorSync().execute(replaceEdgeRequest(key, value, new EdgeReplaceOptions()),
                replaceEdgeResponseDeserializer());
    }

    @Override
    public EdgeUpdateEntity replaceEdge(final String key, final Object value, final EdgeReplaceOptions options) {
        return executorSync().execute(replaceEdgeRequest(key, value, options), replaceEdgeResponseDeserializer());
    }

    @Override
    public EdgeUpdateEntity updateEdge(final String key, final Object value) {
        return executorSync().execute(updateEdgeRequest(key, value, new EdgeUpdateOptions()),
                updateEdgeResponseDeserializer());
    }

    @Override
    public EdgeUpdateEntity updateEdge(final String key, final Object value, final EdgeUpdateOptions options) {
        return executorSync().execute(updateEdgeRequest(key, value, options), updateEdgeResponseDeserializer());
    }

    @Override
    public void deleteEdge(final String key) {
        executorSync().execute(deleteEdgeRequest(key, new EdgeDeleteOptions()), Void.class);
    }

    @Override
    public void deleteEdge(final String key, final EdgeDeleteOptions options) {
        executorSync().execute(deleteEdgeRequest(key, options), Void.class);
    }

}
