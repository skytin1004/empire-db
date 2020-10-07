/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *  http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */
package org.apache.empire.db;

import java.lang.reflect.Constructor;
import java.lang.reflect.InvocationTargetException;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.text.MessageFormat;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

import org.apache.commons.beanutils.ConstructorUtils;
import org.apache.empire.commons.ObjectUtils;
import org.apache.empire.data.ColumnExpr;
import org.apache.empire.data.DataType;
import org.apache.empire.db.exceptions.EmpireSQLException;
import org.apache.empire.db.exceptions.QueryNoResultException;
import org.apache.empire.db.expr.join.DBJoinExpr;
import org.apache.empire.exceptions.BeanInstantiationException;
import org.apache.empire.exceptions.InvalidArgumentException;
import org.apache.empire.exceptions.MiscellaneousErrorException;
import org.apache.empire.exceptions.ObjectNotValidException;
import org.apache.empire.xml.XMLUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.w3c.dom.Document;
import org.w3c.dom.Element;


/**
 * <P>
 * This class is used to perform database queries from a DBCommand object and access the results.<BR>
 * In oder to perform a query call the open() function or - for single row queries - call getRecordData();<BR>
 * You can iterate through the rows using moveNext() or an iterator.<BR>
 * <P>
 * However take care: A reader must always be explicitly closed using the close() method!<BR>
 * Otherwise you may lock the JDBC connection and run out of resources.<BR>
 * Use <PRE>try { ... } finally { reader.close(); } </PRE> to make sure the reader is closed.<BR>
 * <P>
 * To access and work with the query result you can do one of the following:<BR>
 * <ul>
 *  <li>access field values directly by using one of the get... functions (see {@link DBRecordData})</li> 
 *  <li>get the rows as a list of Java Beans using by using {@link DBReader#getBeanList(Class, int)}</li> 
 *  <li>get the rows as an XML-Document using {@link DBReader#getXmlDocument()} </li> 
 *  <li>initialize a DBRecord with the current row data using {@link DBReader#initRecord(DBRowSet, DBRecord)}<br>
 *      This will allow you to modify and update the data. 
 *  </li> 
 * </ul>
 *
 *
 */
public class DBReader extends DBRecordData
{
    private final static long serialVersionUID = 1L;
  
    public abstract class DBReaderIterator implements Iterator<DBRecordData>
    {
        protected int curCount = 0;
        protected int maxCount = 0;

        public DBReaderIterator(int maxCount)
        {
            if (maxCount < 0)
                maxCount = 0x7FFFFFFF; // Highest positive number
            // Set Maxcount
            this.maxCount = maxCount;
        }

        /**
         * Implements the Iterator Interface Method remove not implemented and not applicable.
         */
        @Override
        public void remove()
        {
            log.error("DBReader.remove ist not implemented!");
        }

        /**
         * Disposes the iterator.
         */
        public void dispose()
        {
            curCount = maxCount = -1;
        }
    }

    /**
     * This is an iterator for scrolling resultsets.
     * This iterator has no such limitations as the forward iterator.
     */
    public class DBReaderScrollableIterator extends DBReaderIterator
    {
        public DBReaderScrollableIterator(int maxCount)
        {
            super(maxCount);
        }

        /**
         * Implements the Iterator Interface.
         * 
         * @return true if there is another record to read
         */
        @Override
        public boolean hasNext()
        {
            try
            {   // Check position
                if (curCount >= maxCount)
                    return false;
                // Check Recordset
                if (rset == null || rset.isLast() || rset.isAfterLast())
                    return false;
                // there are more records
                return true;
            } catch (SQLException e) {
                // Error
                throw new EmpireSQLException(getDatabase(), e);
            }
        }

        /**
         * Implements the Iterator Interface.
         * 
         * @return the current Record interface
         */
        @Override
        public DBRecordData next()
        {
            if ((curCount < maxCount && moveNext()))
            {
                curCount++;
                return DBReader.this;
            }
            // Past the end!
            return null;
        }
    }

    /**
     * This is an iterator for forward only resultsets.
     * There is an important limitation on this iterator: After calling
     * hasNext() the caller may not use any functions on the current item any more. i.e.
     * Example:
     *  while (i.hasNext())
     *  {
     *      DBRecordData r = i.next(); 
     *      Object o  = r.getValue(0);  // ok
     *      
     *      bool last = i.hasNext();    // ok
     *      Object o  = r.getValue(0);  // Illegal call!
     *  }
     */
    public class DBReaderForwardIterator extends DBReaderIterator
    {
        private boolean getCurrent = true;
        private boolean hasCurrent = false;

