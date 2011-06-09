package com.proofpoint.discovery;

import com.google.common.base.Preconditions;
import com.google.common.collect.ImmutableSet;
import com.proofpoint.json.JsonCodec;
import com.proofpoint.log.Logger;
import com.proofpoint.units.Duration;
import me.prettyprint.cassandra.model.AllOneConsistencyLevelPolicy;
import me.prettyprint.cassandra.serializers.LongSerializer;
import me.prettyprint.cassandra.serializers.StringSerializer;
import me.prettyprint.cassandra.service.ThriftCfDef;
import me.prettyprint.cassandra.service.ThriftKsDef;
import me.prettyprint.hector.api.Cluster;
import me.prettyprint.hector.api.Keyspace;
import me.prettyprint.hector.api.beans.ColumnSlice;
import me.prettyprint.hector.api.beans.HColumn;
import me.prettyprint.hector.api.beans.OrderedRows;
import me.prettyprint.hector.api.beans.Row;
import me.prettyprint.hector.api.beans.Rows;
import me.prettyprint.hector.api.ddl.ColumnFamilyDefinition;
import me.prettyprint.hector.api.ddl.KeyspaceDefinition;
import me.prettyprint.hector.api.factory.HFactory;
import me.prettyprint.hector.api.mutation.Mutator;
import org.joda.time.DateTime;

import javax.annotation.PostConstruct;
import javax.annotation.PreDestroy;
import javax.inject.Inject;
import javax.inject.Provider;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static com.google.common.base.Predicates.and;
import static com.google.common.collect.Collections2.transform;
import static com.google.common.collect.ImmutableList.copyOf;
import static com.google.common.collect.Iterables.filter;
import static com.google.common.collect.Iterables.find;
import static com.proofpoint.discovery.ColumnFamilies.named;
import static com.proofpoint.discovery.DynamicServiceAnnouncement.toServiceWith;
import static com.proofpoint.discovery.Service.matchesPool;
import static com.proofpoint.discovery.Service.matchesType;
import static java.lang.String.format;

