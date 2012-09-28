/*******************************************************************************
 * * Copyright 2012 Impetus Infotech.
 *  *
 *  * Licensed under the Apache License, Version 2.0 (the "License");
 *  * you may not use this file except in compliance with the License.
 *  * You may obtain a copy of the License at
 *  *
 *  *      http://www.apache.org/licenses/LICENSE-2.0
 *  *
 *  * Unless required by applicable law or agreed to in writing, software
 *  * distributed under the License is distributed on an "AS IS" BASIS,
 *  * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  * See the License for the specific language governing permissions and
 *  * limitations under the License.
 ******************************************************************************/
package com.impetus.client.hbase;

import java.io.IOException;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import javax.persistence.PersistenceException;
import javax.persistence.metamodel.Attribute;
import javax.persistence.metamodel.EntityType;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.apache.hadoop.hbase.HBaseConfiguration;
import org.apache.hadoop.hbase.KeyValue;
import org.apache.hadoop.hbase.client.HTable;
import org.apache.hadoop.hbase.client.HTablePool;
import org.apache.hadoop.hbase.filter.CompareFilter.CompareOp;
import org.apache.hadoop.hbase.filter.Filter;
import org.apache.hadoop.hbase.filter.FilterList;
import org.apache.hadoop.hbase.filter.KeyOnlyFilter;
import org.apache.hadoop.hbase.filter.SingleColumnValueFilter;
import org.apache.hadoop.hbase.util.Bytes;

import com.impetus.client.hbase.admin.DataHandler;
import com.impetus.client.hbase.admin.HBaseDataHandler;
import com.impetus.client.hbase.admin.HBaseDataHandler.HBaseDataWrapper;
import com.impetus.client.hbase.query.HBaseQuery;
import com.impetus.client.hbase.utils.HBaseUtils;
import com.impetus.kundera.Constants;
import com.impetus.kundera.KunderaException;
import com.impetus.kundera.client.Client;
import com.impetus.kundera.client.ClientBase;
import com.impetus.kundera.client.ClientPropertiesSetter;
import com.impetus.kundera.db.RelationHolder;
import com.impetus.kundera.graph.Node;
import com.impetus.kundera.index.IndexManager;
import com.impetus.kundera.lifecycle.states.RemovedState;
import com.impetus.kundera.metadata.KunderaMetadataManager;
import com.impetus.kundera.metadata.MetadataUtils;
import com.impetus.kundera.metadata.model.EntityMetadata;
import com.impetus.kundera.metadata.model.KunderaMetadata;
import com.impetus.kundera.metadata.model.MetamodelImpl;
import com.impetus.kundera.metadata.model.PersistenceUnitMetadata;
import com.impetus.kundera.metadata.model.attributes.AbstractAttribute;
import com.impetus.kundera.persistence.EntityReader;
import com.impetus.kundera.persistence.api.Batcher;
import com.impetus.kundera.persistence.context.jointable.JoinTableData;
import com.impetus.kundera.property.PropertyAccessorHelper;

/**
 * HBase client.
 * 
 * @author impetus
 */
public class HBaseClient extends ClientBase implements Client<HBaseQuery>, Batcher, ClientPropertiesSetter
{
    /** the log used by this class. */
    private static Log log = LogFactory.getLog(HBaseClient.class);

    /** The handler. */
    private DataHandler handler;

    /** The reader. */
    private EntityReader reader;

    private List<Node> nodes = new ArrayList<Node>();

    private int batchSize;

    /**
     * Instantiates a new h base client.
     * 
     * @param indexManager
     *            the index manager
     * @param conf
     *            the conf
     * @param hTablePool
     *            the h table pool
     * @param reader
     *            the reader
     * @param persistenceUnit
     *            the persistence unit
     */
    public HBaseClient(IndexManager indexManager, HBaseConfiguration conf, HTablePool hTablePool, EntityReader reader,
            String persistenceUnit)
    {
        this.indexManager = indexManager;
        this.handler = new HBaseDataHandler(conf, hTablePool);
        this.reader = reader;
        this.persistenceUnit = persistenceUnit;

        PersistenceUnitMetadata puMetadata = KunderaMetadataManager.getPersistenceUnitMetadata(persistenceUnit);
        batchSize = puMetadata.getBatchSize();

    }