        public DBReaderForwardIterator(int maxCount)
        {
            super(maxCount);
        }

        /**
         * Implements the Iterator Interface.
         * 
         * @return true if there is another record to read
         */
        @Override
        public boolean hasNext()
        {
            // Check position
            if (curCount >= maxCount)
                return false;
            if (rset == null)
                throw new ObjectNotValidException(this);
            // Check next Record
            if (getCurrent == true)
            {
                getCurrent = false;
                hasCurrent = moveNext();
            }
            return hasCurrent;
        }

        /**
         * Implements the Iterator Interface.
         * 
         * @return the current Record interface
         */
        @Override
        public DBRecordData next()
        {
            if (hasCurrent == false)
                return null; // Past the end!
            // next called without call to hasNext ?
            if (getCurrent && !moveNext())
            { // No more records
                hasCurrent = false;
                getCurrent = false;
                return null;
            }
            // Move forward
            curCount++;
            getCurrent = true;
            return DBReader.this;
        }
    }

    // Logger
    protected static final Logger log = LoggerFactory.getLogger(DBReader.class);
    
    private static boolean trackOpenResultSets = false; 
    
    /**
     * Support for finding code errors where a DBRecordSet is opened but not closed
     */
    private static ThreadLocal<Map<DBReader, Exception>> threadLocalOpenResultSets = new ThreadLocal<Map<DBReader, Exception>>();
    
    // Object references
    private DBDatabase     db      = null;
    private DBColumnExpr[] colList = null;
    private ResultSet      rset    = null;
    // the field index map
    private Map<ColumnExpr, Integer> fieldIndexMap = null;

    /**
     * Constructs a default DBReader object with the fieldIndexMap enabled.
     */
    public DBReader()
    {
        // Default Constructor
        this(true);
    }

    /**
     * Constructs an empty DBRecordSet object.
     * @param useFieldIndexMap 
     */
    public DBReader(boolean useFieldIndexMap)
    {
        if (useFieldIndexMap)
            fieldIndexMap = new HashMap<ColumnExpr, Integer>();
    }

    /**
     * Returns the current DBDatabase object.
     * 
     * @return the current DBDatabase object
     */
    @Override
    public DBDatabase getDatabase()
    {
        return db;
    }
    
    public boolean getScrollable()
    {
        try
        {
            // Check Resultset
            return (rset!=null && rset.getType()!=ResultSet.TYPE_FORWARD_ONLY); 
        } catch (SQLException e)
        {
            log.error("Cannot determine Resultset type", e);
            return false;
        }
    }

    /**
     * Returns the index value by a specified DBColumnExpr object.
     * 
     * @return the index value
     */
    @Override
    public int getFieldIndex(ColumnExpr column) 
    {
        if (fieldIndexMap==null)
            return findFieldIndex(column);
        // Use fieldIndexMap
        Integer index = fieldIndexMap.get(column);
        if (index==null)
        {   // add to field Index map
            index = findFieldIndex(column);
            fieldIndexMap.put(column, index);
        }
        return index;
    }
    
    /** Get the column Expression at position */
    @Override
    public DBColumnExpr getColumnExpr(int iColumn)
    {
        if (colList == null || iColumn < 0 || iColumn >= colList.length)
            return null; // Index out of range
        // return column Expression
        return colList[iColumn];
    }

    /**
     * Returns the index value by a specified column name.
     * 
     * @param column the column name
     * @return the index value
     */
    @Override
    public int getFieldIndex(String column)
    {
        if (colList != null)
        {
            for (int i = 0; i < colList.length; i++)
                if (colList[i].getName().equalsIgnoreCase(column))
                    return i;
        }
        // not found
        return -1;
    }

    /**
     * Checks wehter a column value is null Unlike the base
     * class implementation, this class directly check the value fromt the
     * resultset.
     * 
     * @param index index of the column
     * @return true if the value is null or false otherwise
     */
    @Override
    public boolean isNull(int index)
    {
        if (index < 0 || index >= colList.length)
        { // Index out of range
            log.error("Index out of range: " + index);
            return true;
        }
        try
        { // Check Value on Resultset
            rset.getObject(index + 1);
            return rset.wasNull();
        } catch (Exception e)
        {
            log.error("isNullValue exception", e);
            return super.isNull(index);
        }
    }

