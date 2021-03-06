package com.avaje.ebeaninternal.server.query;

import com.avaje.ebean.QueryIterator;
import com.avaje.ebean.bean.*;
import com.avaje.ebeaninternal.api.SpiQuery;
import com.avaje.ebeaninternal.api.SpiQuery.Mode;
import com.avaje.ebeaninternal.api.SpiTransaction;
import com.avaje.ebeaninternal.server.autofetch.AutoFetchManager;
import com.avaje.ebeaninternal.server.core.Message;
import com.avaje.ebeaninternal.server.core.OrmQueryRequest;
import com.avaje.ebeaninternal.server.core.SpiOrmQueryRequest;
import com.avaje.ebeaninternal.server.deploy.*;
import com.avaje.ebeaninternal.server.lib.util.StringHelper;
import com.avaje.ebeaninternal.server.type.DataBind;
import com.avaje.ebeaninternal.server.type.DataReader;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.persistence.PersistenceException;
import java.lang.ref.WeakReference;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * An object that represents a SqlSelect statement.
 * <p>
 * The SqlSelect is based on a tree (Object Graph). The tree is traversed to see
 * what parts are included in the tree according to the value of
 * find.getInclude();
 * </p>
 * <p>
 * The tree structure is flattened into a SqlSelectChain. The SqlSelectChain is
 * the key object used in reading the flat resultSet back into Objects.
 * </p>
 */
public class CQuery<T> implements DbReadContext, CancelableQuery {

  private static final Logger logger = LoggerFactory.getLogger(CQuery.class);

  private static final int GLOBAL_ROW_LIMIT = Integer.valueOf(System.getProperty("ebean.query.globallimit", "1000000"));

  /**
   * The resultSet rows read.
   */
  private int rowCount;

  /**
   * The number of master EntityBeans loaded.
   */
  private int loadedBeanCount;

  /**
   * Flag set when no more rows are in the resultSet.
   */
  private boolean noMoreRows;

  /**
   * The 'master' bean just loaded.
   */
  private EntityBean nextBean;

  /**
   * Holds the previous loaded bean.
   */
  private EntityBean currentBean;

  private final BeanPropertyAssocMany<?> lazyLoadManyProperty;

  private Object lazyLoadParentId;

  private EntityBean lazyLoadParentBean;

  /**
   * The 'master' collection being populated.
   */
  private final BeanCollection<T> collection;
  /**
   * The help for the 'master' collection.
   */
  private final BeanCollectionHelp<T> help;

  /**
   * The overall find request wrapper object.
   */
  private final OrmQueryRequest<T> request;

  private final BeanDescriptor<T> desc;

  private final SpiQuery<T> query;

  private Map<String, String> currentPathMap;

  private String currentPrefix;

  /**
   * Where clause predicates.
   */
  private final CQueryPredicates predicates;

  /**
   * Object handling the SELECT generation and reading.
   */
  private final SqlTree sqlTree;

  private final boolean rawSql;

  /**
   * The final sql that is generated.
   */
  private final String sql;

  /**
   * Where clause to show in logs when using an existing query plan.
   */
  private final String logWhereSql;

  /**
   * Set to true if the row number column is included in the sql.
   */
  private final boolean rowNumberIncluded;

  /**
   * Tree that knows how to build the master and detail beans from the
   * resultSet.
   */
  private final SqlTreeNode rootNode;

  /**
   * For master detail query.
   */
  private final BeanPropertyAssocMany<?> manyProperty;

  private final int maxRowsLimit;

  private DataReader dataReader;

  /**
   * The statement used to create the resultSet.
   */
  private PreparedStatement pstmt;

  private boolean cancelled;

  private String bindLog;

  private final CQueryPlan queryPlan;

  private final Mode queryMode;

  private final boolean autoFetchProfiling;

  private final ObjectGraphNode objectGraphNode;

  private final AutoFetchManager autoFetchManager;

  private final WeakReference<NodeUsageListener> autoFetchManagerRef;

  private final Boolean readOnly;

  private long startNano;

  private long executionTimeMicros;

