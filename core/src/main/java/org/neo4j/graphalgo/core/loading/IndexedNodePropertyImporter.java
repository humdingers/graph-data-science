/*
 * Copyright (c) "Neo4j"
 * Neo4j Sweden AB [http://neo4j.com]
 *
 * This file is part of Neo4j.
 *
 * Neo4j is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */
package org.neo4j.graphalgo.core.loading;

import org.jetbrains.annotations.Nullable;
import org.neo4j.graphalgo.NodeLabel;
import org.neo4j.graphalgo.PropertyMapping;
import org.neo4j.graphalgo.api.IdMapping;
import org.neo4j.graphalgo.api.NodeProperties;
import org.neo4j.graphalgo.compat.Neo4jProxy;
import org.neo4j.graphalgo.core.SecureTransaction;
import org.neo4j.graphalgo.core.concurrency.ParallelUtil;
import org.neo4j.graphalgo.core.loading.nodeproperties.NodePropertiesFromStoreBuilder;
import org.neo4j.graphalgo.core.utils.ProgressLogger;
import org.neo4j.graphalgo.core.utils.StatementAction;
import org.neo4j.graphalgo.core.utils.TerminationFlag;
import org.neo4j.graphalgo.core.utils.mem.AllocationTracker;
import org.neo4j.internal.kernel.api.IndexQuery;
import org.neo4j.internal.kernel.api.IndexReadSession;
import org.neo4j.internal.kernel.api.NodeValueIndexCursor;
import org.neo4j.internal.kernel.api.Read;
import org.neo4j.internal.schema.IndexDescriptor;
import org.neo4j.internal.schema.IndexOrder;
import org.neo4j.kernel.api.KernelTransaction;
import org.neo4j.values.storable.NumberValue;
import org.neo4j.values.storable.ValueGroup;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.OptionalDouble;
import java.util.concurrent.ExecutorService;

public final class IndexedNodePropertyImporter extends StatementAction {
    private final int concurrency;
    private final NodeLabel nodeLabel;
    private final PropertyMapping mapping;
    private final IndexDescriptor index;
    private final Optional<IndexQuery> indexQuery;
    private final IdMapping idMap;
    private final ProgressLogger progressLogger;
    private final TerminationFlag terminationFlag;
    private final @Nullable ExecutorService executorService;
    private final int propertyId;
    private final NodePropertiesFromStoreBuilder propertiesBuilder;
    private long imported;
    private long logged;

    IndexedNodePropertyImporter(
        int concurrency,
        SecureTransaction tx,
        NodeLabel nodeLabel,
        PropertyMapping mapping,
        IndexDescriptor index,
        IdMapping idMap,
        ProgressLogger progressLogger,
        TerminationFlag terminationFlag,
        @Nullable ExecutorService executorService,
        AllocationTracker tracker
    ) {
        this(
            concurrency,
            tx,
            nodeLabel,
            mapping,
            index,
            Optional.empty(),
            idMap,
            progressLogger,
            terminationFlag,
            executorService,
            index.schema().getPropertyId(),
            NodePropertiesFromStoreBuilder.of(
                idMap.nodeCount(),
                tracker,
                mapping.defaultValue()
            )
        );
    }

    private IndexedNodePropertyImporter(IndexedNodePropertyImporter from, IndexQuery indexQuery) {
        this(
            from.concurrency,
            from.tx,
            from.nodeLabel,
            from.mapping,
            from.index,
            Optional.of(indexQuery),
            from.idMap,
            from.progressLogger,
            from.terminationFlag,
            from.executorService,
            from.propertyId,
            from.propertiesBuilder
        );
    }

    private IndexedNodePropertyImporter(
        int concurrency,
        SecureTransaction tx,
        NodeLabel nodeLabel,
        PropertyMapping mapping,
        IndexDescriptor index,
        Optional<IndexQuery> indexQuery,
        IdMapping idMap,
        ProgressLogger progressLogger,
        TerminationFlag terminationFlag,
        @Nullable ExecutorService executorService,
        int propertyId,
        NodePropertiesFromStoreBuilder propertiesBuilder
    ) {
        super(tx);
        this.concurrency = concurrency;
        this.nodeLabel = nodeLabel;
        this.mapping = mapping;
        this.index = index;
        this.indexQuery = indexQuery;
        this.idMap = idMap;
        this.progressLogger = progressLogger;
        this.terminationFlag = terminationFlag;
        this.executorService = executorService;
        this.propertyId = propertyId;
        this.propertiesBuilder = propertiesBuilder;
    }

    @Override
    public String threadName() {
        return "index-scan-" + index.getName();
    }