    /**
     * Returns a data value identified by the column index.
     * 
     * @param index index of the column
     * @return the value
     */
    @Override
    public Object getValue(int index)
    {
        // Check params
        if (index < 0 || index >= colList.length)
            throw new InvalidArgumentException("index", index);
        try
        {   // Get Value from Resultset
            DataType dataType = colList[index].getDataType();
            return db.driver.getResultValue(rset, index + 1, dataType);

        } catch (SQLException e)
        { // Operation failed
            throw new EmpireSQLException(this, e);
        }
    }

    /** 
     * Checks if the rowset is open
     *  
     * @return true if the rowset is open
     */
    public boolean isOpen()
    {
        return (rset != null);
    }
    
    /**
     * Opens the reader by executing the given SQL command.<BR>
     * After the reader is open, the reader's position is before the first record.<BR>
     * Use moveNext or iterator() to step through the rows.<BR>
     * Data of the current row can be accessed through the functions on the RecordData interface.<BR>
     * <P>
     * ATTENTION: After using the reader it must be closed using the close() method!<BR>
     * Use <PRE>try { ... } finally { reader.close(); } </PRE> to make sure the reader is closed.<BR>
     * <P>
     * @param cmd the SQL-Command with cmd.getSelect()
     * @param scrollable true if the reader should be scrollable or false if not
     * @param conn a valid JDBC connection.
     */
    public void open(DBCommandExpr cmd, boolean scrollable, Connection conn)
    {
        if (isOpen())
            close();
        // Get the query statement
        String sqlCmd = cmd.getSelect();
        // Collect the query parameters
        Object[] paramValues = cmd.getParamValues();
        List<Object> subqueryParamValues = (cmd instanceof DBCommand) ? findSubQueryParams((DBCommand)cmd) : null;
        if (subqueryParamValues!=null && !subqueryParamValues.isEmpty())
        {   // Check Count
            if (paramValues==null)
            {   // use subquery params
                paramValues = subqueryParamValues.toArray();
            }
            else if (paramValues.length!=subqueryParamValues.size())
            {   // number of params do not match
                String msg = MessageFormat.format("Invalid number of parameters query: provided={0}, required={1}; query="+cmd.getSelect(), paramValues.length, subqueryParamValues.size());
                throw new MiscellaneousErrorException(msg);
            }
        }
        // Execute the query
        DBDatabase queryDb   = cmd.getDatabase();
        ResultSet  queryRset = queryDb.executeQuery(sqlCmd, paramValues, scrollable, conn);
        if (queryRset==null)
            throw new QueryNoResultException(sqlCmd);
        // init
        init(queryDb, cmd.getSelectExprList(), queryRset);
    }

    /**
     * Opens the reader by executing the given SQL command.<BR>
     * <P>
     * see {@link DBReader#open(DBCommandExpr, boolean, Connection)}
     * </P>
     * @param cmd the SQL-Command with cmd.getSelect()
     * @param conn a valid JDBC connection.
     */
    public final void open(DBCommandExpr cmd, Connection conn)
    {
        open(cmd, false, conn);
    }

    /**
     * <P>
     * Opens the reader by executing the given SQL command and moves to the first row.<BR>
     * If true is returned data of the row can be accessed through the functions on the RecordData interface.<BR>
     * This function is intended for single row queries and provided for convenience.<BR>
     * However it behaves exacly as calling reader.open() and reader.moveNext()<BR>
     * <P>
     * ATTENTION: After using the reader it must be closed using the close() method!<BR>
     * Use <PRE>try { ... } finally { reader.close(); } </PRE> to make sure the reader is closed.<BR>
     * <P>
     * @param cmd the SQL-Command with cmd.getSelect()
     * @param conn a valid JDBC connection.
     */
    public void getRecordData(DBCommandExpr cmd, Connection conn)
    { // Open the record
        open(cmd, conn);
        // Get First Record
        if (!moveNext())
        { // Close
            throw new QueryNoResultException(cmd.getSelect());
        }
    }

