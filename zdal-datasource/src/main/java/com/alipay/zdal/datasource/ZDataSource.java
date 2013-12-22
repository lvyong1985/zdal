package com.alipay.zdal.datasource;

import java.sql.SQLException;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

import org.apache.log4j.Logger;

import com.alipay.zdal.datasource.client.util.PoolConditionWriter;
import com.alipay.zdal.datasource.client.util.ZConstants;
import com.alipay.zdal.datasource.resource.adapter.jdbc.local.LocalTxDataSource;
import com.alipay.zdal.datasource.util.PoolCondition;
import com.alipay.zdal.datasource.util.ZDataSourceChanger;
import com.alipay.zdal.valve.Valve;

/**
 * 
 * 
 * @author liangjie.li
 * @version $Id: ZDataSource.java, v 0.1 May 11, 2012 3:38:23 PM liangjie.li Exp $
 */
public class ZDataSource extends AbstractDataSource implements Flusher, Comparable<ZDataSource> {
    /**  */
    private final Valve                           valve             = new Valve();

    private static final Logger                   logger            = Logger
                                                                        .getLogger(ZDataSource.class);

    private static AtomicBoolean                  switching         = new AtomicBoolean(false);
    private final static ScheduledExecutorService service           = Executors
                                                                        .newScheduledThreadPool(2,
                                                                            new ThreadFactory() {

                                                                                private AtomicLong threadCount = new AtomicLong(
                                                                                                                   1);

                                                                                @Override
                                                                                public Thread newThread(
                                                                                                        Runnable r) {
                                                                                    Thread t = new Thread(
                                                                                        r);
                                                                                    t
                                                                                        .setName("zdal-datasource-monitor-"
                                                                                                 + threadCount
                                                                                                     .getAndIncrement());
                                                                                    return t;
                                                                                }
                                                                            });
    private String                                dsName            = "";
    private LocalTxDataSource                     localTxDataSource = null;

    // datasource destroyʱֹͣ���� binghun 20130522
    private ScheduledFuture<?>                    future;

    /**
     * �� LocalTxDataSourceDO����ʼ��zdatasource
     * 
     * @param dataSourceDO
     * @param appName
     * @throws Exception ������ȫ���׳� IllegalArgumentException
     */
    public ZDataSource(LocalTxDataSourceDO dataSourceDO) throws Exception {
        checkParam(dataSourceDO);
        this.dsName = dataSourceDO.getDsName();
        localTxDataSource = ZDataSourceFactory.createLocalTxDataSource(dataSourceDO, this);
        valve.set(dataSourceDO.getSqlValve(), dataSourceDO.getTxValve(), dataSourceDO
            .getTableVave());
        valve.setDsName(dataSourceDO.getDsName());
        PoolConditionWriter poolConditionWriter = new PoolConditionWriter(this);
        future = service.scheduleWithFixedDelay(poolConditionWriter, 0, ZConstants.LOGGER_DELAY,
            TimeUnit.SECONDS);
    }

    /**
     * valve�����ʼ��
     * @param sqlValve
     * @param txValve
     * @param tableValve
     */
    public void setValve(String sqlValve, String txValve, String tableValve) {
        synchronized (valve) {
            valve.reset();
            valve.set(sqlValve, txValve, tableValve);
        }
    }

    /**
     * �����Щ����Ĳ���
     * @param dataSourceDO
     * @throws IllegalArgumentException
     */
    private void checkParam(LocalTxDataSourceDO dataSourceDO) throws IllegalArgumentException {

        if (dataSourceDO == null)
            throw new IllegalArgumentException("DO is null");
        if (dataSourceDO.getDsName() == null)
            throw new IllegalArgumentException("DsName is null");
        if (dataSourceDO.getConnectionURL() == null)
            throw new IllegalArgumentException("connection URL is null");
        if (dataSourceDO.getDriverClass() == null)
            throw new IllegalArgumentException("driverClass is null");
        if (dataSourceDO.getUserName() == null)
            throw new IllegalArgumentException("username is null");
        if (dataSourceDO.getPassWord() == null && dataSourceDO.getEncPassword() == null)
            throw new IllegalArgumentException("both pwd and encPwd are null");
        if (dataSourceDO.getMinPoolSize() == -1)
            throw new IllegalArgumentException("minSize is unset");
        if (dataSourceDO.getMaxPoolSize() == -1)
            throw new IllegalArgumentException("maxSize is unset");
        if (dataSourceDO.getPreparedStatementCacheSize() == -1)
            throw new IllegalArgumentException("preparedStatementCacheSize is unset");
        if (dataSourceDO.getExceptionSorterClassName() == null)
            throw new IllegalArgumentException("ExceptionSorterClassName is null");
    }

