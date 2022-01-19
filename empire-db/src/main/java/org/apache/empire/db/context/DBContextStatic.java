/*
 * ESTEAM Software GmbH, 19.01.2022
 */
package org.apache.empire.db.context;

import java.sql.Connection;

import org.apache.empire.db.DBDatabaseDriver;

public class DBContextStatic extends DBContextBase
{
    private final DBDatabaseDriver driver;
    private final Connection conn;
    
    public DBContextStatic(DBDatabaseDriver driver, Connection conn)
    {
        this.driver = driver;
        this.conn = conn;
    }

    @Override
    public DBDatabaseDriver getDriver()
    {
        return driver;
    }

    @Override
    public Connection getConnection()
    {
        return conn;
    }

}