    /**
     * Closes the DBRecordSet object, the Statement object and detach the columns.<BR>
     * A reader must always be closed immediately after using it.
     */
    @Override
    public void close()
    {
        try
        { // Dispose iterator
            if (iterator != null)
            {
                iterator.dispose();
                iterator = null;
            }
            // Close Recordset
            if (rset != null)
            {
                getDatabase().closeResultSet(rset);
                // remove from tracking-list
                endTrackingThisResultSet();
            }
            // Detach columns
            colList = null;
            rset = null;
            // clear FieldIndexMap
            if (fieldIndexMap!=null)
                fieldIndexMap.clear();
            // Done
        } catch (Exception e)
        { // What's wrong here?
            log.warn(e.toString());
        }
    }

    /**
     * Moves the cursor down the given number of rows.
     * 
     * @param count the number of rows to skip 
     * 
     * @return true if the reader is on a valid record or false otherwise
     */
    public boolean skipRows(int count)
    {
        try
        {   // Check Recordset
            if (rset == null)
                throw new ObjectNotValidException(this);
            // Forward only cursor?
            int type = rset.getType();
            if (type == ResultSet.TYPE_FORWARD_ONLY)
            {
                if (count < 0)
                    throw new InvalidArgumentException("count", count);
                // Move
                for (; count > 0; count--)
                {
                    if (!moveNext())
                        return false;
                }
                return true;
            }
            // Scrollable Cursor
            if (count > 0)
            { // Move a single record first
                if (rset.next() == false)
                    return false;
                // Move relative
                if (count > 1)
                    return rset.relative(count - 1);
            } 
            else if (count < 0)
            { // Move a single record first
                if (rset.previous() == false)
                    return false;
                // Move relative
                if (count < -1)
                    return rset.relative(count + 1);
            }
            return true;

        } catch (SQLException e) {
            // an error occurred
            throw new EmpireSQLException(this, e);
        }
    }

    /**
     * Moves the cursor down one row from its current position.
     * 
     * @return true if the reader is on a valid record or false otherwise
     */
    public boolean moveNext()
    {
        try
        {   // Check Recordset
            if (rset == null)
                throw new ObjectNotValidException(this);
            // Move Next
            if (rset.next() == false)
            { // Close recordset automatically after last record
                close();
                return false;
            }
            return true;

        } catch (SQLException e) {
            // an error occurred
            throw new EmpireSQLException(this, e);
        }
    }

    private DBReaderIterator iterator = null; // there can only be one!

    /**
     * Returns an row iterator for this reader.<BR>
     * There can only be one iterator at a time.
     * <P>
     * @param maxCount the maximum number of item that should be returned by this iterator
     * @return the row iterator
     */
    public Iterator<DBRecordData> iterator(int maxCount)
    {
        if (iterator == null && rset != null)
        {
            if (getScrollable())
                iterator = new DBReaderScrollableIterator(maxCount);
            else
                iterator = new DBReaderForwardIterator(maxCount);
        }
        return iterator;
    }

    /**
     * <PRE>
     * Returns an row iterator for this reader.
     * There can only be one iterator at a time.
     * </PRE>
     * @return the row iterator
     */
    public final Iterator<DBRecordData> iterator()
    {
        return iterator(-1);
    }

    /**
     * <PRE>
     * initializes a DBRecord object with the values of the current row.
     * At least all primary key columns of the target rowset must be provided by this reader.
     * This function is equivalent to calling rowset.initRecord(rec, reader) 
     * set also {@link DBRowSet#initRecord(DBRecord, DBRecordData)});
     * </PRE>
     * @param rowset the rowset to which to attach
     * @param rec the record which to initialize
     */
    public void initRecord(DBRowSet rowset, DBRecord rec)
    {
    	if (rowset==null)
    	    throw new InvalidArgumentException("rowset", rowset);
    	// init Record
    	rowset.initRecord(rec, this);
    }