    /**
     * 
     * 
     * @throws Exception
     */
    public void destroy() throws Exception {
        ZDataSourceFactory.destroy(localTxDataSource);
        future.cancel(false);
        valve.reset();
        synchronized (zdatasourceList) {
            zdatasourceList.remove(this);
        }
    }

    @Override
    protected javax.sql.DataSource getDatasource() throws SQLException {
        if (!switching.get()) {
            return localTxDataSource.getDatasource();
        } else {
            try {
                Thread.sleep(100);
            } catch (InterruptedException e) {
                logger.error(e);
            }
            return getDatasource();
        }
    }

    /**
     * 
     * @see com.alipay.zdal.Flusher#flush(com.alipay.zdal.LocalTxDataSourceDO)
     */
    //    public boolean flush(LocalTxDataSourceDO localTxDataSourceDO) {
    //        if (localTxDataSourceDO == null) {
    //            throw new IllegalArgumentException("The localTxDataSourceDO is NULL for datasource");
    //        }
    //        if (switching.get()) {// ������...
    //            return false;
    //        }
    //        try {
    //            switching.set(true);
    //            destroy();
    //            initialize(localTxDataSourceDO);
    //            synchronized (zdatasourceList) {
    //                zdatasourceList.add(this);
    //            }
    //            valve.set(localTxDataSourceDO.getSqlValve(), localTxDataSourceDO.getTxValve(),
    //                localTxDataSourceDO.getTableVave());
    //        } catch (Exception e) {
    //            logger.error("ˢ������Դ����", e);
    //            return false;
    //        } finally {
    //            switching.set(false);
    //        }
    //
    //        return true;
    //    }

    /**
     * 
     * @throws Exception 
     * @see com.alipay.zdal.datasource.Flusher#flush(java.util.Map)
     */
    public boolean flush(Map<String, String> map) {
        return ZDataSourceChanger.configChange(map, this);
    }

    /**
     * 
     * @param name
     * @param dsConfigs
     * @throws Exception
     */
    public void initialize(String name, Map<String, LocalTxDataSourceDO> dsConfigs)
                                                                                   throws Exception {
        LocalTxDataSourceDO dataSourceDO = null;
        if (dsConfigs == null || (dataSourceDO = dsConfigs.get(name)) == null) {
            throw new Exception("datasource configs is null, please check config file!");
        }
        if (logger.isDebugEnabled()) {
            logger.debug(dsConfigs.toString());
        }

        localTxDataSource = ZDataSourceFactory.createLocalTxDataSource(dataSourceDO, this);

    }

    /**
     * ��ȡ���ӳ���Ϣ
     * @return
     */
    public PoolCondition getPoolCondition() {
        if (localTxDataSource == null)
            return null;
        return localTxDataSource.getPoolCondition();
    }

    /**
     * 
     * @param localTxDataSourceDO
     * @throws Exception
     */
    public void initialize(LocalTxDataSourceDO localTxDataSourceDO) throws Exception {
        localTxDataSource = ZDataSourceFactory.createLocalTxDataSource(localTxDataSourceDO, this);
    }

    public LocalTxDataSource getLocalTxDataSource() {
        return localTxDataSource;
    }

    public boolean isWrapperFor(Class<?> iface) throws SQLException {
        return false;
    }

    public <T> T unwrap(Class<T> iface) throws SQLException {
        return null;
    }

    public void setDsName(String dsName) {
        this.dsName = dsName;
    }

    public String getDsName() {
        return dsName;
    }

    public void setLocalTxDataSource(LocalTxDataSource localTxDataSource) {
        this.localTxDataSource = localTxDataSource;
    }

    public Valve getValve() {
        return valve;
    }

    @Override
    public int compareTo(ZDataSource o) {
        return this.getDsName().compareTo(o.getDsName());
    }

}