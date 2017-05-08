package org.apache.ignite.cache.database.db;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import org.apache.ignite.Ignite;
import org.apache.ignite.IgniteCache;
import org.apache.ignite.cache.CacheMode;
import org.apache.ignite.cache.CacheWriteSynchronizationMode;
import org.apache.ignite.cache.PartitionLossPolicy;
import org.apache.ignite.cache.affinity.rendezvous.RendezvousAffinityFunction;
import org.apache.ignite.configuration.CacheConfiguration;
import org.apache.ignite.configuration.IgniteConfiguration;
import org.apache.ignite.configuration.MemoryConfiguration;
import org.apache.ignite.configuration.MemoryPolicyConfiguration;
import org.apache.ignite.configuration.PersistenceConfiguration;
import org.apache.ignite.internal.util.typedef.internal.U;
import org.apache.ignite.testframework.junits.common.GridCommonAbstractTest;
import org.apache.ignite.testframework.junits.multijvm.IgniteProcessProxy;

/**
 * We start writing to unstable cluster.
 * After that we start killing node.
 * There will be entries in WAL which belongs to evicted partitions.
 * We should ignore them (not throw exceptions). This point is tested.
 */
public class RebalancingOnNotStableTopologyTest extends GridCommonAbstractTest {
    /** Checkpoint frequency. */
    private static final long CHECKPOINT_FREQUENCY = 2_000_000;

    /** Cluster size. */
    private static final int CLUSTER_SIZE = 5;

    /**
     * @throws Exception When fails.
     */
    public void test() throws Exception {
        stopAllGrids();

        Ignite ex = startGrid(0);

        startGrid(1);

        final CountDownLatch startLatch = new CountDownLatch(1);
        final CountDownLatch doneLatch = new CountDownLatch(1);

        final Ignite ex1 = ex;

        final AtomicBoolean stop = new AtomicBoolean();
        final AtomicInteger keyCnt = new AtomicInteger();

        Thread thread = new Thread(new Runnable() {
            @Override public void run() {
                ex1.active(true);

                try {
                    checkTopology(2);

                    startLatch.countDown();

                    IgniteCache<Object, Object> cache1 = ex1.cache("cache1");

                    int key = keyCnt.get();

                    while (!stop.get()) {
                        if (key > 0 && (key % 500 == 0)) {
                            U.sleep(5);

                            System.out.println("key = " + key);
                        }

                        cache1.put(key, -key);

                        key = keyCnt.incrementAndGet();
                    }
                }
                catch (Throwable th) {
                    th.printStackTrace();
                }

                doneLatch.countDown();
            }
        });

        thread.setName("Data-Loader");
        thread.start();

        startLatch.await(60, TimeUnit.SECONDS);

        for (int i = 2; i < CLUSTER_SIZE; i++) {
            startGrid(i);

            U.sleep(5000);
        }

        U.sleep(10000);

        IgniteProcessProxy.kill("db.RebalancingOnNotStableTopologyTest2");

        Thread.sleep(5000);

        IgniteProcessProxy.kill("db.RebalancingOnNotStableTopologyTest1");

        assert doneLatch.getCount() > 0;

        stop.set(true);

        doneLatch.await(600, TimeUnit.SECONDS);

        IgniteProcessProxy.killAll();

        stopAllGrids();

        //start cluster. it will cause memory restoration and reading WAL.
        ex = startGrids(CLUSTER_SIZE);

        ex.active(true);

        checkTopology(CLUSTER_SIZE);

        IgniteCache<Object, Object> cache1 = ex.cache("cache1");

        assert keyCnt.get() > 0;

        for (int i = 0; i < keyCnt.get(); i++)
            assertEquals(-i, cache1.get(i));

        System.out.println("Test finished with total keys count = " + keyCnt.get());
    }

    /** {@inheritDoc} */
    @Override protected IgniteConfiguration getConfiguration(String gridName) throws Exception {
        IgniteConfiguration cfg = super.getConfiguration(gridName);

        cfg.setActiveOnStart(false);

        CacheConfiguration<Integer, Integer> ccfg = new CacheConfiguration<>();

        ccfg.setName("cache1");
        ccfg.setPartitionLossPolicy(PartitionLossPolicy.READ_ONLY_SAFE);
        ccfg.setWriteSynchronizationMode(CacheWriteSynchronizationMode.FULL_SYNC);
        ccfg.setCacheMode(CacheMode.PARTITIONED);
        ccfg.setAffinity(new RendezvousAffinityFunction(false, 32));
        ccfg.setBackups(2);

        cfg.setCacheConfiguration(ccfg);

        PersistenceConfiguration pCfg = new PersistenceConfiguration();

        pCfg.setCheckpointFrequency(CHECKPOINT_FREQUENCY);

        cfg.setPersistenceConfiguration(pCfg);

        MemoryConfiguration memCfg = new MemoryConfiguration();

        MemoryPolicyConfiguration memPlcCfg = new MemoryPolicyConfiguration();

        memPlcCfg.setName("dfltMemPlc");
        memPlcCfg.setSize(200 * 1024 * 1024);

        memCfg.setMemoryPolicies(memPlcCfg);
        memCfg.setDefaultMemoryPolicyName("dfltMemPlc");

        cfg.setMemoryConfiguration(memCfg);

        return cfg;
    }

    /** {@inheritDoc} */
    @Override protected boolean isMultiJvm() {
        return true;
    }

    /** {@inheritDoc} */
    @Override protected boolean checkTopology() {
        return false;
    }

    /** {@inheritDoc} */
    @Override protected void beforeTest() throws Exception {
        stopAllGrids();
        deleteRecursively(U.resolveWorkDirectory(U.defaultWorkDirectory(), "db", false));
    }

    /** {@inheritDoc} */
    @Override protected void afterTest() throws Exception {
        stopAllGrids();
        deleteRecursively(U.resolveWorkDirectory(U.defaultWorkDirectory(), "db", false));
    }

    /** {@inheritDoc} */
    @Override protected long getTestTimeout() {
        return TimeUnit.MINUTES.toMillis(10);
    }
}