    /*
     * (non-Javadoc)
     * 
     * @see com.impetus.kundera.client.Client#find(java.lang.Class,
     * java.lang.Object, java.util.List)
     */
    @Override
    public Object find(Class entityClass, Object rowId)
    {
        EntityMetadata entityMetadata = KunderaMetadataManager.getEntityMetadata(entityClass);
        List<String> relationNames = entityMetadata.getRelationNames();
        // columnFamily has a different meaning for HBase, so it won't be used
        // here
        String tableName = entityMetadata.getTableName();
        Object enhancedEntity = null;
        List results = null;
        try
        {
            if (rowId == null)
            {
                return null;
            }
            results = handler
                    .readData(tableName, entityMetadata.getEntityClazz(), entityMetadata, rowId, relationNames);
            if (results != null)
            {
                enhancedEntity = results.get(0);
            }
        }
        catch (IOException e)
        {
            log.error("Error during find by id, Caused by:" + e.getMessage());
            throw new KunderaException(e);
        }
        return enhancedEntity;
    }

    /*
     * (non-Javadoc)
     * 
     * @see com.impetus.kundera.client.Client#findAll(java.lang.Class,
     * java.lang.Object[])
     */
    @Override
    public <E> List<E> findAll(Class<E> entityClass, Object... rowIds)
    {
        EntityMetadata entityMetadata = KunderaMetadataManager.getEntityMetadata(entityClass);
        List<E> entities = new ArrayList<E>();
        if (rowIds == null)
        {
            return null;
        }
        for (Object rowKey : rowIds)
        {
            E e = null;
            try
            {
                if (rowKey != null)
                {
                    List results = handler.readData(entityMetadata.getTableName(), entityMetadata.getEntityClazz(),
                            entityMetadata, rowKey.toString(), entityMetadata.getRelationNames());
                    if (results != null)
                    {
                        e = (E) results.get(0);
                        entities.add(e);
                    }
                }
            }
            catch (IOException ioex)
            {
                log.error("Error during find All, Caused by:" + ioex.getMessage());
                throw new KunderaException(ioex);
            }

        }
        return entities;
    }

    /*
     * (non-Javadoc)
     * 
     * @see com.impetus.kundera.client.Client#find(java.lang.Class,
     * java.util.Map)
     */
    @Override
    public <E> List<E> find(Class<E> entityClass, Map<String, String> col)
    {
        EntityMetadata entityMetadata = KunderaMetadataManager.getEntityMetadata(getPersistenceUnit(), entityClass);
        List<E> entities = new ArrayList<E>();
        Map<String, Field> columnFamilyNameToFieldMap = MetadataUtils.createSuperColumnsFieldMap(entityMetadata);
        for (String columnFamilyName : col.keySet())
        {
            String entityId = col.get(columnFamilyName);
            if (entityId != null)
            {
                E e = null;
                try
                {

                    List results = handler.readData(entityMetadata.getTableName(), entityMetadata.getEntityClazz(),
                            entityMetadata, entityId, null);
                    if (results != null)
                    {
                        e = (E) results.get(0);
                        // entities.add(e);
                    }
                }
                catch (IOException ioex)
                {
                    log.error("Error during find for embedded entities, Caused by:" + ioex.getMessage());

                    throw new KunderaException(ioex);
                }

                Field columnFamilyField = columnFamilyNameToFieldMap.get(columnFamilyName.substring(0,
                        columnFamilyName.indexOf("|")));
                Object columnFamilyValue = PropertyAccessorHelper.getObject(e, columnFamilyField);
                if (Collection.class.isAssignableFrom(columnFamilyField.getType()))
                {
                    entities.addAll((Collection) columnFamilyValue);
                }
                else
                {
                    entities.add((E) columnFamilyValue);
                }
            }
        }
        return entities;
    }

    /**
     * Method to find entities using JPQL(converted into FilterList.)
     * 
     * @param <E>
     *            parameterized entity class.
     * @param entityClass
     *            entity class.
     * @param metadata
     *            entity metadata.
     * @return list of entities.
     */
    public <E> List<E> findByQuery(Class<E> entityClass, EntityMetadata metadata)
    {
        EntityMetadata entityMetadata = KunderaMetadataManager.getEntityMetadata(entityClass);
        List<String> relationNames = entityMetadata.getRelationNames();
        // columnFamily has a different meaning for HBase, so it won't be used
        // here
        String tableName = entityMetadata.getTableName();
        Object enhancedEntity = null;
        List results = null;
        try
        {

            results = handler.readData(tableName, entityMetadata.getEntityClazz(), entityMetadata, null, relationNames);
        }
        catch (IOException ioex)
        {
            log.error("Error during find All, Caused by:" + ioex.getMessage());
            throw new KunderaException(ioex);
        }
        return results;

    }

