/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.ignite.internal.processors.cache.query;

import org.apache.ignite.*;
import org.apache.ignite.cache.*;
import org.apache.ignite.cache.query.*;
import org.apache.ignite.cluster.*;
import org.apache.ignite.internal.cluster.*;
import org.apache.ignite.internal.processors.cache.*;
import org.apache.ignite.internal.util.typedef.*;
import org.apache.ignite.internal.util.typedef.internal.*;
import org.apache.ignite.lang.*;
import org.apache.ignite.plugin.security.*;
import org.jetbrains.annotations.*;

import javax.cache.*;
import java.util.*;

import static org.apache.ignite.cache.CacheDistributionMode.*;
import static org.apache.ignite.internal.processors.cache.query.GridCacheQueryType.*;

/**
 * Query adapter.
 */
public class GridCacheQueryAdapter<T> implements CacheQuery<T> {
    /** */
    private final GridCacheContext<?, ?> cctx;

    /** */
    private final IgnitePredicate<Cache.Entry<Object, Object>> prjPred;

    /** */
    private final GridCacheQueryType type;

    /** */
    private final IgniteLogger log;

    /** Class name in case of portable query. */
    private final String clsName;

    /** */
    private final String clause;

    /** */
    private final IgniteBiPredicate<Object, Object> filter;

    /** */
    private final boolean incMeta;

    /** */
    private volatile GridCacheQueryMetricsAdapter metrics;

    /** */
    private volatile int pageSize = DFLT_PAGE_SIZE;

    /** */
    private volatile long timeout;

    /** */
    private volatile boolean keepAll = true;

    /** */
    private volatile boolean incBackups;

    /** */
    private volatile boolean dedup;

    /** */
    private volatile ClusterGroup prj;

    /** */
    private boolean keepPortable;

    /** */
    private UUID subjId;

    /** */
    private int taskHash;

    /**
     * @param cctx Context.
     * @param type Query type.
     * @param clsName Class name.
     * @param clause Clause.
     * @param filter Scan filter.
     * @param incMeta Include metadata flag.
     * @param keepPortable Keep portable flag.
     * @param prjPred Cache projection filter.
     */
    public GridCacheQueryAdapter(GridCacheContext<?, ?> cctx,
        GridCacheQueryType type,
        @Nullable IgnitePredicate<Cache.Entry<Object, Object>> prjPred,
        @Nullable String clsName,
        @Nullable String clause,
        @Nullable IgniteBiPredicate<Object, Object> filter,
        boolean incMeta,
        boolean keepPortable) {
        assert cctx != null;
        assert type != null;

        this.cctx = cctx;
        this.type = type;
        this.clsName = clsName;
        this.clause = clause;
        this.prjPred = prjPred;
        this.filter = filter;
        this.incMeta = incMeta;
        this.keepPortable = keepPortable;

        log = cctx.logger(getClass());

        metrics = new GridCacheQueryMetricsAdapter();
    }

    /**
     * @param cctx Context.
     * @param prjPred Cache projection filter.
     * @param type Query type.
     * @param log Logger.
     * @param pageSize Page size.
     * @param timeout Timeout.
     * @param keepAll Keep all flag.
     * @param incBackups Include backups flag.
     * @param dedup Enable dedup flag.
     * @param prj Grid projection.
     * @param filter Key-value filter.
     * @param clsName Class name.
     * @param clause Clause.
     * @param incMeta Include metadata flag.
     * @param keepPortable Keep portable flag.
     * @param subjId Security subject ID.
     * @param taskHash Task hash.
     */
    public GridCacheQueryAdapter(GridCacheContext<?, ?> cctx,
        IgnitePredicate<Cache.Entry<Object, Object>> prjPred,
        GridCacheQueryType type,
        IgniteLogger log,
        int pageSize,
        long timeout,
        boolean keepAll,
        boolean incBackups,
        boolean dedup,
        ClusterGroup prj,
        IgniteBiPredicate<Object, Object> filter,
        @Nullable String clsName,
        String clause,
        boolean incMeta,
        boolean keepPortable,
        UUID subjId,
        int taskHash) {
        this.cctx = cctx;
        this.prjPred = prjPred;
        this.type = type;
        this.log = log;
        this.pageSize = pageSize;
        this.timeout = timeout;
        this.keepAll = keepAll;
        this.incBackups = incBackups;
        this.dedup = dedup;
        this.prj = prj;
        this.filter = filter;
        this.clsName = clsName;
        this.clause = clause;
        this.incMeta = incMeta;
        this.keepPortable = keepPortable;
        this.subjId = subjId;
        this.taskHash = taskHash;
    }