    @Override
    public void accept(KernelTransaction ktx) throws Exception {
        var read = ktx.dataRead();
        try (var indexCursor = Neo4jProxy.allocateNodeValueIndexCursor(
            ktx.cursors(),
            ktx.pageCursorTracer(),
            Neo4jProxy.memoryTracker(ktx)
        )) {
            var indexReadSession = read.indexReadSession(index);
            if (indexQuery.isPresent()) {
                // if indexQuery is not null, we are a parallel batch
                Neo4jProxy.nodeIndexSeek(read, indexReadSession, indexCursor, IndexOrder.NONE, true, indexQuery.get());
            } else {
                // we don't need to check the feature flag, as we set the concurrency to 1 in ScanningNodesImporter
                if (concurrency > 1 && ParallelUtil.canRunInParallel(executorService)) {
                    // try to import in parallel, see if we can find a range
                    var parallelJobs = prepareParallelScan(read, indexReadSession, indexCursor);
                    if (parallelJobs != null) {
                        ParallelUtil.run(parallelJobs, executorService);
                        return;
                    }
                }
                // do a single threaded scan if:
                // feature flag was off, or concurrency is 1, or the thread pool is not usable, or we couldn't find a valid range
                Neo4jProxy.nodeIndexScan(read, indexReadSession, indexCursor, IndexOrder.NONE, true);
            }
            importFromCursor(indexCursor);
        }
    }

    NodeLabel nodeLabel() {
        return nodeLabel;
    }

    PropertyMapping mapping() {
        return mapping;
    }

    long imported() {
        return imported;
    }

    NodeProperties build() {
        return propertiesBuilder.build();
    }

    private @Nullable List<IndexedNodePropertyImporter> prepareParallelScan(
        Read read,
        IndexReadSession indexReadSession,
        NodeValueIndexCursor indexCursor
    ) throws Exception {
        var anyValue = IndexQuery.range(this.propertyId, ValueGroup.NUMBER);
        // find min value
        Neo4jProxy.nodeIndexSeek(read, indexReadSession, indexCursor, IndexOrder.ASCENDING, true, anyValue);
        var min = findFirst(indexCursor);
        if (min.isPresent()) {
            // find max value
            Neo4jProxy.nodeIndexSeek(read, indexReadSession, indexCursor, IndexOrder.DESCENDING, true, anyValue);
            var max = findFirst(indexCursor);
            if (max.isPresent()) {
                var minValue = min.getAsDouble();
                // nextUp to make the range exclusive
                var maxValue = Math.nextUp(max.getAsDouble());
                var range = maxValue - minValue;
                var batchSize = range / concurrency;
                // if min and max are too close together the batchSize could be small enough to not
                // change the value of minValue. In that case, increase it to guarantee that is always
                // has an effect.
                if (minValue == (minValue + batchSize)) {
                    batchSize = Math.nextUp(minValue) - minValue;
                }
                var jobs = new ArrayList<IndexedNodePropertyImporter>(this.concurrency);
                while (minValue < maxValue) {
                    var query = IndexQuery.range(this.propertyId, minValue, true, minValue + batchSize, false);
                    jobs.add(new IndexedNodePropertyImporter(this, query));
                    minValue += batchSize;
                }
                return jobs;
            }
        }
        return null;
    }

    private OptionalDouble findFirst(NodeValueIndexCursor indexCursor) {
        var numberOfProperties = indexCursor.numberOfProperties();
        while (indexCursor.next()) {
            if (indexCursor.hasValue()) {
                var node = indexCursor.nodeReference();
                var nodeId = idMap.toMappedNodeId(node);
                if (nodeId >= 0) {
                    for (int i = 0; i < numberOfProperties; i++) {
                        var propertyKey = indexCursor.propertyKey(i);
                        if (propertyId == propertyKey) {
                            var propertyValue = indexCursor.propertyValue(i);
                            var number = ((NumberValue) propertyValue).doubleValue();
                            if (Double.isFinite(number)) {
                                return OptionalDouble.of(number);
                            }
                        }
                    }
                }
            }
        }
        return OptionalDouble.empty();
    }

    private void importFromCursor(NodeValueIndexCursor indexCursor) {
        var numberOfProperties = indexCursor.numberOfProperties();
        while (indexCursor.next()) {
            if (indexCursor.hasValue()) {
                var node = indexCursor.nodeReference();
                var nodeId = idMap.toMappedNodeId(node);
                if (nodeId >= 0) {
                    for (int i = 0; i < numberOfProperties; i++) {
                        var propertyKey = indexCursor.propertyKey(i);
                        if (propertyId == propertyKey) {
                            var propertyValue = indexCursor.propertyValue(i);
                            propertiesBuilder.set(nodeId, propertyValue);
                            imported += 1;
                            if ((imported & 0x1_FFFFL) == 0L) {
                                progressLogger.logProgress(imported - logged);
                                logged = imported;
                                terminationFlag.assertRunning();
                            }
                        }
                    }
                }
            }
        }
    }
}