    /**
     * Handles find by range query for given start and end row key range values.
     * 
     * @param <E>
     *            parameterized entity class.
     * @param entityClass
     *            entity class.
     * @param metadata
     *            entity metadata
     * @param startRow
     *            start row.
     * @param endRow
     *            end row.
     * @return collection holding results.
     */
    public <E> List<E> findByRange(Class<E> entityClass, EntityMetadata metadata, byte[] startRow, byte[] endRow,
            String[] columns)
    {
        EntityMetadata entityMetadata = KunderaMetadataManager.getEntityMetadata(entityClass);
        List<String> relationNames = entityMetadata.getRelationNames();
        // columnFamily has a different meaning for HBase, so it won't be used
        // here
        String tableName = entityMetadata.getTableName();
        Object enhancedEntity = null;
        List results = null;

        try
        {

            results = handler.readDataByRange(tableName, entityClass, metadata, startRow, endRow, columns);
        }
        catch (IOException ioex)
        {
            log.error("Error during find All, Caused by:" + ioex.getMessage());
            throw new KunderaException(ioex);
        }
        return results;

    }

    /*
     * (non-Javadoc)
     * 
     * @see com.impetus.kundera.client.Client#close()
     */
    @Override
    public void close()
    {
        handler.shutdown();

    }

    /**
     * Setter for filter.
     * 
     * @param filter
     *            filter.
     */
    public void setFilter(Filter filter)
    {
        ((HBaseDataHandler) handler).setFilter(filter);
    }

    /**
     * On persist.
     * 
     * @param entityMetadata
     *            the entity metadata
     * @param entity
     *            the entity
     * @param id
     *            the id
     * @param relations
     *            the relations
     */
    @Override
    protected void onPersist(EntityMetadata entityMetadata, Object entity, Object id, List<RelationHolder> relations)
    {
        String tableName = entityMetadata.getTableName();

        try
        {
            // Write data to HBase

            handler.writeData(tableName, entityMetadata, entity, id, relations);
        }
        catch (IOException e)
        {
            throw new PersistenceException(e);
        }
    }

    @Override
    public void persistJoinTable(JoinTableData joinTableData)
    {
        String joinTableName = joinTableData.getJoinTableName();
        String joinColumnName = joinTableData.getJoinColumnName();
        String invJoinColumnName = joinTableData.getInverseJoinColumnName();
        Map<Object, Set<Object>> joinTableRecords = joinTableData.getJoinTableRecords();

        for (Object key : joinTableRecords.keySet())
        {
            Set<Object> values = joinTableRecords.get(key);
            Object joinColumnValue = key;

            Map<String, Object> columns = new HashMap<String, Object>();
            for (Object childValue : values)
            {
                Object invJoinColumnValue = childValue;
                columns.put(invJoinColumnName + "_" + invJoinColumnValue, invJoinColumnValue);
            }

            if (columns != null && !columns.isEmpty())
            {
                try
                {
                    handler.createTableIfDoesNotExist(joinTableName, Constants.JOIN_COLUMNS_FAMILY_NAME);
                    handler.writeJoinTableData(joinTableName, joinColumnValue, columns);
                }
                catch (IOException e)
                {
                    throw new PersistenceException(e);
                }
            }

        }

    }

    /*
     * (non-Javadoc)
     * 
     * @see
     * com.impetus.kundera.client.Client#getForeignKeysFromJoinTable(java.lang
     * .String, java.lang.String, java.lang.String,
     * com.impetus.kundera.metadata.model.EntityMetadata,
     * com.impetus.kundera.persistence.handler.impl.EntitySaveGraph)
     */
    @Override
    public <E> List<E> getColumnsById(String schemaName,String joinTableName, String joinColumnName, String inverseJoinColumnName,
            Object parentId)
    {
        return handler.getForeignKeysFromJoinTable(joinTableName, parentId, inverseJoinColumnName);

    }

    public void deleteByColumn(String schemaName, String tableName, String columnName, Object columnValue)
    {
        try
        {
            handler.deleteRow(columnValue, tableName);

        }
        catch (IOException e)
        {
            log.error("Error during delete by key. Caused by:" + e.getMessage());
            throw new PersistenceException(e);
        }
    }

