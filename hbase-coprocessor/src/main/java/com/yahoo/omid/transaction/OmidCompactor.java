package com.yahoo.omid.transaction;

import static com.yahoo.omid.committable.hbase.CommitTableConstants.COMMIT_TABLE_NAME_KEY;

import java.io.IOException;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ExecutionException;

import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.hbase.CoprocessorEnvironment;
import org.apache.hadoop.hbase.HColumnDescriptor;
import org.apache.hadoop.hbase.HTableDescriptor;
import org.apache.hadoop.hbase.coprocessor.BaseRegionObserver;
import org.apache.hadoop.hbase.coprocessor.ObserverContext;
import org.apache.hadoop.hbase.coprocessor.RegionCoprocessorEnvironment;
import org.apache.hadoop.hbase.regionserver.CompactorScanner;
import org.apache.hadoop.hbase.regionserver.InternalScanner;
import org.apache.hadoop.hbase.regionserver.ScanType;
import org.apache.hadoop.hbase.regionserver.Store;
import org.apache.hadoop.hbase.regionserver.compactions.CompactionRequest;
import org.apache.hadoop.hbase.util.Bytes;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.common.annotations.VisibleForTesting;
import com.yahoo.omid.committable.CommitTable;
import com.yahoo.omid.committable.hbase.HBaseCommitTable;
import com.yahoo.omid.committable.hbase.HBaseCommitTableConfig;

/**
 * Garbage collector for stale data: triggered upon HBase
 * compactions, it removes data from uncommitted transactions
 * older than the low watermark using a special scanner
 */
public class OmidCompactor extends BaseRegionObserver {

    private static final Logger LOG = LoggerFactory.getLogger(OmidCompactor.class);

    private static final String HBASE_RETAIN_NON_TRANSACTIONALLY_DELETED_CELLS_KEY
        = "omid.hbase.compactor.retain.tombstones";
    private static final boolean HBASE_RETAIN_NON_TRANSACTIONALLY_DELETED_CELLS_DEFAULT = true;

    final static String OMID_COMPACTABLE_CF_FLAG = "OMID_ENABLED";

    private HBaseCommitTableConfig commitTableConf = null;
    private Configuration conf = null;
    @VisibleForTesting
    protected Queue<CommitTable.Client> commitTableClientQueue = new ConcurrentLinkedQueue<>();

    // When compacting, if a cell which has been marked by HBase as Delete or
    // Delete Family (that is, non-transactionally deleted), we allow the user
    // to decide what the compactor scanner should do with it: retain it or not
    // If retained, the deleted cell will appear after a minor compaction, but
    // will be deleted anyways after a major one
    private boolean retainNonTransactionallyDeletedCells;

    public OmidCompactor() {
        LOG.info("Compactor coprocessor initialized via empty constructor");
    }

    @Override
    public void start(CoprocessorEnvironment env) throws IOException {
        LOG.info("Starting compactor coprocessor");
        conf = env.getConfiguration();
        commitTableConf = new HBaseCommitTableConfig();
        String commitTableName = conf.get(COMMIT_TABLE_NAME_KEY);
        if (commitTableName != null) {
            commitTableConf.setTableName(commitTableName);
        }
        retainNonTransactionallyDeletedCells =
                conf.getBoolean(HBASE_RETAIN_NON_TRANSACTIONALLY_DELETED_CELLS_KEY,
                                HBASE_RETAIN_NON_TRANSACTIONALLY_DELETED_CELLS_DEFAULT);
        LOG.info("Compactor coprocessor started");
    }

    @Override
    public void stop(CoprocessorEnvironment e) throws IOException {
        LOG.info("Stopping compactor coprocessor");
        if (commitTableClientQueue != null) {
            for (CommitTable.Client commitTableClient : commitTableClientQueue) {
                commitTableClient.close();
            }
        }
        LOG.info("Compactor coprocessor stopped");
    }

    @Override
    public InternalScanner preCompact(ObserverContext<RegionCoprocessorEnvironment> e,
                                      Store store,
                                      InternalScanner scanner,
                                      ScanType scanType,
                                      CompactionRequest request) throws IOException
    {
        HTableDescriptor desc = e.getEnvironment().getRegion().getTableDesc();
        HColumnDescriptor famDesc
            = desc.getFamily(Bytes.toBytes(store.getColumnFamilyName()));
        boolean omidCompactable = Boolean.valueOf(famDesc.getValue(OMID_COMPACTABLE_CF_FLAG));
        // only column families tagged as compactable are compacted
        // with omid compactor
        if (!omidCompactable) {
            return scanner;
        } else {
            CommitTable.Client commitTableClient = commitTableClientQueue.poll();
            if (commitTableClient == null) {
                commitTableClient = initAndGetCommitTableClient();
            }
            boolean isMajorCompaction = request.isMajor();
            return new CompactorScanner(e,
                                        scanner,
                                        commitTableClient,
                                        commitTableClientQueue,
                                        isMajorCompaction,
                                        retainNonTransactionallyDeletedCells);
        }
    }

    private CommitTable.Client initAndGetCommitTableClient() throws IOException {
        LOG.info("Trying to get the commit table client");
        CommitTable commitTable = new HBaseCommitTable(conf, commitTableConf);
        try {
            CommitTable.Client commitTableClient = commitTable.getClient().get();
            LOG.info("Commit table client obtained {}", commitTableClient.getClass().getCanonicalName());
            return commitTableClient;
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
            throw new IOException("Interrupted getting the commit table client");
        } catch (ExecutionException ee) {
            throw new IOException("Error getting the commit table client", ee.getCause());
        }
    }

}
