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
package org.neo4j.gds.ml.nodemodels.multiclasslogisticregression;

import org.assertj.core.data.Offset;
import org.junit.jupiter.api.Test;
import org.neo4j.gds.ml.nodemodels.logisticregression.ImmutableMultiClassNLRTrainConfig;
import org.neo4j.graphalgo.TestLog;
import org.neo4j.graphalgo.api.Graph;
import org.neo4j.graphalgo.core.utils.mem.AllocationTracker;
import org.neo4j.graphalgo.core.utils.paged.HugeLongArray;
import org.neo4j.graphalgo.extension.GdlExtension;
import org.neo4j.graphalgo.extension.GdlGraph;
import org.neo4j.graphalgo.extension.Inject;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.neo4j.gds.embeddings.graphsage.ddl4j.Dimensions.COLUMNS_INDEX;
import static org.neo4j.gds.embeddings.graphsage.ddl4j.Dimensions.ROWS_INDEX;

@GdlExtension
class MultiClassNLRTrainTest {

    @GdlGraph
    private static final String DB_QUERY =
        "CREATE " +
        "  (n1:N {a: 2.0, b: 1.2, t: 1.0})" +
        ", (n2:N {a: 1.3, b: 0.5, t: 0.0})" +
        ", (n3:N {a: 0.0, b: 2.8, t: 2.0})" +
        ", (n4:N {a: 1.0, b: 0.9, t: 1.0})";

    private static final double NO_PENALTY = 0.0;

    @Inject
    private Graph graph;

    @Test
    void shouldComputeWithDefaultAdamOptimizerAndStreakStopper() {
        var config = ImmutableMultiClassNLRTrainConfig.builder()
            .featureProperties(List.of("a", "b"))
            .targetProperty("t")
            .penalty(NO_PENALTY)
            .maxIterations(100000)
            .tolerance(1e-4)
            .build();

        var nodeIds = HugeLongArray.newArray(graph.nodeCount(), AllocationTracker.empty());
        nodeIds.setAll(i -> i);
        var algo = new MultiClassNLRTrain(graph, nodeIds, config, new TestLog());

        var result = algo.compute();

        assertThat(result).isNotNull();

        var trainedWeights = result.weights();
        assertThat(trainedWeights.dimension(ROWS_INDEX)).isEqualTo(3);
        assertThat(trainedWeights.dimension(COLUMNS_INDEX)).isEqualTo(3);

        assertThat(trainedWeights.data().data()).containsExactly(
            new double[]{
                13.565878816092354, 35.45071542666095, -15.413572599075732,
                33.80331989615432, -35.5612283371057, 10.826705193820422,
                -29.644105601850793, 32.70379721264568, 11.382482785703171
            },
            Offset.offset(1e-8)
        );
    }
}
