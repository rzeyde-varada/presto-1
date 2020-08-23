/*
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
 */
package io.prestosql.sql.planner;

import com.google.common.util.concurrent.Futures;
import com.google.common.util.concurrent.ListenableFuture;
import com.google.common.util.concurrent.SettableFuture;
import io.prestosql.spi.predicate.Domain;
import io.prestosql.spi.predicate.TupleDomain;
import io.prestosql.spi.type.Type;
import io.prestosql.sql.planner.plan.DynamicFilterId;
import io.prestosql.sql.planner.plan.PlanNode;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

import static com.google.common.base.MoreObjects.toStringHelper;
import static com.google.common.base.Preconditions.checkArgument;
import static com.google.common.base.Verify.verify;
import static com.google.common.collect.ImmutableMap.toImmutableMap;
import static com.google.common.util.concurrent.MoreExecutors.directExecutor;
import static java.util.Objects.requireNonNull;
import static java.util.function.Function.identity;

public class LocalDynamicFilterConsumer
{
    // Mapping from dynamic filter ID to its build channel indices.
    private final Map<DynamicFilterId, Integer> buildChannels;

    // Mapping from dynamic filter ID to its build channel type.
    private final Map<DynamicFilterId, Type> filterBuildTypes;

    private final SettableFuture<TupleDomain<DynamicFilterId>> resultFuture;

    // Number of build-side partitions to be collected.
    private final int partitionCount;

    // The resulting predicates from each build-side partition.
    private final List<TupleDomain<DynamicFilterId>> partitions;

    public LocalDynamicFilterConsumer(Map<DynamicFilterId, Integer> buildChannels, Map<DynamicFilterId, Type> filterBuildTypes, int partitionCount)
    {
        this.buildChannels = requireNonNull(buildChannels, "buildChannels is null");
        this.filterBuildTypes = requireNonNull(filterBuildTypes, "filterBuildTypes is null");
        verify(buildChannels.keySet().equals(filterBuildTypes.keySet()), "filterBuildTypes and buildChannels must have same keys");

        this.resultFuture = SettableFuture.create();

        this.partitionCount = partitionCount;
        this.partitions = new ArrayList<>(partitionCount);
    }

    public ListenableFuture<Map<DynamicFilterId, Domain>> getDynamicFilterDomains()
    {
        return Futures.transform(resultFuture, this::convertTupleDomain, directExecutor());
    }

    private synchronized void addPartition(TupleDomain<DynamicFilterId> tupleDomain)
    {
        // Called concurrently by each DynamicFilterSourceOperator instance (when collection is over).
        verify(partitions.size() < partitionCount);
        // NOTE: may result in a bit more relaxed constraint if there are multiple columns and multiple rows.
        // See the comment at TupleDomain::columnWiseUnion() for more details.
        partitions.add(tupleDomain);
        if (partitions.size() == partitionCount) {
            TupleDomain<DynamicFilterId> result = TupleDomain.columnWiseUnion(partitions);
            // No more partitions are left to be processed.
            resultFuture.set(result);
        }
    }

    private Map<DynamicFilterId, Domain> convertTupleDomain(TupleDomain<DynamicFilterId> result)
    {
        if (result.isNone()) {
            // One of the join build symbols has no non-null values, therefore no filters can match predicate
            return buildChannels.keySet().stream()
                    .collect(toImmutableMap(identity(), filterId -> Domain.none(filterBuildTypes.get(filterId))));
        }
        return result.getDomains().get();
    }

    public static LocalDynamicFilterConsumer create(PlanNode probeNode, PlanNode buildNode, Map<DynamicFilterId, Symbol> dynamicFilters, List<Type> buildSourceTypes, int partitionCount)
    {
        checkArgument(!dynamicFilters.isEmpty(), "Join node dynamicFilters is empty.");
        // Collect dynamic filters for all dynamic filters produced by join
        Map<DynamicFilterId, Integer> buildChannels = dynamicFilters.entrySet().stream()
                .collect(toImmutableMap(
                        // Dynamic filter ID
                        Map.Entry::getKey,
                        // Build-side channel index
                        entry -> {
                            Symbol buildSymbol = entry.getValue();
                            int buildChannelIndex = buildNode.getOutputSymbols().indexOf(buildSymbol);
                            verify(buildChannelIndex >= 0);
                            return buildChannelIndex;
                        }));

        Map<DynamicFilterId, Type> filterBuildTypes = buildChannels.entrySet().stream()
                .collect(toImmutableMap(
                        Map.Entry::getKey,
                        entry -> buildSourceTypes.get(entry.getValue())));
        return new LocalDynamicFilterConsumer(buildChannels, filterBuildTypes, partitionCount);
    }

    public Map<DynamicFilterId, Integer> getBuildChannels()
    {
        return buildChannels;
    }

    public Consumer<TupleDomain<DynamicFilterId>> getTupleDomainConsumer()
    {
        return this::addPartition;
    }

    @Override
    public String toString()
    {
        return toStringHelper(this)
                .add("buildChannels", buildChannels)
                .add("partitionCount", partitionCount)
                .add("partitions", partitions)
                .toString();
    }
}
