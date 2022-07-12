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
import com.arangodb.DbName;
import com.arangodb.entity.*;
import com.arangodb.internal.ArangoExecutor.ResponseDeserializer;
import com.arangodb.internal.util.DocumentUtil;
import com.arangodb.internal.util.RequestUtils;
import com.arangodb.model.*;
import com.arangodb.serde.SerdeUtils;
import com.arangodb.velocypack.Type;
import com.arangodb.velocystream.Request;
import com.arangodb.velocystream.RequestType;
import com.fasterxml.jackson.databind.JsonNode;

import java.util.*;

/**
 * @author Mark Vollmary
 * @author Michele Rastelli
 */
public abstract class InternalArangoCollection<A extends InternalArangoDB<E>, D extends InternalArangoDatabase<A, E>, E extends ArangoExecutor>
        extends ArangoExecuteable<E> {

    private static final String COLLECTION = "collection";

    protected static final String PATH_API_COLLECTION = "/_api/collection";
    private static final String PATH_API_DOCUMENT = "/_api/document";
    private static final String PATH_API_INDEX = "/_api/index";
    private static final String PATH_API_IMPORT = "/_api/import";
    private static final String PATH_API_USER = "/_api/user";

    private static final String MERGE_OBJECTS = "mergeObjects";
    private static final String IGNORE_REVS = "ignoreRevs";
    private static final String RETURN_NEW = "returnNew";
    private static final String NEW = "new";
    private static final String RETURN_OLD = "returnOld";
    private static final String OVERWRITE = "overwrite";
    private static final String OVERWRITE_MODE = "overwriteMode";
    private static final String OLD = "old";
    private static final String SILENT = "silent";

    private static final String TRANSACTION_ID = "x-arango-trx-id";

    private final D db;
    protected volatile String name;

    protected InternalArangoCollection(final D db, final String name) {
        super(db.executor, db.util, db.context);
        this.db = db;
        this.name = name;
    }

    public D db() {
        return db;
    }

    public String name() {
        return name;
    }

    protected <T> Request insertDocumentRequest(final T value, final DocumentCreateOptions options) {
        final Request request = request(db.dbName(), RequestType.POST, PATH_API_DOCUMENT, name);
        final DocumentCreateOptions params = (options != null ? options : new DocumentCreateOptions());
        request.putQueryParam(ArangoRequestParam.WAIT_FOR_SYNC, params.getWaitForSync());
        request.putQueryParam(RETURN_NEW, params.getReturnNew());
        request.putQueryParam(RETURN_OLD, params.getReturnOld());
        request.putQueryParam(SILENT, params.getSilent());
        request.putQueryParam(OVERWRITE_MODE, params.getOverwriteMode() != null ? params.getOverwriteMode().getValue() : null);
        request.putQueryParam(MERGE_OBJECTS, params.getMergeObjects());
        request.putHeaderParam(TRANSACTION_ID, params.getStreamTransactionId());

        byte[] body;
        if (value instanceof String) {
            body = getInternalSerialization().serialize(SerdeUtils.INSTANCE.parseJson((String) value));
        } else {
            body = getUserSerialization().serialize(value);
        }
        request.setBody(body);

        return request;
    }

    protected <T> ResponseDeserializer<DocumentCreateEntity<T>> insertDocumentResponseDeserializer(
            final T value, final DocumentCreateOptions options) {
        return response -> {
            final JsonNode body = getInternalSerialization().parse(response.getBody());
            final DocumentCreateEntity<T> doc = getInternalSerialization().deserialize(body, DocumentCreateEntity.class);
            final JsonNode newDoc = body.get(NEW);
            Class<?> clazz = value.getClass();
            if (newDoc != null) {
                if (String.class.isAssignableFrom(clazz)) {
                    doc.setNew((T) SerdeUtils.INSTANCE.writeJson(newDoc));
                } else {
                    doc.setNew(getUserSerialization().deserialize(getInternalSerialization().serialize(newDoc), clazz));
                }
            }
            final JsonNode oldDoc = body.get(OLD);
            if (oldDoc != null) {
                if (String.class.isAssignableFrom(clazz)) {
                    doc.setOld((T) SerdeUtils.INSTANCE.writeJson(oldDoc));
                } else {
                    doc.setOld(getUserSerialization().deserialize(getInternalSerialization().serialize(oldDoc), clazz));
                }
            }
            if (options == null || Boolean.TRUE != options.getSilent()) {
                final Map<String, String> values = new HashMap<>();
                values.put(DocumentFields.ID, doc.getId());
                values.put(DocumentFields.KEY, doc.getKey());
                values.put(DocumentFields.REV, doc.getRev());
                executor.documentCache().setValues(value, values);
            }
            return doc;
        };
    }

    protected <T> Request insertDocumentsRequest(final Collection<T> values, final DocumentCreateOptions params) {
        final Request request = request(db.dbName(), RequestType.POST, PATH_API_DOCUMENT, name);
        request.putQueryParam(ArangoRequestParam.WAIT_FOR_SYNC, params.getWaitForSync());
        request.putQueryParam(RETURN_NEW, params.getReturnNew());
        request.putQueryParam(RETURN_OLD, params.getReturnOld());
        request.putQueryParam(SILENT, params.getSilent());
        request.putQueryParam(OVERWRITE_MODE, params.getOverwriteMode() != null ? params.getOverwriteMode().getValue() : null);
        request.putQueryParam(MERGE_OBJECTS, params.getMergeObjects());
        request.putHeaderParam(TRANSACTION_ID, params.getStreamTransactionId());

        byte[] body = isStringCollection(values) ? getInternalSerialization().serialize(stringCollectionToJsonArray((Collection<String>) values))
                : getUserSerialization().serialize(values);
        request.setBody(body);
        return request;
    }

    @SuppressWarnings("unchecked")
    protected <T> ResponseDeserializer<MultiDocumentEntity<DocumentCreateEntity<T>>> insertDocumentsResponseDeserializer(
            final Collection<T> values, final DocumentCreateOptions params) {
        return response -> {
            Class<T> type = null;
            if (Boolean.TRUE == params.getReturnNew()) {
                if (!values.isEmpty()) {
                    type = (Class<T>) values.iterator().next().getClass();
                }
            }
            final MultiDocumentEntity<DocumentCreateEntity<T>> multiDocument = new MultiDocumentEntity<>();
            final Collection<DocumentCreateEntity<T>> docs = new ArrayList<>();
            final Collection<ErrorEntity> errors = new ArrayList<>();
            final Collection<Object> documentsAndErrors = new ArrayList<>();
            final JsonNode body = getInternalSerialization().parse(response.getBody());
            for (final Iterator<JsonNode> iterator = body.iterator(); iterator.hasNext(); ) {
                final JsonNode next = iterator.next();
                JsonNode isError = next.get(ArangoResponseField.ERROR_FIELD_NAME);
                if (isError != null && isError.booleanValue()) {
                    final ErrorEntity error = getInternalSerialization().deserialize(next, ErrorEntity.class);
                    errors.add(error);
                    documentsAndErrors.add(error);
                } else {
                    final DocumentCreateEntity<T> doc = getInternalSerialization().deserialize(next, DocumentCreateEntity.class);
                    final JsonNode newDoc = next.get(NEW);
                    if (newDoc != null) {
                        doc.setNew(getUserSerialization().deserialize(getInternalSerialization().serialize(newDoc), type));
                    }
                    final JsonNode oldDoc = next.get(OLD);
                    if (oldDoc != null) {
                        doc.setOld(getUserSerialization().deserialize(getInternalSerialization().serialize(oldDoc), type));
                    }
                    docs.add(doc);
                    documentsAndErrors.add(doc);
                }
            }
            multiDocument.setDocuments(docs);
            multiDocument.setErrors(errors);
            multiDocument.setDocumentsAndErrors(documentsAndErrors);
            return multiDocument;
        };
    }

    protected Request importDocumentsRequest(final String values, final DocumentImportOptions options) {
        return importDocumentsRequest(options).putQueryParam("type", ImportType.auto).setBody(getInternalSerialization().serialize(SerdeUtils.INSTANCE.parseJson(values)));
    }

    protected Request importDocumentsRequest(final Collection<?> values, final DocumentImportOptions options) {
        byte[] body = isStringCollection(values) ? getInternalSerialization().serialize(stringCollectionToJsonArray((Collection<String>) values))
                : getUserSerialization().serialize(values);
        return importDocumentsRequest(options).putQueryParam("type", ImportType.list).setBody(body);
    }

    protected Request importDocumentsRequest(final DocumentImportOptions options) {
        final DocumentImportOptions params = options != null ? options : new DocumentImportOptions();
        return request(db.dbName(), RequestType.POST, PATH_API_IMPORT).putQueryParam(COLLECTION, name)
                .putQueryParam(ArangoRequestParam.WAIT_FOR_SYNC, params.getWaitForSync())
                .putQueryParam("fromPrefix", params.getFromPrefix()).putQueryParam("toPrefix", params.getToPrefix())
                .putQueryParam(OVERWRITE, params.getOverwrite()).putQueryParam("onDuplicate", params.getOnDuplicate())
                .putQueryParam("complete", params.getComplete()).putQueryParam("details", params.getDetails());
    }

    protected Request getDocumentRequest(final String key, final DocumentReadOptions options) {
        final Request request = request(db.dbName(), RequestType.GET, PATH_API_DOCUMENT,
                DocumentUtil.createDocumentHandle(name, key));
        final DocumentReadOptions params = (options != null ? options : new DocumentReadOptions());
        request.putHeaderParam(ArangoRequestParam.IF_NONE_MATCH, params.getIfNoneMatch());
        request.putHeaderParam(ArangoRequestParam.IF_MATCH, params.getIfMatch());
        request.putHeaderParam(TRANSACTION_ID, params.getStreamTransactionId());
        if (params.getAllowDirtyRead() == Boolean.TRUE) {
            RequestUtils.allowDirtyRead(request);
        }
        return request;
    }

    protected <T> ResponseDeserializer<T> getDocumentResponseDeserializer(final Class<T> type) {
        return response -> {
            if (String.class.isAssignableFrom(type)) {
                return (T) SerdeUtils.INSTANCE.writeJson(getInternalSerialization().parse(response.getBody()));
            } else {
                return getUserSerialization().deserialize(response.getBody(), type);
            }
        };
    }

    protected Request getDocumentsRequest(final Collection<String> keys, final DocumentReadOptions options) {
        final DocumentReadOptions params = (options != null ? options : new DocumentReadOptions());
        final Request request = request(db.dbName(), RequestType.PUT, PATH_API_DOCUMENT, name)
                .putQueryParam("onlyget", true)
                .putHeaderParam(ArangoRequestParam.IF_NONE_MATCH, params.getIfNoneMatch())
                .putHeaderParam(ArangoRequestParam.IF_MATCH, params.getIfMatch()).setBody(getInternalSerialization().serialize(keys))
                .putHeaderParam(TRANSACTION_ID, params.getStreamTransactionId());
        if (params.getAllowDirtyRead() == Boolean.TRUE) {
            RequestUtils.allowDirtyRead(request);
        }
        return request;
    }

    protected <T> ResponseDeserializer<MultiDocumentEntity<T>> getDocumentsResponseDeserializer(
            final Class<T> type, final DocumentReadOptions options) {
        return response -> {
            final MultiDocumentEntity<T> multiDocument = new MultiDocumentEntity<>();
            final Collection<T> docs = new ArrayList<>();
            final Collection<ErrorEntity> errors = new ArrayList<>();
            final Collection<Object> documentsAndErrors = new ArrayList<>();
            final JsonNode body = getInternalSerialization().parse(response.getBody());
            for (final Iterator<JsonNode> iterator = body.iterator(); iterator.hasNext(); ) {
                final JsonNode next = iterator.next();
                JsonNode isError = next.get(ArangoResponseField.ERROR_FIELD_NAME);
                if (isError != null && isError.booleanValue()) {
                    final ErrorEntity error = getInternalSerialization().deserialize(next, ErrorEntity.class);
                    errors.add(error);
                    documentsAndErrors.add(error);
                } else {
                    final T doc = getUserSerialization().deserialize(getInternalSerialization().serialize(next), type);
                    docs.add(doc);
                    documentsAndErrors.add(doc);
                }
            }
            multiDocument.setDocuments(docs);
            multiDocument.setErrors(errors);
            multiDocument.setDocumentsAndErrors(documentsAndErrors);
            return multiDocument;
        };
    }

    protected <T> Request replaceDocumentRequest(
            final String key, final T value, final DocumentReplaceOptions options) {
        final Request request = request(db.dbName(), RequestType.PUT, PATH_API_DOCUMENT,
                DocumentUtil.createDocumentHandle(name, key));
        final DocumentReplaceOptions params = (options != null ? options : new DocumentReplaceOptions());
        request.putHeaderParam(ArangoRequestParam.IF_MATCH, params.getIfMatch());
        request.putHeaderParam(TRANSACTION_ID, params.getStreamTransactionId());
        request.putQueryParam(ArangoRequestParam.WAIT_FOR_SYNC, params.getWaitForSync());
        request.putQueryParam(IGNORE_REVS, params.getIgnoreRevs());
        request.putQueryParam(RETURN_NEW, params.getReturnNew());
        request.putQueryParam(RETURN_OLD, params.getReturnOld());
        request.putQueryParam(SILENT, params.getSilent());
        request.setBody(getUserSerialization().serialize(value));
        return request;
    }

    protected <T> ResponseDeserializer<DocumentUpdateEntity<T>> replaceDocumentResponseDeserializer(
            final T value, final DocumentReplaceOptions options) {
        return response -> {
            final JsonNode body = getInternalSerialization().parse(response.getBody());
            final DocumentUpdateEntity<T> doc = getInternalSerialization().deserialize(body, DocumentUpdateEntity.class);
            final JsonNode newDoc = body.get(NEW);
            if (newDoc != null) {
                doc.setNew(getUserSerialization().deserialize(getInternalSerialization().serialize(newDoc), value.getClass()));
            }
            final JsonNode oldDoc = body.get(OLD);
            if (oldDoc != null) {
                doc.setOld(getUserSerialization().deserialize(getInternalSerialization().serialize(oldDoc), value.getClass()));
            }
            if (options == null || Boolean.TRUE != options.getSilent()) {
                final Map<String, String> values = new HashMap<>();
                values.put(DocumentFields.REV, doc.getRev());
                executor.documentCache().setValues(value, values);
            }
            return doc;
        };
    }

    protected <T> Request replaceDocumentsRequest(final Collection<T> values, final DocumentReplaceOptions params) {
        final Request request = request(db.dbName(), RequestType.PUT, PATH_API_DOCUMENT, name);
        request.putHeaderParam(ArangoRequestParam.IF_MATCH, params.getIfMatch());
        request.putHeaderParam(TRANSACTION_ID, params.getStreamTransactionId());
        request.putQueryParam(ArangoRequestParam.WAIT_FOR_SYNC, params.getWaitForSync());
        request.putQueryParam(IGNORE_REVS, params.getIgnoreRevs());
        request.putQueryParam(RETURN_NEW, params.getReturnNew());
        request.putQueryParam(RETURN_OLD, params.getReturnOld());
        request.putQueryParam(SILENT, params.getSilent());

        byte[] body = isStringCollection(values) ? getInternalSerialization().serialize(stringCollectionToJsonArray((Collection<String>) values))
                : getUserSerialization().serialize(values);
        request.setBody(body);
        return request;
    }

    @SuppressWarnings("unchecked")
    protected <T> ResponseDeserializer<MultiDocumentEntity<DocumentUpdateEntity<T>>> replaceDocumentsResponseDeserializer(
            final Collection<T> values, final DocumentReplaceOptions params) {
        return response -> {
            Class<T> type = null;
            if (Boolean.TRUE == params.getReturnNew() || Boolean.TRUE == params.getReturnOld()) {
                if (!values.isEmpty()) {
                    type = (Class<T>) values.iterator().next().getClass();
                }
            }
            final MultiDocumentEntity<DocumentUpdateEntity<T>> multiDocument = new MultiDocumentEntity<>();
            final Collection<DocumentUpdateEntity<T>> docs = new ArrayList<>();
            final Collection<ErrorEntity> errors = new ArrayList<>();
            final Collection<Object> documentsAndErrors = new ArrayList<>();
            final JsonNode body = getInternalSerialization().parse(response.getBody());
            for (final Iterator<JsonNode> iterator = body.iterator(); iterator.hasNext(); ) {
                final JsonNode next = iterator.next();
                JsonNode isError = next.get(ArangoResponseField.ERROR_FIELD_NAME);
                if (isError != null && isError.booleanValue()) {
                    final ErrorEntity error = getInternalSerialization().deserialize(next, ErrorEntity.class);
                    errors.add(error);
                    documentsAndErrors.add(error);
                } else {
                    final DocumentUpdateEntity<T> doc = getInternalSerialization().deserialize(next, DocumentUpdateEntity.class);
                    final JsonNode newDoc = next.get(NEW);
                    if (newDoc != null) {
                        doc.setNew(getUserSerialization().deserialize(getInternalSerialization().serialize(newDoc), type));
                    }
                    final JsonNode oldDoc = next.get(OLD);
                    if (oldDoc != null) {
                        doc.setOld(getUserSerialization().deserialize(getInternalSerialization().serialize(oldDoc), type));
                    }
                    docs.add(doc);
                    documentsAndErrors.add(doc);
                }
            }
            multiDocument.setDocuments(docs);
            multiDocument.setErrors(errors);
            multiDocument.setDocumentsAndErrors(documentsAndErrors);
            return multiDocument;
        };
    }

    protected <T> Request updateDocumentRequest(final String key, final T value, final DocumentUpdateOptions options) {
        final Request request = request(db.dbName(), RequestType.PATCH, PATH_API_DOCUMENT,
                DocumentUtil.createDocumentHandle(name, key));
        final DocumentUpdateOptions params = (options != null ? options : new DocumentUpdateOptions());
        request.putHeaderParam(ArangoRequestParam.IF_MATCH, params.getIfMatch());
        request.putHeaderParam(TRANSACTION_ID, params.getStreamTransactionId());
        request.putQueryParam(ArangoRequestParam.KEEP_NULL, params.getKeepNull());
        request.putQueryParam(ArangoRequestParam.WAIT_FOR_SYNC, params.getWaitForSync());
        request.putQueryParam(MERGE_OBJECTS, params.getMergeObjects());
        request.putQueryParam(IGNORE_REVS, params.getIgnoreRevs());
        request.putQueryParam(RETURN_NEW, params.getReturnNew());
        request.putQueryParam(RETURN_OLD, params.getReturnOld());
        request.putQueryParam(SILENT, params.getSilent());
        request.setBody(getUserSerialization().serialize(value));
        return request;
    }

    protected <T, U> ResponseDeserializer<DocumentUpdateEntity<U>> updateDocumentResponseDeserializer(
            final T value, final DocumentUpdateOptions options, final Class<U> returnType) {
        return response -> {
            final JsonNode body = getInternalSerialization().parse(response.getBody());
            final DocumentUpdateEntity<U> doc = getInternalSerialization().deserialize(body, DocumentUpdateEntity.class);
            final JsonNode newDoc = body.get(NEW);
            if (newDoc != null) {
                doc.setNew(getUserSerialization().deserialize(getInternalSerialization().serialize(newDoc), returnType));
            }
            final JsonNode oldDoc = body.get(OLD);
            if (oldDoc != null) {
                doc.setOld(getUserSerialization().deserialize(getInternalSerialization().serialize(oldDoc), returnType));
            }
            if (options == null || Boolean.TRUE != options.getSilent()) {
                final Map<String, String> values = new HashMap<>();
                values.put(DocumentFields.REV, doc.getRev());
                executor.documentCache().setValues(value, values);
            }
            return doc;
        };
    }

    protected <T> Request updateDocumentsRequest(final Collection<T> values, final DocumentUpdateOptions params) {
        final Request request = request(db.dbName(), RequestType.PATCH, PATH_API_DOCUMENT, name);
        final Boolean keepNull = params.getKeepNull();
        request.putHeaderParam(ArangoRequestParam.IF_MATCH, params.getIfMatch());
        request.putHeaderParam(TRANSACTION_ID, params.getStreamTransactionId());
        request.putQueryParam(ArangoRequestParam.KEEP_NULL, keepNull);
        request.putQueryParam(ArangoRequestParam.WAIT_FOR_SYNC, params.getWaitForSync());
        request.putQueryParam(MERGE_OBJECTS, params.getMergeObjects());
        request.putQueryParam(IGNORE_REVS, params.getIgnoreRevs());
        request.putQueryParam(RETURN_NEW, params.getReturnNew());
        request.putQueryParam(RETURN_OLD, params.getReturnOld());
        request.putQueryParam(SILENT, params.getSilent());

        byte[] body = isStringCollection(values) ? getInternalSerialization().serialize(stringCollectionToJsonArray((Collection<String>) values))
                : getUserSerialization().serialize(values);
        request.setBody(body);
        return request;
    }

    @SuppressWarnings("unchecked")
    protected <T> ResponseDeserializer<MultiDocumentEntity<DocumentUpdateEntity<T>>> updateDocumentsResponseDeserializer(
            final Class<T> returnType) {
        return response -> {
            final MultiDocumentEntity<DocumentUpdateEntity<T>> multiDocument = new MultiDocumentEntity<>();
            final Collection<DocumentUpdateEntity<T>> docs = new ArrayList<>();
            final Collection<ErrorEntity> errors = new ArrayList<>();
            final Collection<Object> documentsAndErrors = new ArrayList<>();
            final JsonNode body = getInternalSerialization().parse(response.getBody());
            for (final Iterator<JsonNode> iterator = body.iterator(); iterator.hasNext(); ) {
                final JsonNode next = iterator.next();
                JsonNode isError = next.get(ArangoResponseField.ERROR_FIELD_NAME);
                if (isError != null && isError.booleanValue()) {
                    final ErrorEntity error = getInternalSerialization().deserialize(next, ErrorEntity.class);
                    errors.add(error);
                    documentsAndErrors.add(error);
                } else {
                    final DocumentUpdateEntity<T> doc = getInternalSerialization().deserialize(next, DocumentUpdateEntity.class);
                    final JsonNode newDoc = next.get(NEW);
                    if (newDoc != null) {
                        doc.setNew(getUserSerialization().deserialize(getInternalSerialization().serialize(newDoc), returnType));
                    }
                    final JsonNode oldDoc = next.get(OLD);
                    if (oldDoc != null) {
                        doc.setOld(getUserSerialization().deserialize(getInternalSerialization().serialize(oldDoc), returnType));
                    }
                    docs.add(doc);
                    documentsAndErrors.add(doc);
                }
            }
            multiDocument.setDocuments(docs);
            multiDocument.setErrors(errors);
            multiDocument.setDocumentsAndErrors(documentsAndErrors);
            return multiDocument;
        };
    }

    protected Request deleteDocumentRequest(final String key, final DocumentDeleteOptions options) {
        final Request request = request(db.dbName(), RequestType.DELETE, PATH_API_DOCUMENT,
                DocumentUtil.createDocumentHandle(name, key));
        final DocumentDeleteOptions params = (options != null ? options : new DocumentDeleteOptions());
        request.putHeaderParam(ArangoRequestParam.IF_MATCH, params.getIfMatch());
        request.putHeaderParam(TRANSACTION_ID, params.getStreamTransactionId());
        request.putQueryParam(ArangoRequestParam.WAIT_FOR_SYNC, params.getWaitForSync());
        request.putQueryParam(RETURN_OLD, params.getReturnOld());
        request.putQueryParam(SILENT, params.getSilent());
        return request;
    }

    protected <T> ResponseDeserializer<DocumentDeleteEntity<T>> deleteDocumentResponseDeserializer(
            final Class<T> type) {
        return response -> {
            final JsonNode body = getInternalSerialization().parse(response.getBody());
            final DocumentDeleteEntity<T> doc = getInternalSerialization().deserialize(body, DocumentDeleteEntity.class);
            final JsonNode oldDoc = body.get(OLD);
            if (oldDoc != null) {
                doc.setOld(getUserSerialization().deserialize(getInternalSerialization().serialize(oldDoc), type));
            }
            return doc;
        };
    }

    protected <T> Request deleteDocumentsRequest(final Collection<T> keys, final DocumentDeleteOptions options) {
        final Request request = request(db.dbName(), RequestType.DELETE, PATH_API_DOCUMENT, name);
        final DocumentDeleteOptions params = (options != null ? options : new DocumentDeleteOptions());
        request.putHeaderParam(TRANSACTION_ID, params.getStreamTransactionId());
        request.putQueryParam(ArangoRequestParam.WAIT_FOR_SYNC, params.getWaitForSync());
        request.putQueryParam(RETURN_OLD, params.getReturnOld());
        request.putQueryParam(SILENT, params.getSilent());
        request.setBody(getInternalSerialization().serialize(keys));
        return request;
    }

    protected <T> ResponseDeserializer<MultiDocumentEntity<DocumentDeleteEntity<T>>> deleteDocumentsResponseDeserializer(
            final Class<T> type) {
        return response -> {
            final MultiDocumentEntity<DocumentDeleteEntity<T>> multiDocument = new MultiDocumentEntity<>();
            final Collection<DocumentDeleteEntity<T>> docs = new ArrayList<>();
            final Collection<ErrorEntity> errors = new ArrayList<>();
            final Collection<Object> documentsAndErrors = new ArrayList<>();
            final JsonNode body = getInternalSerialization().parse(response.getBody());
            for (final Iterator<JsonNode> iterator = body.iterator(); iterator.hasNext(); ) {
                final JsonNode next = iterator.next();
                JsonNode isError = next.get(ArangoResponseField.ERROR_FIELD_NAME);
                if (isError != null && isError.booleanValue()) {
                    final ErrorEntity error = getInternalSerialization().deserialize(next, ErrorEntity.class);
                    errors.add(error);
                    documentsAndErrors.add(error);
                } else {
                    final DocumentDeleteEntity<T> doc = getInternalSerialization().deserialize(next, DocumentDeleteEntity.class);
                    final JsonNode oldDoc = next.get(OLD);
                    if (oldDoc != null) {
                        doc.setOld(getUserSerialization().deserialize(getInternalSerialization().serialize(oldDoc), type));
                    }
                    docs.add(doc);
                    documentsAndErrors.add(doc);
                }
            }
            multiDocument.setDocuments(docs);
            multiDocument.setErrors(errors);
            multiDocument.setDocumentsAndErrors(documentsAndErrors);
            return multiDocument;
        };
    }

    protected Request documentExistsRequest(final String key, final DocumentExistsOptions options) {
        final Request request = request(db.dbName(), RequestType.HEAD, PATH_API_DOCUMENT,
                DocumentUtil.createDocumentHandle(name, key));
        final DocumentExistsOptions params = (options != null ? options : new DocumentExistsOptions());
        request.putHeaderParam(TRANSACTION_ID, params.getStreamTransactionId());
        request.putHeaderParam(ArangoRequestParam.IF_MATCH, params.getIfMatch());
        request.putHeaderParam(ArangoRequestParam.IF_NONE_MATCH, params.getIfNoneMatch());
        return request;
    }

    protected Request getIndexRequest(final String id) {
        return request(db.dbName(), RequestType.GET, PATH_API_INDEX, createIndexId(id));
    }

    protected Request deleteIndexRequest(final String id) {
        return request(db.dbName(), RequestType.DELETE, PATH_API_INDEX, createIndexId(id));
    }

    protected ResponseDeserializer<String> deleteIndexResponseDeserializer() {
        return response -> getInternalSerialization().deserialize(response.getBody(), "/id", String.class);
    }

    private String createIndexId(final String id) {
        final String index;
        if (id.matches(DocumentUtil.REGEX_ID)) {
            index = id;
        } else if (id.matches(DocumentUtil.REGEX_KEY)) {
            index = name + "/" + id;
        } else {
            throw new ArangoDBException(String.format("index id %s is not valid.", id));
        }
        return index;
    }

    @Deprecated
    protected Request createHashIndexRequest(final Iterable<String> fields, final HashIndexOptions options) {
        final Request request = request(db.dbName(), RequestType.POST, PATH_API_INDEX);
        request.putQueryParam(COLLECTION, name);
        request.setBody(
                getInternalSerialization().serialize(OptionsBuilder.build(options != null ? options : new HashIndexOptions(), fields)));
        return request;
    }

    @Deprecated
    protected Request createSkiplistIndexRequest(final Iterable<String> fields, final SkiplistIndexOptions options) {
        final Request request = request(db.dbName(), RequestType.POST, PATH_API_INDEX);
        request.putQueryParam(COLLECTION, name);
        request.setBody(
                getInternalSerialization().serialize(OptionsBuilder.build(options != null ? options : new SkiplistIndexOptions(), fields)));
        return request;
    }

    protected Request createPersistentIndexRequest(
            final Iterable<String> fields, final PersistentIndexOptions options) {
        final Request request = request(db.dbName(), RequestType.POST, PATH_API_INDEX);
        request.putQueryParam(COLLECTION, name);
        request.setBody(getInternalSerialization().serialize(
                OptionsBuilder.build(options != null ? options : new PersistentIndexOptions(), fields)));
        return request;
    }

    protected Request createGeoIndexRequest(final Iterable<String> fields, final GeoIndexOptions options) {
        final Request request = request(db.dbName(), RequestType.POST, PATH_API_INDEX);
        request.putQueryParam(COLLECTION, name);
        request.setBody(
                getInternalSerialization().serialize(OptionsBuilder.build(options != null ? options : new GeoIndexOptions(), fields)));
        return request;
    }

    protected Request createFulltextIndexRequest(final Iterable<String> fields, final FulltextIndexOptions options) {
        final Request request = request(db.dbName(), RequestType.POST, PATH_API_INDEX);
        request.putQueryParam(COLLECTION, name);
        request.setBody(
                getInternalSerialization().serialize(OptionsBuilder.build(options != null ? options : new FulltextIndexOptions(), fields)));
        return request;
    }

    protected Request createTtlIndexRequest(final Iterable<String> fields, final TtlIndexOptions options) {
        final Request request = request(db.dbName(), RequestType.POST, PATH_API_INDEX);
        request.putQueryParam(COLLECTION, name);
        request.setBody(
                getInternalSerialization().serialize(OptionsBuilder.build(options != null ? options : new TtlIndexOptions(), fields)));
        return request;
    }

    protected Request createZKDIndexRequest(
            final Iterable<String> fields, final ZKDIndexOptions options) {
        final Request request = request(db.dbName(), RequestType.POST, PATH_API_INDEX);
        request.putQueryParam(COLLECTION, name);
        request.setBody(getInternalSerialization().serialize(OptionsBuilder.build(options != null ? options :
                new ZKDIndexOptions().fieldValueTypes(ZKDIndexOptions.FieldValueTypes.DOUBLE), fields)));
        return request;
    }

    protected Request getIndexesRequest() {
        final Request request = request(db.dbName(), RequestType.GET, PATH_API_INDEX);
        request.putQueryParam(COLLECTION, name);
        return request;
    }

    protected ResponseDeserializer<Collection<IndexEntity>> getIndexesResponseDeserializer() {
        return response -> getInternalSerialization().deserialize(response.getBody(), "/indexes", new Type<Collection<IndexEntity>>() {
        }.getType());
    }

    protected Request truncateRequest(final CollectionTruncateOptions options) {
        final Request request = request(db.dbName(), RequestType.PUT, PATH_API_COLLECTION, name, "truncate");
        final CollectionTruncateOptions params = (options != null ? options : new CollectionTruncateOptions());
        request.putHeaderParam(TRANSACTION_ID, params.getStreamTransactionId());
        return request;
    }

    protected Request countRequest(final CollectionCountOptions options) {
        final Request request = request(db.dbName(), RequestType.GET, PATH_API_COLLECTION, name, "count");
        final CollectionCountOptions params = (options != null ? options : new CollectionCountOptions());
        request.putHeaderParam(TRANSACTION_ID, params.getStreamTransactionId());
        return request;
    }

    protected Request dropRequest(final Boolean isSystem) {
        return request(db.dbName(), RequestType.DELETE, PATH_API_COLLECTION, name).putQueryParam("isSystem", isSystem);
    }

    protected Request getInfoRequest() {
        return request(db.dbName(), RequestType.GET, PATH_API_COLLECTION, name);
    }

    protected Request getPropertiesRequest() {
        return request(db.dbName(), RequestType.GET, PATH_API_COLLECTION, name, "properties");
    }

    protected Request changePropertiesRequest(final CollectionPropertiesOptions options) {
        final Request request = request(db.dbName(), RequestType.PUT, PATH_API_COLLECTION, name, "properties");
        request.setBody(getInternalSerialization().serialize(options != null ? options : new CollectionPropertiesOptions()));
        return request;
    }

    protected Request renameRequest(final String newName) {
        final Request request = request(db.dbName(), RequestType.PUT, PATH_API_COLLECTION, name, "rename");
        request.setBody(getInternalSerialization().serialize(OptionsBuilder.build(new CollectionRenameOptions(), newName)));
        return request;
    }

    protected <T> Request responsibleShardRequest(final T value) {
        final Request request = request(db.dbName(), RequestType.PUT, PATH_API_COLLECTION, name, "responsibleShard");
        request.setBody(getUserSerialization().serialize(value));
        return request;
    }

    protected Request getRevisionRequest() {
        return request(db.dbName(), RequestType.GET, PATH_API_COLLECTION, name, "revision");
    }

    protected Request grantAccessRequest(final String user, final Permissions permissions) {
        return request(DbName.SYSTEM, RequestType.PUT, PATH_API_USER, user, ArangoRequestParam.DATABASE,
                db.dbName().get(), name).setBody(getInternalSerialization().serialize(OptionsBuilder.build(new UserAccessOptions(), permissions)));
    }

    protected Request resetAccessRequest(final String user) {
        return request(DbName.SYSTEM, RequestType.DELETE, PATH_API_USER, user, ArangoRequestParam.DATABASE,
                db.dbName().get(), name);
    }

    protected Request getPermissionsRequest(final String user) {
        return request(DbName.SYSTEM, RequestType.GET, PATH_API_USER, user, ArangoRequestParam.DATABASE,
                db.dbName().get(), name);
    }

    protected ResponseDeserializer<Permissions> getPermissionsResponseDeserialzer() {
        return response -> getInternalSerialization().deserialize(response.getBody(), ArangoResponseField.RESULT_JSON_POINTER, Permissions.class);
    }

    private boolean isStringCollection(final Collection<?> values) {
        return values.stream().allMatch(String.class::isInstance);
    }

    private JsonNode stringCollectionToJsonArray(final Collection<String> values) {
        return SerdeUtils.INSTANCE.parseJson("[" + String.join(",", values) + "]");
    }

}
