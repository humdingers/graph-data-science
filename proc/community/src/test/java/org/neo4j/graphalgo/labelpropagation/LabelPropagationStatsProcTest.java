/*
 * Copyright (c) 2017-2020 "Neo4j,"
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
package org.neo4j.graphalgo.labelpropagation;

import org.junit.jupiter.api.Test;
import org.neo4j.graphalgo.AlgoBaseProc;
import org.neo4j.graphalgo.GdsCypher;
import org.neo4j.graphalgo.compat.MapUtil;
import org.neo4j.graphalgo.core.CypherMapWrapper;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.hamcrest.Matchers.greaterThanOrEqualTo;
import static org.neo4j.graphalgo.assertj.ConditionFactory.containsAllEntriesOf;
import static org.neo4j.graphalgo.assertj.ConditionFactory.containsExactlyInAnyOrderEntriesOf;

class LabelPropagationStatsProcTest extends LabelPropagationProcTest<LabelPropagationStatsConfig> {

    @Override
    public Class<? extends AlgoBaseProc<LabelPropagation, LabelPropagation, LabelPropagationStatsConfig>> getProcedureClazz() {
        return LabelPropagationStatsProc.class;
    }

    @Override
    public LabelPropagationStatsConfig createConfig(CypherMapWrapper mapWrapper) {
        return LabelPropagationStatsConfig.of(getUsername(), Optional.empty(), Optional.empty(), mapWrapper);
    }

    @Test
    void yields() {
        String query = GdsCypher
            .call()
            .withAnyLabel()
            .withAnyRelationshipType()
            .algo("labelPropagation")
            .statsMode()
            .yields();

        assertCypherResult(query, List.of(Map.of(
            "didConverge", true,
            "ranIterations", 2L,
            "communityCount", 10L,
            "communityDistribution", containsExactlyInAnyOrderEntriesOf(Map.of(
                "min", 1L,
                "max", 2L,
                "mean", 1.2,
                "p50", 1L,
                "p75", 1L,
                "p90", 2L,
                "p95", 2L,
                "p99", 2L,
                "p999", 2L
            )),
            "createMillis", greaterThanOrEqualTo(0L),
            "computeMillis", greaterThanOrEqualTo(0L),
            "postProcessingMillis", greaterThanOrEqualTo(0L),
            "configuration", containsAllEntriesOf(MapUtil.map(
                "consecutiveIds", false,
                "maxIterations", 10,
                "seedProperty", null,
                "nodeWeightProperty", null,
                "relationshipWeightProperty", null
            ))
        )));
    }

    @Test
    void zeroCommunitiesInEmptyGraph() {
        runQuery("CALL db.createLabel('VeryTemp')");
        runQuery("CALL db.createRelationshipType('VERY_TEMP')");
        String query = GdsCypher
            .call()
            .withNodeLabel("VeryTemp")
            .withRelationshipType("VERY_TEMP")
            .algo("labelPropagation")
            .statsMode()
            .yields("communityCount");

        assertCypherResult(query, List.of(Map.of("communityCount", 0L)));
    }
}
