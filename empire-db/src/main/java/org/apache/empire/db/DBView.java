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

import java.util.concurrent.atomic.AtomicInteger;

import org.apache.empire.commons.Options;
import org.apache.empire.data.DataType;
import org.apache.empire.db.expr.column.DBValueExpr;
import org.apache.empire.exceptions.InvalidArgumentException;
import org.apache.empire.exceptions.ItemExistsException;
import org.apache.empire.exceptions.NotSupportedException;
import org.apache.empire.xml.XMLUtil;
import org.w3c.dom.Element;


/**
 * This class represents a database view.
 * It contains methods to get and update records from the database
 */
public abstract class DBView extends DBRowSet
{
    // *Deprecated* private static final long serialVersionUID = 1L;

    /**
     * DBViewColumn
     * @author doebele
     */
    public static class DBViewColumn extends DBColumn
    {
        // *Deprecated* private static final long serialVersionUID = 1L;
      
        protected final DBColumnExpr expr;
        protected final DataType     dataType;
        protected final DBColumn     sourceColumn;
        protected final double       size;

        /**
         * Constructs a DBViewColumn object set the specified parameters to this object.
         * 
         * @param view the DBView object
         * @param expr the DBColumnExpr of the source table
         */
        protected DBViewColumn(DBView view, String name, DBColumnExpr expr, double size)
        { // call base
            super(view, name);
            // set Expression
            this.expr = expr;
            // set DataType
            DataType exprType = expr.getDataType();
            if (exprType==DataType.AUTOINC)
                exprType= DataType.INTEGER;
            this.dataType = exprType; 
            // Update Column
            this.sourceColumn = expr.getSourceColumn();
            // from update column
            this.size = (sourceColumn!=null ? sourceColumn.getSize() : size);
            // Add to view
            if (view != null)
                view.addColumn(this);
        }
        
        public DBColumnExpr getSourceColumnExpr()
        {
            return expr;
        }
        
        public DBView getView()
        {
            return (DBView)getRowSet();
        }

        @Override
        public DataType getDataType()
        {
            return dataType;
        }

        @Override
        public double getSize()
        {
            return size;
        }
        
        @Override
        public boolean isAutoGenerated()
        {
            if (sourceColumn==null)
                return false;
            return sourceColumn.isAutoGenerated();
        }

        @Override
        public boolean isReadOnly()
        {   
            if (getView().isUpdateable()==false)
                return true;
            // Check ReadOnly attribute 
            if (sourceColumn!=null)
                return sourceColumn.isReadOnly();
            // AUTOINC's are read only
            return isAutoGenerated();
        }

        @Override
        public boolean isRequired()
        {   
            if (getView().isUpdateable()==false)
                return false;
            // Check update Column
            if (sourceColumn==null)
                return false;
            return sourceColumn.isRequired();
        }    

        @Override
        public Object validateValue(Object value)
        {
            if (sourceColumn==null)
                return value;
            return sourceColumn.validateValue(value);
        }

        @Override
        public Object getAttribute(String name)
        {
            if (attributes != null && attributes.contains(name))
                return attributes.get(name);
            // Otherwise ask expression
            if (sourceColumn==null)
                return null;
            return sourceColumn.getAttribute(name);
        }

        @Override
        public Options getOptions()
        {
            if (options != null)
                return options;
            // Otherwise ask expression
            if (sourceColumn==null)
                return null;
            return sourceColumn.getOptions();
        }

        @Override
        public Element addXml(Element parent, long flags)
        {
            Element elem = XMLUtil.addElement(parent, "column");
            elem.setAttribute("name", name);
            // set default attributes
            if (sourceColumn != null)
                elem.setAttribute("source", sourceColumn.getFullName());
            // size
            double size = getSize();
            if (size > 0)
                elem.setAttribute("size", String.valueOf(size));
            // add All Attributes
            if (attributes != null)
                attributes.addXml(elem, flags);
            // add All Options
            Options fieldOptions = getOptions();
            if (fieldOptions != null)
                fieldOptions.addXml(elem, flags);
            // done
            return elem;
        }

    }

    private static AtomicInteger viewCount = new AtomicInteger(0);

    private String               name;
    private String               alias;
    private DBViewColumn[]       keyColumns;
    private boolean              updateable;                      // true if the view is updateable
    private Boolean              quoteName = null;

    /**
     * Creates a view object for a given view in the database.
     * 
     * @param name the name of the view
     * @param db the database this view belongs to.
     * @param isUpdateable true if the records of this view can be updated 
     */
    public DBView(String name, DBDatabase db, boolean isUpdateable)
    { // Set the column expressions
        super(db);
        // Set Name and Alias
        this.name = name;
        this.alias = "v" + String.valueOf(viewCount.incrementAndGet());
        this.updateable = isUpdateable;
        // Add View to Database
        if (db != null && name != null)
            db.addView(this);
    }

    /**
     * Creates a view object for a given view in the database.
     * 
     * @param name the name of the view
     * @param db the database this view belongs to.
     */
    public DBView(String name, DBDatabase db)
    { // Set the column expressions
        this(name, db, false);
    }

    /**
     * Returns an array of all key columns.
     * @return an array of all key columns
     */
    @Override
    public DBColumn[] getKeyColumns()
    {
        return this.keyColumns;
    }
    