  /**
   * Create the Sql select based on the request.
   */
  @SuppressWarnings("unchecked")
  public CQuery(OrmQueryRequest<T> request, CQueryPredicates predicates, CQueryPlan queryPlan) {
    this.request = request;
    this.queryPlan = queryPlan;
    this.query = request.getQuery();
    this.queryMode = query.getMode();
    this.lazyLoadManyProperty = query.getLazyLoadForParentsProperty();

    this.readOnly = request.isReadOnly();

    this.autoFetchManager = query.getAutoFetchManager();
    this.autoFetchProfiling = autoFetchManager != null;
    this.objectGraphNode = query.getParentNode();
    this.autoFetchManagerRef = autoFetchProfiling ? new WeakReference<NodeUsageListener>(
        autoFetchManager) : null;

    // set the generated sql back to the query
    // so its available to the user...
    query.setGeneratedSql(queryPlan.getSql());

    this.sqlTree = queryPlan.getSqlTree();
    this.rootNode = sqlTree.getRootNode();
    this.manyProperty = sqlTree.getManyProperty();
    this.sql = queryPlan.getSql();
    this.rawSql = queryPlan.isRawSql();
    this.rowNumberIncluded = queryPlan.isRowNumberIncluded();
    this.logWhereSql = queryPlan.getLogWhereSql();
    this.desc = request.getBeanDescriptor();
    this.predicates = predicates;
    this.maxRowsLimit = query.getMaxRows() > 0 ? query.getMaxRows() : GLOBAL_ROW_LIMIT;
    this.help = createHelp(request);
    this.collection = (help != null ? help.createEmptyNoParent() : null);
  }

  private BeanCollectionHelp<T> createHelp(OrmQueryRequest<T> request) {
    if (request.isFindById()) {
      return null;
    } else {
      SpiQuery.Type manyType = request.getQuery().getType();
      if (manyType == null) {
        // subQuery compiled for InQueryExpression
        return null;
      }
      return BeanCollectionHelpFactory.create(request);
    }
  }

  public Boolean isReadOnly() {
    return readOnly;
  }

  public void propagateState(Object e) {
    if (Boolean.TRUE.equals(readOnly)) {
      if (e instanceof EntityBean) {
        ((EntityBean) e)._ebean_getIntercept().setReadOnly(true);
      }
    }
  }

  public DataReader getDataReader() {
    return dataReader;
  }

  public Mode getQueryMode() {
    return queryMode;
  }

  public CQueryPredicates getPredicates() {
    return predicates;
  }

  public SpiOrmQueryRequest<?> getQueryRequest() {
    return request;
  }

  public void cancel() {
    synchronized (this) {
      this.cancelled = true;
      if (pstmt != null) {
        try {
          pstmt.cancel();
        } catch (SQLException e) {
          String msg = "Error cancelling query";
          throw new PersistenceException(msg, e);
        }
      }
    }
  }

  /**
   * Prepare bind and execute query with Forward only hints.
   */
  public boolean prepareBindExecuteQueryForwardOnly(boolean dbPlatformForwardOnlyHint) throws SQLException {
    return prepareBindExecuteQueryWithOption(dbPlatformForwardOnlyHint);
  }

  /**
   * Prepare bind and execute the query normally.
   */
  public boolean prepareBindExecuteQuery() throws SQLException {
    return prepareBindExecuteQueryWithOption(false);
  }

  private boolean prepareBindExecuteQueryWithOption(boolean forwardOnlyHint) throws SQLException {

    synchronized (this) {
      if (cancelled || query.isCancelled()) {
        // cancelled before we started
        cancelled = true;
        return false;
      }

      startNano = System.nanoTime();

      // prepare
      SpiTransaction t = request.getTransaction();
      Connection conn = t.getInternalConnection();

      if (query.isRawSql()) {
        ResultSet suppliedResultSet = query.getRawSql().getResultSet();
        if (suppliedResultSet != null) {
          // this is a user supplied ResultSet so use that
          dataReader = queryPlan.createDataReader(suppliedResultSet);
          bindLog = "";
          return true;
        }
      }

      if (forwardOnlyHint) {
        // Use forward only hints for large resultset processing (Issue 56, MySql specific)
        pstmt = conn.prepareStatement(sql, ResultSet.TYPE_FORWARD_ONLY, ResultSet.CONCUR_READ_ONLY);
        pstmt.setFetchSize(Integer.MIN_VALUE);
      } else {
        pstmt = conn.prepareStatement(sql);
      }

      if (query.getTimeout() > 0) {
        pstmt.setQueryTimeout(query.getTimeout());
      }
      if (query.getBufferFetchSizeHint() > 0) {
        pstmt.setFetchSize(query.getBufferFetchSizeHint());
      }

      DataBind dataBind = new DataBind(pstmt);

      // bind keys for encrypted properties
      queryPlan.bindEncryptedProperties(dataBind);

      bindLog = predicates.bind(dataBind);

      // executeQuery
      ResultSet rset = pstmt.executeQuery();
      dataReader = queryPlan.createDataReader(rset);

      return true;
    }
  }

