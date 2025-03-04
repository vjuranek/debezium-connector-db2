/*
 * Copyright Debezium Authors.
 *
 * Licensed under the Apache Software License version 2.0, available at http://www.apache.org/licenses/LICENSE-2.0
 */
package io.debezium.connector.db2;

import java.util.Optional;

import io.debezium.pipeline.ErrorHandler;
import io.debezium.pipeline.EventDispatcher;
import io.debezium.pipeline.source.snapshot.incremental.IncrementalSnapshotChangeEventSource;
import io.debezium.pipeline.source.snapshot.incremental.SignalBasedIncrementalSnapshotChangeEventSource;
import io.debezium.pipeline.source.spi.ChangeEventSourceFactory;
import io.debezium.pipeline.source.spi.DataChangeEventListener;
import io.debezium.pipeline.source.spi.SnapshotChangeEventSource;
import io.debezium.pipeline.source.spi.SnapshotProgressListener;
import io.debezium.pipeline.source.spi.StreamingChangeEventSource;
import io.debezium.relational.TableId;
import io.debezium.schema.DataCollectionId;
import io.debezium.util.Clock;
import io.debezium.util.Strings;

public class Db2ChangeEventSourceFactory implements ChangeEventSourceFactory<Db2Partition, Db2OffsetContext> {

    private final Db2ConnectorConfig configuration;
    private final Db2Connection dataConnection;
    private final Db2Connection metadataConnection;
    private final ErrorHandler errorHandler;
    private final EventDispatcher<Db2Partition, TableId> dispatcher;
    private final Clock clock;
    private final Db2DatabaseSchema schema;

    public Db2ChangeEventSourceFactory(Db2ConnectorConfig configuration, Db2Connection dataConnection, Db2Connection metadataConnection,
                                       ErrorHandler errorHandler, EventDispatcher<Db2Partition, TableId> dispatcher, Clock clock, Db2DatabaseSchema schema) {
        this.configuration = configuration;
        this.dataConnection = dataConnection;
        this.metadataConnection = metadataConnection;
        this.errorHandler = errorHandler;
        this.dispatcher = dispatcher;
        this.clock = clock;
        this.schema = schema;
    }

    @Override
    public SnapshotChangeEventSource<Db2Partition, Db2OffsetContext> getSnapshotChangeEventSource(SnapshotProgressListener<Db2Partition> snapshotProgressListener) {
        return new Db2SnapshotChangeEventSource(configuration, dataConnection, schema, dispatcher, clock, snapshotProgressListener);
    }

    @Override
    public StreamingChangeEventSource<Db2Partition, Db2OffsetContext> getStreamingChangeEventSource() {
        return new Db2StreamingChangeEventSource(
                configuration,
                dataConnection,
                metadataConnection,
                dispatcher,
                errorHandler,
                clock,
                schema);
    }

    @Override
    public Optional<IncrementalSnapshotChangeEventSource<Db2Partition, ? extends DataCollectionId>> getIncrementalSnapshotChangeEventSource(
                                                                                                                                            Db2OffsetContext offsetContext,
                                                                                                                                            SnapshotProgressListener<Db2Partition> snapshotProgressListener,
                                                                                                                                            DataChangeEventListener<Db2Partition> dataChangeEventListener) {
        // If no data collection id is provided, don't return an instance as the implementation requires
        // that a signal data collection id be provided to work.
        if (Strings.isNullOrEmpty(configuration.getSignalingDataCollectionId())) {
            return Optional.empty();
        }
        final SignalBasedIncrementalSnapshotChangeEventSource<Db2Partition, TableId> incrementalSnapshotChangeEventSource = new SignalBasedIncrementalSnapshotChangeEventSource<>(
                configuration,
                dataConnection,
                dispatcher,
                schema,
                clock,
                snapshotProgressListener,
                dataChangeEventListener);
        return Optional.of(incrementalSnapshotChangeEventSource);
    }
}