    /**
     * identifies the columns that uniquely identify a row in the view
     * @param keyColumns list of columns that uniquely identify a row 
     */
    protected void setKeyColumns(DBViewColumn... keyColumns)
    { // Set Key Columns
        this.keyColumns = keyColumns;
    }

    /**
     * identifies the column that uniquely identifies a row in the view
     * @param keyColumn the column that uniquely identifies a row 
     */
    protected void setKeyColumn(DBViewColumn keyColumn)
    {
        if (keyColumn != null)
            setKeyColumns(new DBViewColumn[] { keyColumn });
        else
            setKeyColumns((DBViewColumn[]) null);
    }

    /**
     * Returns the command required to create the view<br>
     * This is function is only used for the creation of DDL statements
     * @return a command expression that is used to create the view
     */
    public abstract DBCommandExpr createCommand();

    /**
     * Returns the view name of this object.
     * @return the view name of this object
     */
    @Override
    public String getName()
    {
        return name;
    }

    /**
     * Returns the full qualified table name.
     * @return the full qualified table name
     */
    @Override
    public String getFullName()
    {
        String schema = db.getSchema();
        return (schema != null) ? schema + "." + name : name;
    }

    /**
     * Returns the alias name of this object.
     * @return the alias name of this object
     */
    @Override
    public String getAlias()
    {
        return alias;
    }
    
    /**
     * Returns whether or not the view is updateable
     * @return true if the view is updateable or false if not 
     */
    @Override
    public boolean isUpdateable()
    {
        return this.updateable;
    }

    /**
     * Adds a column to the view.
     * 
     * @param col a view column object
     */
    protected void addColumn(DBViewColumn col)
    { // find column by name
        if (col == null || col.getRowSet() != this)
            throw new InvalidArgumentException("col", col);
        if (getColumn(col.getName())!=null)
            throw new ItemExistsException(col.getName());
        // add now
        columns.add(col);
    }

    /**
     * Adds a column to the view.
     * 
     * @param columnName name of the column in the view
     * @param dataType the data type of the column
     * @param size the size of the column
     * @return true if the column was successfully added or false otherwise
     */
    protected final DBViewColumn addColumn(String columnName, DataType dataType, double size)
    {   // find column by name
        return new DBViewColumn(this, columnName, new DBValueExpr(db, null, dataType), size);
    }

    /**
     * Adds a column to the view.
     * 
     * @param columnName name of the column in the view
     * @param dataType the data type of the column
     * @return true if the column was successfully added or false otherwise
     */
    protected final DBViewColumn addColumn(String columnName, DataType dataType)
    {   // find column by name
        return new DBViewColumn(this, columnName, new DBValueExpr(db, null, dataType), 0.0d);
    }

    /**
     * Adds a column to the view.
     * 
     * @param columnName name of the column in the view
     * @param columnExpr the column expression that builds the column
     * @return true if the column was successfully added or false otherwise
     */
    protected final DBViewColumn addColumn(String columnName, DBColumnExpr columnExpr)
    {   // find column by name
        return new DBViewColumn(this, columnName, columnExpr, 0.0d);
    }

    /**
     * Adds a column to the view based on an existing column in another table or view.
     * 
     * @param sourceColumn existing column in another table or view  
     * @return the view column object
     */
    protected final DBViewColumn addColumn(DBTableColumn sourceColumn)
    {   // find column by name
        return new DBViewColumn(this, sourceColumn.getName(), sourceColumn, 0.0d);
    }

    /**
     * This function searchs for equal columns given by the specified DBColumnExpr object.
     * 
     * @param expr the DBColumnExpr object
     * @return the located column (only DBViewColumn objects)
     */
    public DBViewColumn findViewColumn(DBColumnExpr expr)
    {
        for (int i = 0; i < columns.size(); i++)
        {
            DBViewColumn vc = (DBViewColumn) columns.get(i);
            if (vc.expr.equals(expr))
                return vc;
        }
        // not found
        return null;
    }

    /**
     * Creates the SQL-Command adds the alias name to the SQL-Command.
     * 
     * @param buf the SQL-Command
     * @param context the current SQL-Command context
     */
    @Override
    public void addSQL(StringBuilder buf, long context)
    {
        // Append Name
        if ((context & CTX_NAME|CTX_FULLNAME)!=0)
        {   // append Qualified Name 
            db.appendQualifiedName(buf, name, quoteName);
        }
        // Append Alias
        if ((context & CTX_ALIAS)!=0 && alias!=null)
        {    // append alias
             buf.append(getRenameTablePhrase());
             buf.append(alias);
        }
    }

    @Override
    public void updateRecord(DBRecord rec)
    {
        if (updateable==false)
            throw new NotSupportedException(this, "updateRecord");
        // Update the record
        super.updateRecord(rec);
    }
    
    /*
     * (non-Javadoc)
     * 
     * @see org.apache.empire.db.DBRowSet#addRecord(org.apache.empire.db.DBRecord, java.sql.Connection)
     */
    @Override
    public void createRecord(DBRecord record, Object[] initalKey, boolean deferredInit)
    {
        throw new NotSupportedException(this, "createRecord");
    }

    /*
     * (non-Javadoc)
     * 
     * @see org.apache.empire.db.DBRowSet#deleteRecord(java.lang.Object[], java.sql.Connection, boolean)
     */
    @Override
    public void deleteRecord(Object[] key, DBContext context)
    {
        throw new NotSupportedException(this, "deleteRecord");
    }
}