    /**
     * @return cache projection filter.
     */
    @Nullable public IgnitePredicate<Cache.Entry<Object, Object>> projectionFilter() {
        return prjPred;
    }

    /**
     * @return Type.
     */
    public GridCacheQueryType type() {
        return type;
    }

    /**
     * @return Class name.
     */
    @Nullable public String queryClassName() {
        return clsName;
    }

    /**
     * @return Clause.
     */
    @Nullable public String clause() {
        return clause;
    }

    /**
     * @return Include metadata flag.
     */
    public boolean includeMetadata() {
        return incMeta;
    }

    /**
     * @return {@code True} if portable should not be deserialized.
     */
    public boolean keepPortable() {
        return keepPortable;
    }

    /**
     * Forces query to keep portable object representation even if query was created on plain projection.
     *
     * @param keepPortable Keep portable flag.
     */
    public void keepPortable(boolean keepPortable) {
        this.keepPortable = keepPortable;
    }

    /**
     * @return Security subject ID.
     */
    public UUID subjectId() {
        return subjId;
    }

    /**
     * @return Task hash.
     */
    public int taskHash() {
        return taskHash;
    }

    /**
     * @param subjId Security subject ID.
     */
    public void subjectId(UUID subjId) {
        this.subjId = subjId;
    }

    /** {@inheritDoc} */
    @Override public CacheQuery<T> pageSize(int pageSize) {
        A.ensure(pageSize > 0, "pageSize > 0");

        this.pageSize = pageSize;

        return this;
    }

    /**
     * @return Page size.
     */
    public int pageSize() {
        return pageSize;
    }

    /** {@inheritDoc} */
    @Override public CacheQuery<T> timeout(long timeout) {
        A.ensure(timeout >= 0, "timeout >= 0");

        this.timeout = timeout;

        return this;
    }

    /**
     * @return Timeout.
     */
    public long timeout() {
        return timeout;
    }

    /** {@inheritDoc} */
    @Override public CacheQuery<T> keepAll(boolean keepAll) {
        this.keepAll = keepAll;

        return this;
    }

    /**
     * @return Keep all flag.
     */
    public boolean keepAll() {
        return keepAll;
    }

    /** {@inheritDoc} */
    @Override public CacheQuery<T> includeBackups(boolean incBackups) {
        this.incBackups = incBackups;

        return this;
    }

    /**
     * @return Include backups.
     */
    public boolean includeBackups() {
        return incBackups;
    }

    /** {@inheritDoc} */
    @Override public CacheQuery<T> enableDedup(boolean dedup) {
        this.dedup = dedup;

        return this;
    }

    /**
     * @return Enable dedup flag.
     */
    public boolean enableDedup() {
        return dedup;
    }

    /** {@inheritDoc} */
    @Override public CacheQuery<T> projection(ClusterGroup prj) {
        this.prj = prj;

        return this;
    }

    /**
     * @return Grid projection.
     */
    public ClusterGroup projection() {
        return prj;
    }

    /**
     * @return Key-value filter.
     */
    @Nullable public <K, V> IgniteBiPredicate<K, V> scanFilter() {
        return (IgniteBiPredicate<K, V>)filter;
    }

    /**
     * @throws IgniteCheckedException If query is invalid.
     */
    public void validate() throws IgniteCheckedException {
        if ((type != SCAN && type != SET) && !cctx.config().isQueryIndexEnabled())
            throw new IgniteCheckedException("Indexing is disabled for cache: " + cctx.cache().name());
    }

    /**
     * @param res Query result.
     * @param err Error or {@code null} if query executed successfully.
     * @param startTime Start time.
     * @param duration Duration.
     */
    public void onExecuted(Object res, Throwable err, long startTime, long duration) {
        boolean fail = err != null;

        // Update own metrics.
        metrics.onQueryExecute(duration, fail);

        // Update metrics in query manager.
        cctx.queries().onMetricsUpdate(duration, fail);

        if (log.isDebugEnabled())
            log.debug("Query execution finished [qry=" + this + ", startTime=" + startTime +
                ", duration=" + duration + ", fail=" + fail + ", res=" + res + ']');
    }

    /** {@inheritDoc} */
    @Override public CacheQueryFuture<T> execute(@Nullable Object... args) {
        return execute(null, null, args);
    }