public class CassandraDynamicStore
        implements DynamicStore
{
    private final static Logger log = Logger.get(CassandraDynamicStore.class);

    public final static String COLUMN_FAMILY = "dynamic_announcements";
    private static final int PAGE_SIZE = 1000;
    private static final int GC_GRACE_SECONDS = 0;  // don't care about columns being brought back from the dead


    private final JsonCodec<List<Service>> codec = JsonCodec.listJsonCodec(Service.class);
    private final ScheduledExecutorService reaper = new ScheduledThreadPoolExecutor(1);

    private final Keyspace keyspace;

    private final Duration maxAge;

    private final AtomicBoolean initialized = new AtomicBoolean(false);
    private final Provider<DateTime> currentTime;

    @Inject
    public CassandraDynamicStore(
            CassandraStoreConfig config,
            DiscoveryConfig discoveryConfig,
            Provider<DateTime> currentTime,
            Cluster cluster)
    {
        this.currentTime = currentTime;
        maxAge = discoveryConfig.getMaxAge();

        String keyspaceName = config.getKeyspace();
        KeyspaceDefinition definition = cluster.describeKeyspace(keyspaceName);
        if (definition == null) {
            cluster.addKeyspace(new ThriftKsDef(keyspaceName));
        }

        ColumnFamilyDefinition existing = find(cluster.describeKeyspace(keyspaceName).getCfDefs(), named(COLUMN_FAMILY), null);
        if (existing == null) {
            cluster.addColumnFamily(withDefaults(new ThriftCfDef(keyspaceName, COLUMN_FAMILY)));
        }
        else if (needsUpdate(existing)) {
            cluster.updateColumnFamily(withDefaults(existing));
        }

        keyspace = HFactory.createKeyspace(keyspaceName, cluster);
        keyspace.setConsistencyLevelPolicy(new AllOneConsistencyLevelPolicy());
    }

    private boolean needsUpdate(ColumnFamilyDefinition definition)
    {
        return definition.getGcGraceSeconds() != GC_GRACE_SECONDS;
    }

    private ColumnFamilyDefinition withDefaults(ColumnFamilyDefinition original)
    {
        ThriftCfDef updated = new ThriftCfDef(original);
        updated.setGcGraceSeconds(GC_GRACE_SECONDS);
        return updated;
    }

    @PostConstruct
    public void initialize()
    {
        if (!initialized.compareAndSet(false, true)) {
            throw new IllegalStateException("Already initialized");
        }

        reaper.scheduleWithFixedDelay(new Runnable()
        {
            @Override
            public void run()
            {
                try {
                    removeExpired();
                }
                catch (Throwable e) {
                    log.error(e);
                }
            }
        }, 0, 1, TimeUnit.MINUTES);
    }

    @PreDestroy
    public void shutdown()
    {
        reaper.shutdown();
    }

    @Override
    public boolean put(Id<Node> nodeId, DynamicAnnouncement announcement)
    {
        Preconditions.checkNotNull(nodeId, "nodeId is null");
        Preconditions.checkNotNull(announcement, "announcement is null");

        List<Service> services = copyOf(transform(announcement.getServiceAnnouncements(), toServiceWith(nodeId, announcement.getLocation(), announcement.getPool())));
        String value = codec.toJson(services);

        DateTime expiration = currentTime.get().plusMillis((int) maxAge.toMillis());
        DateTime now = currentTime.get();

        // insert our record
        HFactory.createMutator(keyspace, StringSerializer.get())
                .addInsertion(nodeId.toString(), COLUMN_FAMILY, HFactory.createColumn(expiration.getMillis(), value, now.getMillis(), LongSerializer.get(), StringSerializer.get()))
                .execute();

        // get all unexpired entries
        Rows<String, Long, String> rows = HFactory.createMultigetSliceQuery(keyspace, StringSerializer.get(), LongSerializer.get(), StringSerializer.get())
                .setColumnFamily(COLUMN_FAMILY)
                .setKeys(nodeId.toString())
                .setRange(now.getMillis(), null, false, Integer.MAX_VALUE)
                .execute()
                .get();

        for (Row<String, Long, String> row : rows) {
            // there should be only one row since we queried for one key
            for (HColumn<Long, String> column : row.getColumnSlice().getColumns()) {
                // if column is not yet expired but it's older (timestamp-wise) than ours, assume an entry already existed
                if (column.getClock() < now.getMillis()) {
                    return false;
                }
            }
        }

        return true;
    }

    @Override
    public boolean delete(Id<Node> nodeId)
    {
        boolean exists = exists(nodeId);

        // TODO: race condition here....

        Mutator<String> mutator = HFactory.createMutator(keyspace, StringSerializer.get());
        mutator.addDeletion(nodeId.toString(), COLUMN_FAMILY, currentTime.get().getMillis());
        mutator.execute();

        return exists;
    }

    public Set<Service> getAll()
    {
        ImmutableSet.Builder<Service> builder = ImmutableSet.builder();

        String start = null;
        OrderedRows<String, Long, String> rows;

        do {
            rows = HFactory.createRangeSlicesQuery(keyspace, StringSerializer.get(), LongSerializer.get(), StringSerializer.get())
                    .setColumnFamily(COLUMN_FAMILY)
                    .setKeys(start, null)
                    .setRange(null, currentTime.get().getMillis(), true, Integer.MAX_VALUE)
                    .setRowCount(PAGE_SIZE)
                    .execute()
                    .get();

            for (Row<String, Long, String> row : rows) {
                // select the newest column
                HColumn<Long, String> chosen = null;
                for (HColumn<Long, String> column : row.getColumnSlice().getColumns()) {
                    if (chosen == null || column.getClock() > chosen.getClock()) {
                        chosen = column;
                    }
                }
                if (chosen != null) {
                    builder.addAll(codec.fromJson(chosen.getValue()));
                }
            }

            if (rows.getCount() > 0) {
                start = rows.peekLast().getKey();
            }
        }
        while (rows.getCount() == PAGE_SIZE);

        return builder.build();
    }

    @Override
    public Set<Service> get(String type)
    {
        return ImmutableSet.copyOf(filter(getAll(), matchesType(type)));
    }

    @Override
    public Set<Service> get(String type, String pool)
    {
        return ImmutableSet.copyOf(filter(getAll(), and(matchesType(type), matchesPool(pool))));
    }

    private boolean exists(Id<Node> nodeId)
    {
        ColumnSlice<Long, String> slice = HFactory.createSliceQuery(keyspace, StringSerializer.get(), LongSerializer.get(), StringSerializer.get())
                .setColumnFamily(COLUMN_FAMILY)
                .setKey(nodeId.toString())
                .setRange(expirationCutoff().getMillis(), null, false, 1)
                .execute()
                .get();

        return !slice.getColumns().isEmpty();
    }

    private void removeExpired()
    {
        int count = 1000;
        OrderedRows<String, Long, String> rows;
        String start = null;
        do {
            rows = HFactory.createRangeSlicesQuery(keyspace, StringSerializer.get(), LongSerializer.get(), StringSerializer.get())
                    .setColumnFamily(COLUMN_FAMILY)
                    .setKeys(start, null)
                    .setRange(null, currentTime.get().getMillis(), false, Integer.MAX_VALUE)
                    .setRowCount(count)
                    .execute()
                    .get();

            Mutator<String> mutator = HFactory.createMutator(keyspace, StringSerializer.get());
            for (Row<String, Long, String> row : rows) {
                for (HColumn<Long, String> column : row.getColumnSlice().getColumns()) {
                    mutator.addDeletion(row.getKey(), COLUMN_FAMILY, column.getName(), LongSerializer.get(), currentTime.get().getMillis());
                }
            }
            mutator.execute();

            if (rows.getCount() > 0) {
                start = rows.peekLast().getKey();
            }
        }
        while (rows.getCount() == count);
    }

    private DateTime expirationCutoff()
    {
        return currentTime.get().minusMillis((int) maxAge.toMillis());
    }
}
