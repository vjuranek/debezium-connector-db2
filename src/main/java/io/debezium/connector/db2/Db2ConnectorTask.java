/*
 * Copyright Debezium Authors.
 *
 * Licensed under the Apache Software License version 2.0, available at http://www.apache.org/licenses/LICENSE-2.0
 */
package io.debezium.connector.db2;

import java.sql.SQLException;
import java.util.List;
import java.util.stream.Collectors;

import org.apache.kafka.connect.errors.ConnectException;
import org.apache.kafka.connect.source.SourceRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.debezium.config.Configuration;
import io.debezium.config.Field;
import io.debezium.connector.base.ChangeEventQueue;
import io.debezium.connector.common.BaseSourceTask;
import io.debezium.pipeline.ChangeEventSourceCoordinator;
import io.debezium.pipeline.DataChangeEvent;
import io.debezium.pipeline.ErrorHandler;
import io.debezium.pipeline.EventDispatcher;
import io.debezium.pipeline.metrics.DefaultChangeEventSourceMetricsFactory;
import io.debezium.pipeline.spi.Offsets;
import io.debezium.relational.TableId;
import io.debezium.schema.TopicSelector;
import io.debezium.util.Clock;
import io.debezium.util.SchemaNameAdjuster;

/**
 * The main task executing streaming from DB2.
 * Responsible for lifecycle management the streaming code.
 *
 * @author Jiri Pechanec
 *
 */
public class Db2ConnectorTask extends BaseSourceTask<Db2Partition, Db2OffsetContext> {

    private static final Logger LOGGER = LoggerFactory.getLogger(Db2ConnectorTask.class);
    private static final String CONTEXT_NAME = "db2-server-connector-task";

    private volatile Db2TaskContext taskContext;
    private volatile ChangeEventQueue<DataChangeEvent> queue;
    private volatile Db2Connection dataConnection;
    private volatile Db2Connection metadataConnection;
    private volatile ErrorHandler errorHandler;
    private volatile Db2DatabaseSchema schema;

    @Override
    public String version() {
        return Module.version();
    }

    @Override
    public ChangeEventSourceCoordinator<Db2Partition, Db2OffsetContext> start(Configuration config) {
        final Db2ConnectorConfig connectorConfig = new Db2ConnectorConfig(config);
        final TopicSelector<TableId> topicSelector = Db2TopicSelector.defaultSelector(connectorConfig);
        final SchemaNameAdjuster schemaNameAdjuster = connectorConfig.schemaNameAdjustmentMode().createAdjuster();

        // By default do not load whole result sets into memory
        config = config.edit()
                .withDefault("database.responseBuffering", "adaptive")
                .withDefault("database.fetchSize", 10_000)
                .build();

        dataConnection = new Db2Connection(connectorConfig.getJdbcConfig());
        metadataConnection = new Db2Connection(connectorConfig.getJdbcConfig());
        try {
            dataConnection.setAutoCommit(false);
        }
        catch (SQLException e) {
            throw new ConnectException(e);
        }
        this.schema = new Db2DatabaseSchema(connectorConfig, schemaNameAdjuster, topicSelector, dataConnection);
        this.schema.initializeStorage();

        Offsets<Db2Partition, Db2OffsetContext> previousOffsets = getPreviousOffsets(new Db2Partition.Provider(connectorConfig),
                new Db2OffsetContext.Loader(connectorConfig));
        final Db2Partition partition = previousOffsets.getTheOnlyPartition();
        final Db2OffsetContext previousOffset = previousOffsets.getTheOnlyOffset();

        if (previousOffset != null) {
            schema.recover(partition, previousOffset);
        }

        taskContext = new Db2TaskContext(connectorConfig, schema);

        final Clock clock = Clock.system();

        // Set up the task record queue ...
        this.queue = new ChangeEventQueue.Builder<DataChangeEvent>()
                .pollInterval(connectorConfig.getPollInterval())
                .maxBatchSize(connectorConfig.getMaxBatchSize())
                .maxQueueSize(connectorConfig.getMaxQueueSize())
                .loggingContextSupplier(() -> taskContext.configureLoggingContext(CONTEXT_NAME))
                .build();

        errorHandler = new ErrorHandler(Db2Connector.class, connectorConfig, queue);

        final Db2EventMetadataProvider metadataProvider = new Db2EventMetadataProvider();

        final EventDispatcher<Db2Partition, TableId> dispatcher = new EventDispatcher<>(
                connectorConfig,
                topicSelector,
                schema,
                queue,
                connectorConfig.getTableFilters().dataCollectionFilter(),
                DataChangeEvent::new,
                metadataProvider,
                schemaNameAdjuster);

        ChangeEventSourceCoordinator<Db2Partition, Db2OffsetContext> coordinator = new ChangeEventSourceCoordinator<>(
                previousOffsets,
                errorHandler,
                Db2Connector.class,
                connectorConfig,
                new Db2ChangeEventSourceFactory(connectorConfig, dataConnection, metadataConnection, errorHandler, dispatcher, clock, schema),
                new DefaultChangeEventSourceMetricsFactory<>(),
                dispatcher,
                schema);

        coordinator.start(taskContext, this.queue, metadataProvider);

        return coordinator;
    }

    @Override
    public List<SourceRecord> doPoll() throws InterruptedException {
        final List<DataChangeEvent> records = queue.poll();

        final List<SourceRecord> sourceRecords = records.stream()
                .map(DataChangeEvent::getRecord)
                .collect(Collectors.toList());

        return sourceRecords;
    }

    @Override
    public void doStop() {
        try {
            if (dataConnection != null) {
                dataConnection.close();
            }
        }
        catch (SQLException e) {
            LOGGER.error("Exception while closing JDBC connection", e);
        }

        try {
            if (metadataConnection != null) {
                metadataConnection.close();
            }
        }
        catch (SQLException e) {
            LOGGER.error("Exception while closing JDBC metadata connection", e);
        }

        if (schema != null) {
            schema.close();
        }
    }

    @Override
    protected Iterable<Field> getAllConfigurationFields() {
        return Db2ConnectorConfig.ALL_FIELDS;
    }
}