    /*
     * (non-Javadoc)
     * 
     * @see com.impetus.kundera.client.Client#delete(java.lang.Object,
     * java.lang.Object)
     */
    @Override
    public void delete(Object entity, Object pKey)
    {
        EntityMetadata metadata = KunderaMetadataManager.getEntityMetadata(entity.getClass());
        deleteByColumn(metadata.getSchema(), metadata.getTableName(),
                ((AbstractAttribute) metadata.getIdAttribute()).getJPAColumnName(), pKey);
    }

    /*
     * (non-Javadoc)
     * 
     * @see com.impetus.kundera.client.Client#findByRelation(java.lang.String,
     * java.lang.Object, java.lang.Class)
     */
    @Override
    public List<Object> findByRelation(String colName, Object colValue, Class entityClazz)
    {
        CompareOp operator = HBaseUtils.getOperator("=", false);

        EntityMetadata m = KunderaMetadataManager.getEntityMetadata(entityClazz);

        byte[] valueInBytes = HBaseUtils.getBytes(colValue);
        SingleColumnValueFilter f = new SingleColumnValueFilter(Bytes.toBytes(colName), Bytes.toBytes(colName),
                operator, valueInBytes);
        // f.setFilterIfMissing(true);
        try
        {
            return ((HBaseDataHandler) handler).scanData(f, m.getTableName(), entityClazz, m, colName);
        }
        catch (IOException ioe)
        {
            log.error("Error during find By Relation, Caused by:" + ioe.getMessage());
            throw new KunderaException(ioe);
        }
        catch (InstantiationException ie)
        {
            log.error("Error during find By Relation, Caused by:" + ie.getMessage());
            throw new KunderaException(ie);
        }
        catch (IllegalAccessException iae)
        {
            log.error("Error during find By Relation, Caused by:" + iae.getMessage());
            throw new KunderaException(iae);
        }
    }

    /*
     * (non-Javadoc)
     * 
     * @see com.impetus.kundera.client.Client#getReader()
     */
    @Override
    public EntityReader getReader()
    {
        return reader;
    }

    /*
     * (non-Javadoc)
     * 
     * @see com.impetus.kundera.client.Client#getQueryImplementor()
     */
    @Override
    public Class<HBaseQuery> getQueryImplementor()
    {
        return HBaseQuery.class;
    }

    /*
     * (non-Javadoc)
     * 
     * @see com.impetus.kundera.client.Client#findIdsByColumn(java.lang.String,
     * java.lang.String, java.lang.String, java.lang.Object, java.lang.Class)
     */
    @Override
    public Object[] findIdsByColumn(String schemaName, String tableName, String pKeyName, String columnName,
            Object columnValue, Class entityClazz)
    {
        CompareOp operator = HBaseUtils.getOperator("=", false);
        EntityMetadata m = KunderaMetadataManager.getEntityMetadata(entityClazz);

        byte[] valueInBytes = HBaseUtils.getBytes(columnValue);
        Filter f = new SingleColumnValueFilter(Bytes.toBytes(columnName), Bytes.toBytes(columnName), operator,
                valueInBytes);
        KeyOnlyFilter keyFilter = new KeyOnlyFilter();
        FilterList filterList = new FilterList(f, keyFilter);
        try
        {
            return handler.scanRowyKeys(filterList, tableName, Constants.JOIN_COLUMNS_FAMILY_NAME, columnName + "_"
                    + columnValue, m.getIdAttribute().getBindableJavaType());
        }
        catch (IOException e)
        {
            log.error("Error while executing findIdsByColumn(), Caused by: " + e.getMessage());
            throw new KunderaException(e);
        }
    }

    /*
     * (non-Javadoc)
     * 
     * @see
     * com.impetus.kundera.persistence.api.Batcher#addBatch(com.impetus.kundera
     * .graph.Node)
     */
    public void addBatch(Node node)
    {

        if (node != null)
        {
            nodes.add(node);
        }

        onBatchLimit();
    }

    /*
     * (non-Javadoc)
     * 
     * @see com.impetus.kundera.persistence.api.Batcher#getBatchSize()
     */
    @Override
    public int getBatchSize()
    {
        return batchSize;
    }