  /**
   * Close the resources.
   * <p>
   * The jdbc resultSet and statement need to be closed. Its important that this
   * method is called.
   * </p>
   */
  public void close() {
    try {
      if (dataReader != null) {
        dataReader.close();
        dataReader = null;
      }
    } catch (SQLException e) {
      logger.error(null, e);
    }
    try {
      if (pstmt != null) {
        pstmt.close();
        pstmt = null;
      }
    } catch (SQLException e) {
      logger.error(null, e);
    }
  }

  /**
   * Return the persistence context.
   */
  public PersistenceContext getPersistenceContext() {
    return request.getPersistenceContext();
  }

  public void setLazyLoadedChildBean(EntityBean bean, Object lazyLoadParentId) {

    if (lazyLoadParentId != null) {
      if (!lazyLoadParentId.equals(this.lazyLoadParentId)) {
        // get the appropriate parent bean from the persistence context
        this.lazyLoadParentBean = (EntityBean) getPersistenceContext().get(lazyLoadManyProperty.getBeanDescriptor().getBeanType(), lazyLoadParentId);
        this.lazyLoadParentId = lazyLoadParentId;
      }

      // add the loadedBean to the appropriate collection of lazyLoadParentBean
      lazyLoadManyProperty.addBeanToCollectionWithCreate(lazyLoadParentBean, bean);
    }
  }

  /**
   * Read a row from the result set returning a bean.
   * <p>
   * If the query includes a many then the first object in the returned array is
   * the one/master and the second the many/detail.
   * </p>
   */
  private boolean readNextBean() throws SQLException {

    if (!moveToNextRow()) {
      if (currentBean == null) {
        return false;
      } else {
        // the last bean
        nextBean = currentBean;
        loadedBeanCount++;
        return true;
      }
    }

    loadedBeanCount++;

    if (manyProperty == null) {
      // only single resultSet row required to build object so we are done
      // read a single resultSet row into single bean
      nextBean = rootNode.load(this, null, null);
      return true;
    }

    if (nextBean == null) {
      // very first read
      nextBean = rootNode.load(this, null, null);
    } else {
      // nextBean set to previously read currentBean
      nextBean = currentBean;
      // check the current row we have just moved to
      if (checkForDifferentBean()) {
        return true;
      }
    }
    readUntilDifferentBeanStarted();
    return true;
  }

  /**
   * Read resultSet rows until we hit the end or get a different bean.
   */
  private void readUntilDifferentBeanStarted() throws SQLException {
    while (moveToNextRow()) {
      if (checkForDifferentBean()) return;
    }
  }

  /**
   * Read the currentBean from the row data returning true if the bean
   * is different to the nextBean (false if we need to read more rows).
   */
  private boolean checkForDifferentBean() throws SQLException {
    currentBean = rootNode.load(this, null, null);
    return currentBean != nextBean;
  }

  /**
   * Return true if we can move to the next resultSet row.
   */
  private boolean moveToNextRow() throws SQLException {

    if (!dataReader.next()) {
      noMoreRows = true;
      return false;
    }

    rowCount++;
    dataReader.resetColumnPosition();
    if (rowNumberIncluded) {
      // row_number() column used for limit features
      dataReader.incrementPos(1);
    }
    return true;
  }

  public long getQueryExecutionTimeMicros() {
    return executionTimeMicros;
  }

  public boolean readBean() throws SQLException {

    boolean result = hasNext();
    updateExecutionStatistics();
    return result;
  }

  protected EntityBean next() {
    return nextBean;
  }

  protected boolean hasNext() throws SQLException {

    synchronized (this) {
      if (noMoreRows || cancelled || loadedBeanCount >= maxRowsLimit) {
        return false;
      }
      return readNextBean();
    }
  }

  public BeanCollection<T> readCollection() throws SQLException {

    while (hasNext()) {
      EntityBean bean = next();
      help.add(collection, bean);
    }

    updateExecutionStatistics();
    return collection;
  }