    /** {@inheritDoc} */
    @Override public <R> CacheQueryFuture<R> execute(IgniteReducer<T, R> rmtReducer, @Nullable Object... args) {
        return execute(rmtReducer, null, args);
    }

    /** {@inheritDoc} */
    @Override public <R> CacheQueryFuture<R> execute(IgniteClosure<T, R> rmtTransform, @Nullable Object... args) {
        return execute(null, rmtTransform, args);
    }

    @Override public QueryMetrics metrics() {
        return metrics.copy();
    }

    @Override public void resetMetrics() {
        metrics = new GridCacheQueryMetricsAdapter();
    }

    /**
     * @param rmtReducer Optional reducer.
     * @param rmtTransform Optional transformer.
     * @param args Arguments.
     * @return Future.
     */
    @SuppressWarnings("IfMayBeConditional")
    private <R> CacheQueryFuture<R> execute(@Nullable IgniteReducer<T, R> rmtReducer,
        @Nullable IgniteClosure<T, R> rmtTransform, @Nullable Object... args) {
        Collection<ClusterNode> nodes = nodes();

        cctx.checkSecurity(GridSecurityPermission.CACHE_READ);

        if (nodes.isEmpty())
            return new GridCacheQueryErrorFuture<>(cctx.kernalContext(), new ClusterGroupEmptyCheckedException());

        if (log.isDebugEnabled())
            log.debug("Executing query [query=" + this + ", nodes=" + nodes + ']');

        if (cctx.deploymentEnabled()) {
            try {
                cctx.deploy().registerClasses(filter, rmtReducer, rmtTransform);
                cctx.deploy().registerClasses(args);
            }
            catch (IgniteCheckedException e) {
                return new GridCacheQueryErrorFuture<>(cctx.kernalContext(), e);
            }
        }

        if (subjId == null)
            subjId = cctx.localNodeId();

        taskHash = cctx.kernalContext().job().currentTaskNameHash();

        GridCacheQueryBean bean = new GridCacheQueryBean(this, (IgniteReducer<Object, Object>)rmtReducer,
            (IgniteClosure<Object, Object>)rmtTransform, args);

        GridCacheQueryManager qryMgr = cctx.queries();

        boolean loc = nodes.size() == 1 && F.first(nodes).id().equals(cctx.localNodeId());

        if (type == SQL_FIELDS || type == SPI)
            return (CacheQueryFuture<R>)(loc ? qryMgr.queryFieldsLocal(bean) :
                qryMgr.queryFieldsDistributed(bean, nodes));
        else
            return (CacheQueryFuture<R>)(loc ? qryMgr.queryLocal(bean) : qryMgr.queryDistributed(bean, nodes));
    }

    /**
     * @return Nodes to execute on.
     */
    private Collection<ClusterNode> nodes() {
        CacheMode cacheMode = cctx.config().getCacheMode();

        switch (cacheMode) {
            case LOCAL:
                if (prj != null)
                    U.warn(log, "Ignoring query projection because it's executed over LOCAL cache " +
                        "(only local node will be queried): " + this);

                return Collections.singletonList(cctx.localNode());

            case REPLICATED:
                if (prj != null)
                    return nodes(cctx, prj);

                CacheDistributionMode mode = cctx.config().getDistributionMode();

                return mode == PARTITIONED_ONLY || mode == NEAR_PARTITIONED ?
                    Collections.singletonList(cctx.localNode()) :
                    Collections.singletonList(F.rand(nodes(cctx, null)));

            case PARTITIONED:
                return nodes(cctx, prj);

            default:
                throw new IllegalStateException("Unknown cache distribution mode: " + cacheMode);
        }
    }

    /**
     * @param cctx Cache context.
     * @param prj Projection (optional).
     * @return Collection of data nodes in provided projection (if any).
     */
    private static Collection<ClusterNode> nodes(final GridCacheContext<?, ?> cctx, @Nullable final ClusterGroup prj) {
        assert cctx != null;

        return F.view(CU.allNodes(cctx), new P1<ClusterNode>() {
            @Override public boolean apply(ClusterNode n) {
                CacheDistributionMode mode = U.distributionMode(n, cctx.name());

                return (mode == PARTITIONED_ONLY || mode == NEAR_PARTITIONED) &&
                    (prj == null || prj.node(n.id()) != null);
            }
        });
    }

    /** {@inheritDoc} */
    @Override public String toString() {
        return S.toString(GridCacheQueryAdapter.class, this);
    }
}