    /**
     * Returns the result of a query as a list of objects restricted
     * to a maximum number of objects (unless maxCount is -1).
     * 
     * @param c the collection to add the objects to
     * @param t the class type of the objects in the list
     * @param maxCount the maximum number of objects
     * 
     * @return the list of T
     */
    @SuppressWarnings("unchecked")
    public <C extends Collection<T>, T> C getBeanList(C c, Class<T> t, int maxCount)
    {
        // Check Recordset
        if (rset == null)
        {   // Resultset not available
            throw new ObjectNotValidException(this);
        }
        // Query List
        try
        {
            // Check whether we can use a constructor
            Class<?>[] paramTypes = new Class[getFieldCount()];
            for (int i = 0; i < colList.length; i++)
                paramTypes[i] = DBExpr.getValueClass(colList[i].getDataType()); 
            // Find Constructor
            Constructor<?> ctor = findMatchingAccessibleConstructor(t, paramTypes);
            Object[] args = (ctor!=null) ? new Object[getFieldCount()] : null; 
            
            // Create a list of beans
            while (moveNext() && maxCount != 0)
            { // Create bean an init
                if (ctor!=null)
                {   // Use Constructor
                    Class<?>[] ctorParamTypes = ctor.getParameterTypes();
                    for (int i = 0; i < getFieldCount(); i++)
                        args[i] = ObjectUtils.convert(ctorParamTypes[i], getValue(i));
                    T bean = (T)ctor.newInstance(args);
                    c.add(bean);
                }
                else
                {   // Use Property Setters
                    T bean = t.newInstance();
                    setBeanProperties(bean);
                    c.add(bean);
                }
                // Decrease count
                if (maxCount > 0)
                    maxCount--;
            }
            // done
            return c;
        } catch (InvocationTargetException e) {
            throw new BeanInstantiationException(t, e);
        } catch (IllegalAccessException e) {
            throw new BeanInstantiationException(t, e);
        } catch (InstantiationException e) {
            throw new BeanInstantiationException(t, e);
        }
    }
    
    /**
     * Returns the result of a query as a list of objects.
     * 
     * @param t the class type of the objects in the list
     * @param maxItems the maximum number of objects
     * 
     * @return the list of T
     */
    public final <T> ArrayList<T> getBeanList(Class<T> t, int maxItems) {
        return getBeanList(new ArrayList<T>(), t, maxItems);
    }
    
    /**
     * Returns the result of a query as a list of objects.
     * 
     * @param t the class type of the objects in the list
     * 
     * @return the list of T
     */
    public final <T> ArrayList<T> getBeanList(Class<T> t) {
        return getBeanList(t, -1);
    }
    
    /**
     * Moves the cursor down one row from its current position.
     * 
     * @return the number of column descriptions added to the Element
     */
    @Override
    public int addColumnDesc(Element parent)
    {
        if (colList == null)
            throw new ObjectNotValidException(this);
        // Add Field Description
        for (int i = 0; i < colList.length; i++)
            colList[i].addXml(parent, 0);
        // return count
        return colList.length; 
    }

    /**
     * Adds all children to a parent.
     * 
     * @param parent the parent element below which to search the child
     * @return the number of row values added to the element
     */
    @Override
    public int addRowValues(Element parent)
    {
        if (rset == null)
            throw new ObjectNotValidException(this);
        // Add all children
        for (int i = 0; i < colList.length; i++)
        { // Read all
            String name = colList[i].getName();
            String idColumnAttr = getXmlDictionary().getRowIdColumnAttribute();
            if (name.equalsIgnoreCase("id"))
            { // Add Attribute
                parent.setAttribute(idColumnAttr, getString(i));
            } 
            else
            { // Add Element
                String value = getString(i);
                Element elem = XMLUtil.addElement(parent, name, value);
                if (value == null)
                    elem.setAttribute("null", "yes"); // Null-Value
            }
        }
        // return count
        return colList.length; 
    }

    /**
     * Adds all children to a parent.
     * 
     * @param parent the parent element below which to search the child
     * @return the number of rows added to the element
     */
    public int addRows(Element parent)
    {
        int count = 0;
        if (rset == null)
            return 0;
        // Add all rows
        String rowElementName = getXmlDictionary().getRowElementName();
        while (moveNext())
        {
            addRowValues(XMLUtil.addElement(parent, rowElementName));
            count++;
        }
        return count;
    }
    
    /**
     * returns the DBXmlDictionary that should used to generate XMLDocuments<BR>
     * @return the DBXmlDictionary
     */
    protected DBXmlDictionary getXmlDictionary()
    {
        return DBXmlDictionary.getInstance();
    }

    /**
     * Returns a XML document with the field description an values of this record.
     * 
     * @return the new XML Document object
     */
    @Override
    public Document getXmlDocument()
    {
        if (rset == null)
            return null;
        // Create Document
        String rowsetElementName = getXmlDictionary().getRowSetElementName();
        Element root = XMLUtil.createDocument(rowsetElementName);
        // Add Field Description
        addColumnDesc(root);
        // Add row rset
        addRows(root);
        // return Document
        return root.getOwnerDocument();
    }