  protected void updateExecutionStatistics() {
    try {
      long exeNano = System.nanoTime() - startNano;
      executionTimeMicros = TimeUnit.NANOSECONDS.toMicros(exeNano);

      if (autoFetchProfiling) {
        autoFetchManager.collectQueryInfo(objectGraphNode, loadedBeanCount, executionTimeMicros);
      }
      queryPlan.executionTime(loadedBeanCount, executionTimeMicros, objectGraphNode);

    } catch (Exception e) {
      logger.error("Error updating execution statistics", e);
    }
  }

  public QueryIterator<T> readIterate(int bufferSize, OrmQueryRequest<T> request) {

    if (bufferSize > 0) {
      return new CQueryIteratorWithBuffer<T>(this, request, bufferSize);

    } else {
      return new CQueryIteratorSimple<T>(this, request);
    }
  }

  public String getLoadedRowDetail() {
    if (manyProperty == null) {
      return String.valueOf(rowCount);
    } else {
      return loadedBeanCount + ":" + rowCount;
    }
  }

  public void register(String path, EntityBeanIntercept ebi) {

    path = getPath(path);
    request.getGraphContext().register(path, ebi);
  }

  public void register(String path, BeanCollection<?> bc) {

    path = getPath(path);
    request.getGraphContext().register(path, bc);
  }

  /**
   * Return the query name.
   */
  public String getName() {
    return query.getName();
  }

  /**
   * Return true if this is a raw sql query as opposed to Ebean generated sql.
   */
  public boolean isRawSql() {
    return rawSql;
  }

  /**
   * Return the where predicate for display in the transaction log.
   */
  public String getLogWhereSql() {
    return logWhereSql;
  }

  /**
   * Return the property that is associated with the many. There can only be one
   * per SqlSelect. This can be null.
   */
  public BeanPropertyAssocMany<?> getManyProperty() {
    return manyProperty;
  }

  /**
   * Get the summary of the sql.
   */
  public String getSummary() {
    return sqlTree.getSummary();
  }

  public String getBindLog() {
    return bindLog;
  }

  public SpiTransaction getTransaction() {
    return request.getTransaction();
  }

  public String getBeanType() {
    return desc.getFullName();
  }

  /**
   * Return the short bean name.
   */
  public String getBeanName() {
    return desc.getName();
  }

  /**
   * Return the generated sql.
   */
  public String getGeneratedSql() {
    return sql;
  }

  /**
   * Create a PersistenceException including interesting information like the
   * bindLog and sql used.
   */
  public PersistenceException createPersistenceException(SQLException e) {

    return createPersistenceException(e, getTransaction(), bindLog, sql);
  }

  /**
   * Create a PersistenceException including interesting information like the
   * bindLog and sql used.
   */
  public static PersistenceException createPersistenceException(SQLException e, SpiTransaction t, String bindLog, String sql) {

    if (t.isLogSummary()) {
      // log the error to the transaction log
      String errMsg = StringHelper.replaceStringMulti(e.getMessage(), new String[]{"\r", "\n"}, "\\n ");
      String msg = "ERROR executing query:   bindLog[" + bindLog + "] error[" + errMsg + "]";
      t.logSummary(msg);
    }

    // ensure 'rollback' is logged if queryOnly transaction
    t.getConnection();

    // build a decent error message for the exception
    String m = Message.msg("fetch.sqlerror", e.getMessage(), bindLog, sql);
    return new PersistenceException(m, e);
  }

  /**
   * Should we create profileNodes for beans created in this query.
   * <p>
   * This is true for all queries except lazy load bean queries.
   * </p>
   */
  public boolean isAutoFetchProfiling() {
    // need query.isProfiling() because we just take the data
    // from the lazy loaded or refreshed beans and put it into the already
    // existing beans which are already collecting usage information
    return autoFetchProfiling && query.isUsageProfiling();
  }

  private String getPath(String propertyName) {

    if (currentPrefix == null) {
      return propertyName;
    } else if (propertyName == null) {
      return currentPrefix;
    }

    String path = currentPathMap.get(propertyName);
    if (path != null) {
      return path;
    } else {
      return currentPrefix + "." + propertyName;
    }
  }

  public void profileBean(EntityBeanIntercept ebi, String prefix) {

    ObjectGraphNode node = request.getGraphContext().getObjectGraphNode(prefix);

    ebi.setNodeUsageCollector(new NodeUsageCollector(node, autoFetchManagerRef));
  }

  public void setCurrentPrefix(String currentPrefix, Map<String, String> currentPathMap) {
    this.currentPrefix = currentPrefix;
    this.currentPathMap = currentPathMap;
  }

}