    /*
     * (non-Javadoc)
     * 
     * @see com.impetus.kundera.persistence.api.Batcher#executeBatch()
     */
    @Override
    public int executeBatch()
    {

        Map<HTable, List<HBaseDataWrapper>> data = new HashMap<HTable, List<HBaseDataWrapper>>();

        try
        {
            for (Node node : nodes)
            {
                if (node.isDirty())
                {
                    HTable hTable = null;
                    Object rowKey = node.getEntityId();
                    Object entity = node.getData();
                    if (node.isInState(RemovedState.class))
                    {
                        delete(entity, rowKey);
                    }
                    else
                    {
                        EntityMetadata metadata = KunderaMetadataManager.getEntityMetadata(node.getDataClass());

                        HBaseDataWrapper columnWrapper = new HBaseDataHandler.HBaseDataWrapper(rowKey,
                                new java.util.HashSet<Attribute>(), entity, null);

                        MetamodelImpl metaModel = (MetamodelImpl) KunderaMetadata.INSTANCE.getApplicationMetadata()
                                .getMetamodel(metadata.getPersistenceUnit());

                        EntityType entityType = metaModel.entity(node.getDataClass());

                        List<HBaseDataWrapper> embeddableData = new ArrayList<HBaseDataHandler.HBaseDataWrapper>();
                        // List<HBaseDataWrapper> columnWrapper = new
                        // ArrayList<HBaseDataHandler.HBaseDataWrapper>();

                        hTable = ((HBaseDataHandler) handler).gethTable(metadata.getTableName());
                        ((HBaseDataHandler) handler).preparePersistentData(metadata.getTableName(), entity, rowKey,
                                metaModel, entityType.getAttributes(), columnWrapper, embeddableData);

                        List<HBaseDataWrapper> dataSet = null;
                        if (data.containsKey(hTable))
                        {
                            dataSet = data.get(metadata.getTableName());
                            addRecords(columnWrapper, embeddableData, dataSet);

                        }
                        else
                        {
                            dataSet = new ArrayList<HBaseDataHandler.HBaseDataWrapper>();
                            addRecords(columnWrapper, embeddableData, dataSet);
                            data.put(hTable, dataSet);
                        }
                    }
                }
            }

            if (!data.isEmpty())
            {
                ((HBaseDataHandler) handler).batch_insert(data);
            }
            return data.size();
        }
        catch (IOException e)
        {
            log.error("Error while executing batch insert/update, Caused by: " + e.getMessage());
            throw new KunderaException(e);
        }

    }

    /**
     * Add records to data wrapper.
     * 
     * @param columnWrapper
     *            column wrapper
     * @param embeddableData
     *            embeddable data
     * @param dataSet
     *            data collection set
     */
    private void addRecords(HBaseDataWrapper columnWrapper, List<HBaseDataWrapper> embeddableData,
            List<HBaseDataWrapper> dataSet)
    {
        dataSet.add(columnWrapper);

        if (!embeddableData.isEmpty())
        {
            dataSet.addAll(embeddableData);
        }
    }

    /**
     * Check on batch limit.
     */
    private void onBatchLimit()
    {
        if (batchSize > 0 && batchSize == nodes.size())
        {
            executeBatch();
            nodes.clear();
        }
    }

    @Override
    public void populateClientProperties(Client client, Map<String, Object> properties)
    {
        new HBaseClientProperties().populateClientProperties(client, properties);
    }

    /* (non-Javadoc)
     * @see com.impetus.kundera.client.Client#getColumnsById(java.lang.String, java.lang.String, java.lang.String, java.lang.String, java.lang.Object)
     */
    @Override
    public <E> List<E> getColumnsById(String tableName, String pKeyColumnName, String columnName,
            Object pKeyColumnValue)
    {
        // TODO Auto-generated method stub
        return null;
    }

    /* (non-Javadoc)
     * @see com.impetus.kundera.client.Client#findIdsByColumn(java.lang.String, java.lang.String, java.lang.String, java.lang.Object, java.lang.Class)
     */
    @Override
    public Object[] findIdsByColumn(String tableName, String pKeyName, String columnName, Object columnValue,
            Class entityClazz)
    {
        // TODO Auto-generated method stub
        return null;
    }

    /* (non-Javadoc)
     * @see com.impetus.kundera.client.Client#deleteByColumn(java.lang.String, java.lang.String, java.lang.Object)
     */
    @Override
    public void deleteByColumn(String tableName, String columnName, Object columnValue)
    {
        // TODO Auto-generated method stub
        
    }

}