    /** returns the number of the elements of the colList array */
    @Override
    public int getFieldCount()
    {
        return (colList != null) ? colList.length : 0;
    }

    /**
     * Initialize the reader from an open JDBC-ResultSet 
     * @param db the database
     * @param colList the query column expressions
     * @param rset the JDBC-ResultSet
     */
    protected void init(DBDatabase db, DBColumnExpr[] colList, ResultSet rset)
    {
        this.db = db;
        this.colList = colList;
        this.rset = rset;
        // clear fieldIndexMap         
        if (fieldIndexMap!=null)
            fieldIndexMap.clear();
        // add to tracking list (if enabled)
        trackThisResultSet();
    }

    /**
     * Access the column expression list
     * @return the column expression list
     */
    protected final DBColumnExpr[] getColumnExprList()
    {
        return colList;
    }

    /**
     * Access the JDBC-ResultSet
     * @return the JDBC-ResultSet
     */
    protected final ResultSet getResultSet()
    {
        return rset;
    }

    /**
     * finds the field Index of a given column expression
     * Internally used as helper for getFieldIndex()
     * @return the index value
     */
    protected int findFieldIndex(ColumnExpr column)
    {
        if (colList == null)
            return -1;
        // First chance: Try to find an exact match
        for (int i = 0; i < colList.length; i++)
        {
            if (colList[i].equals(column))
                return i;
        }
        // Second chance: Try Update Column
        if (column instanceof DBColumn)
        {
            for (int i = 0; i < colList.length; i++)
            {
                DBColumn updColumn = colList[i].getUpdateColumn();                    
                if (updColumn!=null && updColumn.equals(column))
                    return i;
                 // Query Expression?
                if (updColumn instanceof DBQueryColumn)
                {   updColumn = ((DBQueryColumn)updColumn).getExpr().getUpdateColumn();
                    if (updColumn!=null && updColumn.equals(column))
                        return i;
                }
            }
        }
        // not found!
        return -1;
    }

    /**
     * internal helper function to find parameterized subqueries
     * @param cmd the command
     * @return a list of parameter arrays, one for each subquery
     */
    protected List<Object> findSubQueryParams(DBCommand cmd)
    {
        List<Object> subQueryParams = null;
        List<DBJoinExpr> joins = cmd.getJoins();
        if (joins==null)
            return null;  // no joins
        // check the joins
        for (DBJoinExpr j : joins)
        {
            DBRowSet rsl = j.getLeftTable();
            DBRowSet rsr = j.getRightTable();
            if (rsl instanceof DBQuery)
            {   // the left join is a query
                subQueryParams = addSubQueryParams((DBQuery)rsl, subQueryParams);
            }
            if (rsr instanceof DBQuery)
            {   // the right join is a query
                subQueryParams = addSubQueryParams((DBQuery)rsr, subQueryParams);
            }
        }
        return subQueryParams; 
    }
    
    /**
     * Adds any subquery params to the supplied list
     * @param query the subquery
     * @param list the current list of parameters
     * @return the new list of parameters
     */
    private List<Object> addSubQueryParams(DBQuery query, List<Object> list)
    {
        DBCommandExpr sqcmd = query.getCommandExpr();
        Object[] params = query.getCommandExpr().getParamValues();
        if (params!=null && params.length>0)
        {   // add params
            if (list== null)
                list = new ArrayList<Object>();
            for (Object p : params)
                list.add(p);    
        }
        // recurse
        if (sqcmd instanceof DBCommand)
        {   // check this command too
            List<Object> sqlist = findSubQueryParams((DBCommand)sqcmd);
            if (sqlist!=null && !sqlist.isEmpty())
            {   // make one list
                if (list!= null)
                    list.addAll(sqlist);
                else 
                    list = sqlist;
            }
        }
        return list;
    }

    /**
     * Support for finding code errors where a DBRecordSet is opened but not closed.
     * 
     * @author bond
     */
    protected synchronized void trackThisResultSet()
    {
        // check if enabled
        if (trackOpenResultSets==false)
            return;
        // add this to the vector of open resultsets on this thread
        Map<DBReader, Exception> openResultSets = threadLocalOpenResultSets.get();
        if (openResultSets == null)
        {
            // Lazy initialization of the
            openResultSets = new HashMap<DBReader, Exception>(2);
            threadLocalOpenResultSets.set(openResultSets);
        }

        Exception stackException = openResultSets.get(this);
        if (stackException != null)
        {
            log.error("DBRecordSet.addOpenResultSet called for an object which is already in the open list. This is the stack of the method opening the object which was not previously closed.", stackException);
            // the code continues and overwrites the logged object with the new one
        }
        // get the current stack trace
        openResultSets.put(this, new Exception());
    }

    /**
     * Support for finding code errors where a DBRecordSet is opened but not closed.
     * 
     * @author bond
     */
    protected synchronized void endTrackingThisResultSet()
    {
        // check if enabled
        if (trackOpenResultSets==false)
            return;
        // remove
        Map<DBReader, Exception> openResultSets = threadLocalOpenResultSets.get();
        if (openResultSets.containsKey(this) == false)
        {
            log.error("DBRecordSet.removeOpenResultSet called for an object which is not in the open list. Here is the current stack.", new Exception());
        } 
        else
        {
            openResultSets.remove(this);
        }
    }

    /*
    private void writeObject(ObjectOutputStream stream) throws IOException {
        if (rset != null) {
            throw new NotSerializableException(DBReader.class.getName() + " (due to attached ResultSet)");
        }
    }
    */

    /**
     * copied from org.apache.commons.beanutils.ConstructorUtils since it's private there
     */
    protected static Constructor<?> findMatchingAccessibleConstructor(Class<?> clazz, Class<?>[] parameterTypes)
    {
        // See if we can find the method directly
        // probably faster if it works
        // (I am not sure whether it's a good idea to run into Exceptions)
        // try {
        //     Constructor ctor = clazz.getConstructor(parameterTypes);
        //     try {
        //         // see comment in org.apache.commons.beanutils.ConstructorUtils
        //         ctor.setAccessible(true);
        //     } catch (SecurityException se) { /* ignore */ }
        //     return ctor;
        // } catch (NoSuchMethodException e) { /* SWALLOW */ }

        // search through all constructors 
        int paramSize = parameterTypes.length;
        Constructor<?>[] ctors = clazz.getConstructors();
        for (int i = 0, size = ctors.length; i < size; i++)
        {   // compare parameters
            Class<?>[] ctorParams = ctors[i].getParameterTypes();
            int ctorParamSize = ctorParams.length;
            if (ctorParamSize == paramSize)
            {   // Param Size matches
                boolean match = true;
                for (int n = 0; n < ctorParamSize; n++)
                {
                    if (!ObjectUtils.isAssignmentCompatible(ctorParams[n], parameterTypes[n]))
                    {
                        match = false;
                        break;
                    }
                }
                if (match) {
                    // get accessible version of method
                    Constructor<?> ctor = ConstructorUtils.getAccessibleConstructor(ctors[i]);
                    if (ctor != null) {
                        try {
                            ctor.setAccessible(true);
                        } catch (SecurityException se) { /* ignore */ }
                        return ctor;
                    }
                }
            }
        }
        return null;
    }

    /**
     * Enables or disabled tracking of open ResultSets
     * @param enable true to enable or false otherwise
     * @return the previous state of the trackOpenResultSets
     */
    public static synchronized boolean enableOpenResultSetTracking(boolean enable)
    {
        boolean prev = trackOpenResultSets;
        trackOpenResultSets = enable;
        return prev;
    }
    
    /**
     * <PRE>
     * Call this if you want to check whether there are any unclosed resultsets
     * It logs stack traces to help find piece of code 
     * where a DBReader was opened but not closed.
     * </PRE>
     */
    public static synchronized void checkOpenResultSets()
    {
        // check if enabled
        if (trackOpenResultSets==false)
            throw new MiscellaneousErrorException("Open-ResultSet-Tracking has not been enabled. Use DBReader.enableOpenResultSetTracking() to enable or disable.");
        // Check map
        Map<DBReader, Exception> openResultSets = threadLocalOpenResultSets.get();
        if (openResultSets != null && openResultSets.isEmpty() == false)
        {
            // we have found a(n) open result set(s). Now show the stack trace(s)
            Object keySet[] = openResultSets.keySet().toArray();
            for (int i = 0; i < keySet.length; i++)
            {
                Exception stackException = openResultSets.get(keySet[i]);
                log.error("A DBReader was not closed. Stack of opening code is ", stackException);
            }
            openResultSets.clear();
        }
    }
     
}